package com.ambitiousconcepts.opsatlas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.AuthenticatedByDefault;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * That the door is shut (ADR 0013).
 *
 * <p>The rest of the suite authenticates by default, which is what lets two
 * hundred tests run through the security filter chain instead of around it - and
 * it is exactly why this file has to exist. A default that quietly stopped
 * applying would leave every one of those tests passing, and a filter chain that
 * quietly stopped requiring anything would too. The only thing that catches
 * either is a test that asks without a token and expects to be refused.
 *
 * <p>Every case below overrides the default with {@code anonymous()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIT extends PostgresTestBase {

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("reading the catalog without a token is refused")
    void an_unauthenticated_read_is_refused() throws Exception {
        mockMvc.perform(get("/api/v1/services").with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("registering a service without a token is refused")
    void an_unauthenticated_write_is_refused() throws Exception {
        // The endpoint that mattered most before this phase: anything that could
        // reach the port could put a service in the catalog.
        mockMvc.perform(post("/api/v1/services")
                        .with(anonymous())
                        .contentType(YAML)
                        .content("apiVersion: opsatlas.ambitiousconcepts.io/v1\nkind: Service\n"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reporting observations without a token is refused")
    void an_unauthenticated_observation_is_refused() throws Exception {
        // Fabricated health is the write with no audit trail: results are folded
        // into counters, so there is no per-probe row to compare against later.
        mockMvc.perform(post("/api/v1/observations")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"forged-batch-01\",\"observerId\":\"nobody\",\"observations\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token is what makes the difference, not the endpoint")
    void the_same_request_succeeds_with_a_token() throws Exception {
        // Same URL, same method, one difference. Without this, "401 everywhere"
        // would also be satisfied by an application that was simply broken.
        mockMvc.perform(get("/api/v1/services").with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/services")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a verified token for somebody this system does not know is refused")
    void an_unprovisioned_caller_is_refused() throws Exception {
        // The distinction this phase exists to make. Storm-Gate mints tokens for
        // its own accounts, and its accounts are not this catalog's users - so
        // "the signature checks out" cannot mean "may read the fleet", or every
        // deployment sharing an identity provider would be readable by everyone
        // on it.
        mockMvc.perform(get("/api/v1/services")
                        .with(jwt().jwt(token -> token.issuer(AuthenticatedByDefault.ISSUER)
                                .claim("id", "a-real-account-nobody-provisioned"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type")
                        .value("https://opsatlas.ambitiousconcepts.io/problems/not-provisioned"));
    }

    @Test
    @DisplayName("the same subject from a different issuer is a different person")
    void the_issuer_is_part_of_the_identity() throws Exception {
        // Two identity providers can hand out the same opaque id. Matching on
        // the subject alone would hand one organization's catalog to whoever
        // happened to collide with it elsewhere.
        mockMvc.perform(get("/api/v1/services")
                        .with(jwt().jwt(token -> token.issuer("https://somewhere-else.test.invalid")
                                .claim("id", AuthenticatedByDefault.SUBJECT))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("being turned away is a problem document, like every other error")
    void a_refusal_carries_the_one_error_shape() throws Exception {
        // CLAUDE.md section 9: one shape everywhere, carrying a correlationId.
        // Spring Security answers 401 with an empty body by default, and an empty
        // 401 cannot say whether the token was missing, expired or simply unknown.
        String body = mockMvc.perform(get("/api/v1/services").with(anonymous()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"correlationId\"").contains("problems/unauthenticated");
    }

    @Test
    @DisplayName("liveness stays open, because a load balancer has no token to offer")
    void health_is_reachable_without_a_token() throws Exception {
        mockMvc.perform(get("/actuator/health").with(anonymous())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the generated contract stays open; it describes the API and holds no data")
    void the_api_document_is_reachable_without_a_token() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(anonymous())).andExpect(status().isOk());
    }
}
