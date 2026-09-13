package com.ambitiousconcepts.opsatlas.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A real PostgreSQL for integration tests.
 *
 * <p>CLAUDE.md section 11 requires Testcontainers against real PostgreSQL rather
 * than an in-memory substitute. That is not pedantry here: the schema in
 * {@code V1__catalog.sql} leans on composite foreign keys, regex CHECK
 * constraints and jsonb, none of which H2 models the same way. A test passing
 * against H2 would tell us nothing about whether the constraints work.
 *
 * <p>The container is static, so one instance is shared by every test class that
 * extends this, and Flyway runs against it once.
 *
 * <p>Requires a running Docker daemon. Where one is unavailable these tests do
 * not pass - they cannot run, and must be reported as unavailable rather than
 * as passing.
 */
@Testcontainers
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("opsatlas")
            .withUsername("opsatlas")
            .withPassword("opsatlas")
            .withReuse(false);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
