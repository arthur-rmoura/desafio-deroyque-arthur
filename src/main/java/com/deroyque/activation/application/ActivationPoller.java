package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Varredura periódica que garante o envio de toda solicitação gravada: novas que não foram
 * despachadas (fila cheia, queda da instância após o commit), retries agendados e tentativas cujo
 * lease expirou. Pode rodar em várias instâncias ao mesmo tempo: a reivindicação com lock otimista
 * garante que cada tentativa é executada por uma só.
 */
@Component
public class ActivationPoller {

	private static final Logger log = LoggerFactory.getLogger(ActivationPoller.class);

	private final ActivationRequestRepository repository;
	private final ActivationDispatcher dispatcher;
	private final ProcessingProperties properties;
	private final Clock clock;

	public ActivationPoller(ActivationRequestRepository repository, ActivationDispatcher dispatcher,
			ProcessingProperties properties, Clock clock) {
		this.repository = repository;
		this.dispatcher = dispatcher;
		this.properties = properties;
		this.clock = clock;
	}

	public void dispatchDueRequests() {
		var due = repository.findIdsDueForProcessing(clock.instant(), Limit.of(properties.batchSize()));
		if (!due.isEmpty()) {
			log.debug("{} solicitações elegíveis para envio", due.size());
		}
		due.forEach(dispatcher::dispatchAsync);
	}

}
