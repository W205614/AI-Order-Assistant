package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceAftersaleService {
  private final JdbcTemplate db;
  private final CommerceCheckoutService checkouts;
  private final CampusCatalogService catalog;
  private final CommerceCouponService coupons;
  private final CommerceEventService events;
  private final AuditService audit;

  public CommerceAftersaleService(
      JdbcTemplate db,
      CommerceCheckoutService checkouts,
      CampusCatalogService catalog,
      CommerceCouponService coupons,
      CommerceEventService events,
      AuditService audit) {
    this.db = db;
    this.checkouts = checkouts;
    this.catalog = catalog;
    this.coupons = coupons;
    this.events = events;
    this.audit = audit;
  }

  @Transactional
  public String apply(RefundInput x) {
    long user = user();
    text(x.reason(), 500, "退款原因");
    DraftService.validKey(x.requestKey());
    check(x.items() != null && !x.items().isEmpty() && x.items().size() <= 20, "请选择退款商品与数量");
    var order = checkouts.lockOrder(x.orderId());
    String fingerprint = hash(json(x));
    var old =
        db.queryForList(
            "SELECT * FROM aftersale WHERE user_id=? AND request_key=?", user, x.requestKey());
    if (!old.isEmpty()) {
      check(fingerprint.equals(old.getFirst().get("request_fingerprint")), "退款幂等键内容不一致");
      return (String) old.getFirst().get("id");
    }
    // Money can be fully refunded while zero-paid units still await cancellation.
    // Unit availability below, together with credit's amount cap, prevents double refunds.
    check(
        Set.of("SIMULATED_PAID", "PARTIALLY_REFUNDED", "SIMULATED_REFUNDED")
                .contains(order.get("payment_status"))
            && num(order, "status") < 5,
        "订单不允许申请退款");
    String id = UUID.randomUUID().toString();
    BigDecimal amount = ZERO;
    var currentUnits = checkouts.lockOrderUnits(x.orderId());
    var units = new ArrayList<Map<String, Object>>();
    var itemIds = new HashSet<Long>();
    for (var i : x.items()) {
      check(i.quantity() > 0 && i.quantity() <= 99 && itemIds.add(i.orderItemId()), "退款数量无效或商品重复");
      var available =
          currentUnits.stream()
              .filter(
                  u ->
                      id(u, "order_item_id") == i.orderItemId()
                          && "AVAILABLE".equals(u.get("status")))
              .sorted(Comparator.comparingLong(u -> id(u, "unit_no")))
              .limit(i.quantity())
              .toList();
      check(available.size() == i.quantity(), "退款数量超过剩余可退数量");
      for (var u : available) {
        amount = amount.add(amount(u, "goods_paid")).add(amount(u, "packaging_paid"));
        units.add(u);
      }
    }
    db.update(
        "INSERT INTO"
            + " aftersale(id,order_id,user_id,reason,status,amount,request_key,request_fingerprint)"
            + " VALUES(?,?,?,?,'PENDING',?,?,?)",
        id,
        x.orderId(),
        user,
        x.reason(),
        amount,
        x.requestKey(),
        fingerprint);
    for (var u : units) {
      db.update(
          "UPDATE order_unit SET status='PENDING',aftersale_id=? WHERE id=?", id, u.get("id"));
      db.update("INSERT INTO aftersale_unit VALUES(?,?)", id, u.get("id"));
    }
    events.record(user, id(order, "merchant_id"), null, "AFTERSALE_APPLIED", id);
    if (!Boolean.TRUE.equals(order.get("production_started")))
      decideLocked(
          order,
          one(db, "SELECT * FROM aftersale WHERE id=?", id),
          new DecisionInput(true, "制作前自动退款"));
    return id;
  }

  public List<Map<String, Object>> list() {
    String role = UserContext.role();
    List<Map<String, Object>> rows;
    if ("CUSTOMER".equals(role))
      rows =
          db.queryForList(
              "SELECT a.* FROM aftersale a WHERE a.user_id=? ORDER BY created_at DESC LIMIT 100",
              user());
    else if ("PLATFORM_ADMIN".equals(role))
      rows = db.queryForList("SELECT * FROM aftersale ORDER BY created_at DESC LIMIT 200");
    else {
      UserContext.requireRole("OWNER", "STAFF");
      rows =
          db.queryForList(
              "SELECT a.* FROM aftersale a JOIN orders o ON o.id=a.order_id WHERE o.merchant_id=?"
                  + " ORDER BY a.created_at DESC LIMIT 100",
              UserContext.merchantId());
    }
    for (var r : rows)
      r.put(
          "units",
          db.queryForList(
              "SELECT u.order_item_id,i.dish_name,u.unit_no,u.goods_paid,u.packaging_paid FROM"
                  + " aftersale_unit a JOIN order_unit u ON u.id=a.unit_id JOIN order_item i ON"
                  + " i.id=u.order_item_id WHERE a.aftersale_id=? ORDER BY u.id",
              r.get("id")));
    return rows;
  }

  @Transactional
  public void decide(String id, DecisionInput input) {
    UserContext.requireRole("OWNER", "STAFF", "PLATFORM_ADMIN");
    text(input.reason(), 500, "审核说明");
    var a = one(db, "SELECT * FROM aftersale WHERE id=?", id);
    var order = checkouts.lockOrder(id(a, "order_id"));
    a = one(db, "SELECT * FROM aftersale WHERE id=? FOR UPDATE", id);
    if (Set.of("APPROVED", "REJECTED").contains(a.get("status"))) {
      check((input.approve() ? "APPROVED" : "REJECTED").equals(a.get("status")), "审核结果已确定");
      return;
    }
    if ("DISPUTED".equals(a.get("status"))) UserContext.requireRole("PLATFORM_ADMIN");
    decideLocked(order, a, input);
  }

  private void decideLocked(Map<String, Object> order, Map<String, Object> a, DecisionInput input) {
    String id = (String) a.get("id");
    check(Set.of("PENDING", "DISPUTED").contains(a.get("status")), "售后状态已变化");
    var currentUnits = checkouts.lockOrderUnits(id(order, "id"));
    var selected =
        currentUnits.stream()
            .filter(u -> id.equals(u.get("aftersale_id")) && "PENDING".equals(u.get("status")))
            .toList();
    if (input.approve()) {
      checkouts.credit(order, amount(a, "amount"), "aftersale:" + id);
      if (!Boolean.TRUE.equals(order.get("production_started"))) {
        var needs = checkouts.stockNeeds(selected);
        catalog.lockStocks(needs.keySet());
        for (var n : needs.entrySet())
          catalog.stockChange(
              catalog.stock(n.getKey(), true), n.getValue(), id(order, "id"), "refund:" + id);
      }
      for (var unit : selected) {
        db.update("UPDATE order_unit SET status='REFUNDED' WHERE id=?", unit.get("id"));
        unit.put("status", "REFUNDED");
      }
      if (!Boolean.TRUE.equals(order.get("production_started"))
          && currentUnits.stream().allMatch(u -> "REFUNDED".equals(u.get("status")))) {
        var current = one(db, "SELECT * FROM orders WHERE id=?", order.get("id"));
        checkouts.credit(current, amount(order, "delivery_fee"), "refund-delivery:" + id);
        db.update(
            "UPDATE orders SET"
                + " status=5,payment_status='SIMULATED_REFUNDED',fulfillment_status='CANCELLED',inventory_released=TRUE"
                + " WHERE id=?",
            order.get("id"));
        coupons.release((String) order.get("checkout_id"), id(order, "merchant_id"), false);
        if (checkouts.allCancelledBeforeProduction((String) order.get("checkout_id")))
          coupons.release((String) order.get("checkout_id"), null, false);
        checkouts.releaseCapacity(order);
      }
    } else
      for (var unit : selected)
        db.update(
            "UPDATE order_unit SET status='AVAILABLE',aftersale_id=NULL WHERE id=?",
            unit.get("id"));
    db.update(
        "UPDATE aftersale SET status=?,decision_reason=? WHERE id=?",
        input.approve() ? "APPROVED" : "REJECTED",
        input.reason(),
        id);
    events.record(id(order, "user_id"), id(order, "merchant_id"), null, "AFTERSALE_DECIDED", id);
    audit.record(id(order, "merchant_id"), "AFTERSALE_DECIDED", id);
  }

  @Transactional
  public void dispute(String id, String reason) {
    text(reason, 500, "申诉原因");
    var a = one(db, "SELECT * FROM aftersale WHERE id=? AND user_id=?", id, user());
    checkouts.lockOrder(id(a, "order_id"));
    a = one(db, "SELECT * FROM aftersale WHERE id=? FOR UPDATE", id);
    check("REJECTED".equals(a.get("status")), "仅驳回的申请可以申诉");
    var originalIds =
        db.queryForList("SELECT unit_id FROM aftersale_unit WHERE aftersale_id=?", id).stream()
            .map(u -> id(u, "unit_id"))
            .collect(java.util.stream.Collectors.toSet());
    var units =
        checkouts.lockOrderUnits(id(a, "order_id")).stream()
            .filter(u -> originalIds.contains(id(u, "id")))
            .toList();
    check(units.stream().allMatch(u -> "AVAILABLE".equals(u.get("status"))), "原申请商品已有其他售后，无法重复申诉");
    for (var u : units)
      db.update(
          "UPDATE order_unit SET status='PENDING',aftersale_id=? WHERE id=?", id, u.get("id"));
    db.update(
        "UPDATE aftersale SET status='DISPUTED',disputed=TRUE,decision_reason=? WHERE id=?",
        reason,
        id);
    events.record(user(), null, null, "AFTERSALE_DISPUTED", id);
  }
}
