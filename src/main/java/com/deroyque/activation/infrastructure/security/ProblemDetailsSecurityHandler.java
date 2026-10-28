package com.deroyque.activation.infrastructure.security;

import com.deroyque.activation.shared.ApiProblems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Respostas 401/403 no mesmo formato Problem Details da API. Delega aos handlers padrão do Spring
 * Security para manter o header {@code WWW-Authenticate} (RFC 6750).
 */
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
	private final BearerTokenAccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();
	private final ObjectMapper objectMapper;

	ProblemDetailsSecurityHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		bearerEntryPoint.commence(request, response, exception);
		write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Não autenticado",
				"Token de acesso ausente, inválido ou expirado.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException exception) throws IOException {
		bearerAccessDeniedHandler.handle(request, response, exception);
		write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Acesso negado",
				"O token não possui o escopo necessário para esta operação.");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String type,
			String title, String detail) throws IOException {
		var problem = ApiProblems.withContext(ApiProblems.create(status, type, title, detail),
				request.getRequestURI());
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}

}
