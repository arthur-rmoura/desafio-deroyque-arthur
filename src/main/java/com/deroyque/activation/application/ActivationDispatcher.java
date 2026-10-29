package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.InvalidStatusTransitionException;
import com.deroyque.activation.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Envia solicitações ao provedor externo em três passos, sem manter transação aberta durante a
 * chamada HTTP:
 * <ol>
 *   <li>reivindica a solicitação (transação curta + lock otimista; só uma instância vence);</li>
 *   <li>chama o provedor;</li>
 *   <li>grava o resultado (transação curta).</li>
 * </ol>
 * Se a aplicação cair entre os passos, a solicitação continua no banco com {@code nextAttemptAt}
 * vencido (ou lease expirado) e o {@link ActivationPoller} a reenvia. Como o id da solicitação é
 * enviado como chave de idempotência, o reenvio não duplica a ativação no provedor.
 */
@Component
public class ActivationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

	static final String MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED";

	private final ActivationRequestRepository repository;
	private final TransactionOperations transactions;
	private final ActivationProvider provider;
	private final RetryPolicy retryPolicy;
	private final ProcessingProperties properties;
	private final ActivationMetrics metrics;
	private final Clock clock;
	private final TaskExecutor executor;

	public ActivationDispatcher(ActivationRequestRepository repository, TransactionOperations transactions,
			ActivationProvider provider, RetryPolicy retryPolicy, ProcessingProperties properties,
			ActivationMetrics metrics, Clock clock, @Qualifier("activationDispatchExecutor") TaskExecutor executor) {
		this.repository = repository;
		this.transactions = transactions;
		this.provider = provider;
		this.retryPolicy = retryPolicy;
		this.properties = properties;
		this.metrics = metrics;
		this.clock = clock;
		this.executor = executor;
	}

	/**
	 * Agenda o envio em background. É apenas uma otimização de latência: se a fila estiver cheia ou
	 * a instância cair, o {@link ActivationPoller} encontra a solicitação no banco.
	 */
	public void dispatchAsync(UUID requestId) {
		try {
			executor.execute(() -> {
				try {
					dispatch(requestId);
				}
				catch (RuntimeException e) {
					log.error("Erro inesperado ao processar a solicitação {}", requestId, e);
				}
			});
		}
		catch (TaskRejectedException e) {
			log.warn("Fila de envio cheia; solicitação {} será enviada na próxima varredura", requestId);
		}
	}

	public void dispatch(UUID requestId) {
		var claimed = claim(requestId);
		if (claimed.isEmpty()) {
			return;
		}
		var request = claimed.get();
		try (var ignored = MDC.putCloseable(CorrelationId.MDC_KEY, request.getCorrelationId())) {
			log.info("Enviando solicitação {} ao provedor (tentativa {})", requestId, request.getAttempts());
			var result = provider.activate(ProviderActivation.of(request));
			metrics.providerOutcome(result);
			recordOutcome(requestId, request.getAttempts(), result);
		}
	}

	private Optional<ActivationRequest> claim(UUID requestId) {
		var now = clock.instant();
		try {
			return transactions.execute(tx -> repository.findById(requestId)
					.filter(request -> request.isDueForProcessing(now))
					.map(request -> {
						request.startAttempt(now, properties.leaseDuration());
						return repository.saveAndFlush(request);
					}));
		}
		catch (OptimisticLockingFailureException e) {
			log.debug("Solicitação {} já reivindicada por outra instância", requestId);
			return Optional.empty();
		}
	}

	private void recordOutcome(UUID requestId, int attempt, ProviderResult result) {
		try {
			transactions.executeWithoutResult(tx -> {
				var request = repository.findById(requestId).orElseThrow();
				if (request.getAttempts() != attempt) {
					// O lease expirou e outra tentativa já começou; ela é quem decide o resultado.
					log.warn("Resultado obsoleto da tentativa {} da solicitação {} descartado", attempt, requestId);
					return;
				}
				apply(request, result, clock.instant());
				repository.saveAndFlush(request);
				if (request.getStatus().isTerminal()) {
					metrics.requestFinished(request.getStatus());
				}
			});
		}
		catch (OptimisticLockingFailureException | InvalidStatusTransitionException e) {
			log.warn("Resultado da solicitação {} descartado: alterada concorrentemente ({})", requestId,
					e.getMessage());
		}
	}

	private void apply(ActivationRequest request, ProviderResult result, Instant now) {
		switch (result) {
			case ProviderResult.Success ignored -> {
				request.complete(now);
				log.info("Solicitação {} ativada", request.getId());
			}
			case ProviderResult.PermanentFailure failure -> {
				request.fail(failure.reason(), now);
				log.warn("Solicitação {} recusada pelo provedor: {}", request.getId(), failure.reason());
			}
			case ProviderResult.RetryableFailure failure -> retryOrGiveUp(request, failure, now);
		}
	}

	private void retryOrGiveUp(ActivationRequest request, ProviderResult.RetryableFailure failure, Instant now) {
		if (request.getAttempts() >= properties.maxAttempts()) {
			request.fail(MAX_ATTEMPTS_EXCEEDED + " (último erro: " + failure.reason() + ")", now);
			log.error("Solicitação {} falhou após {} tentativas; último erro: {}", request.getId(),
					request.getAttempts(), failure.reason());
			return;
		}
		var delay = retryPolicy.delayAfterFailure(request.getAttempts(), failure.retryAfter());
		request.scheduleRetry(now.plus(delay), failure.reason(), now);
		log.warn("Falha temporária na solicitação {} ({}); nova tentativa em {}", request.getId(), failure.reason(),
				delay);
	}

}
