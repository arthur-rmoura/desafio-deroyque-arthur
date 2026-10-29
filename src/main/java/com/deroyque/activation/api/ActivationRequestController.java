package com.deroyque.activation.api;

import com.deroyque.activation.application.ActivationRequestService;
import com.deroyque.activation.domain.ActivationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.security.Principal;
import java.util.UUID;

/**
 * O {@link Principal} é o sistema consumidor autenticado (claim {@code sub} do JWT). Toda operação é
 * restrita às solicitações criadas por ele.
 */
@RestController
@RequestMapping("/api/v1/requests")
class ActivationRequestController {

	static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final ActivationRequestService service;

	ActivationRequestController(ActivationRequestService service) {
		this.service = service;
	}

	@Operation(summary = "Cria uma solicitação de ativação (idempotente por externalId)")
	@ApiResponse(responseCode = "201", description = "Solicitação criada; processamento assíncrono")
	@ApiResponse(responseCode = "200", description = "Repetição de uma solicitação já existente com o mesmo conteúdo")
	@ApiResponse(responseCode = "400", description = "Dados inválidos")
	@ApiResponse(responseCode = "409", description = "externalId já utilizado com conteúdo diferente")
	@PostMapping
	ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequestBody body,
			Principal principal) {
		var result = service.create(body.toCommand(principal.getName()));
		var response = ActivationRequestResponse.from(result.request());
		if (result.created()) {
			var location = ServletUriComponentsBuilder.fromCurrentRequest()
					.path("/{id}")
					.buildAndExpand(response.id())
					.toUri();
			return ResponseEntity.created(location).body(response);
		}
		return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(response);
	}

	@Operation(summary = "Consulta uma solicitação e seu status atual")
	@ApiResponse(responseCode = "404", description = "Solicitação inexistente (ou de outro consumidor)")
	@GetMapping("/{id}")
	ActivationRequestResponse get(@PathVariable UUID id, Principal principal) {
		return ActivationRequestResponse.from(service.get(id, principal.getName()));
	}

	@Operation(summary = "Lista as solicitações do consumidor, das mais recentes para as mais antigas")
	@GetMapping
	PageResponse<ActivationRequestResponse> list(
			@RequestParam(required = false) ActivationStatus status,
			@RequestParam(required = false) @Size(max = 64) String customerId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			Principal principal) {
		var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
		return PageResponse.from(service.list(principal.getName(), status, customerId, pageable),
				ActivationRequestResponse::from);
	}

}
