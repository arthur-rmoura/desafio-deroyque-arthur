package com.deroyque.activation.infrastructure.provider;

import com.deroyque.activation.application.ProviderActivation;
import com.deroyque.activation.application.ProviderResult.PermanentFailure;
import com.deroyque.activation.application.ProviderResult.RetryableFailure;
import com.deroyque.activation.application.ProviderResult.Success;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class HttpActivationProviderTest {

	private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
	private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private final ProviderActivation activation = new ProviderActivation(
			UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90"), "CUST-10001", "INTERNET-500", "corr-123");

	private HttpActivationProvider provider;

	@BeforeEach
	void setUp() {
		provider = providerFor(URI.create(wireMock.baseUrl()));
	}

	private static HttpActivationProvider providerFor(URI baseUrl) {
		var properties = new ProviderProperties(baseUrl, "secret-key", Duration.ofMillis(300), READ_TIMEOUT);
		RestClient restClient = new ProviderClientConfiguration().providerRestClient(RestClient.builder(), properties);
		return new HttpActivationProvider(restClient, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static void stubStatus(int status) {
		wireMock.stubFor(post(HttpActivationProvider.ACTIVATE_PATH).willReturn(aResponse().withStatus(status)));
	}

	@Test
	void successSendsIdempotencyKeyCorrelationAndCredential() {
		wireMock.stubFor(post(HttpActivationProvider.ACTIVATE_PATH)
				.willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ACTIVATED\"}")));

		var result = provider.activate(activation);

		assertThat(result).isInstanceOf(Success.class);
		wireMock.verify(postRequestedFor(urlEqualTo(HttpActivationProvider.ACTIVATE_PATH))
				.withHeader("Idempotency-Key", equalTo("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90"))
				.withHeader("X-Correlation-Id", equalTo("corr-123"))
				.withHeader("X-Api-Key", equalTo("secret-key"))
				.withRequestBody(equalToJson("""
						{"requestId": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
						 "customerId": "CUST-10001",
						 "productCode": "INTERNET-500"}
						""")));
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 409, 422})
	void clientErrorsArePermanent(int status) {
		stubStatus(status);

		assertThat(provider.activate(activation)).isEqualTo(new PermanentFailure("HTTP_" + status));
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 408, 500, 502, 504})
	void serverErrorsAndCredentialProblemsAreRetryable(int status) {
		stubStatus(status);

		assertThat(provider.activate(activation)).isEqualTo(new RetryableFailure("HTTP_" + status));
	}

	@Test
	void serviceUnavailableHonorsRetryAfterInSeconds() {
		wireMock.stubFor(post(HttpActivationProvider.ACTIVATE_PATH)
				.willReturn(aResponse().withStatus(503).withHeader(HttpHeaders.RETRY_AFTER, "30")));

		assertThat(provider.activate(activation))
				.isEqualTo(new RetryableFailure("HTTP_503", Optional.of(Duration.ofSeconds(30))));
	}

	@Test
	void tooManyRequestsHonorsRetryAfterAsHttpDate() {
		wireMock.stubFor(post(HttpActivationProvider.ACTIVATE_PATH)
				.willReturn(aResponse().withStatus(429)
						.withHeader(HttpHeaders.RETRY_AFTER, "Mon, 26 Oct 2026 14:02:00 GMT")));

		assertThat(provider.activate(activation))
				.isEqualTo(new RetryableFailure("HTTP_429", Optional.of(Duration.ofMinutes(2))));
	}

	@Test
	void slowResponseBeyondReadTimeoutIsRetryableTimeout() {
		wireMock.stubFor(post(HttpActivationProvider.ACTIVATE_PATH)
				.willReturn(aResponse().withStatus(200).withFixedDelay((int) READ_TIMEOUT.toMillis() * 4)));

		var started = System.nanoTime();
		var result = provider.activate(activation);

		assertThat(result).isEqualTo(new RetryableFailure("READ_TIMEOUT"));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(READ_TIMEOUT.multipliedBy(3));
	}

	@Test
	void connectionRefusedIsRetryable() throws Exception {
		int closedPort;
		try (var socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		var unreachable = providerFor(URI.create("http://localhost:" + closedPort));

		assertThat(unreachable.activate(activation)).isEqualTo(new RetryableFailure("CONNECTION_ERROR"));
	}

	@Test
	void invalidRetryAfterIsIgnored() {
		assertThat(HttpActivationProvider.parseRetryAfter("soon", NOW)).isEmpty();
		assertThat(HttpActivationProvider.parseRetryAfter("Mon, 26 Oct 2026 13:00:00 GMT", NOW))
				.contains(Duration.ZERO);
	}

	@Test
	void unexpectedStatusIsRetryable() {
		assertThat(HttpActivationProvider.classify(HttpStatusCode.valueOf(302),
				new HttpHeaders(), NOW)).isInstanceOf(RetryableFailure.class);
	}

}
