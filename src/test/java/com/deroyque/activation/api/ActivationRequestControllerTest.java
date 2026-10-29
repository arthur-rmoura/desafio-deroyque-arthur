package com.deroyque.activation.api;

import com.deroyque.activation.application.ActivationRequestNotFoundException;
import com.deroyque.activation.application.ActivationRequestService;
import com.deroyque.activation.application.CreationResult;
import com.deroyque.activation.application.ExternalIdConflictException;
import com.deroyque.activation.application.NewActivationRequest;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationStatus;
import com.deroyque.activation.infrastructure.security.SecurityConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfiguration.class)
@ActiveProfiles("test")
class ActivationRequestControllerTest {

	private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
	private static final String VALID_BODY = """
			{"externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500"}
			""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ActivationRequestService service;

	private static JwtRequestPostProcessor client(String clientId, String... scopes) {
		var authorities = Arrays.stream(scopes)
				.map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
				.toList();
		return jwt().jwt(token -> token.subject(clientId)).authorities(authorities);
	}

	private static JwtRequestPostProcessor writer() {
		return client("crm", "activation:write");
	}

	private static JwtRequestPostProcessor reader() {
		return client("crm", "activation:read");
	}

	private static ActivationRequest request() {
		return ActivationRequest.create("EXT-12345", "crm", "CUST-10001", "INTERNET-500", "corr", NOW);
	}

	@Nested
	class Create {

		@Test
		void returns201WithLocationForNewRequest() throws Exception {
			var created = request();
			when(service.create(any())).thenReturn(new CreationResult(created, true));

			mockMvc.perform(post("/api/v1/requests").with(writer())
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", endsWith("/api/v1/requests/" + created.getId())))
					.andExpect(header().doesNotExist(ActivationRequestController.REPLAYED_HEADER))
					.andExpect(jsonPath("$.id").value(created.getId().toString()))
					.andExpect(jsonPath("$.externalId").value("EXT-12345"))
					.andExpect(jsonPath("$.customerId").value("CUST-10001"))
					.andExpect(jsonPath("$.productCode").value("INTERNET-500"))
					.andExpect(jsonPath("$.status").value("PENDING"))
					.andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
					.andExpect(jsonPath("$.updatedAt").value("2026-10-26T14:03:11Z"))
					.andExpect(jsonPath("$.failureReason").doesNotExist());

			verify(service).create(new NewActivationRequest("crm", "EXT-12345", "CUST-10001", "INTERNET-500"));
		}

		@Test
		void returns200ForLegitimateRetry() throws Exception {
			when(service.create(any())).thenReturn(new CreationResult(request(), false));

			mockMvc.perform(post("/api/v1/requests").with(writer())
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isOk())
					.andExpect(header().string(ActivationRequestController.REPLAYED_HEADER, "true"))
					.andExpect(jsonPath("$.externalId").value("EXT-12345"));
		}

		@Test
		void returns409WhenExternalIdWasUsedWithDifferentContent() throws Exception {
			when(service.create(any())).thenThrow(new ExternalIdConflictException("EXT-12345"));

			mockMvc.perform(post("/api/v1/requests").with(writer())
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Solicitação duplicada"))
					.andExpect(jsonPath("$.type").value(endsWith("/problems/duplicate-request")));
		}

		@Test
		void returns400WithFieldErrorsForInvalidData() throws Exception {
			var body = """
					{"externalId": "", "customerId": "CUST 10001", "productCode": "%s"}
					""".formatted("X".repeat(65));

			mockMvc.perform(post("/api/v1/requests").with(writer())
							.header("X-Correlation-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
							.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Dados inválidos"))
					.andExpect(jsonPath("$.status").value(400))
					.andExpect(jsonPath("$.instance").value("/api/v1/requests"))
					.andExpect(jsonPath("$.correlationId").value("4bf92f3577b34da6a3ce929d0e0e4736"))
					.andExpect(jsonPath("$.errors[?(@.field == 'externalId')].message").value("é obrigatório"))
					.andExpect(jsonPath("$.errors[?(@.field == 'customerId')]").exists())
					.andExpect(jsonPath("$.errors[?(@.field == 'productCode')].message")
							.value("deve ter no máximo 64 caracteres"));

			verifyNoInteractions(service);
		}

		@Test
		void returns400WhenFieldIsMissing() throws Exception {
			mockMvc.perform(post("/api/v1/requests").with(writer())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"externalId\": \"EXT-1\", \"productCode\": \"INTERNET-500\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("customerId"))
					.andExpect(jsonPath("$.errors[0].message").value("é obrigatório"));
		}

		@Test
		void returns400ForMalformedJson() throws Exception {
			mockMvc.perform(post("/api/v1/requests").with(writer())
							.contentType(MediaType.APPLICATION_JSON).content("{\"externalId\": "))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.title").value("Requisição malformada"))
					.andExpect(jsonPath("$.detail").value(not(containsString("Jackson"))));
		}

		@Test
		void returns500WithoutInternalDetails() throws Exception {
			when(service.create(any())).thenThrow(new IllegalStateException("conexão com db-prod-01 recusada"));

			mockMvc.perform(post("/api/v1/requests").with(writer())
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.title").value("Erro interno"))
					.andExpect(jsonPath("$.correlationId").exists())
					.andExpect(content().string(not(containsString("db-prod-01"))))
					.andExpect(content().string(not(containsString("IllegalStateException"))));
		}

	}

	@Nested
	class Query {

		@Test
		void returnsExistingRequest() throws Exception {
			var existing = request();
			when(service.get(existing.getId(), "crm")).thenReturn(existing);

			mockMvc.perform(get("/api/v1/requests/{id}", existing.getId()).with(reader()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(existing.getId().toString()))
					.andExpect(jsonPath("$.status").value("PENDING"));
		}

		@Test
		void returns404ForUnknownRequest() throws Exception {
			var id = UUID.randomUUID();
			when(service.get(id, "crm")).thenThrow(new ActivationRequestNotFoundException(id));

			mockMvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
					.andExpect(status().isNotFound())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Solicitação não encontrada"))
					.andExpect(jsonPath("$.instance").value("/api/v1/requests/" + id));
		}

		@Test
		void returns400ForInvalidId() throws Exception {
			mockMvc.perform(get("/api/v1/requests/{id}", "not-a-uuid").with(reader()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.detail").value("Valor inválido para o parâmetro 'id'."));
		}

		@Test
		void listsWithFiltersAndPagination() throws Exception {
			var existing = request();
			when(service.list(eq("crm"), eq(ActivationStatus.PENDING), eq("CUST-10001"),
					any())).thenReturn(new PageImpl<>(List.of(existing), PageRequest.of(1, 10), 11));

			mockMvc.perform(get("/api/v1/requests").with(reader())
							.param("status", "PENDING").param("customerId", "CUST-10001")
							.param("page", "1").param("size", "10"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.content[0].id").value(existing.getId().toString()))
					.andExpect(jsonPath("$.page").value(1))
					.andExpect(jsonPath("$.size").value(10))
					.andExpect(jsonPath("$.totalElements").value(11))
					.andExpect(jsonPath("$.totalPages").value(2));
		}

		@Test
		void rejectsPageSizeAboveLimit() throws Exception {
			mockMvc.perform(get("/api/v1/requests").with(reader()).param("size", "500"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("size"));
		}

		@Test
		void rejectsUnknownStatus() throws Exception {
			mockMvc.perform(get("/api/v1/requests").with(reader()).param("status", "DONE"))
					.andExpect(status().isBadRequest());
		}

	}

	@Nested
	class Security {

		@Test
		void returns401WithoutToken() throws Exception {
			mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Não autenticado"))
					.andExpect(jsonPath("$.correlationId").exists());

			verifyNoInteractions(service);
		}

		@Test
		void returns403WhenReadOnlyClientTriesToCreate() throws Exception {
			mockMvc.perform(post("/api/v1/requests").with(reader())
							.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
					.andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Acesso negado"));

			verifyNoInteractions(service);
		}

		@Test
		void returns403WhenWriteOnlyClientTriesToRead() throws Exception {
			mockMvc.perform(get("/api/v1/requests").with(writer()))
					.andExpect(status().isForbidden());
		}

		@Test
		void deniesRouteWithoutExplicitRuleEvenWithAllScopes() throws Exception {
			mockMvc.perform(delete("/api/v1/requests/{id}", UUID.randomUUID())
							.with(client("crm", "activation:read", "activation:write")))
					.andExpect(status().isForbidden())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.title").value("Acesso negado"));

			verifyNoInteractions(service);
		}

		@Test
		void requiresAuthenticationOnRouteWithoutExplicitRule() throws Exception {
			mockMvc.perform(delete("/api/v1/requests/{id}", UUID.randomUUID()))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
		}

		@Test
		void echoesValidCorrelationIdAndReplacesInvalidOne() throws Exception {
			mockMvc.perform(get("/api/v1/requests").with(reader()).header("X-Correlation-Id", "abc-12345-def"))
					.andExpect(header().string("X-Correlation-Id", "abc-12345-def"));

			mockMvc.perform(get("/api/v1/requests").with(reader()).header("X-Correlation-Id", "bad\nvalue"))
					.andExpect(header().string("X-Correlation-Id", matchesPattern("[0-9a-f]{32}")));
		}

	}

}
