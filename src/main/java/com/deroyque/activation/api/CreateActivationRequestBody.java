package com.deroyque.activation.api;

import com.deroyque.activation.application.NewActivationRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Regras de validação: os três campos são obrigatórios, com até 64 caracteres, contendo apenas
 * letras, números e {@code . _ : -}.
 */
public record CreateActivationRequestBody(
		@Schema(description = "Identificador da solicitação no sistema de origem; chave de idempotência",
				example = "EXT-12345")
		@NotBlank(message = IDENTIFIER_REQUIRED)
		@Size(max = IDENTIFIER_MAX_LENGTH, message = IDENTIFIER_TOO_LONG)
		@Pattern(regexp = IDENTIFIER_PATTERN, message = IDENTIFIER_INVALID)
		String externalId,

		@Schema(example = "CUST-10001")
		@NotBlank(message = IDENTIFIER_REQUIRED)
		@Size(max = IDENTIFIER_MAX_LENGTH, message = IDENTIFIER_TOO_LONG)
		@Pattern(regexp = IDENTIFIER_PATTERN, message = IDENTIFIER_INVALID)
		String customerId,

		@Schema(example = "INTERNET-500")
		@NotBlank(message = IDENTIFIER_REQUIRED)
		@Size(max = IDENTIFIER_MAX_LENGTH, message = IDENTIFIER_TOO_LONG)
		@Pattern(regexp = IDENTIFIER_PATTERN, message = IDENTIFIER_INVALID)
		String productCode) {

	static final int IDENTIFIER_MAX_LENGTH = 64;
	static final String IDENTIFIER_PATTERN = "[A-Za-z0-9._:-]*";
	static final String IDENTIFIER_REQUIRED = "é obrigatório";
	static final String IDENTIFIER_TOO_LONG = "deve ter no máximo 64 caracteres";
	static final String IDENTIFIER_INVALID = "deve conter apenas letras, números e os caracteres . _ : -";

	NewActivationRequest toCommand(String clientId) {
		return new NewActivationRequest(clientId, externalId, customerId, productCode);
	}

}
