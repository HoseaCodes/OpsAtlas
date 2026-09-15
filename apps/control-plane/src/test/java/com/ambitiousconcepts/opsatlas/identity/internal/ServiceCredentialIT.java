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
@TestPropertySource(
        properties = "opsatlas.credentials.keys={observer: 'opsatlas_sk_EXAMPLE_not_a_real_key_for_tests_01'}")
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
                        applicationCredentials(),
                        jdbc,
                        java.time.Clock.systemUTC(),
                        java.util.Map.of("observer", "opsatlas_sk_EXAMPLE_the_replacement_key_for_test"))
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

    @Test
    @DisplayName("a key also works on Authorization, for callers that can send nothing else")
    void the_key_is_accepted_as_a_bearer_credential() throws Exception {
        // Prometheus scrape configuration offers `authorization` and basic auth
        // and no way to set an arbitrary header. Without this the metrics
        // endpoints could only be left open.
        mockMvc.perform(get("/api/v1/services").with(anonymous()).header("Authorization", "Bearer " + KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a bearer value that is not one of our keys is left to the token decoder")
    void a_real_token_is_not_intercepted() throws Exception {
        // The two mechanisms must never contend for the same string. Anything
        // without this system's key prefix is passed through untouched - here it
        // reaches the JWT decoder, fails to parse, and is refused as a token
        // rather than as a bad key.
        mockMvc.perform(get("/api/v1/services")
                        .with(anonymous())
                        .header("Authorization", "Bearer not.a.real.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("metrics need a credential now, and liveness still does not")
    void the_metrics_endpoints_are_no_longer_open() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(anonymous())).andExpect(status().isUnauthorized());

        // Not asserting 200: whether the Prometheus endpoint is registered at all
        // depends on a meter registry this context does not configure, and a 404
        // from the dispatcher is still proof that authorization let the request
        // through - which is the rule under test.
        mockMvc.perform(get("/actuator/prometheus").with(anonymous()).header(ServiceCredentials.HEADER, KEY))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getStatus())
                        .as("a credentialled scrape must not be refused")
                        .isNotEqualTo(401));

        // A load balancer has no credential to offer, and liveness discloses
        // nothing worth protecting.
        mockMvc.perform(get("/actuator/health").with(anonymous())).andExpect(status().isOk());
    }

    @Autowired
    private ServiceCredentialRepository credentials;

    private ServiceCredentialRepository applicationCredentials() {
        return credentials;
    }
}
