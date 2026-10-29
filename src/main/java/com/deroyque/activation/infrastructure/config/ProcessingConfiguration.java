package com.deroyque.activation.infrastructure.config;

import com.deroyque.activation.application.ProcessingProperties;
import com.deroyque.activation.application.RetryPolicy;
import com.deroyque.activation.infrastructure.provider.ProviderProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.Random;

@Configuration(proxyBeanMethods = false)
class ProcessingConfiguration {

	ProcessingConfiguration(ProcessingProperties processing, ProviderProperties provider) {
		// Se o lease vencer antes da chamada terminar, outra instância pode reenviar a mesma solicitação
		// enquanto a primeira chamada ainda está em andamento.
		if (processing.leaseDuration().compareTo(provider.maxCallDuration()) <= 0) {
			throw new IllegalStateException(
					"activation.processing.lease-duration (%s) deve ser maior que connect-timeout + read-timeout do provedor (%s)"
						.formatted(processing.leaseDuration(), provider.maxCallDuration()));
		}
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	RetryPolicy retryPolicy(ProcessingProperties properties) {
		// java.util.Random: thread-safe e do módulo java.base. RandomGenerator.getDefault() depende do módulo
		// jdk.random, ausente em JREs enxutos como o da imagem Docker.
		return new RetryPolicy(properties.initialBackoff(), properties.maxBackoff(), new Random());
	}

	/**
	 * Pool limitado: com o provedor lento, as chamadas simultâneas não crescem sem controle. O que não
	 * couber na fila é recuperado pela varredura periódica.
	 */
	@Bean
	ThreadPoolTaskExecutor activationDispatchExecutor(ProcessingProperties properties) {
		var executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("activation-dispatch-");
		executor.setCorePoolSize(properties.workerThreads());
		executor.setMaxPoolSize(properties.workerThreads());
		executor.setQueueCapacity(properties.queueCapacity());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}

}
