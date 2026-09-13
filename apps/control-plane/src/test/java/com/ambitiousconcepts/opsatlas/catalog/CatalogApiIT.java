package com.ambitiousconcepts.opsatlas.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.shared.CorrelationIdFilter;
import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The phase 1 API contract, against a real PostgreSQL.
 *
 * <p>The catalog is empty in this phase because registration arrives in phase 2.
 * What is being verified is everything around that emptiness: the pagination
 * envelope, the organization scoping, the correlation id, and the error shape.
 * Phase 2 writes rows into a contract that is already proven.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiIT extends PostgresTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void an_empty_catalog_is_an_empty_page_not_an_error() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());

        // Serialized as null rather than omitted. The field is part of the
        // contract on every page, so a client never has to distinguish "there
        // are no more pages" from "this server forgot to tell me".
        //
        // Asserted on the raw body rather than with jsonPath, because jsonPath
        // treats an explicit null as absent and would pass either way - which
        // is precisely the distinction being tested.
        String body = mockMvc.perform(get("/api/v1/services"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).isEqualTo("{\"items\":[],\"nextCursor\":null}");
    }

    @Test
    void a_supplied_correlation_id_is_echoed_back() throws Exception {
        mockMvc.perform(get("/api/v1/services").header(CorrelationIdFilter.HEADER, "trace-abc-12345"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "trace-abc-12345"));
    }

    @Test
    void a_correlation_id_is_generated_when_none_is_supplied() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER))
                .isNotBlank()
                .matches("[A-Za-z0-9_-]{8,64}");
    }

    @Test
    void an_implausible_correlation_id_is_replaced_rather_than_echoed() throws Exception {
        // The inbound value reaches log files and a response header. Echoing an
        // arbitrary client string would be a log-injection and header-splitting
        // vector, so it is constrained; the request itself still succeeds.
        String hostile = "abc\r\nX-Injected: yes";

        MvcResult result = mockMvc.perform(get("/api/v1/services").header(CorrelationIdFilter.HEADER, hostile))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER)).doesNotContain("X-Injected");
    }

    @Test
    void a_malformed_cursor_is_a_problem_document_not_a_stack_trace() throws Exception {
        mockMvc.perform(get("/api/v1/services").param("cursor", "not-a-real-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/invalid-cursor"))
                .andExpect(jsonPath("$.title").value("Invalid pagination cursor"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                // CLAUDE.md section 9: correlationId on every response and error.
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.violations[0].pointer").value("/cursor"));
    }

    @Test
    void a_limit_outside_the_permitted_range_names_the_parameter_and_the_constraint() throws Exception {
        // "One or more parameters were invalid" makes the caller guess which one.
        // The parameter name and the constraint both have to reach the response.
        mockMvc.perform(get("/api/v1/services").param("limit", "10000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/invalid-parameter"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.violations[0].pointer").value("/limit"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("limit")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    void a_limit_below_the_permitted_range_is_rejected_too() throws Exception {
        mockMvc.perform(get("/api/v1/services").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].pointer").value("/limit"));
    }

    @Test
    void a_limit_that_is_not_a_number_is_rejected_with_the_standard_shape() throws Exception {
        mockMvc.perform(get("/api/v1/services").param("limit", "twenty"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://opsatlas.ambitiousconcepts.io/problems/invalid-parameter"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("limit")));
    }

    @Test
    void an_unmapped_path_is_a_problem_document_too() throws Exception {
        mockMvc.perform(get("/api/v1/services/../../etc/passwd"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void the_health_endpoint_reports_the_database() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
