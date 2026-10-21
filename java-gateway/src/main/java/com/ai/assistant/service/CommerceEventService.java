package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.security.AuthService;
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
  private final Map<EventEmitter, Subscription> streams = new ConcurrentHashMap<>();

  private static final class Subscription {
    final long id;
    final boolean rider;
    final EventSession session;
    volatile long after;
    volatile boolean closing;
    final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    Subscription(long id, boolean rider, long after, EventSession session) {
      this.id = id; this.rider = rider; this.after = after; this.session = session;
    }
  }
  private final EventDispatch sender;
  private final AuthService auth;

  public CommerceEventService(JdbcTemplate db, EventDispatch sender, AuthService auth) {
    this.db = db;
    this.sender = sender;
    this.auth = auth;
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

  public synchronized SseEmitter subscribe(long after, boolean rider) {
    UserContext.requireRole(rider ? "RIDER" : "CUSTOMER");
    long id = UserContext.getCurrentId();
    var session = EventSession.capture();
    check(after >= 0, "事件游标无效");
    if (streams.size() >= 200
        || streams.values().stream().filter(s -> s.id == id && s.rider == rider).count() >= 3)
      throw new BusinessException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "SSE_LIMIT", "通知连接过多");
    var e = new EventEmitter();
    streams.put(e, new Subscription(id, rider, after, session));
    e.onCompletion(() -> streams.remove(e));
    e.onTimeout(() -> streams.remove(e));
    e.onError(x -> streams.remove(e));
    schedule(e, streams.get(e));
    return e;
  }

  @Scheduled(fixedDelay = 2000)
  public void dispatch() {
    for (var entry : streams.entrySet()) schedule(entry.getKey(), entry.getValue());
  }

  private void close(EventEmitter emitter) {
    var s = streams.get(emitter);
    if (s != null) s.closing = true;
    if (emitter.tryComplete()) streams.remove(emitter);
  }

  private void schedule(EventEmitter e, Subscription s) {
    if (s.closing) { close(e); return; }
    sender.submit(s.busy, () -> {
      if (!streams.containsKey(e)) return;
      try {
        s.session.validate(auth);
        var rows = replay(s.id, s.rider, s.after);
        for (var r : rows) {
          long after = id(r, "id");
          e.send(SseEmitter.event().id(Long.toString(after)).name("commerce").data(r));
          s.after = after;
        }
        e.send(SseEmitter.event().comment("heartbeat"));
      } catch (Exception ignored) { close(e); }
    }, () -> close(e));
  }
}
