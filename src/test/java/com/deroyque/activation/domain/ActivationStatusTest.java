package com.deroyque.activation.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationStatusTest {

	@ParameterizedTest(name = "{0} -> {1} permitido? {2}")
	@CsvSource({
			"PENDING,    PROCESSING, true",
			"PENDING,    COMPLETED,  false",
			"PENDING,    FAILED,     false",
			"PROCESSING, PROCESSING, true",
			"PROCESSING, COMPLETED,  true",
			"PROCESSING, FAILED,     true",
			"PROCESSING, PENDING,    false",
			"COMPLETED,  PROCESSING, false",
			"COMPLETED,  FAILED,     false",
			"FAILED,     PROCESSING, false",
			"FAILED,     COMPLETED,  false",
	})
	void transitions(ActivationStatus from, ActivationStatus to, boolean allowed) {
		assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
	}

}
