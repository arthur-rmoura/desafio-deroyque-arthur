package com.deroyque.activation.application;

/** O externalId já pertence a uma solicitação com conteúdo diferente. */
public class ExternalIdConflictException extends RuntimeException {

	private final String externalId;

	public ExternalIdConflictException(String externalId) {
		super("externalId %s já utilizado por outra solicitação".formatted(externalId));
		this.externalId = externalId;
	}

	public String getExternalId() {
		return externalId;
	}

}
