package com.deroyque.activation.infrastructure.provider;

import com.deroyque.activation.application.ActivationProvider;
import com.deroyque.activation.application.ProviderActivation;
import com.deroyque.activation.application.ProviderResult;
import com.deroyque.activation.application.ProviderResult.PermanentFailure;
import com.deroyque.activation.application.ProviderResult.RetryableFailure;
import com.deroyque.activation.application.ProviderResult.Success;
import com.deroyque.activation.shared.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * Cliente HTTP do provedor de ativação ({@code POST /provider/v1/activate}).
 *
 * <p>Classificação das respostas:
 * <ul>
 *   <li>2xx: sucesso;</li>
 *   <li>429 e 503: falha temporária, respeitando o {@code Retry-After};</li>
 *   <li>408, 5xx, timeout e falha de conexão: falha temporária;</li>
 *   <li>401 e 403: falha temporária — o problema é a credencial deste serviço, não a solicitação;</li>
 *   <li>demais 4xx: falha permanente (a solicitação foi recusada; repetir não muda o resultado).</li>
 * </ul>
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

	private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

	static final String ACTIVATE_PATH = "/provider/v1/activate";
	static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	private final RestClient restClient;
	private final Clock clock;

	public HttpActivationProvider(RestClient providerRestClient, Clock clock) {
		this.restClient = providerRestClient;
		this.clock = clock;
	}

	@Override
	public ProviderResult activate(ProviderActivation activation) {
		try {
			return restClient.post()
					.uri(ACTIVATE_PATH)
					.contentType(MediaType.APPLICATION_JSON)
					.header(IDEMPOTENCY_KEY_HEADER, activation.requestId().toString())
					.header(CorrelationId.HEADER, activation.correlationId())
					.body(new ActivateBody(activation.requestId(), activation.customerId(), activation.productCode()))
					.exchange((request, response) -> classify(response.getStatusCode(), response.getHeaders(),
							clock.instant()));
		}
		catch (ResourceAccessException e) {
			var reason = ioFailureReason(e);
			log.warn("Falha de comunicação com o provedor: {} ({})", reason, e.getMessage());
			return new RetryableFailure(reason);
		}
	}

	static ProviderResult classify(HttpStatusCode status, HttpHeaders headers, Instant now) {
		if (status.is2xxSuccessful()) {
			return new Success();
		}
		var reason = "HTTP_" + status.value();
		return switch (status.value()) {
			case 429, 503 -> new RetryableFailure(reason, parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER), now));
			case 401, 403, 408 -> new RetryableFailure(reason);
			default -> status.is4xxClientError() ? new PermanentFailure(reason) : new RetryableFailure(reason);
		};
	}

	/**
	 * Timeout de leitura é ambíguo: o provedor pode ter ativado o produto sem conseguir responder.
	 * A nova tentativa é segura porque reenvia a mesma {@code Idempotency-Key}.
	 */
	private static String ioFailureReason(ResourceAccessException e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpConnectTimeoutException) {
				return "CONNECT_TIMEOUT";
			}
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return "READ_TIMEOUT";
			}
		}
		return "CONNECTION_ERROR";
	}

	/** {@code Retry-After} pode vir em segundos ou como data HTTP (RFC 9110, seção 10.2.3). */
	static Optional<Duration> parseRetryAfter(String value, Instant now) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))));
		}
		catch (NumberFormatException notSeconds) {
			try {
				var retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				var delay = Duration.between(now, retryAt);
				return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
			}
			catch (DateTimeParseException notADate) {
				return Optional.empty();
			}
		}
	}

	record ActivateBody(UUID requestId, String customerId, String productCode) {
	}

}
