package com.deroyque.activation.application;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

	public ActivationRequestNotFoundException(UUID id) {
		super("Solicitação %s não encontrada".formatted(id));
	}

}
