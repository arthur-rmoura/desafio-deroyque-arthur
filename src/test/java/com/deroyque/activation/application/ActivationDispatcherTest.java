package com.deroyque.activation.application;

import com.deroyque.activation.application.ProviderResult.PermanentFailure;
import com.deroyque.activation.application.ProviderResult.RetryableFailure;
import com.deroyque.activation.application.ProviderResult.Success;
import com.deroyque.activation.domain.ActivationRequest;
import com.deroyque.activation.domain.ActivationRequestRepository;
import com.deroyque.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

@ExtendWith(MockitoExtension.class)
class ActivationDispatcherTest {

	private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
	private static final Duration LEASE = Duration.ofSeconds(30);
	private static final int MAX_ATTEMPTS = 3;

	@Mock
	private ActivationRequestRepository repository;

	@Mock
	private ActivationProvider provider;

	private ActivationDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		var properties = new ProcessingProperties(MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofMinutes(1), LEASE,
				Duration.ofSeconds(5), 10, 1, 10);
		// Jitter fixo no mínimo do intervalo para tornar o agendamento determinístico.
		RandomGenerator noJitter = () -> 0L;
		dispatcher = new ActivationDispatcher(repository, TransactionOperations.withoutTransaction(), provider,
				new RetryPolicy(properties.initialBackoff(), properties.maxBackoff(), noJitter), properties,
				new ActivationMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC),
				new SyncTaskExecutor());
	}

	private ActivationRequest storedRequest() {
		var request = ActivationRequest.create("EXT-1", "crm", "CUST-1", "INTERNET-500", "corr-1", NOW);
		when(repository.findById(request.getId())).thenReturn(Optional.of(request));
		return request;
	}

	private void saveReturnsEntity() {
		when(repository.saveAndFlush(any())).then(returnsFirstArg());
	}

	@Test
	void successCompletesRequestAndSendsIdempotencyKey() {
		var request = storedRequest();
		saveReturnsEntity();
		when(provider.activate(any())).thenReturn(new Success());

		dispatcher.dispatch(request.getId());

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		var sent = ArgumentCaptor.forClass(ProviderActivation.class);
		verify(provider).activate(sent.capture());
		assertThat(sent.getValue().requestId()).isEqualTo(request.getId());
		assertThat(sent.getValue().correlationId()).isEqualTo("corr-1");
	}

	@Test
	void permanentFailureMarksRequestAsFailed() {
		var request = storedRequest();
		saveReturnsEntity();
		when(provider.activate(any())).thenReturn(new PermanentFailure("HTTP_400"));

		dispatcher.dispatch(request.getId());

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(request.getFailureReason()).isEqualTo("HTTP_400");
	}

	@Test
	void retryableFailureKeepsProcessingAndSchedulesBackoff() {
		var request = storedRequest();
		saveReturnsEntity();
		when(provider.activate(any())).thenReturn(new RetryableFailure("HTTP_500"));

		dispatcher.dispatch(request.getId());

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		assertThat(request.getAttempts()).isEqualTo(1);
		assertThat(request.getFailureReason()).isEqualTo("HTTP_500");
		assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusMillis(500));
	}

	@Test
	void retryAfterFromProviderDefinesNextAttempt() {
		var request = storedRequest();
		saveReturnsEntity();
		when(provider.activate(any()))
				.thenReturn(new RetryableFailure("HTTP_429", Optional.of(Duration.ofSeconds(20))));

		dispatcher.dispatch(request.getId());

		assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(20));
	}

	@Test
	void retryableFailureOnLastAttemptMarksRequestAsFailed() {
		var request = storedRequest();
		for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
			request.startAttempt(NOW, LEASE);
			request.scheduleRetry(NOW, "HTTP_500", NOW);
		}
		saveReturnsEntity();
		when(provider.activate(any())).thenReturn(new RetryableFailure("READ_TIMEOUT"));

		dispatcher.dispatch(request.getId());

		assertThat(request.getAttempts()).isEqualTo(MAX_ATTEMPTS);
		assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
		assertThat(request.getFailureReason()).startsWith("MAX_ATTEMPTS_EXCEEDED").contains("READ_TIMEOUT");
	}

	@Test
	void doesNotCallProviderWhileAnotherAttemptHoldsTheLease() {
		var request = storedRequest();
		request.startAttempt(NOW, LEASE);

		dispatcher.dispatch(request.getId());

		verify(provider, never()).activate(any());
	}

	@Test
	void doesNotCallProviderForFinishedRequest() {
		var request = storedRequest();
		request.startAttempt(NOW, LEASE);
		request.complete(NOW);

		dispatcher.dispatch(request.getId());

		verify(provider, never()).activate(any());
	}

	@Test
	void doesNotCallProviderWhenAnotherInstanceWinsTheClaim() {
		var request = storedRequest();
		when(repository.saveAndFlush(any()))
				.thenThrow(new ObjectOptimisticLockingFailureException(ActivationRequest.class, request.getId()));

		dispatcher.dispatch(request.getId());

		verify(provider, never()).activate(any());
	}

	@Test
	void discardsOutcomeOfAttemptThatLostItsLease() {
		var claimed = ActivationRequest.create("EXT-1", "crm", "CUST-1", "INTERNET-500", "corr-1", NOW);
		// Ao gravar o resultado, a solicitação já foi reivindicada de novo por outra instância.
		var reclaimed = ActivationRequest.create("EXT-1", "crm", "CUST-1", "INTERNET-500", "corr-1", NOW);
		reclaimed.startAttempt(NOW, LEASE);
		reclaimed.scheduleRetry(NOW, "HTTP_500", NOW);
		reclaimed.startAttempt(NOW, LEASE);
		when(repository.findById(claimed.getId())).thenReturn(Optional.of(claimed), Optional.of(reclaimed));
		saveReturnsEntity();
		when(provider.activate(any())).thenReturn(new Success());

		dispatcher.dispatch(claimed.getId());

		assertThat(reclaimed.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
		verify(repository).saveAndFlush(claimed);
		verify(repository, never()).saveAndFlush(reclaimed);
	}

}
