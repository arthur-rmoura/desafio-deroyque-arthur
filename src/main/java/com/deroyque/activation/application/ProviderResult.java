package com.deroyque.activation.application;

import java.time.Duration;
import java.util.Optional;

/** Resultado de uma chamada ao provedor, já classificado quanto à possibilidade de nova tentativa. */
public sealed interface ProviderResult {

	record Success() implements ProviderResult {
	}

	/** O provedor recusou a solicitação; repetir não muda o resultado. */
	record PermanentFailure(String reason) implements ProviderResult {
	}

	/**
	 * Falha que pode se resolver sozinha (erro 5xx, limite de taxa, timeout, falha de conexão).
	 *
	 * @param retryAfter prazo sugerido pelo provedor (header {@code Retry-After}), se houver
	 */
	record RetryableFailure(String reason, Optional<Duration> retryAfter) implements ProviderResult {

		public RetryableFailure(String reason) {
			this(reason, Optional.empty());
		}

	}

}
