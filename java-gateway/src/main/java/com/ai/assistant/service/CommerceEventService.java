package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;

import com.ai.assistant.security.UserContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class CommerceEventService {
  private final JdbcTemplate db;
  private final Map<SseEmitter, Subscription> streams = new ConcurrentHashMap<>();

  private record Subscription(long id, boolean rider, long after) {}

  public CommerceEventService(JdbcTemplate db) {
    this.db = db;
  }

  public void record(Long user, Long merchant, Long rider, String kind, Object resource) {
    check(TransactionSynchronizationManager.isSynchronizationActive(), "事件必须在事务中写入");
    String resourceId = String.valueOf(resource);
    // Business rows always lock before the shared cursor, including when a caller records
    // multiple events while it is still changing inventory or processing another shop.
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void beforeCommit(boolean readOnly) {
            one(db, "SELECT id FROM commerce_event_cursor WHERE id=1 FOR UPDATE");
            db.update(
                "INSERT INTO commerce_event(user_id,merchant_id,rider_id,kind,resource_id)"
                    + " VALUES(?,?,?,?,?)",
                user,
                merchant,
                rider,
                kind,
                resourceId);
            // prepareForCommit runs before synchronization callbacks; cursor contention can
            // exhaust the deadline here, so the event and business writes must still roll back.
            UserContext.checkDeadline();
          }
        });
  }

  public List<Map<String, Object>> replay(long id, boolean rider, long after) {
    check(after >= 0, "事件游标无效");
    return db.queryForList(
        "SELECT id,kind,resource_id,created_at FROM commerce_event WHERE "
            + (rider ? "rider_id" : "user_id")
            + "=? AND id>? ORDER BY id LIMIT 100",
        id,
        after);
  }

  public SseEmitter subscribe(long after, boolean rider) {
    UserContext.requireRole(rider ? "RIDER" : "CUSTOMER");
    long id = UserContext.getCurrentId();
    check(
        after >= 0
            && streams.size() < 200
            && streams.values().stream().filter(s -> s.id() == id && s.rider() == rider).count()
                < 3,
        "事件连接过多或游标无效");
    var e = new SseEmitter(1800000L);
    streams.put(e, new Subscription(id, rider, after));
    e.onCompletion(() -> streams.remove(e));
    e.onTimeout(() -> streams.remove(e));
    e.onError(x -> streams.remove(e));
    return e;
  }

  @Scheduled(fixedDelay = 2000)
  public void dispatch() {
    for (var entry : streams.entrySet()) {
      var e = entry.getKey();
      var s = entry.getValue();
      try {
        var rows = replay(s.id(), s.rider(), s.after());
        long after = s.after();
        for (var r : rows) {
          after = id(r, "id");
          e.send(SseEmitter.event().id(Long.toString(after)).name("commerce").data(r));
        }
        streams.replace(e, s, new Subscription(s.id(), s.rider(), after));
        e.send(SseEmitter.event().comment("heartbeat"));
      } catch (Exception ignored) {
        streams.remove(e);
        e.complete();
      }
    }
  }
}
