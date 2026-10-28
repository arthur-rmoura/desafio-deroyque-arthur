package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import com.deroyque.activation.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.deroyque.activation.domain.ActivationRequestRepository.hasCustomerId;
import static com.deroyque.activation.domain.ActivationRequestRepository.hasStatus;
import static com.deroyque.activation.domain.ActivationRequestRepository.ownedBy;

@Service
public class ActivationRequestService {

	private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

	private final ActivationRequestRepository repository;
	private final TransactionOperations transactions;
	private final ActivationDispatcher dispatcher;
	private final ActivationMetrics metrics;
	private final Clock clock;

	public ActivationRequestService(ActivationRequestRepository repository, TransactionOperations transactions,
			ActivationDispatcher dispatcher, ActivationMetrics metrics, Clock clock) {
		this.repository = repository;
		this.transactions = transactions;
		this.dispatcher = dispatcher;
		this.metrics = metrics;
		this.clock = clock;
	}

	/**
	 * Cria a solicitação de forma idempotente pelo {@code externalId}.
	 *
	 * <p>A consulta prévia só evita um insert desnecessário no caso comum (retry do sistema de origem);
	 * ela não garante unicidade, porque duas requisições simultâneas podem passar pela consulta antes
	 * de qualquer uma gravar. Quem garante é a constraint única no banco: o insert perdedor falha e a
	 * requisição é tratada como repetição da vencedora.
	 *
	 * <p>A chamada ao provedor não acontece aqui: a solicitação é gravada como PENDING e o envio
	 * ocorre fora desta transação ({@link ActivationDispatcher}).
	 *
	 * @throws ExternalIdConflictException se o externalId já existe com conteúdo diferente
	 */
	public CreationResult create(NewActivationRequest command) {
		var existing = repository.findByExternalId(command.externalId());
		if (existing.isPresent()) {
			return replay(existing.get(), command);
		}

		ActivationRequest created;
		try {
			created = transactions.execute(tx -> repository.saveAndFlush(ActivationRequest.create(
					command.externalId(), command.clientId(), command.customerId(), command.productCode(),
					CorrelationId.current(), clock.instant().truncatedTo(ChronoUnit.MILLIS))));
		}
		catch (DataIntegrityViolationException e) {
			var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
			log.info("Inserção concorrente para externalId={}; usando solicitação {}", command.externalId(),
					winner.getId());
			return replay(winner, command);
		}

		log.info("Solicitação {} criada para externalId={}", created.getId(), created.getExternalId());
		metrics.requestCreated();
		dispatcher.dispatchAsync(created.getId());
		return new CreationResult(created, true);
	}

	private CreationResult replay(ActivationRequest existing, NewActivationRequest command) {
		if (!existing.hasSameContent(command.clientId(), command.customerId(), command.productCode())) {
			throw new ExternalIdConflictException(command.externalId());
		}
		log.info("Repetição da solicitação {} (externalId={})", existing.getId(), existing.getExternalId());
		return new CreationResult(existing, false);
	}

	/**
	 * Solicitações de outro consumidor são tratadas como inexistentes, para não revelar que o id existe.
	 */
	public ActivationRequest get(UUID id, String clientId) {
		return repository.findById(id)
				.filter(request -> request.isOwnedBy(clientId))
				.orElseThrow(() -> new ActivationRequestNotFoundException(id));
	}

	public Page<ActivationRequest> list(String clientId, ActivationStatus status, String customerId,
			Pageable pageable) {
		var filter = Specification.allOf(ownedBy(clientId), hasStatus(status), hasCustomerId(customerId));
		return repository.findAll(filter, pageable);
	}

}
