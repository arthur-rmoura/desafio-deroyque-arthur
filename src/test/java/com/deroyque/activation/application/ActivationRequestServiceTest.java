package com.deroyque.activation.application;

import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
	private static final NewActivationRequest COMMAND =
			new NewActivationRequest("crm", "EXT-12345", "CUST-10001", "INTERNET-500");

	@Mock
	private ActivationRequestRepository repository;

	@Mock
	private ActivationDispatcher dispatcher;

	private ActivationRequestService service;

	@BeforeEach
	void setUp() {
		service = new ActivationRequestService(repository, TransactionOperations.withoutTransaction(), dispatcher,
				new ActivationMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ActivationRequest existing(String clientId, String productCode) {
		return ActivationRequest.create("EXT-12345", clientId, "CUST-10001", productCode, "corr", NOW);
	}

	@Test
	void createsPendingRequestAndSchedulesDispatch() {
		when(repository.findByExternalId("EXT-12345")).thenReturn(Optional.empty());
		when(repository.saveAndFlush(any())).then(returnsFirstArg());

		var result = service.create(COMMAND);

		assertThat(result.created()).isTrue();
		var request = result.request();
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(request.getExternalId()).isEqualTo("EXT-12345");
		assertThat(request.getClientId()).isEqualTo("crm");
		assertThat(request.getCreatedAt()).isEqualTo(NOW);
		verify(dispatcher).dispatchAsync(request.getId());
	}

	@Test
	void retryWithSameContentReturnsExistingRequest() {
		var existing = existing("crm", "INTERNET-500");
		when(repository.findByExternalId("EXT-12345")).thenReturn(Optional.of(existing));

		var result = service.create(COMMAND);

		assertThat(result.created()).isFalse();
		assertThat(result.request()).isSameAs(existing);
		verify(repository, never()).saveAndFlush(any());
		verify(dispatcher, never()).dispatchAsync(any());
	}

	@Test
	void sameExternalIdWithDifferentContentIsConflict() {
		when(repository.findByExternalId("EXT-12345")).thenReturn(Optional.of(existing("crm", "TV-100")));

		assertThatThrownBy(() -> service.create(COMMAND)).isInstanceOf(ExternalIdConflictException.class);
	}

	@Test
	void sameExternalIdFromAnotherClientIsConflictAndDoesNotLeakTheRequest() {
		when(repository.findByExternalId("EXT-12345")).thenReturn(Optional.of(existing("billing", "INTERNET-500")));

		assertThatThrownBy(() -> service.create(COMMAND)).isInstanceOf(ExternalIdConflictException.class);
	}

	@Test
	void concurrentInsertLosingTheRaceReturnsTheWinner() {
		var winner = existing("crm", "INTERNET-500");
		// A consulta não encontra nada, mas outra requisição grava antes deste insert.
		when(repository.findByExternalId("EXT-12345")).thenReturn(Optional.empty(), Optional.of(winner));
		when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_external_id"));

		var result = service.create(COMMAND);

		assertThat(result.created()).isFalse();
		assertThat(result.request()).isSameAs(winner);
		verify(dispatcher, never()).dispatchAsync(any());
	}

	@Test
	void integrityViolationUnrelatedToDuplicityIsPropagated() {
		when(repository.findByExternalId("EXT-12345")).thenReturn(Optional.empty());
		var failure = new DataIntegrityViolationException("not null");
		when(repository.saveAndFlush(any())).thenThrow(failure);

		assertThatThrownBy(() -> service.create(COMMAND)).isSameAs(failure);
	}

	@Test
	void requestFromAnotherClientIsReportedAsNotFound() {
		var request = existing("billing", "INTERNET-500");
		when(repository.findById(request.getId())).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.get(request.getId(), "crm"))
				.isInstanceOf(ActivationRequestNotFoundException.class);
		assertThat(service.get(request.getId(), "billing")).isSameAs(request);
	}

}
