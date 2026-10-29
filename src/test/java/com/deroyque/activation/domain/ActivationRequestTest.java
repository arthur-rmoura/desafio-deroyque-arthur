package com.deroyque.activation.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

	private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
	private static final Duration LEASE = Duration.ofSeconds(30);

	private static ActivationRequest newRequest() {
		return ActivationRequest.create("EXT-1", "crm", "CUST-1", "INTERNET-500", "corr-1", NOW);
	}

	private static ActivationRequest processingRequest() {
		var request = newRequest();
		request.startAttempt(NOW, LEASE);
		return request;
	}

	@Test
	void newRequestIsPendingAndImmediatelyDue() {
		var request = newRequest();

		assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
		assertThat(request.getAttempts()).isZero();
		assertThat(request.isDueForProcessing(NOW)).isTrue();
		assertThat(request.getCreatedAt()).isEqualTo(request.getUpdatedAt()).isEqualTo(NOW);
	}

	@Nested
	class StartAttempt {

		@Test
		void movesToProcessingAndHoldsLease() {
			var request = newRequest();

			request.startAttempt(NOW, LEASE);

			assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
			assertThat(request.getAttempts()).isEqualTo(1);
			assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plus(LEASE));
			assertThat(request.isDueForProcessing(NOW.plusSeconds(29))).isFalse();
		}

		@Test
		void canBeReclaimedAfterLeaseExpires() {
			var request = processingRequest();
			var afterLease = NOW.plus(LEASE);

			request.startAttempt(afterLease, LEASE);

			assertThat(request.getAttempts()).isEqualTo(2);
			assertThat(request.getNextAttemptAt()).isEqualTo(afterLease.plus(LEASE));
		}

		@Test
		void rejectsAttemptWhileLeaseIsHeld() {
			var request = processingRequest();

			assertThatThrownBy(() -> request.startAttempt(NOW.plusSeconds(1), LEASE))
					.isInstanceOf(IllegalStateException.class);
		}

	}

	@Nested
	class Outcomes {

		@Test
		void completeIsTerminal() {
			var request = processingRequest();

			request.complete(NOW.plusSeconds(1));

			assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
			assertThat(request.getNextAttemptAt()).isNull();
			assertThat(request.isDueForProcessing(NOW.plusSeconds(3600))).isFalse();
			assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
		}

		@Test
		void failKeepsReason() {
			var request = processingRequest();

			request.fail("HTTP_400", NOW);

			assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
			assertThat(request.getFailureReason()).isEqualTo("HTTP_400");
			assertThat(request.getNextAttemptAt()).isNull();
		}

		@Test
		void scheduleRetryStaysInProcessing() {
			var request = processingRequest();
			var retryAt = NOW.plusSeconds(10);

			request.scheduleRetry(retryAt, "HTTP_500", NOW);

			assertThat(request.getStatus()).isEqualTo(ActivationStatus.PROCESSING);
			assertThat(request.getFailureReason()).isEqualTo("HTTP_500");
			assertThat(request.isDueForProcessing(retryAt)).isTrue();
		}

	}

	@Nested
	class InvalidTransitions {

		@Test
		void pendingCannotCompleteWithoutBeingProcessed() {
			var request = newRequest();

			assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
			assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
			assertThatThrownBy(() -> request.scheduleRetry(NOW, "x", NOW))
					.isInstanceOf(InvalidStatusTransitionException.class);
		}

		@Test
		void completedIsNeverReopened() {
			var request = processingRequest();
			request.complete(NOW);

			assertThatThrownBy(() -> request.fail("x", NOW)).isInstanceOf(InvalidStatusTransitionException.class);
			assertThatThrownBy(() -> request.scheduleRetry(NOW, "x", NOW))
					.isInstanceOf(InvalidStatusTransitionException.class);
			assertThatThrownBy(() -> request.startAttempt(NOW, LEASE)).isInstanceOf(IllegalStateException.class);
			assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
		}

		@Test
		void failedIsNeverReopened() {
			var request = processingRequest();
			request.fail("HTTP_400", NOW);

			assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
			assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
		}

	}

	@Test
	void sameContentRequiresSameClientCustomerAndProduct() {
		var request = newRequest();

		assertThat(request.hasSameContent("crm", "CUST-1", "INTERNET-500")).isTrue();
		assertThat(request.hasSameContent("crm", "CUST-1", "TV-100")).isFalse();
		assertThat(request.hasSameContent("crm", "CUST-2", "INTERNET-500")).isFalse();
		assertThat(request.hasSameContent("billing", "CUST-1", "INTERNET-500")).isFalse();
	}

}
