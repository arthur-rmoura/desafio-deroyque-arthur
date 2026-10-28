package com.deroyque.activation.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

	private final RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(10),
			RandomGenerator.getDefault());

	@ParameterizedTest(name = "após {0} falha(s): entre {1}ms e {2}ms")
	@CsvSource({
			"1,  500,  1000",
			"2,  1000, 2000",
			"3,  2000, 4000",
			"4,  4000, 8000",
			"5,  5000, 10000",
			"60, 5000, 10000",
	})
	void exponentialBackoffWithJitterIsBoundedByMax(int failedAttempts, long minMillis, long maxMillis) {
		for (int i = 0; i < 200; i++) {
			var delay = policy.delayAfterFailure(failedAttempts, Optional.empty());
			assertThat(delay.toMillis()).isBetween(minMillis, maxMillis);
		}
	}

	@Test
	void honorsRetryAfterFromProvider() {
		assertThat(policy.delayAfterFailure(1, Optional.of(Duration.ofSeconds(7)))).isEqualTo(Duration.ofSeconds(7));
	}

	@Test
	void capsRetryAfterAtMaxBackoff() {
		assertThat(policy.delayAfterFailure(1, Optional.of(Duration.ofHours(1)))).isEqualTo(Duration.ofSeconds(10));
	}

}
