package com.deroyque.activation.api;

import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * @param failureReason motivo da última falha junto ao provedor (ex.: {@code HTTP_400},
 *                      {@code READ_TIMEOUT}); ausente quando não houve falha
 */
public record ActivationRequestResponse(
		UUID id,
		String externalId,
		String customerId,
		String productCode,
		ActivationStatus status,
		@JsonInclude(JsonInclude.Include.NON_NULL) String failureReason,
		Instant createdAt,
		Instant updatedAt) {

	static ActivationRequestResponse from(ActivationRequest request) {
		return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
				request.getProductCode(), request.getStatus(), request.getFailureReason(), request.getCreatedAt(),
				request.getUpdatedAt());
	}

}
