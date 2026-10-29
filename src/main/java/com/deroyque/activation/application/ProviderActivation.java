package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequest;

import java.util.UUID;

/**
 * @param requestId enviado também como chave de idempotência: repetir a chamada (ex.: após um
 *                  timeout) não deve gerar uma segunda ativação no provedor
 */
public record ProviderActivation(UUID requestId, String customerId, String productCode, String correlationId) {

	static ProviderActivation of(ActivationRequest request) {
		return new ProviderActivation(request.getId(), request.getCustomerId(), request.getProductCode(),
				request.getCorrelationId());
	}

}
