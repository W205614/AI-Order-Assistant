package com.ai.assistant.service;

import com.ai.assistant.model.Order;
import com.ai.assistant.security.UserContext;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@Slf4j
public class OrderStatusEventBroker {
  private final EventDispatch dispatch;

  private static final class Connection {
    final EventEmitter emitter = new EventEmitter();
    final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    volatile long after;
    boolean connected;
    volatile boolean closing;
    Connection(long after) { this.after = after; }
  }

  private record Scope(long merchant, Long user) {}

  private final Map<Scope, Set<Connection>> streams = new ConcurrentHashMap<>();
  private final JdbcTemplate jdbc;
  private final io.micrometer.core.instrument.Counter dropped;

  public OrderStatusEventBroker(
      JdbcTemplate jdbc, io.micrometer.core.instrument.MeterRegistry registry, EventDispatch dispatch) {
    this.jdbc = jdbc;
    this.dispatch = dispatch;
    dropped = registry.counter("order_events_live_dropped_total");
    io.micrometer.core.instrument.Gauge.builder(
            "order_events_connections", streams, s -> s.values().stream().mapToInt(Set::size).sum())
        .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "order_events_queue", dispatch, EventDispatch::queued)
        .register(registry);
  }

  /** 写入事务内；在线推送只在提交之后进行，失败不影响订单。 */
  public void record(Order order) {
    if (!TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Event requires transaction");
    jdbc.queryForObject("SELECT id FROM order_event_cursor WHERE id=1 FOR UPDATE", Integer.class);
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var ps =
              c.prepareStatement(
                  "INSERT INTO order_event(merchant_id,user_id,order_id,user_seq,status)"
                      + " VALUES(?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setLong(1, order.getMerchantId());
          ps.setLong(2, order.getUserId());
          ps.setLong(3, order.getId());
          ps.setLong(4, order.getUserSeq());
          ps.setInt(5, order.getStatus());
          return ps;
        },
        keys);

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            signal(new Scope(order.getMerchantId(), order.getUserId()));
            signal(new Scope(order.getMerchantId(), null));
          }
        });
  }

  public synchronized SseEmitter subscribe(Long user, long after) {
    if (after < 0) throw new IllegalArgumentException("事件游标无效");
    var scope = new Scope(UserContext.merchantId(), user);
    var group = streams.computeIfAbsent(scope, k -> ConcurrentHashMap.newKeySet());
    if (group.size() >= 3 || streams.values().stream().mapToInt(Set::size).sum() >= 200) {
      if (group.isEmpty()) streams.remove(scope, group);
      throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "SSE_LIMIT", "通知连接过多");
    }
    var connection = new Connection(after);
    group.add(connection);
    var emitter = connection.emitter;
    emitter.onCompletion(() -> remove(scope, connection));
    emitter.onTimeout(() -> remove(scope, connection));
    emitter.onError(e -> remove(scope, connection));
    schedule(scope, connection);
    return emitter;
  }

  public List<Map<String, Object>> replay(Long user, long after) {
    if (after < 0) throw new IllegalArgumentException("事件游标无效");
    return replay(new Scope(UserContext.merchantId(), user), after);
  }

  private List<Map<String, Object>> replay(Scope scope, long after) {
    String sql =
        "SELECT id,order_id AS orderId,user_seq AS userSeq,status FROM order_event WHERE"
            + " merchant_id=? AND id>?";
    return scope.user() == null
        ? jdbc.queryForList(sql + " ORDER BY id LIMIT 100", scope.merchant(), after)
        : jdbc.queryForList(
            sql + " AND user_id=? ORDER BY id LIMIT 100", scope.merchant(), after, scope.user());
  }

  private void signal(Scope scope) {
    for (var connection : streams.getOrDefault(scope, Set.of())) schedule(scope, connection);
  }

  private void schedule(Scope scope, Connection connection) {
    if (connection.closing) { close(scope, connection); return; }
    dispatch.submit(connection.busy, () -> {
      if (!streams.getOrDefault(scope, Set.of()).contains(connection)) return;
      try {
        var rows = replay(scope, connection.after);
        for (var row : rows) {
          long id = ((Number) row.get("id")).longValue();
          connection.emitter.send(SseEmitter.event().id(Long.toString(id)).name("order-status").data(row));
          connection.after = id;
        }
        if (!connection.connected) {
          connection.emitter.send(SseEmitter.event().name("connected")
              .data(Map.of("nextId", connection.after, "hasMore", rows.size() == 100)));
          connection.connected = true;
        } else connection.emitter.send(SseEmitter.event().comment("heartbeat"));
      } catch (Exception ignored) { close(scope, connection); }
    }, () -> { dropped.increment(); close(scope, connection); });
  }

  private synchronized void remove(Scope scope, Connection connection) {
    var group = streams.get(scope);
    if (group != null) {
      group.remove(connection);
      if (group.isEmpty()) streams.remove(scope, group);
    }
  }

  private void close(Scope scope, Connection connection) {
    connection.closing = true;
    // Keep pending closes counted against admission until completion can acquire the emitter.
    if (connection.emitter.tryComplete()) remove(scope, connection);
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    for (var scope : streams.keySet()) signal(scope);

  }
}
