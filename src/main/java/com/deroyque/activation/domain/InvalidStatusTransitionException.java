package com.deroyque.activation.domain;

import java.util.UUID;

public class InvalidStatusTransitionException extends RuntimeException {

	public InvalidStatusTransitionException(UUID requestId, ActivationStatus from, ActivationStatus to) {
		super("Transição de status inválida para a solicitação %s: %s -> %s".formatted(requestId, from, to));
	}

}
