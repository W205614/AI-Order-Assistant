package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceFulfillmentService {
  private final JdbcTemplate db;
  private final CommerceCheckoutService checkouts;
  private final CampusCatalogService catalog;
  private final CommerceEventService events;
  private final AuditService audit;

  public CommerceFulfillmentService(
      JdbcTemplate db,
      CommerceCheckoutService checkouts,
      CampusCatalogService catalog,
      CommerceEventService events,
      AuditService audit) {
    this.db = db;
    this.checkouts = checkouts;
    this.catalog = catalog;
    this.events = events;
    this.audit = audit;
  }

  public List<Map<String, Object>> orders() {
    return orders("all", 1, 100);
  }

  public List<Map<String, Object>> orders(String scope, int page, int pageSize) {
    UserContext.requireRole("OWNER", "STAFF");
    long offset = pageOffset(scope, page, pageSize);
    String terminal = "(o.status IN (4,5,6) OR COALESCE(o.fulfillment_status,'') IN"
        + " ('COMPLETED','CANCELLED','CLOSED'))";
    String filter = "active".equals(scope) ? " AND NOT " + terminal
        : "history".equals(scope) ? " AND " + terminal : "";
    var rows =
        db.queryForList(
            "SELECT"
                + " o.id,o.user_seq,o.total_amount,o.refunded_amount,o.fulfillment_mode,o.fulfillment_status,o.campus_id,o.reservation_slot_id,o.recipient_name,o.recipient_phone,o.delivery_address,o.create_time,"
                + " s.starts_at reservation_starts_at,s.ends_at"
                + " reservation_ends_at,s.prepare_minutes, (s.id IS NULL OR"
                + " DATE_SUB(s.starts_at,INTERVAL s.prepare_minutes MINUTE)<=NOW())"
                + " ready_for_preparation FROM orders o LEFT JOIN reservation_slot s ON"
                + " s.id=o.reservation_slot_id WHERE o.merchant_id=? AND o.commerce_version=2"
                + filter + " ORDER BY ready_for_preparation DESC,o.id DESC LIMIT ? OFFSET ?",
            UserContext.merchantId(), pageSize, offset);
    for (var order : rows)
      order.put(
          "items",
          db.queryForList(
              "SELECT i.id,i.dish_name,i.quantity,i.remark,(SELECT COUNT(*) FROM order_unit u"
                  + " WHERE u.order_item_id=i.id AND u.status='REFUNDED') refunded_quantity"
                  + " FROM order_item i WHERE i.order_id=? AND i.merchant_id=? ORDER BY i.id",
              order.get("id"),
              UserContext.merchantId()));
    return rows;
  }

  public List<Map<String, Object>> slots(long merchant) {
    return db.queryForList(
        "SELECT * FROM reservation_slot WHERE merchant_id=? AND enabled=TRUE AND cutoff_at>? AND"
            + " starts_at<=? ORDER BY starts_at LIMIT 200",
        merchant,
        BusinessTime.now(),
        BusinessTime.now().plusDays(7));
  }

  @Transactional
  public long slot(SlotInput x) {
    UserContext.requireRole("OWNER");
    var now = BusinessTime.now();
    check(
        x.startsAt() != null
            && x.endsAt() != null
            && x.cutoffAt() != null
            && x.startsAt().isAfter(now)
            && !x.startsAt().isAfter(now.plusDays(7))
            && x.endsAt().isAfter(x.startsAt())
            && !x.endsAt().isAfter(x.startsAt().plusHours(4))
            && !x.cutoffAt().isAfter(x.startsAt())
            && x.cutoffAt().isAfter(now)
            && x.capacity() > 0
            && x.capacity() <= 10000
            && x.prepareMinutes() > 0
            && x.prepareMinutes() <= 1440,
        "预约时间、提前量或容量无效");
    var m = one(db, "SELECT * FROM merchant WHERE id=? FOR SHARE", UserContext.merchantId());
    var start = LocalTime.parse(String.valueOf(m.get("opens_at")));
    var end = LocalTime.parse(String.valueOf(m.get("closes_at")));
    for (var t = x.startsAt(); t.isBefore(x.endsAt()); t = t.plusMinutes(1))
      check(CommerceQuoteService.inside(t.toLocalTime(), start, end), "预约时段必须在营业时间内");
    long id =
        insert(
            db,
            "INSERT INTO"
                + " reservation_slot(merchant_id,starts_at,ends_at,cutoff_at,prepare_minutes,capacity)"
                + " VALUES(?,?,?,?,?,?)",
            UserContext.merchantId(),
            x.startsAt(),
            x.endsAt(),
            x.cutoffAt(),
            x.prepareMinutes(),
            x.capacity());
    audit.record("RESERVATION_SLOT_CREATED", id);
    return id;
  }

  public void disableSlot(long slot) {
    UserContext.requireRole("OWNER");
    check(
        db.update(
                "UPDATE reservation_slot SET enabled=FALSE WHERE id=? AND merchant_id=?",
                slot,
                UserContext.merchantId())
            == 1,
        "预约时段不存在");
    audit.record("RESERVATION_SLOT_DISABLED", slot);
  }

  @Transactional(noRollbackFor = VerificationException.class)
  public void transition(long id, Transition x) {
    UserContext.requireRole("OWNER", "STAFF");
    var o = checkouts.lockOrder(id);
    String state = (String) o.get("fulfillment_status");
    String next;
    switch (Objects.toString(x.action(), "")) {
      case "ACCEPT" -> {
        if ("ACCEPTED".equals(state)) return;
        check("PENDING_ACCEPTANCE".equals(state), "订单不允许接单");
        next = "ACCEPTED";
        db.update("UPDATE orders SET accepted_at=? WHERE id=?", BusinessTime.now(), id);
      }
      case "START" -> {
        if ("PREPARING".equals(state)) return;
        check("ACCEPTED".equals(state), "请先接单");
        if (o.get("reservation_slot_id") != null) {
          var s =
              one(db, "SELECT * FROM reservation_slot WHERE id=?", o.get("reservation_slot_id"));
          check(
              !BusinessTime.now()
                  .isBefore(time(s, "starts_at").minusMinutes(num(s, "prepare_minutes"))),
              "尚未到预约备餐时间");
        }
        next = "PREPARING";
        db.update("UPDATE orders SET production_started=TRUE,status=2 WHERE id=?", id);
      }
      case "READY" -> {
        if ("READY".equals(state)) return;
        check("PREPARING".equals(state), "订单不在制作中");
        next = "READY";
        db.update("UPDATE orders SET ready_at=? WHERE id=?", BusinessTime.now(), id);
        if ("DELIVERY".equals(o.get("fulfillment_mode")))
          db.update(
              "INSERT INTO delivery_task(order_id,merchant_id,campus_id) VALUES(?,?,?)",
              id,
              o.get("merchant_id"),
              o.get("campus_id"));
      }
      case "PICKUP" -> {
        if ("COMPLETED".equals(state)) return;
        check("PICKUP".equals(o.get("fulfillment_mode")) && "READY".equals(state), "订单不可自提核销");
        verify(o, x.code());
        next = "COMPLETED";
        db.update("UPDATE orders SET status=4,deliver_time=? WHERE id=?", BusinessTime.now(), id);
      }
      default -> throw new IllegalArgumentException("履约操作无效");
    }
    db.update("UPDATE orders SET fulfillment_status=? WHERE id=?", next, id);
    events.record(id(o, "user_id"), id(o, "merchant_id"), null, "FULFILLMENT_CHANGED", id);
    audit.record(id(o, "merchant_id"), "FULFILLMENT_" + next, id);
  }

  public List<Map<String, Object>> tasks() {
    return tasks("all", 1, 200);
  }

  public record Recipient(String recipient_name, String recipient_phone, String delivery_address) {}

  public List<Map<String, Object>> tasks(String scope, int page, int pageSize) {
    UserContext.requireRole("RIDER");
    long offset = pageOffset(scope, page, pageSize);
    long r = UserContext.getCurrentId();
    var rows =
        db.queryForList(
            "SELECT"
                + " t.id,t.order_id,t.merchant_id,t.campus_id,t.rider_id,t.status,t.version,t.exception_reason,t.picked_at,t.created_at,m.name"
                + " merchant_name,m.campus_id origin_campus,c.name origin_campus_name,o.user_seq"
                + " FROM delivery_task t JOIN merchant m ON m.id=t.merchant_id JOIN campus c ON"
                + " c.id=m.campus_id JOIN orders o ON o.id=t.order_id WHERE (t.rider_id=? OR"
                + " (t.status='WAITING' AND EXISTS(SELECT 1 FROM rider_campus rc WHERE"
                + " rc.rider_id=? AND rc.campus_id=t.campus_id)))"
                + taskScope(scope) + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
            r,
            r, pageSize, offset);
    for (var t : rows) {
      t.put("recipient", null);
      if (t.get("rider_id") != null && id(t, "rider_id") == r
          && Set.of("ASSIGNED", "PICKED_UP", "EXCEPTION").contains(t.get("status"))) {
        var recipients = db.queryForList(
            "SELECT o.recipient_name,o.recipient_phone,o.delivery_address FROM orders o"
                + " JOIN delivery_task t ON t.order_id=o.id WHERE t.id=? AND t.rider_id=?"
                + " AND t.status IN ('ASSIGNED','PICKED_UP','EXCEPTION')", t.get("id"), r);
        if (recipients.isEmpty()) continue;
        var recipient = recipients.getFirst();
        t.put(
            "recipient",
            new Recipient((String) recipient.get("recipient_name"),
                (String) recipient.get("recipient_phone"),
                (String) recipient.get("delivery_address")));
      }
    }
    return rows;
  }

  @Transactional
  public void claim(long task, long version) {
    UserContext.requireRole("RIDER");
    var base = one(db, "SELECT * FROM delivery_task WHERE id=?", task);
    var order = one(db, "SELECT * FROM orders WHERE id=?", base.get("order_id"));
    checkouts.lockAggregate(order);
    var t = one(db, "SELECT * FROM delivery_task WHERE id=? FOR UPDATE", task);
    long r = UserContext.getCurrentId();
    check(
        db.queryForObject(
                "SELECT COUNT(*) FROM rider_campus WHERE rider_id=? AND campus_id=?",
                Integer.class,
                r,
                t.get("campus_id"))
            == 1,
        "骑手不服务该校区");
    if ("ASSIGNED".equals(t.get("status")) && t.get("rider_id") != null && id(t, "rider_id") == r)
      return;
    check(
        db.update(
                "UPDATE delivery_task SET"
                    + " rider_id=?,status='ASSIGNED',version=version+1,updated_at=? WHERE id=? AND"
                    + " status='WAITING' AND version=?",
                r,
                BusinessTime.now(),
                task,
                version)
            == 1,
        "任务已被接走或版本已变化");
    events.record(id(order, "user_id"), id(order, "merchant_id"), r, "TASK_ASSIGNED", task);
    audit.record(id(order, "merchant_id"), "TASK_CLAIMED", task);
  }

  @Transactional(noRollbackFor = VerificationException.class)
  public void riderTransition(long task, Transition x) {
    UserContext.requireRole("RIDER");
    var base =
        one(
            db,
            "SELECT * FROM delivery_task WHERE id=? AND rider_id=?",
            task,
            UserContext.getCurrentId());
    var o =
        checkouts.lockAggregate(one(db, "SELECT * FROM orders WHERE id=?", base.get("order_id")));
    var t =
        one(
            db,
            "SELECT * FROM delivery_task WHERE id=? AND rider_id=? FOR UPDATE",
            task,
            UserContext.getCurrentId());
    String state = (String) t.get("status"), next;
    switch (Objects.toString(x.action(), "")) {
      case "COLLECT" -> {
        if ("PICKED_UP".equals(state)) return;
        check("ASSIGNED".equals(state), "任务不允许取餐");
        next = "PICKED_UP";
        db.update("UPDATE delivery_task SET picked_at=? WHERE id=?", BusinessTime.now(), task);
        db.update(
            "UPDATE orders SET status=3,fulfillment_status='DELIVERING' WHERE id=?", o.get("id"));
      }
      case "DELIVER" -> {
        if ("DELIVERED".equals(state)) return;
        check("PICKED_UP".equals(state), "任务不在配送中");
        verify(o, x.code());
        next = "DELIVERED";
        db.update(
            "UPDATE orders SET status=4,fulfillment_status='COMPLETED',deliver_time=? WHERE id=?",
            BusinessTime.now(),
            o.get("id"));
      }
      case "RELEASE" -> {
        check("ASSIGNED".equals(state) && t.get("picked_at") == null, "取餐后只能由平台处理异常");
        next = "WAITING";
        db.update("UPDATE delivery_task SET rider_id=NULL WHERE id=?", task);
      }
      case "EXCEPTION" -> {
        check(Set.of("ASSIGNED", "PICKED_UP").contains(state), "任务不可标记异常");
        text(x.code(), 500, "异常原因");
        next = "EXCEPTION";
        db.update("UPDATE delivery_task SET exception_reason=? WHERE id=?", x.code(), task);
      }
      default -> throw new IllegalArgumentException("配送操作无效");
    }
    db.update(
        "UPDATE delivery_task SET status=?,version=version+1,updated_at=? WHERE id=?",
        next,
        BusinessTime.now(),
        task);
    events.record(
        id(o, "user_id"), id(o, "merchant_id"), UserContext.getCurrentId(), "TASK_" + next, task);
    audit.record(id(o, "merchant_id"), "TASK_" + next, task);
  }

  @Transactional
  public void assign(long task, AssignInput x) {
    UserContext.requireRole("PLATFORM_ADMIN");
    text(x.reason(), 500, "指派原因");
    var t = one(db, "SELECT * FROM delivery_task WHERE id=?", task);
    var o = checkouts.lockAggregate(one(db, "SELECT * FROM orders WHERE id=?", t.get("order_id")));
    t = one(db, "SELECT * FROM delivery_task WHERE id=? FOR UPDATE", task);
    check(
        id(t, "version") == x.expectedVersion() && !"DELIVERED".equals(t.get("status")),
        "任务已完成或版本变化");
    if (x.riderId() != null)
      one(
          db,
          "SELECT a.id FROM admin_user a JOIN rider_campus rc ON rc.rider_id=a.id WHERE a.id=? AND"
              + " a.role='RIDER' AND a.enabled=TRUE AND rc.campus_id=?",
          x.riderId(),
          t.get("campus_id"));
    else check(t.get("picked_at") == null, "取餐后不能放回任务池，请指定接续骑手");
    String next =
        x.riderId() == null ? "WAITING" : t.get("picked_at") == null ? "ASSIGNED" : "PICKED_UP";
    db.update(
        "UPDATE delivery_task SET"
            + " rider_id=?,status=?,version=version+1,exception_reason=?,updated_at=? WHERE id=?",
        x.riderId(),
        next,
        x.reason(),
        BusinessTime.now(),
        task);
    events.record(id(o, "user_id"), id(o, "merchant_id"), x.riderId(), "TASK_REASSIGNED", task);
    if (t.get("rider_id") != null)
      events.record(null, null, id(t, "rider_id"), "TASK_REASSIGNED", task);
    audit.record(null, "TASK_REASSIGNED", task);
  }

  private void verify(Map<String, Object> order, String code) {
    var saved =
        one(db, "SELECT * FROM customer_order_code WHERE order_id=? FOR UPDATE", order.get("id"));
    check(num(saved, "failed_attempts") < 10, "核验失败次数过多，请联系平台");
    if (code == null
        || !code.matches("[0-9]{6}")
        || !java.security.MessageDigest.isEqual(
            hash(code).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            String.valueOf(order.get("verification_code_hash"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
      db.update(
          "UPDATE customer_order_code SET failed_attempts=failed_attempts+1 WHERE order_id=?",
          order.get("id"));
      throw new VerificationException();
    }
  }

  public void resetVerification(long order) {
    UserContext.requireRole("PLATFORM_ADMIN");
    db.update("UPDATE customer_order_code SET failed_attempts=0 WHERE order_id=?", order);
    audit.record(null, "VERIFICATION_UNLOCKED", order);
  }

  static final class VerificationException extends BusinessException {
    VerificationException() {
      super(HttpStatus.CONFLICT, "INVALID_PICKUP_CODE", "取餐码错误");
    }
  }

  public List<Map<String, Object>> platformTasks() {
    return platformTasks("all", 1, 200);
  }

  public List<Map<String, Object>> platformTasks(String scope, int page, int pageSize) {
    UserContext.requireRole("PLATFORM_ADMIN");
    long offset = pageOffset(scope, page, pageSize);
    return db.queryForList("SELECT t.* FROM delivery_task t WHERE 1=1"
        + taskScope(scope) + " ORDER BY t.id DESC LIMIT ? OFFSET ?", pageSize, offset);
  }

  private static long pageOffset(String scope, int page, int pageSize) {
    check(Set.of("all", "active", "history").contains(Objects.toString(scope, "")),
        "列表范围需为all、active或history");
    check(page >= 1 && pageSize >= 1 && pageSize <= 200, "分页参数无效");
    return ((long) page - 1) * pageSize;
  }

  private static String taskScope(String scope) {
    return switch (scope) {
      case "active" -> " AND t.status IN ('WAITING','ASSIGNED','PICKED_UP','EXCEPTION')";
      case "history" -> " AND t.status='DELIVERED'";
      default -> "";
    };
  }

  public List<Map<String, Object>> riders() {
    UserContext.requireRole("PLATFORM_ADMIN");
    return db.queryForList(
        "SELECT a.id,a.username,a.enabled,GROUP_CONCAT(rc.campus_id ORDER BY rc.campus_id)"
            + " campus_ids FROM admin_user a LEFT JOIN rider_campus rc ON rc.rider_id=a.id WHERE"
            + " a.role='RIDER' GROUP BY a.id ORDER BY a.id");
  }

  public List<Map<String, Object>> exceptions() {
    UserContext.requireRole("PLATFORM_ADMIN");
    return db.queryForList(
        "SELECT o.id,o.merchant_id,o.fulfillment_status,o.fulfillment_mode,o.create_time,t.status"
            + " task_status FROM orders o LEFT JOIN delivery_task t ON t.order_id=o.id LEFT JOIN"
            + " reservation_slot s ON s.id=o.reservation_slot_id WHERE o.commerce_version=2 AND"
            + " o.status BETWEEN 1 AND 3 AND (t.status='EXCEPTION' OR (t.status='WAITING' AND"
            + " t.created_at<DATE_SUB(NOW(),INTERVAL 15 MINUTE)) OR"
            + " (o.fulfillment_status='PENDING_ACCEPTANCE' AND"
            + " o.create_time<DATE_SUB(NOW(),INTERVAL 15 MINUTE)) OR (s.ends_at<NOW() AND"
            + " o.fulfillment_status IN ('ACCEPTED','PENDING_ACCEPTANCE')) OR"
            + " (o.fulfillment_mode='PICKUP' AND o.ready_at<DATE_SUB(NOW(),INTERVAL 60 MINUTE)))"
            + " ORDER BY o.id LIMIT 200");
  }
}
