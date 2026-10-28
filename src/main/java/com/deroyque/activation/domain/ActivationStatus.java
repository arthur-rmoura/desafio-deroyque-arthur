package com.deroyque.activation.domain;

/**
 * Ciclo de vida de uma solicitação de ativação.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED
 *                 │  ▲
 *                 │  └── nova tentativa (falha temporária)
 *                 └────► FAILED
 * </pre>
 *
 * Falhas temporárias mantêm a solicitação em {@code PROCESSING}: para o consumidor ela continua
 * "em processamento", e as novas tentativas são um detalhe interno controlado por
 * {@code attempts}/{@code nextAttemptAt}. Estados finais nunca são reabertos.
 */
public enum ActivationStatus {

	PENDING,
	PROCESSING,
	COMPLETED,
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}

	public boolean canTransitionTo(ActivationStatus target) {
		return switch (this) {
			case PENDING -> target == PROCESSING;
			case PROCESSING -> target == PROCESSING || target == COMPLETED || target == FAILED;
			case COMPLETED, FAILED -> false;
		};
	}

}
