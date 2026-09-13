package com.ambitiousconcepts.opsatlas.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A GitHub Contents API that answers on localhost.
 *
 * <p>A real HTTP server rather than a mocked {@code RestClient}, because the
 * behaviour worth testing here <em>is</em> HTTP: conditional requests, ETags,
 * the 403-versus-rate-limit ambiguity GitHub actually has, a body larger than
 * the cap, and a connection that is simply refused. A mock would assert that the
 * code calls the methods it calls, which is not the same thing.
 *
 * <p>Uses the JDK's own {@code com.sun.net.httpserver}, so no dependency is
 * added to test a dependency-free concern.
 */
public final class StubGitHub implements AutoCloseable {

    /** What the stub should do for a given repository and path. */
    public sealed interface Response {
        record Ok(String body, String etag) implements Response {}

        record NotFound() implements Response {}

        record Unauthorized() implements Response {}

        record Forbidden() implements Response {}

        record RateLimited() implements Response {}

        record Oversized(int bytes) implements Response {}

        record ServerError() implements Response {}
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> ifNoneMatchSeen = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    public StubGitHub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub GitHub", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Route a repository and path to a response. Key is exactly what the reader will request. */
    public StubGitHub route(String repository, String path, Response response) {
        routes.put("/repos/" + repository + "/contents/" + path, response);
        return this;
    }

    public StubGitHub serve(String repository, String path, String body) {
        return route(repository, path, new Response.Ok(body, "\"etag-" + body.hashCode() + "\""));
    }

    public List<String> requestPaths() {
        return List.copyOf(requests);
    }

    public List<String> ifNoneMatchHeaders() {
        return List.copyOf(ifNoneMatchSeen);
    }

    public int requestCount() {
        return requestCount.get();
    }

    public void reset() {
        requests.clear();
        ifNoneMatchSeen.clear();
        requestCount.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestURI().toString());

        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null) {
            ifNoneMatchSeen.add(ifNoneMatch);
        }

        Response response = routes.get(path);
        if (response == null) {
            send(exchange, 404, "{\"message\":\"Not Found\"}");
            return;
        }

        switch (response) {
            case Response.Ok ok -> {
                // The conditional request, which is the whole economy of
                // polling: a matching ETag transfers nothing.
                if (ok.etag() != null && ok.etag().equals(ifNoneMatch)) {
                    exchange.getResponseHeaders().set("ETag", ok.etag());
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                if (ok.etag() != null) {
                    exchange.getResponseHeaders().set("ETag", ok.etag());
                }
                send(exchange, 200, ok.body());
            }
            case Response.NotFound ignored -> send(exchange, 404, "{\"message\":\"Not Found\"}");
            case Response.Unauthorized ignored -> send(exchange, 401, "{\"message\":\"Bad credentials\"}");
            case Response.Forbidden ignored -> {
                // GitHub's genuinely ambiguous case: 403 with quota remaining is
                // "you may not", 403 with zero remaining is "not right now".
                exchange.getResponseHeaders().set("x-ratelimit-remaining", "4999");
                send(exchange, 403, "{\"message\":\"Resource not accessible\"}");
            }
            case Response.RateLimited ignored -> {
                exchange.getResponseHeaders().set("x-ratelimit-remaining", "0");
                exchange.getResponseHeaders()
                        .set("x-ratelimit-reset", String.valueOf(java.time.Instant.now().plusSeconds(600).getEpochSecond()));
                send(exchange, 403, "{\"message\":\"API rate limit exceeded\"}");
            }
            case Response.Oversized oversized -> send(exchange, 200, "x".repeat(oversized.bytes()));
            case Response.ServerError ignored -> send(exchange, 500, "{\"message\":\"Server Error\"}");
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
