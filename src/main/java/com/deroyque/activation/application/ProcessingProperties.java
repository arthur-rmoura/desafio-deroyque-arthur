package com.deroyque.activation.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param maxAttempts     número máximo de chamadas ao provedor antes de marcar a solicitação como FAILED
 * @param initialBackoff  espera antes da segunda tentativa; dobra a cada falha
 * @param maxBackoff      teto da espera entre tentativas (também limita o {@code Retry-After} do provedor)
 * @param leaseDuration   por quanto tempo uma tentativa em andamento "segura" a solicitação; deve ser maior
 *                        que o timeout total da chamada ao provedor
 * @param pollInterval    intervalo da varredura de solicitações pendentes ou com retry vencido
 * @param batchSize       máximo de solicitações enfileiradas por varredura
 * @param workerThreads   chamadas simultâneas ao provedor por instância
 * @param queueCapacity   solicitações aguardando worker; ao lotar, a varredura seguinte as recupera
 */
@Validated
@ConfigurationProperties("activation.processing")
public record ProcessingProperties(
		@Min(1) int maxAttempts,
		@NotNull Duration initialBackoff,
		@NotNull Duration maxBackoff,
		@NotNull Duration leaseDuration,
		@NotNull Duration pollInterval,
		@Min(1) int batchSize,
		@Min(1) int workerThreads,
		@Min(0) int queueCapacity) {
}
