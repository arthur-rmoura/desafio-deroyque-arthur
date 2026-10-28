package com.deroyque.activation.infrastructure.provider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * @param apiKey credencial do serviço junto ao provedor; vem de variável de ambiente / cofre de
 *               segredos e nunca é registrada em log
 */
@Validated
@ConfigurationProperties("activation.provider")
public record ProviderProperties(
		@NotNull URI baseUrl,
		@NotBlank String apiKey,
		@NotNull Duration connectTimeout,
		@NotNull Duration readTimeout) {

	/** Tempo máximo que uma chamada ao provedor pode levar antes de ser abortada. */
	public Duration maxCallDuration() {
		return connectTimeout.plus(readTimeout);
	}

	@Override
	public String toString() {
		return "ProviderProperties[baseUrl=%s, apiKey=***, connectTimeout=%s, readTimeout=%s]"
				.formatted(baseUrl, connectTimeout, readTimeout);
	}

}
