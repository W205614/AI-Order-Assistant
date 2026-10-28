package com.ai.assistant.client;

import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** 调用 Python LangGraph Agent 服务的 HTTP 客户端。 支持任意嵌套 JSON body（history 数组）与可配置长超时（LLM 推理慢）。 */
@Component
@Slf4j
public class AgentHttpClient implements AutoCloseable {

  /** 连接池随 Spring 单例复用；不能按每个聊天请求重复建连。 */
  private final CloseableHttpClient httpClient =
      HttpClients.custom().evictExpiredConnections().build();
  private final ScheduledThreadPoolExecutor deadlines = deadlineTimer();

  private static ScheduledThreadPoolExecutor deadlineTimer() {
    var timer = new ScheduledThreadPoolExecutor(1, task -> {
      var thread = new Thread(task, "agent-http-deadline");
      thread.setDaemon(true);
      return thread;
    });
    timer.setRemoveOnCancelPolicy(true);
    timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return timer;
  }

  /**
   * 向 Python Agent 发送 JSON POST 请求
   *
   * @param url 完整 URL
   * @param body 任意 Java 对象（会被 fastjson2 序列化为 JSON）
   * @param timeoutMs 超时(毫秒)
   * @return 响应体字符串
   */
  public String doPostJson(
      String url, Object body, long timeoutMs, String internalApiKey, Long userId, String requestId)
      throws IOException {
    if (timeoutMs <= 0) throw new IllegalArgumentException("Agent timeout must be positive");
    long deadline = UserContext.effectiveDeadline(System.currentTimeMillis() + timeoutMs);
    if (body instanceof java.util.Map<?, ?> map && map.get("deadlineEpochMs") instanceof Number value)
      deadline = Math.min(deadline, value.longValue());
    long remaining = remainingMillis(deadline);
    HttpPost httpPost = new HttpPost(url);
    RequestConfig config =
        RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(Math.min(2000, remaining)))
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(1000, remaining)))
            .setResponseTimeout(Timeout.ofMilliseconds(remaining))
            .build();
    httpPost.setConfig(config);
    Object payload = body;
    if (body instanceof java.util.Map<?, ?> map) {
      httpPost.setHeader("X-Agent-Merchant-Id", String.valueOf(map.get("merchantId")));
      httpPost.setHeader("X-Agent-Deadline", Long.toString(deadline));
      var bounded = new LinkedHashMap<Object, Object>(map);
      bounded.put("deadlineEpochMs", deadline);
      payload = bounded;
    }
    if (internalApiKey != null && !internalApiKey.isBlank()) {
      httpPost.setHeader("X-Agent-Internal-Key", internalApiKey);
    }
    if (userId != null) {
      httpPost.setHeader("X-Agent-User-Id", String.valueOf(userId));
    }
    if (requestId != null && !requestId.isBlank()) {
      httpPost.setHeader("X-Request-Id", requestId);
    }

    String json = JSON.toJSONString(payload);
    StringEntity entity = new StringEntity(json, ContentType.APPLICATION_JSON);
    httpPost.setEntity(entity);

    // Log URL and size only (full Chinese body would garble in GBK console and spam logs)
    log.info("Forward to Agent: POST {} traceId={} (body {} chars)", url, requestId, json.length());
    // A phase timeout alone permits connect + pool + body waits to accumulate.
    // Cancel this request at the shared absolute deadline, including slow bodies.
    try (var cancellation = new RequestDeadline(httpPost, deadline)) {
      try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
        boolean consumed = false;
        try {
          String responseBody =
              response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), "UTF-8");
          consumed = true;
          cancellation.complete();
          int status = response.getCode();
          if (status < 200 || status >= 300) {
            log.warn("Agent request failed: HTTP {}", status);
            throw new com.ai.assistant.service.BusinessException(
                org.springframework.http.HttpStatus.valueOf(
                    status == 429 ? 429 : status == 504 ? 504 : 503),
                status == 429 ? "AI_CAPACITY" : status == 504 ? "AI_TIMEOUT" : "AI_UNAVAILABLE",
                "AI 服务暂时不可用，请通过菜单继续点餐");
          }
          if (responseBody.isBlank()) throw new IOException("Agent 服务返回空响应");
          return responseBody;
        } finally {
          // Disarm before response.close() can return a connection to the pool.
          cancellation.close();
          // Do not drain an incomplete body after cancelling its deadline task.
          if (!consumed) response.close(CloseMode.IMMEDIATE);
        }
      } catch (org.apache.hc.core5.http.ParseException e) {
        if (cancellation.expired() || System.currentTimeMillis() >= deadline) {
          var timeout = timeout();
          timeout.initCause(e);
          throw timeout;
        }
        throw new IOException("解析 Agent 响应失败", e);
      } catch (IOException e) {
        if (cancellation.expired() || System.currentTimeMillis() >= deadline) {
          var timeout = timeout();
          timeout.initCause(e);
          throw timeout;
        }
        throw e;
      }
    }
  }

  private static long remainingMillis(long deadline) throws SocketTimeoutException {
    long remaining = deadline - System.currentTimeMillis();
    if (remaining <= 0) throw timeout();
    return remaining;
  }

  private static SocketTimeoutException timeout() {
    return new SocketTimeoutException("Agent request deadline exceeded");
  }

  private final class RequestDeadline implements AutoCloseable {
    private final long deadline;
    private final ScheduledFuture<?> timer;
    private boolean active = true;
    private boolean expired;

    private RequestDeadline(HttpPost request, long deadline) throws SocketTimeoutException {
      this.deadline = deadline;
      timer = deadlines.schedule(() -> {
        synchronized (this) {
          if (!active) return;
          active = false;
          expired = true;
          request.cancel();
        }
      }, remainingMillis(deadline), TimeUnit.MILLISECONDS);
    }

    private synchronized boolean expired() { return expired; }

    private synchronized void complete() throws SocketTimeoutException {
      if (expired) throw timeout();
      remainingMillis(deadline);
      active = false;
      timer.cancel(false);
    }

    @Override public void close() {
      synchronized (this) { active = false; }
      timer.cancel(false);
    }
  }

  @PreDestroy
  @Override public void close() throws IOException {
    deadlines.shutdownNow();
    httpClient.close();
  }
}
