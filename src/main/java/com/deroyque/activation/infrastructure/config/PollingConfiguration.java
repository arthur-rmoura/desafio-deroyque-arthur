package com.deroyque.activation.infrastructure.config;

import com.deroyque.activation.application.ActivationPoller;
import com.deroyque.activation.application.ProcessingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "activation.processing.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class PollingConfiguration implements SchedulingConfigurer {

	private final ActivationPoller poller;
	private final ProcessingProperties properties;

	PollingConfiguration(ActivationPoller poller, ProcessingProperties properties) {
		this.poller = poller;
		this.properties = properties;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(poller::dispatchDueRequests, properties.pollInterval());
	}

}
