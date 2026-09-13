package com.ambitiousconcepts.opsatlas.support;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
 *
 * <p>One shared container means one shared database, so every test starts from
 * the state Flyway left rather than from whatever the last test happened to
 * leave behind - see {@link #resetToTheSeededState()}.
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

    /**
     * Organization rows that belong to the schema rather than to a test.
     *
     * <p>Captured from the database the first time a test resets it, which is
     * after Flyway has run and before any test body has executed. Read rather
     * than hardcoded: the seeded id is deliberately package-private in
     * {@code identity.internal}, and widening production visibility to let a
     * test know it would be the wrong trade. A later migration that seeds
     * another organization is picked up with no change here.
     */
    private static Set<UUID> seededOrganizations;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Empties every table a test could have written to, before every test.
     *
     * <p>The container is static and shared, so without this a class that plants
     * rows leaves them for whichever class runs next. That is not hypothetical:
     * {@code CatalogApiIT} asserts an empty catalog and has been broken twice by
     * exactly this, each time by a new class that reset state on the way in and
     * not on the way out.
     *
     * <p>Five classes had grown their own hand-written delete lists, in
     * different orders and covering different tables, and each list was another
     * chance to forget one. The tables are discovered from the database instead,
     * so a table added by a future migration is covered without anybody
     * remembering to add it.
     *
     * <p>Deliberately not {@code @Transactional} with a rollback. Several tests
     * here depend on real commit semantics - {@code REQUIRES_NEW} in the source
     * store, optimistic locking, and idempotent ingestion reading back what it
     * committed - and wrapping them in a rolled-back transaction would quietly
     * change what they prove.
     */
    @BeforeEach
    void resetToTheSeededState() {
        if (seededOrganizations == null) {
            seededOrganizations = Set.copyOf(jdbc.queryForList("select id from organization", UUID.class));
        }

        List<String> tables = jdbc.queryForList(
                """
                select tablename from pg_tables
                where schemaname = 'public' and tablename not in ('flyway_schema_history', 'organization')
                """,
                String.class);

        if (!tables.isEmpty()) {
            jdbc.execute("truncate table "
                    + tables.stream().map(table -> '"' + table + '"').collect(Collectors.joining(", "))
                    + " restart identity cascade");
        }

        if (!seededOrganizations.isEmpty()) {
            String placeholders = seededOrganizations.stream().map(id -> "?").collect(Collectors.joining(", "));
            jdbc.update(
                    "delete from organization where id not in (" + placeholders + ")",
                    seededOrganizations.toArray());
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Scheduler and GitHub defaults live in src/test/resources/application.properties,
        // below @DynamicPropertySource in precedence, so a test that arranges a
        // stub overrides them without racing this method.
    }
}
