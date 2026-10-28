package com.deroyque.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação de um produto para um cliente.
 *
 * <p>O status só muda pelos métodos de negócio ({@link #startAttempt}, {@link #complete},
 * {@link #fail}, {@link #scheduleRetry}), que validam a transição. Não há setters.
 *
 * <p>{@code nextAttemptAt} indica quando a solicitação deve ser (re)enviada ao provedor. Ao iniciar
 * uma tentativa ele é empurrado para {@code agora + lease}: se a instância cair durante a chamada,
 * a solicitação volta a ficar elegível quando o lease expira, sem processo de reconciliação à parte.
 * Em estados finais ele é {@code null}.
 *
 * <p>{@code @Version} protege contra escrita concorrente: duas instâncias não conseguem
 * reivindicar a mesma tentativa nem sobrescrever o resultado uma da outra.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

	@Id
	private UUID id;

	@Column(name = "external_id", nullable = false, updatable = false)
	private String externalId;

	@Column(name = "client_id", nullable = false, updatable = false)
	private String clientId;

	@Column(name = "customer_id", nullable = false, updatable = false)
	private String customerId;

	@Column(name = "product_code", nullable = false, updatable = false)
	private String productCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ActivationStatus status;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "correlation_id", nullable = false, updatable = false)
	private String correlationId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	protected ActivationRequest() {
		// exigido pelo JPA
	}

	private ActivationRequest(String externalId, String clientId, String customerId, String productCode,
			String correlationId, Instant now) {
		this.id = UUID.randomUUID();
		this.externalId = Objects.requireNonNull(externalId, "externalId");
		this.clientId = Objects.requireNonNull(clientId, "clientId");
		this.customerId = Objects.requireNonNull(customerId, "customerId");
		this.productCode = Objects.requireNonNull(productCode, "productCode");
		this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
		this.status = ActivationStatus.PENDING;
		this.attempts = 0;
		this.nextAttemptAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static ActivationRequest create(String externalId, String clientId, String customerId,
			String productCode, String correlationId, Instant now) {
		return new ActivationRequest(externalId, clientId, customerId, productCode, correlationId, now);
	}

	/**
	 * Reivindica a solicitação para uma nova chamada ao provedor. Enquanto o lease não expira,
	 * nenhuma outra instância a considera elegível.
	 */
	public void startAttempt(Instant now, Duration lease) {
		if (!isDueForProcessing(now)) {
			throw new IllegalStateException("Solicitação %s não está elegível para processamento".formatted(id));
		}
		moveTo(ActivationStatus.PROCESSING, now);
		this.attempts++;
		this.nextAttemptAt = now.plus(lease);
	}

	public void complete(Instant now) {
		moveTo(ActivationStatus.COMPLETED, now);
		this.nextAttemptAt = null;
		this.failureReason = null;
	}

	public void fail(String reason, Instant now) {
		moveTo(ActivationStatus.FAILED, now);
		this.nextAttemptAt = null;
		this.failureReason = reason;
	}

	/** Falha temporária: continua em PROCESSING e agenda a próxima tentativa. */
	public void scheduleRetry(Instant retryAt, String reason, Instant now) {
		if (status != ActivationStatus.PROCESSING) {
			throw new InvalidStatusTransitionException(id, status, ActivationStatus.PROCESSING);
		}
		moveTo(ActivationStatus.PROCESSING, now);
		this.nextAttemptAt = retryAt;
		this.failureReason = reason;
	}

	public boolean isDueForProcessing(Instant now) {
		return !status.isTerminal() && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
	}

	/** Indica se uma nova requisição com o mesmo externalId é um retry legítimo desta solicitação. */
	public boolean hasSameContent(String clientId, String customerId, String productCode) {
		return this.clientId.equals(clientId)
				&& this.customerId.equals(customerId)
				&& this.productCode.equals(productCode);
	}

	public boolean isOwnedBy(String clientId) {
		return this.clientId.equals(clientId);
	}

	private void moveTo(ActivationStatus target, Instant now) {
		if (!status.canTransitionTo(target)) {
			throw new InvalidStatusTransitionException(id, status, target);
		}
		this.status = target;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getExternalId() {
		return externalId;
	}

	public String getClientId() {
		return clientId;
	}

	public String getCustomerId() {
		return customerId;
	}

	public String getProductCode() {
		return productCode;
	}

	public ActivationStatus getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	@Override
	public boolean equals(Object other) {
		return this == other || (other instanceof ActivationRequest that && id.equals(that.id));
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return "ActivationRequest[id=%s, externalId=%s, status=%s, attempts=%d]"
				.formatted(id, externalId, status, attempts);
	}

}
