package com.ambitiousconcepts.opsatlas.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The first way in.
 *
 * <p>Called directly rather than waited for: it runs on application start, and
 * {@code PostgresTestBase} empties the table before each test, so the row it
 * created at startup is gone by the time a test body runs. Calling it is also
 * the only way to assert it twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "opsatlas.bootstrap.principal.issuer=https://bootstrap.test.invalid",
            "opsatlas.bootstrap.principal.subject=first-operator",
            "opsatlas.bootstrap.principal.display-name=First Operator"
        })
class PrincipalBootstrapIT extends PostgresTestBase {

    private static final String ISSUER = "https://bootstrap.test.invalid";
    private static final String SUBJECT = "first-operator";

    @Autowired
    private PrincipalBootstrap bootstrap;

    @Autowired
    private PrincipalRepository principals;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the configured identity is provisioned into the one organization")
    void it_provisions_the_configured_identity() {
        bootstrap.provisionIfConfigured();

        assertThat(principals.findByIssuerAndSubject(ISSUER, SUBJECT))
                .as("a deployment that admits nobody is the problem this exists to solve")
                .isPresent()
                .get()
                .satisfies(found -> assertThat(found.getDisplayName()).isEqualTo("First Operator"));
    }

    @Test
    @DisplayName("running it again changes nothing")
    void it_is_idempotent() {
        // It runs on every start, and the variables are meant to be safe to
        // leave set. A second row for the same identity would be a duplicate
        // the unique constraint would refuse - loudly, on startup, in a
        // deployment that was working yesterday.
        bootstrap.provisionIfConfigured();
        bootstrap.provisionIfConfigured();
        bootstrap.provisionIfConfigured();

        assertThat(jdbc.queryForObject(
                        "select count(*) from principal where issuer = ? and subject = ?",
                        Integer.class,
                        ISSUER,
                        SUBJECT))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a bootstrapped identity can actually get in")
    void the_provisioned_identity_is_accepted() throws Exception {
        // The property worth testing end to end: provisioning is not a row in a
        // table, it is somebody being able to read the catalog.
        mockMvc.perform(get("/api/v1/services")
                        .with(jwt().jwt(token -> token.issuer(ISSUER).claim("id", SUBJECT))))
                .andExpect(status().isForbidden());

        bootstrap.provisionIfConfigured();

        mockMvc.perform(get("/api/v1/services")
                        .with(jwt().jwt(token -> token.issuer(ISSUER).claim("id", SUBJECT))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("half an identity refuses to start rather than provisioning nobody")
    void half_a_configuration_is_refused() {
        // A deployment with a subject and no issuer looks configured and admits
        // no one, and the symptom arrives later as "my token does not work".
        PrincipalBootstrap halfConfigured =
                new PrincipalBootstrap(principals, jdbc, clock, "", SUBJECT, "");

        assertThatThrownBy(halfConfigured::provisionIfConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    @DisplayName("configuring nothing provisions nothing")
    void no_configuration_provisions_nobody() {
        // The ordinary case for a deployment whose people are already set up.
        new PrincipalBootstrap(principals, jdbc, clock, "", "", "").provisionIfConfigured();

        assertThat(jdbc.queryForObject("select count(*) from principal where subject = ?", Integer.class, SUBJECT))
                .isZero();
    }
}
