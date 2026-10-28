package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Métricas de negócio. Latência e taxa de erro HTTP (da API e do provedor) já são publicadas pelo
 * Spring Boot em {@code http.server.requests} e {@code http.client.requests}.
 */
@Component
public class ActivationMetrics {

	private final MeterRegistry registry;
	private final Counter created;

	public ActivationMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.created = Counter.builder("activation.requests.received")
				.description("Solicitações de ativação registradas")
				.register(registry);
	}

	void requestCreated() {
		created.increment();
	}

	void providerOutcome(ProviderResult result) {
		var outcome = switch (result) {
			case ProviderResult.Success ignored -> "success";
			case ProviderResult.PermanentFailure ignored -> "permanent_failure";
			case ProviderResult.RetryableFailure ignored -> "retryable_failure";
		};
		Counter.builder("activation.provider.outcomes")
				.description("Resultados das chamadas ao provedor de ativação")
				.tag("outcome", outcome)
				.register(registry)
				.increment();
	}

	void requestFinished(ActivationStatus status) {
		Counter.builder("activation.requests.finished")
				.description("Solicitações que chegaram a um estado final")
				.tag("status", status.name())
				.register(registry)
				.increment();
	}

}
