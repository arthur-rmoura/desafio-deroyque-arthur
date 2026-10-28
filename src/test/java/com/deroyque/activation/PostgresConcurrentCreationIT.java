package com.deroyque.activation;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mesmo cenário contra o PostgreSQL de produção (Testcontainers), que também valida as migrações
 * Flyway no banco real. Ignorado quando não há Docker disponível; no CI sempre roda.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrentCreationIT extends ConcurrentCreationTestSupport {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

}
