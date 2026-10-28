package com.deroyque.activation.application;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Backoff exponencial com jitter ("equal jitter"): após a n-ésima falha, espera um valor aleatório
 * entre metade e o total de {@code min(máximo, inicial * 2^(n-1))}. O jitter evita que várias
 * solicitações que falharam juntas (ex.: provedor fora do ar) voltem todas no mesmo instante.
 * Um {@code Retry-After} do provedor tem precedência, limitado ao máximo configurado.
 */
public class RetryPolicy {

	private static final int MAX_SHIFT = 30;

	private final Duration initialBackoff;
	private final Duration maxBackoff;
	private final RandomGenerator random;

	public RetryPolicy(Duration initialBackoff, Duration maxBackoff, RandomGenerator random) {
		this.initialBackoff = initialBackoff;
		this.maxBackoff = maxBackoff;
		this.random = random;
	}

	public Duration delayAfterFailure(int failedAttempts, Optional<Duration> retryAfter) {
		if (retryAfter.isPresent()) {
			var requested = retryAfter.get();
			return requested.compareTo(maxBackoff) > 0 ? maxBackoff : requested;
		}
		int shift = Math.min(Math.max(failedAttempts - 1, 0), MAX_SHIFT);
		long ceiling = Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() << shift);
		long half = ceiling / 2;
		return Duration.ofMillis(half + random.nextLong(ceiling - half + 1));
	}

}
