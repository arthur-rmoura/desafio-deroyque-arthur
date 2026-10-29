package com.deroyque.activation.shared;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * Fábrica de respostas de erro no formato Problem Details (RFC 9457), sempre com o
 * {@code correlationId} para que o consumidor possa informar o erro e o suporte localizá-lo no log.
 */
public final class ApiProblems {

	private static final String TYPE_BASE = "https://api.deroyque.example/problems/";

	private ApiProblems() {
	}

	public static ProblemDetail create(HttpStatusCode status, String type, String title, String detail) {
		var problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create(TYPE_BASE + type));
		problem.setTitle(title);
		return problem;
	}

	public static ProblemDetail withContext(ProblemDetail problem, String requestPath) {
		if (problem.getInstance() == null && requestPath != null) {
			problem.setInstance(URI.create(requestPath));
		}
		problem.setProperty("correlationId", CorrelationId.current());
		return problem;
	}

}
