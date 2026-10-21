package com.zzzzyj.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.config.RerankProperties;
import com.zzzzyj.smartpai.service.ModelProviderConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RerankClientTest {
    private final RerankClient client = new RerankClient(new RerankProperties(),
            mock(ModelProviderConfigService.class), new ObjectMapper());

    @Test
    void mapsIndicesAndSortsScores() {
        String response = """
                {"output":{"results":[{"index":0,"relevance_score":0.1},{"index":1,"relevance_score":0.9}]}}
                """;
        assertEquals(List.of(new RerankClient.RerankHit(1, 0.9), new RerankClient.RerankHit(0, 0.1)),
                client.parseResponse(response, 2));
    }

    @Test
    void rejectsMalformedOrIncompleteResponseForFallback() {
        assertThrows(IllegalStateException.class, () -> client.parseResponse("{}", 1));
        assertThrows(IllegalStateException.class,
                () -> client.parseResponse("{\"output\":{\"results\":[]}}", 1));
        assertThrows(IllegalStateException.class, () -> client.parseResponse(
                "{\"output\":{\"results\":[{\"index\":5,\"relevance_score\":0.9}]}}", 1));
    }
    @Test
    void postsQueryAndDocumentsToHttpEndpoint() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.8}]}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            RerankProperties settings = new RerankProperties();
            settings.setApiKey("test-key");
            settings.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
            RerankClient httpClient = new RerankClient(settings,
                    mock(ModelProviderConfigService.class), new ObjectMapper());
            assertEquals(List.of(new RerankClient.RerankHit(0, 0.8)), httpClient.rerank("question", List.of("text")));
            var body = new ObjectMapper().readTree(requestBody.get());
            assertEquals("question", body.path("input").path("query").asText());
            assertEquals("text", body.path("input").path("documents").get(0).asText());
            assertEquals(1, body.path("parameters").path("top_n").asInt());
            assertEquals("Bearer test-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

}
