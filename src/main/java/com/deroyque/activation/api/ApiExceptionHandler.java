package com.deroyque.activation.api;

import com.deroyque.activation.application.ActivationRequestNotFoundException;
import com.deroyque.activation.application.ExternalIdConflictException;
import com.deroyque.activation.shared.ApiProblems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Traduz exceções em Problem Details (RFC 9457). Nenhuma resposta expõe stack trace, nome de classe
 * ou mensagem interna; erros inesperados são registrados no log com o correlationId devolvido ao
 * consumidor.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	record InvalidField(String field, String message) {
	}

	@ExceptionHandler(ActivationRequestNotFoundException.class)
	ResponseEntity<Object> handleNotFound(ActivationRequestNotFoundException ex, WebRequest request) {
		var problem = ApiProblems.create(HttpStatus.NOT_FOUND, "not-found", "Solicitação não encontrada",
				"Não existe solicitação com o id informado.");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
	}

	@ExceptionHandler(ExternalIdConflictException.class)
	ResponseEntity<Object> handleConflict(ExternalIdConflictException ex, WebRequest request) {
		var problem = ApiProblems.create(HttpStatus.CONFLICT, "duplicate-request", "Solicitação duplicada",
				"O externalId '%s' já foi utilizado em uma solicitação com dados diferentes."
					.formatted(ex.getExternalId()));
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Erro inesperado ao processar {}", requestPath(request), ex);
		var problem = ApiProblems.create(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro interno",
				"Ocorreu um erro inesperado. Informe o correlationId ao suporte.");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
				.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(MessageSourceResolvable::getDefaultMessage)
						.map(message -> new InvalidField(result.getMethodParameter().getParameterName(), message)))
				.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		var problem = ApiProblems.create(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos",
				"Valor inválido para o parâmetro '%s'.".formatted(ex.getPropertyName()));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var problem = ApiProblems.create(HttpStatus.BAD_REQUEST, "malformed-request", "Requisição malformada",
				"O corpo da requisição está ausente ou não é um JSON válido.");
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/** Ponto único de saída: completa todo ProblemDetail, inclusive os gerados pelo Spring (404, 405, 415...). */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			ApiProblems.withContext(problem, requestPath(request));
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	private static ProblemDetail validationProblem(List<InvalidField> errors) {
		var problem = ApiProblems.create(HttpStatus.BAD_REQUEST, "validation-error", "Dados inválidos",
				"Um ou mais campos não passaram na validação.");
		problem.setProperty("errors", errors);
		return problem;
	}

	private static String requestPath(WebRequest request) {
		return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : null;
	}

}
