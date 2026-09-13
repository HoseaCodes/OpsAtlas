package com.ambitiousconcepts.opsatlas.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ambitiousconcepts.opsatlas.support.PostgresTestBase;
import com.ambitiousconcepts.opsatlas.support.StubGitHub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The sources API, over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
class SourceApiIT extends PostgresTestBase {

    private static final Path EXAMPLES = Path.of(System.getProperty("opsatlas.examples.dir"));
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubGitHub GITHUB = new StubGitHub();

    @DynamicPropertySource
    static void pointAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("opsatlas.github.base-url", GITHUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        GITHUB.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clear() {
        GITHUB.reset();
    }

    private String watch(String repository) throws Exception {
        String body = mockMvc.perform(post("/api/v1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"github\",\"repository\":\"" + repository
                                + "\",\"ref\":\"main\",\"path\":\"service.yaml\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(body).get("id").asText();
    }

    @Test
    @DisplayName("watching a repository returns the source and its location")
    void watch_a_repository() throws Exception {
        mockMvc.perform(post("/api/v1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"ambitious-concepts/orders-api\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.provider").value("github"))
                .andExpect(jsonPath("$.repository").value("ambitious-concepts/orders-api"))
                // Sensible defaults rather than required fields: most manifests
                // are at service.yaml on the default branch.
                .andExpect(jsonPath("$.ref").value("HEAD"))
                .andExpect(jsonPath("$.path").value("service.yaml"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.lastOutcome").doesNotExist())
                .andExpect(jsonPath("$.consecutiveFailures").value(0));
    }

    @Test
    @DisplayName("a malformed repository is refused with a located violation")
    void malformed_repository_is_refused() throws Exception {
        mockMvc.perform(post("/api/v1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"https://evil.example.com/a/b\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.violations[0].pointer").value("/repository"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a path that escapes the repository is refused")
    void traversal_path_is_refused() throws Exception {
        mockMvc.perform(post("/api/v1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"acme/demo\",\"path\":\"../../etc/passwd\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].pointer").value("/repository"));
    }

    @Test
    @DisplayName("an unknown provider is refused, naming the ones that exist")
    void unknown_provider_is_refused() throws Exception {
        mockMvc.perform(post("/api/v1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"gitlab\",\"repository\":\"acme/demo\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].pointer").value("/provider"))
                .andExpect(jsonPath("$.violations[0].message").value(org.hamcrest.Matchers.containsString("github")));
    }

    @Test
    @DisplayName("a manual sync reports the outcome, and registers the service")
    void sync_now_registers() throws Exception {
        GITHUB.serve("ambitious-concepts/orders-api", "service.yaml", Files.readString(EXAMPLES.resolve("orders-api.yaml")));
        String id = watch("ambitious-concepts/orders-api");

        mockMvc.perform(post("/api/v1/sources/" + id + "/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastOutcome").value("REGISTERED"))
                .andExpect(jsonPath("$.serviceId").isNotEmpty())
                .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/services/orders-api")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a failed sync is still a 200: the request worked, the sync did not")
    void failed_sync_is_not_a_server_error() throws Exception {
        // Returning 502 here would leave a caller unable to distinguish "the
        // sync ran and GitHub was down" from "this endpoint is broken".
        GITHUB.route("acme/missing", "service.yaml", new StubGitHub.Response.NotFound());
        String id = watch("acme/missing");

        mockMvc.perform(post("/api/v1/sources/" + id + "/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastOutcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.lastDetail").isNotEmpty())
                .andExpect(jsonPath("$.consecutiveFailures").value(1));
    }

    @Test
    @DisplayName("sources page with a cursor like every other collection")
    void sources_are_cursor_paged() throws Exception {
        for (String repository : new String[] {"acme/one", "acme/two", "acme/three"}) {
            watch(repository);
        }

        JsonNode first = MAPPER.readTree(mockMvc.perform(get("/api/v1/sources").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(first.get("nextCursor").isNull()).isFalse();

        mockMvc.perform(get("/api/v1/sources")
                        .param("limit", "2")
                        .param("cursor", first.get("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    @DisplayName("a source can be disabled and re-enabled without losing its history")
    void disable_and_enable() throws Exception {
        GITHUB.route("acme/missing", "service.yaml", new StubGitHub.Response.NotFound());
        String id = watch("acme/missing");
        mockMvc.perform(post("/api/v1/sources/" + id + "/sync"));

        mockMvc.perform(post("/api/v1/sources/" + id + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                // Switching a failing source off must not erase why it failed.
                .andExpect(jsonPath("$.lastOutcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.consecutiveFailures").value(1));

        mockMvc.perform(post("/api/v1/sources/" + id + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @DisplayName("unwatching returns 204 and the source is gone")
    void unwatch() throws Exception {
        String id = watch("acme/demo");

        mockMvc.perform(delete("/api/v1/sources/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/sources/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown source is a 404 with a correlation id")
    void unknown_source() throws Exception {
        mockMvc.perform(get("/api/v1/sources/" + java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
