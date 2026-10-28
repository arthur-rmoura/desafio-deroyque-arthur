package com.deroyque.activation;

import com.deroyque.activation.application.ActivationPoller;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo: API (MockMvc + segurança) → banco (H2 com migrações Flyway) → processamento
 * assíncrono → provedor simulado (WireMock).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivationFlowIT {

	private static final String ACTIVATE_PATH = "/provider/v1/activate";

	@RegisterExtension
	static WireMockExtension provider = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void providerProperties(DynamicPropertyRegistry registry) {
		registry.add("activation.provider.base-url", provider::baseUrl);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ActivationRequestRepository repository;

	@Autowired
	private ActivationPoller poller;

	private static JwtRequestPostProcessor client(String clientId) {
		return jwt().jwt(token -> token.subject(clientId).claim("scope", "activation:read activation:write"));
	}

	private ResultActions create(String clientId, String externalId, String productCode) throws Exception {
		var body = """
				{"externalId": "%s", "customerId": "CUST-10001", "productCode": "%s"}
				""".formatted(externalId, productCode);
		return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/requests")
				.with(client(clientId))
				.header("X-Correlation-Id", "it-correlation-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private UUID createAndGetId(String externalId, String productCode) throws Exception {
		var response = create("crm", externalId, productCode)
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode json = objectMapper.readTree(response);
		return UUID.fromString(json.get("id").asText());
	}

	private ActivationRequest awaitRequest(UUID id, Predicate<ActivationRequest> condition) {
		return await().atMost(Duration.ofSeconds(10))
				.until(() -> repository.findById(id).orElseThrow(), condition);
	}

	private static String uniqueExternalId() {
		return "EXT-" + UUID.randomUUID();
	}

	@Test
	void createdRequestIsActivatedAsynchronously() throws Exception {
		provider.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));

		var id = createAndGetId(uniqueExternalId(), "INTERNET-500");

		awaitRequest(id, request -> request.getStatus() == ActivationStatus.COMPLETED);
		mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("crm")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE_PATH))
				.withHeader("Idempotency-Key", equalTo(id.toString()))
				.withHeader("X-Correlation-Id", equalTo("it-correlation-0001")));
	}

	@Test
	void providerRejectionMarksRequestAsFailed() throws Exception {
		provider.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(400)));

		var id = createAndGetId(uniqueExternalId(), "INVALID-PRODUCT");

		awaitRequest(id, request -> request.getStatus() == ActivationStatus.FAILED);
		mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("crm")))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.failureReason").value("HTTP_400"));
	}

	@Test
	void transientProviderErrorIsRetriedWithSameIdempotencyKey() throws Exception {
		provider.stubFor(post(ACTIVATE_PATH).inScenario("instável")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withStatus(500))
				.willSetStateTo("recuperado"));
		provider.stubFor(post(ACTIVATE_PATH).inScenario("instável")
				.whenScenarioStateIs("recuperado")
				.willReturn(aResponse().withStatus(200)));

		var id = createAndGetId(uniqueExternalId(), "INTERNET-500");

		var afterFirstAttempt = awaitRequest(id, request -> "HTTP_500".equals(request.getFailureReason()));
		assertThat(afterFirstAttempt.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(afterFirstAttempt.getAttempts()).isEqualTo(1);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			poller.dispatchDueRequests();
			assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		});
		assertThat(repository.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
		provider.verify(2, postRequestedFor(urlEqualTo(ACTIVATE_PATH))
				.withHeader("Idempotency-Key", equalTo(id.toString())));
	}

	@Test
	void providerTimeoutKeepsRequestProcessingForRetry() throws Exception {
		provider.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200).withFixedDelay(1_500)));

		var id = createAndGetId(uniqueExternalId(), "SLOW-PRODUCT");

		var request = awaitRequest(id, r -> "READ_TIMEOUT".equals(r.getFailureReason()));
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(request.getNextAttemptAt()).isNotNull();
	}

	@Test
	void duplicateExternalIdNeverCreatesSecondRecord() throws Exception {
		provider.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));
		var externalId = uniqueExternalId();

		var id = createAndGetId(externalId, "INTERNET-500");

		create("crm", externalId, "INTERNET-500")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
		create("crm", externalId, "TV-100")
				.andExpect(status().isConflict());
		create("billing", externalId, "INTERNET-500")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.id").doesNotExist());

		assertThat(repository.findAll()).filteredOn(r -> r.getExternalId().equals(externalId)).hasSize(1);
		awaitRequest(id, request -> request.getStatus().isTerminal());
	}

	@Test
	void consumersCannotSeeEachOthersRequests() throws Exception {
		provider.stubFor(post(ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));
		var id = createAndGetId(uniqueExternalId(), "INTERNET-500");
		awaitRequest(id, request -> request.getStatus().isTerminal());

		mockMvc.perform(get("/api/v1/requests/{id}", id).with(client("billing")))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/requests").with(client("billing")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/v1/requests").with(client("crm"))
						.param("status", "COMPLETED").param("customerId", "CUST-10001"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(id)).exists());
	}

}
