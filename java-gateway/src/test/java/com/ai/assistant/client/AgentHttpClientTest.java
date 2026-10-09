package com.ai.assistant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentHttpClientTest {

  private HttpServer server;
  private final AtomicReference<String> requestId = new AtomicReference<>();

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void returnsBodyForSuccessfulResponse() throws Exception {
    startServer(200, "{\"reply\":\"ok\"}");
    String body =
        new AgentHttpClient()
            .doPostJson(url(), Map.of("message", "test"), 2000, "secret", 1L, "trace-test-1");
    assertEquals("{\"reply\":\"ok\"}", body);
    assertEquals("trace-test-1", requestId.get());
  }

  @Test
  void rejectsNonSuccessfulAgentResponse() throws Exception {
    startServer(401, "{\"detail\":\"unauthorized\"}");
    assertThrows(
        com.ai.assistant.service.BusinessException.class,
        () ->
            new AgentHttpClient()
                .doPostJson(url(), Map.of("message", "test"), 2000, "wrong", 1L, "trace-test-2"));
  }

  @Test
  void rejectsEmptySuccessfulResponse() throws Exception {
    startServer(204, "");
    assertThrows(
        IOException.class,
        () ->
            new AgentHttpClient()
                .doPostJson(url(), Map.of("message", "test"), 2000, "secret", 1L, "trace-test-3"));
  }

  @Test
  void acceptedRequestWithoutResponseHasAResponseTimeout() throws Exception {
    var received = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat", exchange -> {
      received.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    var client = new AgentHttpClient();
    try {
      assertThrows(SocketTimeoutException.class, () -> client.doPostJson(
          url(), Map.of("message", "test"), 200, "secret", 1L, "trace-response-timeout"));
      assertTrue(received.await(1, TimeUnit.SECONDS), "the Agent accepted the HTTP request");
    } finally {
      release.countDown();
      client.close();
    }
  }

  private void startServer(int status, String responseBody) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat",
        exchange -> {
          requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
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
