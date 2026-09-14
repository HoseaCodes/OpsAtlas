package com.ambitiousconcepts.opsatlas.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * How a machine of this system's own gets in (ADR 0013).
 *
 * <p>Every request here is {@code anonymous()}, which overrides the suite's
 * default token. Otherwise the key would never be reached: a request that is
 * already authenticated as a person does not need one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "opsatlas.credentials.observer.key=opsatlas_sk_EXAMPLE_not_a_real_key_for_tests_01")
class ServiceCredentialIT extends PostgresTestBase {

    private static final String KEY = "opsatlas_sk_EXAMPLE_not_a_real_key_for_tests_01";

    @Autowired
    private ServiceCredentialBootstrap bootstrap;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void provision() {
        // The base class empties every table before each test, so the row this
        // created at startup is gone by the time a test body runs.
        bootstrap.provisionIfConfigured();
    }

    @Test
    @DisplayName("the observer's key is accepted")
    void a_valid_key_is_accepted() throws Exception {
        mockMvc.perform(get("/api/v1/services").with(anonymous()).header(ServiceCredentials.HEADER, KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a key nobody issued is refused")
    void an_unknown_key_is_refused() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                        .with(anonymous())
                        .header(ServiceCredentials.HEADER, "opsatlas_sk_EXAMPLE_never_issued_to_anybody_here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("something that is not a key at all is refused without a database lookup")
    void a_malformed_key_is_refused() throws Exception {
        mockMvc.perform(get("/api/v1/services").with(anonymous()).header(ServiceCredentials.HEADER, "hunter2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the database never holds the key itself")
    void only_the_hash_is_stored() {
        String stored = jdbc.queryForObject("select secret_hash from service_credential", String.class);

        assertThat(stored).isNotEqualTo(KEY).matches("[0-9a-f]{64}").isEqualTo(ServiceCredentials.hash(KEY));
    }

    @Test
    @DisplayName("rotating the key stops the old one working at once")
    void rotation_revokes_the_previous_key() throws Exception {
        mockMvc.perform(get("/api/v1/services").with(anonymous()).header(ServiceCredentials.HEADER, KEY))
                .andExpect(status().isOk());

        // What an operator does when a key has leaked: change it and restart.
        // Both keys working for a while would leave the leaked one live.
        new ServiceCredentialBootstrap(
                        applicationCredentials(), jdbc, java.time.Clock.systemUTC(), "opsatlas_sk_EXAMPLE_the_replacement_key_for_test")
                .provisionIfConfigured();

        mockMvc.perform(get("/api/v1/services").with(anonymous()).header(ServiceCredentials.HEADER, KEY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services")
                        .with(anonymous())
                        .header(ServiceCredentials.HEADER, "opsatlas_sk_EXAMPLE_the_replacement_key_for_test"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("using a key records that it was used, so a dead one can be removed")
    void use_is_recorded() throws Exception {
        assertThat(jdbc.queryForObject("select last_used_at from service_credential", java.sql.Timestamp.class))
                .as("never used yet")
                .isNull();

        mockMvc.perform(get("/api/v1/services").with(anonymous()).header(ServiceCredentials.HEADER, KEY))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select last_used_at from service_credential", java.sql.Timestamp.class))
                .as("nobody can safely delete a credential they cannot tell is dead")
                .isNotNull();
    }

    @Autowired
    private ServiceCredentialRepository credentials;

    private ServiceCredentialRepository applicationCredentials() {
        return credentials;
    }
}
