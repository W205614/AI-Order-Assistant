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
  private final java.util.concurrent.ThreadPoolExecutor dispatch =
      new java.util.concurrent.ThreadPoolExecutor(
          2,
          2,
          0L,
          java.util.concurrent.TimeUnit.MILLISECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(200),
          r -> {
            var t = new Thread(r, "order-event-dispatch");
            t.setDaemon(true);
            return t;
          },
          new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

  @jakarta.annotation.PreDestroy
  public void stop() {
    dispatch.shutdownNow();
  }

  private record Scope(long merchant, Long user) {}

  private final Map<Scope, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
  private final JdbcTemplate jdbc;

  public OrderStatusEventBroker(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
    long id = Objects.requireNonNull(keys.getKey()).longValue();
    Map<String, Object> data =
        Map.of(
            "orderId", order.getId(), "userSeq", order.getUserSeq(), "status", order.getStatus());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              dispatch.execute(
                  () -> {
                    publish(new Scope(order.getMerchantId(), order.getUserId()), id, data);
                    publish(new Scope(order.getMerchantId(), null), id, data);
                  });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
              log.warn("Live event queue full; persisted events remain available for replay");
            }
          }
        });
  }

  public synchronized SseEmitter subscribe(Long user, long after) {
    if (after < 0) throw new IllegalArgumentException("事件游标无效");
    var scope = new Scope(UserContext.merchantId(), user);
    var group = streams.computeIfAbsent(scope, k -> ConcurrentHashMap.newKeySet());
    if (group.size() >= 3 || streams.values().stream().mapToInt(Set::size).sum() >= 200)
      throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "SSE_LIMIT", "通知连接过多");
    var emitter = new SseEmitter(1800000L);
    group.add(emitter);
    emitter.onCompletion(() -> remove(scope, emitter));
    emitter.onTimeout(() -> remove(scope, emitter));
    emitter.onError(e -> remove(scope, emitter));
    var rows = replay(user, after);
    for (var row : rows) send(scope, emitter, ((Number) row.get("id")).longValue(), row);
    // nextId 允许客户端继续补读超过 100 条的事件；业务列表始终是状态依据。
    long next = rows.isEmpty() ? after : ((Number) rows.getLast().get("id")).longValue();
    try {
      emitter.send(
          SseEmitter.event()
              .name("connected")
              .data(Map.of("nextId", next, "hasMore", rows.size() == 100)));
    } catch (Exception ignored) {
      close(scope, emitter);
    }
    return emitter;
  }

  public List<Map<String, Object>> replay(Long user, long after) {
    if (after < 0) throw new IllegalArgumentException("事件游标无效");
    String sql =
        "SELECT id,order_id AS orderId,user_seq AS userSeq,status FROM order_event WHERE"
            + " merchant_id=? AND id>?";
    return user == null
        ? jdbc.queryForList(sql + " ORDER BY id LIMIT 100", UserContext.merchantId(), after)
        : jdbc.queryForList(
            sql + " AND user_id=? ORDER BY id LIMIT 100", UserContext.merchantId(), after, user);
  }

  private void publish(Scope scope, long id, Object data) {
    for (var emitter : streams.getOrDefault(scope, Set.of())) send(scope, emitter, id, data);
  }

  private void send(Scope scope, SseEmitter emitter, long id, Object data) {
    try {
      emitter.send(SseEmitter.event().id(Long.toString(id)).name("order-status").data(data));
    } catch (Exception ignored) {
      close(scope, emitter);
    }
  }

  private void remove(Scope scope, SseEmitter emitter) {
    var group = streams.get(scope);
    if (group != null) group.remove(emitter);
  }

  private void close(Scope scope, SseEmitter emitter) {
    remove(scope, emitter);
    try {
      emitter.complete();
    } catch (Exception ignored) {
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    for (var entry : streams.entrySet())
      for (var emitter : entry.getValue()) {
        try {
          emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (Exception ignored) {
          close(entry.getKey(), emitter);
        }
      }
    streams.entrySet().removeIf(e -> e.getValue().isEmpty());
  }
}
