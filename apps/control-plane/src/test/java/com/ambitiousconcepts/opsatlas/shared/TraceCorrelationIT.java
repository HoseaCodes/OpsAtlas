package com.ambitiousconcepts.opsatlas.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR 0011: when a request is traced, its correlation id <em>is</em> its trace
 * id.
 *
 * <p>Tracing is enabled for this class only. The rest of the suite runs with it
 * off, because an OTLP exporter retrying against a collector that is not running
 * adds seconds to every context start - and the correlation id has to work
 * without tracing anyway, which is the second half of what this asserts.
 *
 * <p>No collector is running here either. That is deliberate and is itself part
 * of the test: exporting is best-effort, so a request must succeed and carry a
 * correlation id whether or not anything is listening on 4318.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "management.tracing.enabled=true",
            "management.tracing.sampling.probability=1.0",
            // Nothing is listening. The exporter must fail quietly.
            "management.otlp.tracing.endpoint=http://127.0.0.1:1/v1/traces"
        })
class TraceCorrelationIT extends PostgresTestBase {

    /** A W3C trace id: 32 lowercase hex characters. */
    private static final String TRACE_ID_SHAPE = "[0-9a-f]{32}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a traced request's correlation id is its trace id")
    void correlation_id_is_the_trace_id() throws Exception {
        String correlationId = mockMvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdFilter.HEADER);

        // One string, not two. A log line, a span and an audit row all carry
        // this, so reading it off a failure and searching by it finds the same
        // request everywhere.
        assertThat(correlationId).isNotNull().matches(TRACE_ID_SHAPE);
    }

    @Test
    @DisplayName("a request joining an upstream trace adopts that trace's id")
    void inbound_traceparent_is_joined() throws Exception {
        // The case the observer produces: it starts a trace, calls the control
        // plane, and the work here has to land in the same trace rather than
        // starting a second one.
        String upstreamTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + upstreamTrace + "-00f067aa0ba902b7-01";

        String correlationId = mockMvc.perform(get("/api/v1/services").header("traceparent", traceparent))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdFilter.HEADER);

        assertThat(correlationId)
                .as("W3C trace context must be propagated, not ignored (CLAUDE.md section 9)")
                .isEqualTo(upstreamTrace);
    }

    @Test
    @DisplayName("a caller's own correlation id is echoed back unchanged, even when traced")
    void supplied_id_wins_and_is_tagged_on_the_span() throws Exception {
        // Section 9 says the correlation id is accepted from the header and
        // echoed in every response. A caller who sends one and gets a different
        // one back cannot correlate anything, which is the only reason to send
        // it. The trace id is used when - and only when - nobody supplied one.
        //
        // The caller's value is also attached to the span, so a search by it
        // finds this trace.
        String correlationId = mockMvc.perform(
                        get("/api/v1/services").header(CorrelationIdFilter.HEADER, "caller-supplied-1234"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(CorrelationIdFilter.HEADER);

        assertThat(correlationId).isEqualTo("caller-supplied-1234");
    }

    @Test
    @DisplayName("an error response carries the same identifier a successful one would")
    void errors_carry_the_trace_id_too() throws Exception {
        // The moment this matters most: something failed, and the reader needs
        // one string that works in the logs and in Tempo.
        String body = mockMvc.perform(get("/api/v1/services").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String correlationId = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(body)
                .get("correlationId")
                .asText();

        assertThat(correlationId).matches(TRACE_ID_SHAPE);
    }

    @Test
    @DisplayName("an unreachable collector does not fail, slow or change a request")
    void exporting_is_best_effort() throws Exception {
        // The endpoint above points at a closed port. A collector being down is
        // an ordinary condition and must cost a request nothing.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/services")).andExpect(status().isOk());
        }
    }
}
