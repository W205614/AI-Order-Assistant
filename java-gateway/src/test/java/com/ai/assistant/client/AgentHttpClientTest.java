package com.ai.assistant.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentHttpClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void returnsBodyForSuccessfulResponse() throws Exception {
        startServer(200, "{\"reply\":\"ok\"}");
        String body = new AgentHttpClient().doPostJson(url(), Map.of("message", "test"), 2000, "secret", 1L);
        assertEquals("{\"reply\":\"ok\"}", body);
    }

    @Test
    void rejectsNonSuccessfulAgentResponse() throws Exception {
        startServer(401, "{\"detail\":\"unauthorized\"}");
        assertThrows(IOException.class,
                () -> new AgentHttpClient().doPostJson(url(), Map.of("message", "test"), 2000, "wrong", 1L));
    }

    @Test
    void rejectsEmptySuccessfulResponse() throws Exception {
        startServer(204, "");
        assertThrows(IOException.class,
                () -> new AgentHttpClient().doPostJson(url(), Map.of("message", "test"), 2000, "secret", 1L));
    }

    private void startServer(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }
}
