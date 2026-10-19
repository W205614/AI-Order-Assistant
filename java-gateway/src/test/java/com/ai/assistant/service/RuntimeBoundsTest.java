package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ai.assistant.security.*;
import com.ai.assistant.config.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;

class RuntimeBoundsTest {
  @AfterEach void clear() { UserContext.clear(); }

  @Test void actualByteLimitCoversUnknownLengthBulkSingleAndReader() throws Exception {
    for (boolean reader : List.of(false, true)) {
      MockHttpServletRequest request = new MockHttpServletRequest() {
        @Override public long getContentLengthLong() { return -1; }
      };
      request.setContent(new byte[LimitedBodyRequest.MAX_BYTES]);
      assertEquals(LimitedBodyRequest.MAX_BYTES, new LimitedBodyRequest(request).getInputStream().readAllBytes().length);
      request = new MockHttpServletRequest();
      byte[] bytes = new byte[LimitedBodyRequest.MAX_BYTES + 1];
      Arrays.fill(bytes, (byte)'a');
      request.setContent(bytes);
      var limited = new LimitedBodyRequest(request);
      if (reader) assertThrows(LimitedBodyRequest.TooLarge.class, () -> limited.getReader().transferTo(java.io.Writer.nullWriter()));
      else assertThrows(LimitedBodyRequest.TooLarge.class, () -> limited.getInputStream().readAllBytes());
    }
    var cause = new LimitedBodyRequest.TooLarge();
    var handler = new GlobalExceptionHandler();
    assertEquals(413, handler.invalid(new HttpMessageNotReadableException("wrapped", cause,
        new org.springframework.mock.http.MockHttpInputMessage(new byte[0]))).getStatusCode().value());
    assertEquals(504, handler.databaseTimeout(new org.springframework.dao.QueryTimeoutException("timeout")).getStatusCode().value());
    assertEquals(503, handler.databaseUnavailable(new org.springframework.dao.DataAccessResourceFailureException("network")).getStatusCode().value());
  }

  @Test void boundedDispatchRejectsWithoutCallerRunsAndOnlyOneTaskPerConnection() throws Exception {
    var dispatch = new EventDispatch(1, 1);
    var running = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      AtomicBoolean first = new AtomicBoolean();
      dispatch.submit(first, () -> {
        running.countDown();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }, () -> fail("first rejected"));
      assertTrue(running.await(2, TimeUnit.SECONDS));
      dispatch.submit(first, () -> fail("overlapping connection task"), () -> fail("busy rejected"));
      var queued = new CountDownLatch(1);
      dispatch.submit(new AtomicBoolean(), queued::countDown, () -> fail("queue rejected"));
      var rejected = new AtomicBoolean();
      assertTimeout(java.time.Duration.ofMillis(300), () -> dispatch.submit(new AtomicBoolean(),
          () -> fail("caller runs"), () -> rejected.set(true)));
      assertTrue(rejected.get());
      assertEquals(1, dispatch.queued());
      release.countDown();
      assertTrue(queued.await(2, TimeUnit.SECONDS));
    } finally { release.countDown(); dispatch.stop(); }
  }

  @Test void bothBrokersDoDatabaseReplayOffSchedulerAndCaptureTenant() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
      assertTrue(Thread.currentThread().getName().startsWith("event-sender"));
      entered.countDown();
      release.await();
      return List.of(Map.of("id", 1L));
    });
    var dispatch = new EventDispatch(2, 10);
    var legacy = new OrderStatusEventBroker(jdbc, new SimpleMeterRegistry(), dispatch);
    var campus = new CommerceEventService(jdbc, dispatch);
    try {
      UserContext.set(7L, null, "CUSTOMER", 11L);
      assertTimeout(java.time.Duration.ofMillis(300), () -> {
        legacy.subscribe(7L, 0);
        campus.subscribe(0, false);
        legacy.heartbeat();
        campus.dispatch();
      });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      // Both event I/O workers are blocked, yet scheduler entrypoints remain bounded.
      assertTimeout(java.time.Duration.ofMillis(300), () -> { legacy.heartbeat(); campus.dispatch(); });
      var maintenanceDb = mock(JdbcTemplate.class);
      when(maintenanceDb.queryForList(anyString())).thenReturn(List.of(Map.of("id", 3L, "merchant_id", 11L)));
      var transactions = mock(OrderTransactionService.class);
      var maintenance = new OrderMaintenance(maintenanceDb, transactions);
      var scheduler = Executors.newSingleThreadExecutor();
      try {
        scheduler.submit(() -> { legacy.heartbeat(); campus.dispatch(); maintenance.sweep(); })
            .get(1, TimeUnit.SECONDS);
        verify(transactions).expire(3L);
      } finally { scheduler.shutdownNow(); }
      legacy.subscribe(7L, 0);
      legacy.subscribe(7L, 0);
      assertThrows(BusinessException.class, () -> legacy.subscribe(7L, 0));
      verify(jdbc).queryForList(contains("merchant_id=?"), eq(11L), eq(0L), eq(7L));
    } finally { release.countDown(); dispatch.stop(); }
  }

  @Test void deadlineUsesConservativeWholeSecondsAndDoesNotClaimPastWritesRolledBack() {
    assertEquals(8, UserContext.databaseTimeoutSeconds(8));
    UserContext.setTrustedDeadline(System.currentTimeMillis() + 2500);
    assertTrue(UserContext.databaseTimeoutSeconds(8) <= 2);
    UserContext.setTrustedDeadline(System.currentTimeMillis() + 400);
    assertThrows(BusinessException.class, () -> UserContext.databaseTimeoutSeconds(8));
  }

  @Test void authenticationFilterResponds413ForKnownAndChunkedBodies() throws Exception {
    for (boolean knownLength : List.of(true, false)) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/register") {
        @Override public long getContentLengthLong() {
          return knownLength ? super.getContentLengthLong() : -1;
        }
      };
      request.setContent(new byte[LimitedBodyRequest.MAX_BYTES + 1]);
      if (!knownLength) request.addHeader("Transfer-Encoding", "chunked");
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      new AuthenticationFilter(mock(AuthService.class), "").doFilter(request, response,
          (req, res) -> req.getInputStream().readAllBytes());
      assertEquals(413, response.getStatus());
      assertTrue(response.getContentAsString().contains("BODY_TOO_LARGE"));
    }
  }

  @Test void concurrentSubscriptionsCannotExceedPerUserLimit() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    var sender = new EventDispatch(2, 200);
    var legacy = new OrderStatusEventBroker(jdbc, new SimpleMeterRegistry(), sender);
    var campus = new CommerceEventService(jdbc, sender);
    var pool = Executors.newFixedThreadPool(8);
    try {
      for (boolean old : List.of(true, false)) {
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var futures = new ArrayList<Future<?>>();
        for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> {
          UserContext.set(7L, null, "CUSTOMER", 11L);
          try {
            if (old) legacy.subscribe(7L, 0); else campus.subscribe(0, false);
            accepted.incrementAndGet();
          } catch (BusinessException expected) { assertEquals("SSE_LIMIT", expected.errorCode()); }
          finally { UserContext.clear(); }
        }));
        for (var f : futures) f.get(2, TimeUnit.SECONDS);
        assertEquals(3, accepted.get());
      }
    } finally { pool.shutdownNow(); sender.stop(); }
  }

  @Test void completionDoesNotWaitBehindEmitterWriteLock() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    class BlockedEmitter extends EventEmitter {
      void blockedWrite() {
        writeLock.lock();
        try {
          entered.countDown();
          release.await();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { writeLock.unlock(); }
      }
    }
    var emitter = new BlockedEmitter();
    var worker = Executors.newSingleThreadExecutor();
    try {
      var write = worker.submit(emitter::blockedWrite);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTimeout(java.time.Duration.ofMillis(300), () -> assertFalse(emitter.tryComplete()));
      release.countDown();
      write.get(2, TimeUnit.SECONDS);
      assertTrue(emitter.tryComplete());
    } finally { release.countDown(); worker.shutdownNow(); }
  }

  @Test void completionUsesErrorDispatchWithoutNetworkFlush() throws Exception {
    var emitter = new EventEmitter();
    Class<?> handlerType = Class.forName("org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
    var terminated = new AtomicBoolean();
    Object handler = java.lang.reflect.Proxy.newProxyInstance(handlerType.getClassLoader(),
        new Class<?>[]{handlerType}, (proxy, method, args) -> {
          if (method.getName().equals("complete")) fail("Normal completion flushes network output");
          if (method.getName().equals("completeWithError")) terminated.set(true);
          return null;
        });
    var initialize = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
        .getDeclaredMethod("initialize", handlerType);
    initialize.setAccessible(true);
    initialize.invoke(emitter, handler);
    assertTrue(emitter.tryComplete());
    assertTrue(terminated.get());
  }
}
