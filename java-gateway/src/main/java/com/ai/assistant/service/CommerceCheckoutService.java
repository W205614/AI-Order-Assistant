package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommerceCheckoutService {
  private final JdbcTemplate db;
  private final CommerceQuoteService quotes;
  private final CampusCatalogService catalog;
  private final CommerceCouponService coupons;
  private final OrderSafetyService safety;
  private final CommerceEventService events;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final CommerceMetrics metrics;

  public CommerceCheckoutService(
      JdbcTemplate db,
      CommerceQuoteService quotes,
      CampusCatalogService catalog,
      CommerceCouponService coupons,
      OrderSafetyService safety,
      CommerceEventService events,
      AuditService audit,
      org.springframework.transaction.PlatformTransactionManager manager,
      CommerceMetrics metrics) {
    this.db = db;
    this.quotes = quotes;
    this.catalog = catalog;
    this.coupons = coupons;
    this.safety = safety;
    this.events = events;
    this.audit = audit;
    this.tx = new TransactionTemplate(manager);
    this.metrics = metrics;
  }

  @Transactional
  public Map<String, Object> confirm(ConfirmInput input, String key) {
    try {
      return confirmLocked(input, key);
    } catch (RuntimeException e) {
      metrics.failure("confirm");
      throw e;
    }
  }

  private Map<String, Object> confirmLocked(ConfirmInput input, String key) {
    long u = user();
    DraftService.validKey(key);
    check(input != null && input.quoteId() != null && input.quoteId().length() == 36, "报价标识无效");
    safety.lockUser(u);
    String fingerprint = hash(input.quoteId() + ":" + input.expectedVersion());
    var existing =
        db.queryForList("SELECT * FROM checkout WHERE user_id=? AND idempotency_key=?", u, key);
    if (!existing.isEmpty()) {
      check(fingerprint.equals(existing.getFirst().get("request_fingerprint")), "幂等键已绑定其他请求");
      return detail((String) existing.getFirst().get("id"));
    }
    var row =
        one(
            db,
            "SELECT * FROM checkout_quote WHERE id=? AND user_id=? FOR UPDATE",
            input.quoteId(),
            u);
    check(id(row, "version") == input.expectedVersion(), "报价版本不符");
    check(row.get("consumed_checkout_id") == null, "报价已经确认");
    check(time(row, "expires_at").isAfter(BusinessTime.now()), "报价已过期，请重新报价");
    Quote old = JSON.parseObject(String.valueOf(row.get("snapshot_json")), Quote.class);
    check(old.unavailable().isEmpty() && !old.shops().isEmpty(), "请先移除不可提交店铺并重新报价确认");
    one(
        db,
        "SELECT id FROM campus WHERE id=? AND enabled=TRUE FOR SHARE",
        old.request().campusId());
    for (var shop : old.shops())
      one(db, "SELECT id FROM merchant WHERE id=? FOR SHARE", shop.merchantId());
    var skus = new TreeSet<Long>();
    for (var shop : old.shops())
      for (var line : shop.lines()) for (var c : line.product().components()) skus.add(c.skuId());
    catalog.lockStocks(skus);
    Quote fresh = quotes.calculate(old.request());
    if (!fresh.unavailable().isEmpty()
        || fresh.addressVersion() != old.addressVersion()
        || !sameSnapshot(old.shops(), fresh.shops()))
      throw new BusinessException(
          HttpStatus.CONFLICT, "REQUOTE_REQUIRED", "商品、地址、优惠或配送规则已变化，请重新报价核对");
    String checkout = UUID.randomUUID().toString();
    var expires = BusinessTime.now().plusMinutes(10);
    db.update(
        "INSERT INTO"
            + " checkout(id,user_id,quote_id,idempotency_key,request_fingerprint,status,total_amount,expires_at)"
            + " VALUES(?,?,?,?,?,'UNPAID',?,?)",
        checkout,
        u,
        old.id(),
        key,
        fingerprint,
        old.total(),
        expires);
    var selectedCoupons = new ArrayList<Long>();
    for (var s : old.shops()) if (s.couponId() != null) selectedCoupons.add(s.couponId());
    if (old.request().platformCoupon() != null) selectedCoupons.add(old.request().platformCoupon());
    check(new HashSet<>(selectedCoupons).size() == selectedCoupons.size(), "同一优惠券不能重复使用");
    coupons.lock(selectedCoupons, checkout);
    Map<String, Object> address =
        "DELIVERY".equals(old.request().fulfillmentMode())
            ? one(
                db,
                "SELECT * FROM customer_address WHERE id=? AND user_id=?",
                old.request().addressId(),
                u)
            : Map.of("recipient_name", "到店自提", "phone", "", "detail", "");
    for (var s : old.shops()) createOrder(checkout, u, expires, address, s);
    db.update("UPDATE checkout_quote SET consumed_checkout_id=? WHERE id=?", checkout, old.id());
    for (var s : old.shops())
      for (var l : s.lines())
        db.update(
            "DELETE FROM cart_item WHERE id=? AND user_id=? AND version=?",
            l.cartId(),
            u,
            l.cartVersion());
    UserContext.checkDeadline();
    events.record(u, null, null, "CHECKOUT_CONFIRMED", checkout);
    audit.record(null, "CHECKOUT_CONFIRMED", checkout);
    return detail(checkout);
  }

  private void createOrder(
      String checkout,
      long u,
      java.time.LocalDateTime expires,
      Map<String, Object> address,
      ShopQuote s) {
    if (s.slotId() != null)
      check(
          db.update(
                  "UPDATE reservation_slot SET reserved_count=reserved_count+1 WHERE id=? AND"
                      + " merchant_id=? AND enabled=TRUE AND reserved_count<capacity AND"
                      + " cutoff_at>?",
                  s.slotId(),
                  s.merchantId(),
                  BusinessTime.now())
              == 1,
          "预约时段容量已满");
    db.update(
        "INSERT INTO user_order_sequence(user_id,next_seq) VALUES(?,1) ON DUPLICATE KEY UPDATE"
            + " user_id=VALUES(user_id)",
        u);
    long seq =
        ((Number)
                one(db, "SELECT next_seq FROM user_order_sequence WHERE user_id=? FOR UPDATE", u)
                    .get("next_seq"))
            .longValue();
    db.update("UPDATE user_order_sequence SET next_seq=? WHERE user_id=?", seq + 1, u);
    long order =
        insert(
            db,
            "INSERT INTO"
                + " orders(merchant_id,user_id,user_seq,total_amount,remark,status,payment_status,payment_expires_at,create_time,recipient_name,recipient_phone,delivery_address,delivery_region,checkout_id,commerce_version,campus_id,fulfillment_mode,fulfillment_status,goods_amount,packaging_amount,delivery_fee,shop_discount,platform_discount,reservation_slot_id)"
                + " VALUES(?,?,?,?,?,0,'UNPAID',?,?,?,?,?,?,?,2,?,?,'UNPAID',?,?,?,?,?,?)",
            s.merchantId(),
            u,
            seq,
            s.payable(),
            "",
            expires,
            BusinessTime.now(),
            address.get("recipient_name"),
            address.get("phone"),
            address.get("detail"),
            String.valueOf(s.campusId()),
            checkout,
            s.campusId(),
            s.mode(),
            s.goods(),
            s.packaging(),
            s.delivery(),
            s.shopDiscount(),
            s.platformDiscount(),
            s.slotId());
    var unitGoods = new ArrayList<BigDecimal>();
    for (var l : s.lines())
      for (int i = 0; i < l.quantity(); i++) unitGoods.add(l.product().price());
    var shopAlloc = allocate(s.shopDiscount(), unitGoods);
    var remaining = new ArrayList<BigDecimal>();
    for (int i = 0; i < unitGoods.size(); i++)
      remaining.add(unitGoods.get(i).subtract(shopAlloc.get(i)));
    var platformAlloc = allocate(s.platformDiscount(), remaining);
    int unitIndex = 0;
    var stockNeeds = new TreeMap<Long, Integer>();
    for (var l : s.lines()) {
      var p = l.product();
      BigDecimal sd = ZERO, pd = ZERO;
      for (int i = 0; i < l.quantity(); i++) {
        sd = sd.add(shopAlloc.get(unitIndex + i));
        pd = pd.add(platformAlloc.get(unitIndex + i));
      }
      long item =
          insert(
              db,
              "INSERT INTO"
                  + " order_item(order_id,merchant_id,dish_id,dish_name,quantity,price,amount,product_type,product_id,packaging_amount,shop_discount,platform_discount,remark)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
              order,
              s.merchantId(),
              p.dishId() == 0 ? null : p.dishId(),
              p.name(),
              l.quantity(),
              p.price(),
              p.price().multiply(BigDecimal.valueOf(l.quantity())),
              p.type(),
              p.id(),
              p.packagingFee().multiply(BigDecimal.valueOf(l.quantity())),
              sd,
              pd,
              l.remark());
      for (var c : p.components()) {
        db.update("INSERT INTO order_stock_component VALUES(?,?,?)", item, c.skuId(), c.quantity());
        stockNeeds.merge(c.skuId(), Math.multiplyExact(c.quantity(), l.quantity()), Integer::sum);
      }
      for (int i = 0; i < l.quantity(); i++, unitIndex++)
        db.update(
            "INSERT INTO"
                + " order_unit(order_id,order_item_id,unit_no,goods_paid,packaging_paid,shop_discount,platform_discount)"
                + " VALUES(?,?,?,?,?,?,?)",
            order,
            item,
            i + 1,
            remaining.get(unitIndex).subtract(platformAlloc.get(unitIndex)),
            p.packagingFee(),
            shopAlloc.get(unitIndex),
            platformAlloc.get(unitIndex));
    }
    for (var e : stockNeeds.entrySet())
      catalog.stockChange(
          catalog.stock(e.getKey(), true), -e.getValue(), order, "reserve:" + order);
  }

  public List<Map<String, Object>> list() {
    return list(1, 100);
  }

  public List<Map<String, Object>> list(int page, int pageSize) {
    check(page >= 1 && page <= 1000000 && pageSize >= 1 && pageSize <= 100, "结算分页参数无效");
    return db.queryForList(
        "SELECT * FROM checkout WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
        user(),
        pageSize,
        (long) (page - 1) * pageSize);
  }

  public Map<String, Object> detail(String id) {
    var c = one(db, "SELECT * FROM checkout WHERE id=? AND user_id=?", id, user());
    var orders =
        db.queryForList(
            "SELECT o.*,m.name merchant_name FROM orders o JOIN merchant m ON m.id=o.merchant_id"
                + " WHERE checkout_id=? AND user_id=? ORDER BY o.id",
            id,
            user());
    for (var o : orders) {
      sanitize(o);
      o.put(
          "items",
          db.queryForList(
              "SELECT i.*,(SELECT COUNT(*) FROM order_unit u WHERE u.order_item_id=i.id AND"
                  + " u.status='AVAILABLE') refundable_quantity FROM order_item i WHERE order_id=?"
                  + " ORDER BY id",
              id(o, "id")));
      o.put(
          "aftersales",
          db.queryForList(
              "SELECT * FROM aftersale WHERE order_id=? ORDER BY created_at", id(o, "id")));
    }
    c.put("orders", orders);
    return c;
  }

  private void sanitize(Map<String, Object> order) {
    order.remove("verification_code_hash");
  }

  public Map<String, Object> authorizedOrder(long id) {
    var order = one(db, "SELECT * FROM orders WHERE id=? AND commerce_version=2", id);
    String role = UserContext.role();
    if ("CUSTOMER".equals(role)) check(id(order, "user_id") == user(), "无权访问订单");
    else if (Set.of("OWNER", "STAFF").contains(role))
      check(id(order, "merchant_id") == UserContext.merchantId(), "无权访问订单");
    else UserContext.requireRole("PLATFORM_ADMIN");
    return order;
  }

  public Map<String, Object> lockOrder(long id) {
    if ("CUSTOMER".equals(UserContext.role())) safety.lockUser(user());
    var order = authorizedOrder(id);
    return lockAggregate(order);
  }

  /** Caller must authorize before calling this internal aggregate lock. */
  Map<String, Object> lockAggregate(Map<String, Object> order) {
    safety.lockUser(id(order, "user_id"));
    one(db, "SELECT id FROM checkout WHERE id=? FOR UPDATE", order.get("checkout_id"));
    return one(db, "SELECT * FROM orders WHERE id=? FOR UPDATE", id(order, "id"));
  }

  private Map<String, Object> ownCheckoutLock(String checkout) {
    long u = user();
    safety.lockUser(u);
    return one(db, "SELECT * FROM checkout WHERE id=? AND user_id=? FOR UPDATE", checkout, u);
  }

  @Transactional
  public Map<String, Object> pay(String id) {
    var c = ownCheckoutLock(id);
    if ("PAID".equals(c.get("status"))) return detail(id);
    check(
        "UNPAID".equals(c.get("status")) && time(c, "expires_at").isAfter(BusinessTime.now()),
        "结算已关闭或超过支付期限");
    var orders = lockCheckoutOrders(id);
    check(orders.stream().allMatch(o -> num(o, "status") == 0), "子订单状态不允许支付");
    db.update(
        "INSERT INTO commerce_payment(checkout_id,operation_key,kind,amount)"
            + " VALUES(?,?,'PAYMENT',?)",
        id,
        "pay:" + id,
        c.get("total_amount"));
    coupons.paid(id);
    for (var o : orders) {
      long oid = id(o, "id");
      String code = String.format("%06d", new SecureRandom().nextInt(1000000));
      db.update(
          "UPDATE orders SET"
              + " status=1,payment_status='SIMULATED_PAID',fulfillment_status='PENDING_ACCEPTANCE',verification_code_hash=?"
              + " WHERE id=?",
          hash(code),
          oid);
      // Only the owning customer can retrieve this code; no code is included in event or audit
      // data.
      db.update(
          "INSERT INTO customer_order_code(order_id,user_id,code) VALUES(?,?,?)",
          oid,
          user(),
          code);
      db.update(
          "INSERT INTO payment_record(merchant_id,order_id,kind,amount) VALUES(?,?,'PAYMENT',?)",
          o.get("merchant_id"),
          oid,
          o.get("total_amount"));
    }
    db.update("UPDATE checkout SET status='PAID' WHERE id=?", id);
    events.record(user(), null, null, "CHECKOUT_PAID", id);
    audit.record(null, "CHECKOUT_PAID", id);
    return detail(id);
  }

  public String code(long order) {
    one(db, "SELECT id FROM orders WHERE id=? AND user_id=?", order, user());
    return (String)
        one(
                db,
                "SELECT code FROM customer_order_code WHERE order_id=? AND user_id=?",
                order,
                user())
            .get("code");
  }

  @Transactional
  public Map<String, Object> close(String checkout) {
    var c = ownCheckoutLock(checkout);
    if ("CLOSED".equals(c.get("status"))) return detail(checkout);
    check("UNPAID".equals(c.get("status")), "已支付结算请逐店取消或申请售后");
    closeUnpaid(c);
    return detail(checkout);
  }

  private void closeUnpaid(Map<String, Object> c) {
    String checkout = (String) c.get("id");
    var orders = lockCheckoutOrders(checkout);
    // SKU IDs can be interleaved across merchants. Reserve and compensate the entire
    // checkout in the same global order instead of acquiring one shop at a time.
    var components =
        db.queryForList(
            "SELECT DISTINCT c.sku_id FROM order_stock_component c JOIN order_item i"
                + " ON i.id=c.order_item_id JOIN orders o ON o.id=i.order_id"
                + " WHERE o.checkout_id=?"
                + " ORDER BY c.sku_id",
            checkout);
    catalog.lockStocks(components.stream().map(row -> id(row, "sku_id")).toList());
    coupons.release(checkout, null, true);
    for (var o : orders) {
      releaseStockAll(o);
      releaseCapacity(o);
      db.update(
          "UPDATE orders SET status=6,payment_status='CLOSED',fulfillment_status='CLOSED' WHERE"
              + " id=?",
          id(o, "id"));
    }
    db.update("UPDATE checkout SET status='CLOSED' WHERE id=?", checkout);
    events.record(id(c, "user_id"), null, null, "CHECKOUT_CLOSED", checkout);
  }

  @Transactional
  public Map<String, Object> cancel(long order) {
    var o = lockOrder(order);
    if (num(o, "status") == 5 || num(o, "status") == 6)
      return Map.of("orderId", order, "status", o.get("status"));
    if ("UNPAID".equals(o.get("payment_status"))) {
      closeUnpaid(one(db, "SELECT * FROM checkout WHERE id=?", o.get("checkout_id")));
      return Map.of("orderId", order, "closedCheckout", o.get("checkout_id"));
    }
    check(!Boolean.TRUE.equals(o.get("production_started")), "制作开始后请申请售后");
    check(num(o, "status") == 1, "订单不能取消");
    var units = lockOrderUnits(order);
    check(units.stream().noneMatch(u -> "PENDING".equals(u.get("status"))), "存在待处理售后，不能整单取消");
    BigDecimal remaining = amount(o, "total_amount").subtract(amount(o, "refunded_amount"));
    credit(o, remaining, "cancel:" + order);
    releaseStockAll(o, units);
    for (var unit : units)
      db.update("UPDATE order_unit SET status='REFUNDED' WHERE id=?", unit.get("id"));
    db.update(
        "UPDATE orders SET"
            + " status=5,payment_status='SIMULATED_REFUNDED',fulfillment_status='CANCELLED' WHERE"
            + " id=?",
        order);
    coupons.release((String) o.get("checkout_id"), id(o, "merchant_id"), false);
    if (allCancelledBeforeProduction((String) o.get("checkout_id")))
      coupons.release((String) o.get("checkout_id"), null, false);
    releaseCapacity(o);
    events.record(id(o, "user_id"), id(o, "merchant_id"), null, "ORDER_CANCELLED", order);
    audit.record(id(o, "merchant_id"), "COMMERCE_CANCELLED", order);
    return Map.of("orderId", order, "refunded", remaining);
  }

  void releaseCapacity(Map<String, Object> o) {
    if (o.get("reservation_slot_id") != null && !Boolean.TRUE.equals(o.get("capacity_released"))) {
      check(
          db.update(
                  "UPDATE reservation_slot SET reserved_count=reserved_count-1 WHERE id=? AND"
                      + " reserved_count>0",
                  o.get("reservation_slot_id"))
              == 1,
          "预约容量异常");
      db.update("UPDATE orders SET capacity_released=TRUE WHERE id=?", id(o, "id"));
    }
  }

  void releaseStockAll(Map<String, Object> o) {
    releaseStockAll(o, lockOrderUnits(id(o, "id")));
  }

  private void releaseStockAll(Map<String, Object> o, List<Map<String, Object>> units) {
    if (Boolean.TRUE.equals(o.get("inventory_released"))) return;
    var needs =
        stockNeeds(units.stream().filter(u -> !"REFUNDED".equals(u.get("status"))).toList());
    catalog.lockStocks(needs.keySet());
    for (var n : needs.entrySet())
      catalog.stockChange(
          catalog.stock(n.getKey(), true), n.getValue(), id(o, "id"), "release:" + id(o, "id"));
    db.update("UPDATE orders SET inventory_released=TRUE WHERE id=?", id(o, "id"));
  }

  private List<Map<String, Object>> lockCheckoutOrders(String checkout) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row :
        db.queryForList("SELECT id FROM orders WHERE checkout_id=? ORDER BY id", checkout))
      result.add(one(db, "SELECT * FROM orders WHERE id=? FOR UPDATE", row.get("id")));
    return result;
  }

  boolean allCancelledBeforeProduction(String checkout) {
    return lockCheckoutOrders(checkout).stream()
        .allMatch(o -> num(o, "status") == 5 && !Boolean.TRUE.equals(o.get("production_started")));
  }

  /** Membership is immutable; status must be a primary-key current read after aggregate locking. */
  List<Map<String, Object>> lockOrderUnits(long order) {
    var result = new ArrayList<Map<String, Object>>();
    for (var row : db.queryForList("SELECT id FROM order_unit WHERE order_id=? ORDER BY id", order))
      result.add(one(db, "SELECT * FROM order_unit WHERE id=? FOR UPDATE", row.get("id")));
    return result;
  }

  Map<Long, Integer> stockNeeds(List<Map<String, Object>> units) {
    var quantities = new TreeMap<Long, Integer>();
    for (var unit : units) quantities.merge(id(unit, "order_item_id"), 1, Integer::sum);
    var needs = new TreeMap<Long, Integer>();
    if (quantities.isEmpty()) return needs;
    for (var component :
        db.queryForList(
            "SELECT * FROM order_stock_component WHERE order_item_id IN ("
                + String.join(",", Collections.nCopies(quantities.size(), "?"))
                + ")",
            quantities.keySet().toArray()))
      needs.merge(
          id(component, "sku_id"),
          Math.multiplyExact(
              num(component, "quantity_per_unit"), quantities.get(id(component, "order_item_id"))),
          Integer::sum);
    return needs;
  }

  void credit(Map<String, Object> order, BigDecimal amount, String key) {
    check(
        amount.signum() >= 0
            && amount.compareTo(
                    amount(order, "total_amount").subtract(amount(order, "refunded_amount")))
                <= 0,
        "退款超出原实付");
    db.update(
        "INSERT INTO commerce_payment(checkout_id,order_id,operation_key,kind,amount)"
            + " VALUES(?,?,?,'REFUND',?)",
        order.get("checkout_id"),
        order.get("id"),
        key,
        amount);
    db.update(
        "UPDATE orders SET"
            + " payment_status=IF(refunded_amount+?>=total_amount,'SIMULATED_REFUNDED','PARTIALLY_REFUNDED'),refunded_amount=refunded_amount+?"
            + " WHERE id=?",
        amount,
        amount,
        order.get("id"));
    db.update(
        "UPDATE checkout SET refunded_amount=refunded_amount+? WHERE id=?",
        amount,
        order.get("checkout_id"));
  }

  @Scheduled(fixedDelay = 15000, initialDelay = 20000)
  public void expire() {
    for (var c :
        db.queryForList(
            "SELECT id,user_id FROM checkout WHERE status='UNPAID' AND expires_at<=? ORDER BY id"
                + " LIMIT 100",
            BusinessTime.now())) {
      try {
        tx.executeWithoutResult(
            status -> {
              safety.lockUser(id(c, "user_id"));
              var row = one(db, "SELECT * FROM checkout WHERE id=? FOR UPDATE", c.get("id"));
              if ("UNPAID".equals(row.get("status"))
                  && !time(row, "expires_at").isAfter(BusinessTime.now())) closeUnpaid(row);
            });
      } catch (RuntimeException e) {
        metrics.failure("expire_compensation");
      }
    }
  }
}
