package com.ai.assistant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.JSON;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentHttpClientTest {

  private HttpServer server;
  private ExecutorService workers;
  private final AgentHttpClient client = new AgentHttpClient();
  private final AtomicReference<String> requestId = new AtomicReference<>();
  private final AtomicReference<String> deadlineHeader = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();

  @AfterEach
  void stopServer() throws IOException {
    UserContext.clear();
    client.close();
    if (server != null) server.stop(0);
    if (workers != null) workers.shutdownNow();
  }

  @Test
  void returnsBodyForSuccessfulResponse() throws Exception {
    startServer(200, "{\"reply\":\"ok\"}");
    String body =
        client
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
            client
                .doPostJson(url(), Map.of("message", "test"), 2000, "wrong", 1L, "trace-test-2"));
  }

  @Test
  void rejectsEmptySuccessfulResponse() throws Exception {
    startServer(204, "");
    assertThrows(
        IOException.class,
        () ->
            client
                .doPostJson(url(), Map.of("message", "test"), 2000, "secret", 1L, "trace-test-3"));
  }

  @Test
  void acceptedRequestWithoutResponseHasAResponseTimeout() throws Exception {
    var received = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    startServer(exchange -> {
      received.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    try {
      assertThrows(SocketTimeoutException.class, () -> client.doPostJson(
          url(), Map.of("message", "test"), 200, "secret", 1L, "trace-response-timeout"));
      assertTrue(received.await(1, TimeUnit.SECONDS), "the Agent accepted the HTTP request");
    } finally {
      release.countDown();
    }
  }

  @Test
  void expiredDeadlineRejectsBeforeOpeningConnection() throws Exception {
    startServer(200, "{\"reply\":\"ok\"}");
    assertThrows(SocketTimeoutException.class, () -> client.doPostJson(url(),
        Map.of("message", "test", "deadlineEpochMs", System.currentTimeMillis() - 1),
        2000, "secret", 1L, "expired"));
    assertNull(requestId.get(), "an expired request must not reach the Agent");
  }

  @Test
  void trustedDeadlineIsIdenticalInHeaderAndCopiedPayload() throws Exception {
    startServer(200, "{\"reply\":\"ok\"}");
    long trusted = System.currentTimeMillis() + 3000;
    long requested = trusted + 10000;
    var payload = Map.of("merchantId", 0, "deadlineEpochMs", requested);
    UserContext.setTrustedDeadline(trusted);
    assertEquals("{\"reply\":\"ok\"}", client.doPostJson(
        url(), payload, 5000, "secret", 1L, "bounded"));
    assertEquals(trusted, Long.parseLong(deadlineHeader.get()));
    assertEquals(trusted, JSON.parseObject(requestBody.get()).getLongValue("deadlineEpochMs"));
    assertEquals(requested, ((Number) payload.get("deadlineEpochMs")).longValue(),
        "do not mutate the caller's map");
  }

  @Test
  void absoluteDeadlineCancelsAContinuouslyStreamingBody() throws Exception {
    var received = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    startServer(exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 0);
      received.countDown();
      try {
        for (int i = 0; i < 40; i++) {
          exchange.getResponseBody().write('x');
          exchange.getResponseBody().flush();
          if (release.await(50, TimeUnit.MILLISECONDS)) break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // The deadline closes this streaming socket.
      } finally { exchange.close(); }
    });
    long started = System.nanoTime();
    try {
      assertThrows(SocketTimeoutException.class, () -> client.doPostJson(url(),
          Map.of("message", "test", "deadlineEpochMs", System.currentTimeMillis() + 400),
          5000, "secret", 1L, "streaming"));
      assertTrue(received.await(1, TimeUnit.SECONDS));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1600,
          "regular body bytes must not extend the shared request deadline");
    } finally { release.countDown(); }
  }

  @Test
  void completedRequestTimerDoesNotCancelTheNextPooledRequest() throws Exception {
    var firstConnection = new AtomicReference<InetSocketAddress>();
    var secondConnection = new AtomicReference<InetSocketAddress>();
    startServer(exchange -> {
      exchange.getRequestBody().readAllBytes();
      if ("second".equals(exchange.getRequestHeaders().getFirst("X-Request-Id"))) {
        secondConnection.set(exchange.getRemoteAddress());
        try { Thread.sleep(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      } else firstConnection.set(exchange.getRemoteAddress());
      byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    assertEquals("ok", client.doPostJson(url(),
        Map.of("deadlineEpochMs", System.currentTimeMillis() + 700), 3000, "secret", 1L, "first"));
    assertEquals("ok", client.doPostJson(url(),
        Map.of("deadlineEpochMs", System.currentTimeMillis() + 3000), 3000, "secret", 1L, "second"));
    assertEquals(firstConnection.get(), secondConnection.get(), "exercise an actually reused connection");
  }

  private void startServer(int status, String responseBody) throws IOException {
    startServer(
        exchange -> {
          requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
          deadlineHeader.set(exchange.getRequestHeaders().getFirst("X-Agent-Deadline"));
          requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          if (bytes.length > 0) exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private void startServer(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newCachedThreadPool(task -> {
      var thread = new Thread(task, "test-agent-http");
      thread.setDaemon(true);
      return thread;
    });
    server.setExecutor(workers);
    server.createContext("/chat", handler);
    server.start();
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
  }
}
