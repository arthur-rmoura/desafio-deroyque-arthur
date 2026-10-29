package com.deroyque.activation;

import com.deroyque.activation.application.ActivationProvider;
import com.deroyque.activation.application.ActivationRequestService;
import com.deroyque.activation.application.CreationResult;
import com.deroyque.activation.application.NewActivationRequest;
import com.deroyque.activation.application.ProviderResult;
import com.deroyque.activation.domain.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Várias requisições idênticas disparadas ao mesmo tempo, contra um banco real: exatamente uma cria
 * o registro e todas as outras recebem o mesmo registro como repetição.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class ConcurrentCreationTestSupport {

	private static final int CONCURRENT_REQUESTS = 16;

	@Autowired
	private ActivationRequestService service;

	@Autowired
	private ActivationRequestRepository repository;

	@MockitoBean
	private ActivationProvider provider;

	@BeforeEach
	void providerAcceptsEverything() {
		when(provider.activate(any())).thenReturn(new ProviderResult.Success());
	}

	@RepeatedTest(3)
	void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
		var command = new NewActivationRequest("crm", "EXT-" + UUID.randomUUID(), "CUST-10001", "INTERNET-500");
		var startSignal = new CountDownLatch(1);
		Callable<CreationResult> createWhenSignaled = () -> {
			startSignal.await();
			return service.create(command);
		};

		var results = new ArrayList<CreationResult>();
		try (var executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
			List<Future<CreationResult>> futures = new ArrayList<>();
			for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
				futures.add(executor.submit(createWhenSignaled));
			}
			startSignal.countDown();
			for (var future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
		}

		assertThat(results).filteredOn(CreationResult::created).hasSize(1);
		var createdId = results.stream().filter(CreationResult::created).findFirst().orElseThrow().request().getId();
		assertThat(results).allSatisfy(result -> assertThat(result.request().getId()).isEqualTo(createdId));
		assertThat(repository.findAll()).filteredOn(r -> r.getExternalId().equals(command.externalId())).hasSize(1);
	}

}
