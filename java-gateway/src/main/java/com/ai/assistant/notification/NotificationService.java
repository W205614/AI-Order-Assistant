package com.ai.assistant.notification;

import com.ai.assistant.config.BackgroundTask;
import com.ai.assistant.integration.support.SmsSender;
import com.ai.assistant.security.AuthException;
import com.ai.assistant.security.AuthService;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.EventDispatch;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Notifications consume committed business events; delivery cannot roll back a transaction. */
@Service
public class NotificationService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final SmsSender sms;
  private final AuthService auth;
  private final EventDispatch sender;
  private final Map<String, String> templates;
  private final io.micrometer.core.instrument.Counter failures;
  private final Map<NotificationEmitter, Stream> streams = new ConcurrentHashMap<>();
  private final AtomicBoolean deliveryBusy = new AtomicBoolean();
  private final ExecutorService deliveryWorker =
      Executors.newSingleThreadExecutor(
          task -> {
            var thread = new Thread(task, "notification-delivery");
            thread.setDaemon(true);
            return thread;
          });

  private static final class NotificationEmitter extends SseEmitter {
    volatile boolean closing;

    NotificationEmitter() {
      super(1800000L);
    }

    boolean closeWithoutWaiting() {
      closing = true;
      // As with business SSE, the MVC initial flush can hold this lock on a request thread.
      if (!writeLock.tryLock()) return false;
      try {
        completeWithError(
            new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                "站内通知连接已关闭，请重新连接"));
        return true;
      } finally {
        writeLock.unlock();
      }
    }
  }

  private static final class Stream {
    final String type;
    final long account;
    final String token;
    final String role;
    final AtomicBoolean busy = new AtomicBoolean();
    long after;

    Stream(long after) {
      type = accountType();
      account = principal();
      token = UserContext.getToken();
      role = UserContext.role();
      this.after = after;
    }
  }

  public NotificationService(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      SmsSender sms,
      AuthService auth,
      EventDispatch sender,
      MeterRegistry meters,
      @Value("${notification.sms.order-template:}") String order,
      @Value("${notification.sms.ready-template:}") String ready,
      @Value("${notification.sms.aftersale-template:}") String aftersale,
      @Value("${notification.sms.exception-template:}") String exception) {
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.sms = sms;
    this.auth = auth;
    this.sender = sender;
    templates =
        Map.of("ORDER", order, "READY", ready, "AFTERSALE", aftersale, "EXCEPTION", exception);
    failures = meters.counter("notification_delivery_failures_total");
    meters.gauge(
        "notification_delivery_pending",
        this,
        service -> service.countTasks("status IN ('PENDING','SENDING')"));
    meters.gauge(
        "notification_delivery_failed",
        this,
        service -> service.countTasks("status IN ('FAILED','UNKNOWN')"));
  }

  private double countTasks(String predicate) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM notification_task WHERE " + predicate, Long.class);
  }

  static String accountType() {
    return UserContext.isAdmin() ? "MANAGEMENT" : "CUSTOMER";
  }

  static long principal() {
    Long id = UserContext.getCurrentId();
    if (id == null) throw new AuthException("请先登录");
    return id;
  }

  public List<Map<String, Object>> list(long before) {
    if (before <= 0) throw new IllegalArgumentException("通知游标无效");
    return db.queryForList(
        "SELECT id,kind,resource_id,message,read_at,created_at FROM notification"
            + " WHERE account_type=? AND account_id=? AND id<? ORDER BY id DESC LIMIT 50",
        accountType(),
        principal(),
        before);
  }

  public void read(long id) {
    int changed =
        db.update(
            "UPDATE notification SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND account_type=?"
                + " AND account_id=?",
            id,
            accountType(),
            principal());
    if (changed == 0) throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "通知不存在");
  }

  @Scheduled(fixedDelay = 2000, initialDelay = 5000)
  public void collect() {
    collect("commerce");
    collect("order");
  }

  private void collect(String source) {
    tx.executeWithoutResult(
        status -> {
          long cursor =
              db.queryForObject(
                  "SELECT last_id FROM notification_cursor WHERE source=? FOR UPDATE",
                  Long.class,
                  source);
          String table = source.equals("commerce") ? "commerce_event" : "order_event";
          var events =
              db.queryForList(
                  "SELECT * FROM " + table + " WHERE id>? ORDER BY id LIMIT 100", cursor);
          for (var event : events) {
            if (source.equals("commerce")) commerce(event);
            else legacy(event);
            cursor = ((Number) event.get("id")).longValue();
          }
          db.update("UPDATE notification_cursor SET last_id=? WHERE source=?", cursor, source);
        });
  }

  private void commerce(Map<String, Object> event) {
    String kind = String.valueOf(event.get("kind"));
    String resource = String.valueOf(event.get("resource_id"));
    String key = "commerce:" + event.get("id");
    String message =
        switch (kind) {
          case "CHECKOUT_CONFIRMED" -> "订单已创建，请在有效期内完成支付";
          case "CHECKOUT_PAID" -> "结算已确认，请在订单页查看履约进度";
          case "CHECKOUT_CLOSED" -> "未支付结算已关闭";
          case "ORDER_CANCELLED" -> "订单已取消，请在订单页核对退款进度";
          case "FULFILLMENT_CHANGED",
              "FULFILLMENT_ACCEPTED",
              "FULFILLMENT_PREPARING",
              "FULFILLMENT_DELIVERING",
              "FULFILLMENT_COMPLETED" ->
              "商户更新了备餐或履约状态，请查看订单";
          case "FULFILLMENT_READY" -> "备餐已经完成，请查看订单的自提或配送安排";
          case "AFTERSALE_APPLIED" -> "已提交售后申请";
          case "AFTERSALE_DECIDED" -> "售后审核已更新，请查看审核结果及退款状态";
          case "AFTERSALE_DISPUTED" -> "售后申诉等待平台处理";
          case "REFUND_SUCCEEDED" -> "渠道退款成功已确认，请在订单页核对退款记录";
          case "TASK_ASSIGNED", "TASK_REASSIGNED" -> "配送任务分配已更新";
          case "TASK_CHANGED" -> "配送任务状态已更新";
          default -> "订单业务状态已更新，请查看相关记录";
        };
    if (event.get("user_id") instanceof Number user)
      add("CUSTOMER", user.longValue(), key, kind, resource, message);
    if (event.get("merchant_id") instanceof Number merchant)
      merchant(merchant.longValue(), key, kind, resource, message);
    if (event.get("rider_id") instanceof Number rider)
      add("MANAGEMENT", rider.longValue(), key, kind, resource, message);
    if (kind.equals("CHECKOUT_PAID"))
      for (var order :
          db.queryForList("SELECT id,merchant_id FROM orders WHERE checkout_id=?", resource))
        merchant(
            ((Number) order.get("merchant_id")).longValue(),
            key + ":" + order.get("id"),
            "NEW_ORDER",
            String.valueOf(order.get("id")),
            "本店收到已支付订单，请确认接单");
    boolean exception = kind.contains("EXCEPTION") || kind.equals("AFTERSALE_DISPUTED");
    if (kind.startsWith("TASK")) {
      var tasks = db.queryForList("SELECT status FROM delivery_task WHERE id=?", resource);
      exception |= !tasks.isEmpty() && "EXCEPTION".equals(tasks.getFirst().get("status"));
    }
    if (exception)
      for (var admin :
          db.queryForList("SELECT id FROM admin_user WHERE role='PLATFORM_ADMIN' AND enabled=TRUE"))
        add(
            "MANAGEMENT",
            ((Number) admin.get("id")).longValue(),
            key,
            "EXCEPTION",
            resource,
            "配送异常或售后申诉需要平台处理，请打开平台待办");
  }

  private void legacy(Map<String, Object> event) {
    String key = "order:" + event.get("id");
    String resource = String.valueOf(event.get("order_id"));
    int status = ((Number) event.get("status")).intValue();
    add(
        "CUSTOMER",
        ((Number) event.get("user_id")).longValue(),
        key,
        "ORDER_STATUS",
        resource,
        "订单状态已更新，请在我的订单查看");
    if (status > 0)
      merchant(
          ((Number) event.get("merchant_id")).longValue(),
          key,
          "ORDER_STATUS",
          resource,
          "本店订单状态已更新，请查看订单列表");
  }

  private void merchant(long merchant, String key, String kind, String resource, String message) {
    for (var account :
        db.queryForList(
            "SELECT id FROM admin_user WHERE merchant_id=? AND enabled=TRUE AND role IN"
                + " ('OWNER','STAFF')",
            merchant))
      add("MANAGEMENT", ((Number) account.get("id")).longValue(), key, kind, resource, message);
  }

  private void add(
      String type, long account, String key, String kind, String resource, String message) {
    db.update(
        "INSERT IGNORE INTO"
            + " notification(account_type,account_id,event_key,kind,resource_id,message)"
            + " VALUES(?,?,?,?,?,?)",
        type,
        account,
        key,
        kind,
        resource,
        message);
    String group =
        kind.contains("AFTERSALE") || kind.startsWith("REFUND_")
            ? "AFTERSALE"
            : kind.contains("EXCEPTION")
                ? "EXCEPTION"
                : kind.equals("FULFILLMENT_READY")
                    ? "READY"
                    : kind.equals("NEW_ORDER") ? "ORDER" : "";
    String template = templates.getOrDefault(group, "");
    if (!template.isBlank())
      db.update(
          "INSERT IGNORE INTO notification_task(notification_id,template_code) SELECT id,? FROM"
              + " notification WHERE account_type=? AND account_id=? AND event_key=?",
          template,
          type,
          account,
          key);
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 10000)
  public void scheduleDelivery() {
    if (!deliveryBusy.compareAndSet(false, true)) return;
    deliveryWorker.execute(
        () -> {
          try {
            deliver();
          } finally {
            deliveryBusy.set(false);
          }
        });
  }

  public void deliver() {
    // A process death after provider acceptance is ambiguous; never blindly resend an expired
    // lease.
    db.update(
        "UPDATE notification_task SET status='UNKNOWN',last_error='LEASE_EXPIRED'"
            + " WHERE status='SENDING' AND lease_until<NOW()");
    var ids =
        db.queryForList(
            "SELECT id FROM notification_task WHERE status='PENDING' AND attempts<5"
                + " AND available_at<=NOW() ORDER BY id LIMIT 20");
    for (var row : ids)
      BackgroundTask.run(
          "notification-" + row.get("id"), () -> deliver(((Number) row.get("id")).longValue()));
  }

  @jakarta.annotation.PreDestroy
  public void shutdown() {
    deliveryWorker.shutdownNow();
  }

  private void deliver(long id) {
    String lease = UUID.randomUUID().toString();
    int claimed =
        db.update(
            "UPDATE notification_task SET"
                + " status='SENDING',attempts=attempts+1,lease_token=?,lease_until=DATE_ADD(NOW(),INTERVAL"
                + " 60 SECOND) WHERE id=? AND status='PENDING' AND available_at<=NOW() AND"
                + " attempts<5",
            lease,
            id);
    if (claimed != 1) return;
    var task =
        db.queryForMap(
            "SELECT t.*,n.account_type,n.account_id,n.resource_id FROM notification_task t"
                + " JOIN notification n ON n.id=t.notification_id WHERE t.id=?",
            id);
    var bindings =
        db.queryForList(
            "SELECT phone FROM verified_phone WHERE account_type=? AND account_id=?",
            task.get("account_type"),
            task.get("account_id"));
    if (bindings.isEmpty()) {
      db.update(
          "UPDATE notification_task SET status='SKIPPED',last_error='NO_VERIFIED_PHONE' WHERE id=?"
              + " AND lease_token=?",
          id,
          lease);
      return;
    }
    try {
      String reference =
          sms.send(
              String.valueOf(bindings.getFirst().get("phone")),
              String.valueOf(task.get("template_code")),
              Map.of("resource", String.valueOf(task.get("resource_id"))));
      db.update(
          "UPDATE notification_task SET status='ACCEPTED',provider_reference=?,last_error=NULL"
              + " WHERE id=? AND lease_token=? AND status='SENDING'",
          reference,
          id,
          lease);
    } catch (BusinessException failure) {
      failures.increment();
      String code = failure.errorCode();
      if (code.equals("SMS_REJECTED")) {
        int attempts = ((Number) task.get("attempts")).intValue();
        db.update(
            "UPDATE notification_task SET status=?,available_at=DATE_ADD(NOW(),INTERVAL ?"
                + " SECOND),last_error='PROVIDER_REJECTED' WHERE id=? AND lease_token=? AND"
                + " status='SENDING'",
            attempts >= 5 ? "FAILED" : "PENDING",
            30 * (1 << Math.min(attempts, 5)),
            id,
            lease);
      } else {
        String state =
            Set.of("SMS_DISABLED", "SMS_TEMPLATE_MISSING").contains(code) ? "FAILED" : "UNKNOWN";
        db.update(
            "UPDATE notification_task SET status=?,last_error=? WHERE id=? AND lease_token=? AND"
                + " status='SENDING'",
            state,
            state.equals("FAILED") ? "SMS_NOT_CONFIGURED" : "PROVIDER_RESULT_UNKNOWN",
            id,
            lease);
      }
    } catch (RuntimeException failure) {
      failures.increment();
      // An unclassified network error may have reached the provider. Require an operator decision.
      db.update(
          "UPDATE notification_task SET status='UNKNOWN',last_error='PROVIDER_RESULT_UNKNOWN'"
              + " WHERE id=? AND lease_token=? AND status='SENDING'",
          id,
          lease);
    }
  }

  public List<Map<String, Object>> tasks(long before) {
    UserContext.requireRole("PLATFORM_ADMIN");
    if (before <= 0) throw new IllegalArgumentException("通知任务游标无效");
    return db.queryForList(
        "SELECT"
            + " id,notification_id,status,attempts,available_at,provider_reference,last_error,created_at"
            + " FROM notification_task WHERE id<? ORDER BY id DESC LIMIT 100",
        before);
  }

  public void retry(long id, boolean acknowledgeDuplicateRisk) {
    UserContext.requireRole("PLATFORM_ADMIN");
    if (!acknowledgeDuplicateRisk) throw new IllegalArgumentException("请确认重新发送可能产生重复通知");
    int changed =
        db.update(
            "UPDATE notification_task SET status='PENDING',available_at=NOW(),last_error=NULL"
                + " WHERE id=? AND status IN ('FAILED','UNKNOWN','SKIPPED') AND attempts<5",
            id);
    if (changed != 1) throw BusinessException.conflict("任务不存在、已结束或达到五次发送上限", null);
    db.update(
        "INSERT INTO audit_log(actor,action,resource_id) VALUES(?,'NOTIFICATION_RETRY',?)",
        UserContext.actor(),
        id);
  }

  public synchronized SseEmitter subscribe(long after) {
    if (after < 0) throw new IllegalArgumentException("通知游标无效");
    var stream = new Stream(after);
    if (stream.token == null) throw new AuthException("请先登录");
    if (streams.size() >= 200
        || streams.values().stream()
                .filter(s -> s.type.equals(stream.type) && s.account == stream.account)
                .count()
            >= 2) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "SSE_LIMIT", "通知连接过多");
    var emitter = new NotificationEmitter();
    streams.put(emitter, stream);
    emitter.onCompletion(() -> streams.remove(emitter));
    emitter.onTimeout(() -> streams.remove(emitter));
    emitter.onError(e -> streams.remove(emitter));
    dispatch(emitter, stream);
    return emitter;
  }

  @Scheduled(fixedDelay = 3000)
  public void dispatch() {
    streams.forEach(this::dispatch);
  }

  private void dispatch(NotificationEmitter emitter, Stream stream) {
    if (emitter.closing) {
      if (emitter.closeWithoutWaiting()) streams.remove(emitter);
      return;
    }
    sender.submit(
        stream.busy,
        () -> {
          if (!streams.containsKey(emitter)) return;
          try {
            var claims = auth.validate(stream.type.equals("MANAGEMENT"), stream.token);
            String key = stream.type.equals("MANAGEMENT") ? "adminId" : "userId";
            if (!Objects.equals(stream.role, claims.get("role"))
                || ((Number) claims.get(key)).longValue() != stream.account)
              throw new AuthException("登录已失效");
            for (var row :
                db.queryForList(
                    "SELECT id,kind,resource_id,message,created_at FROM notification"
                        + " WHERE account_type=? AND account_id=? AND id>? ORDER BY id LIMIT 50",
                    stream.type,
                    stream.account,
                    stream.after)) {
              long id = ((Number) row.get("id")).longValue();
              emitter.send(
                  SseEmitter.event().id(String.valueOf(id)).name("notification").data(row));
              stream.after = id;
            }
            emitter.send(SseEmitter.event().comment("heartbeat"));
          } catch (Exception failure) {
            if (emitter.closeWithoutWaiting()) streams.remove(emitter);
          }
        },
        () -> {
          if (emitter.closeWithoutWaiting()) streams.remove(emitter);
        });
  }
}
