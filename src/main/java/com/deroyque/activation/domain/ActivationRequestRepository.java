package com.deroyque.activation.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
		extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

	Optional<ActivationRequest> findByExternalId(String externalId);

	/**
	 * Solicitações que precisam ser (re)enviadas ao provedor: novas, com retry agendado vencido ou
	 * com lease expirado (instância caiu no meio da chamada). Estados finais têm
	 * {@code nextAttemptAt} nulo e nunca entram aqui.
	 */
	@Query("select r.id from ActivationRequest r where r.nextAttemptAt <= :now order by r.nextAttemptAt")
	List<UUID> findIdsDueForProcessing(Instant now, Limit limit);

	static Specification<ActivationRequest> ownedBy(String clientId) {
		return (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
	}

	static Specification<ActivationRequest> hasStatus(ActivationStatus status) {
		return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	static Specification<ActivationRequest> hasCustomerId(String customerId) {
		return customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
	}

}
