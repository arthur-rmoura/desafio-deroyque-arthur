package com.deroyque.activation.shared;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identificador de correlação: recebido (ou gerado) na borda HTTP, gravado com a solicitação e
 * reenviado ao provedor, para que uma ativação possa ser rastreada nos logs de ponta a ponta.
 */
public final class CorrelationId {

	public static final String HEADER = "X-Correlation-Id";
	public static final String MDC_KEY = "correlationId";

	// Restringe o formato aceito do cliente para evitar log injection e valores gigantes.
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

	private CorrelationId() {
	}

	public static String sanitizeOrGenerate(String candidate) {
		return candidate != null && VALID.matcher(candidate).matches() ? candidate : generate();
	}

	public static String current() {
		var value = MDC.get(MDC_KEY);
		return value != null ? value : generate();
	}

	private static String generate() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
