package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;

import com.ai.assistant.config.BackgroundTask;
import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.*;
import io.micrometer.core.instrument.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable payment orchestration. Only short state transitions run inside transactions. */
@Service
public class ProviderPaymentService {
  public record Input(String sourceType, String sourceId, String scene) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final PaymentSettings settings;
  private final PaymentProvider provider;
  private final ObjectProvider<CommerceCheckoutService> checkouts;
  private final ObjectProvider<OrderTransactionService> legacy;
  private final OrderSafetyService safety;
  private final ProviderRefundLedger refunds;
  private final CommerceEventService events;
  private final AuditService audit;
  private final java.util.concurrent.atomic.AtomicBoolean sweeping =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.ExecutorService worker =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "provider-payments");
            thread.setDaemon(true);
            return thread;
          });

  public ProviderPaymentService(
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      PaymentSettings settings,
      PaymentProvider provider,
      ObjectProvider<CommerceCheckoutService> checkouts,
      ObjectProvider<OrderTransactionService> legacy,
      OrderSafetyService safety,
      ProviderRefundLedger refunds,
      CommerceEventService events,
      AuditService audit,
      MeterRegistry registry) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.settings = settings;
    this.provider = provider;
    this.checkouts = checkouts;
    this.legacy = legacy;
    this.safety = safety;
    this.refunds = refunds;
    this.events = events;
    this.audit = audit;
    Gauge.builder(
            "payment_unknown_count",
            db,
            j ->
                j.queryForObject(
                    "SELECT COUNT(*) FROM provider_payment WHERE status IN ('UNKNOWN','REVIEW')",
                    Double.class))
        .register(registry);
    Gauge.builder(
            "payment_refund_pending_count",
            db,
            j ->
                j.queryForObject(
                    "SELECT COUNT(*) FROM provider_refund WHERE status<>'SUCCESS'", Double.class))
        .register(registry);
    Gauge.builder(
            "payment_reconciliation_differences_count",
            db,
            j ->
                j.queryForObject(
                    "SELECT COUNT(*) FROM payment_reconciliation_difference WHERE resolved_at IS"
                        + " NULL",
                    Double.class))
        .register(registry);
  }

  public Map<String, Object> create(Input input, String clientIp) {
    long user = user();
    check(
        input != null
            && Set.of("CHECKOUT", "ORDER").contains(Objects.toString(input.sourceType(), "")),
        "支付来源无效");
    check(Set.of("NATIVE", "H5", "JSAPI").contains(Objects.toString(input.scene(), "")), "支付场景无效");
    text(input.sourceId(), 36, "订单标识");
    String payment = tx.execute(status -> prepare(input, user, clientIp));
    var row = payment(payment);
    if ("CREATED".equals(row.get("status"))) submit(payment);
    return session(payment, user);
  }

  private String prepare(Input in, long user, String ip) {
    safety.lockUser(user);
    List<Map<String, Object>> orders;
    LocalDateTime expiry;
    String mode;
    if ("CHECKOUT".equals(in.sourceType())) {
      var c =
          one(
              db,
              "SELECT * FROM checkout WHERE id=? AND user_id=? FOR UPDATE",
              in.sourceId(),
              user);
      orders =
          db.queryForList(
              "SELECT * FROM orders WHERE checkout_id=? ORDER BY id FOR UPDATE", in.sourceId());
      expiry = time(c, "expires_at");
      mode = (String) c.get("payment_mode");
    } else {
      check(in.sourceId().matches("[1-9][0-9]{0,17}"), "订单标识无效");
      var o =
          one(
              db,
              "SELECT * FROM orders WHERE id=? AND user_id=? AND commerce_version=1 FOR UPDATE",
              in.sourceId(),
              user);
      orders = List.of(o);
      expiry = time(o, "payment_expires_at");
      mode = (String) o.get("payment_mode");
    }
    var old =
        db.queryForList(
            "SELECT * FROM provider_payment WHERE source_type=? AND source_id=?",
            in.sourceType(),
            in.sourceId());
    if (!old.isEmpty()) {
      check(id(old.getFirst(), "user_id") == user, "无权访问支付");
      return (String) old.getFirst().get("id");
    }
    check(
        !orders.isEmpty()
            && orders.stream().allMatch(o -> num(o, "status") == 0)
            && expiry.isAfter(BusinessTime.now()),
        "订单已关闭、已支付或超过期限");
    check(settings.mode.equals(mode), "订单支付模式与当前环境不同，请取消后重新下单");
    if (settings.real()
        && "JSAPI".equals(in.scene())
        && db.queryForList(
                "SELECT user_id FROM payment_wechat_identity WHERE user_id=? AND app_id=?",
                user,
                settings.appId)
            .isEmpty())
      throw new BusinessException(HttpStatus.CONFLICT, "WECHAT_OPENID_REQUIRED", "请先完成微信支付授权");
    String id = UUID.randomUUID().toString().replace("-", "");
    long total =
        orders.stream()
            .map(o -> amount(o, "total_amount"))
            .reduce(ZERO, BigDecimal::add)
            .movePointRight(2)
            .longValueExact();
    db.update(
        "INSERT INTO"
            + " provider_payment(id,source_type,source_id,user_id,mode,scene,status,amount,expires_at,client_ip)"
            + " VALUES(?,?,?,?,?,?,'CREATED',?,?,?)",
        id,
        in.sourceType(),
        in.sourceId(),
        user,
        mode,
        in.scene(),
        total,
        expiry,
        ip);
    for (var o : orders) {
      String sub = "";
      if (settings.real())
        sub =
            (String)
                one(
                        db,
                        "SELECT sub_mch_id FROM merchant_payment_binding WHERE merchant_id=? AND"
                            + " enabled=TRUE FOR SHARE",
                        o.get("merchant_id"))
                    .get("sub_mch_id");
      db.update(
          "INSERT INTO"
              + " provider_payment_part(payment_id,order_id,merchant_id,out_trade_no,sub_mch_id,amount)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          o.get("id"),
          o.get("merchant_id"),
          UUID.randomUUID().toString().replace("-", ""),
          sub,
          amount(o, "total_amount").movePointRight(2).longValueExact());
    }
    return id;
  }

  private void submit(String id) {
    if (!claim("provider_payment", id)) return;
    var p = payment(id);
    try {
      if (!"WECHAT_PARTNER".equals(p.get("mode"))) {
        tx.executeWithoutResult(
            s ->
                UserContext.asCustomer(
                    id(p, "user_id"),
                    () -> {
                      if ("CHECKOUT".equals(p.get("source_type")))
                        checkouts.getObject().pay((String) p.get("source_id"));
                      else {
                        var o = sourceOrders(p).getFirst();
                        UserContext.within(
                            id(o, "merchant_id"),
                            () -> legacy.getObject().pay(id(p, "user_id"), id(o, "user_seq")));
                      }
                      db.update(
                          "UPDATE provider_payment SET status='PAID',lease_until=NULL WHERE id=?",
                          id);
                      db.update(
                          "UPDATE provider_payment_part SET status='PAID' WHERE payment_id=?", id);
                      return null;
                    }));
        return;
      }
      if (id(p, "amount") == 0) {
        tx.executeWithoutResult(s -> completePayment(p));
        return;
      }
      var body = new JSONObject();
      body.put("combine_appid", settings.appId);
      body.put("combine_mchid", settings.mchId);
      body.put("combine_out_trade_no", id);
      body.put("notify_url", settings.notifyBase + "/callbacks/wechatpay/payment");
      body.put(
          "time_expire",
          time(p, "expires_at")
              .atOffset(ZoneOffset.ofHours(8))
              .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")));
      var children = new JSONArray();
      for (var part : parts(id))
        if (id(part, "amount") > 0)
          children.add(
              JSONObject.of(
                  "mchid",
                  settings.mchId,
                  "sub_mchid",
                  part.get("sub_mch_id"),
                  "out_trade_no",
                  part.get("out_trade_no"),
                  "attach",
                  "order:" + part.get("order_id"),
                  "description",
                  description(id(part, "order_id")),
                  "amount",
                  JSONObject.of("total_amount", part.get("amount"), "currency", "CNY")));
      body.put("sub_orders", children);
      String scene = (String) p.get("scene");
      if ("H5".equals(scene))
        body.put(
            "scene_info",
            JSONObject.of(
                "payer_client_ip",
                p.get("client_ip"),
                "device_id",
                "campus-web",
                "h5_info",
                JSONObject.of("type", "Wap", "app_name", "校园点餐", "app_url", settings.notifyBase)));
      if ("JSAPI".equals(scene))
        body.put(
            "combine_payer_info",
            JSONObject.of(
                "openid",
                one(
                        db,
                        "SELECT open_id FROM payment_wechat_identity WHERE user_id=? AND app_id=?",
                        p.get("user_id"),
                        settings.appId)
                    .get("open_id")));
      JSONObject response =
          provider.request(
              "POST", "/v3/combine-transactions/" + scene.toLowerCase(Locale.ROOT), body);
      Map<String, Object> action;
      if ("JSAPI".equals(scene)) {
        String stamp = Long.toString(Instant.now().getEpochSecond()),
            nonce = UUID.randomUUID().toString().replace("-", "");
        String pack = "prepay_id=" + required(response, "prepay_id");
        action =
            Map.of(
                "type",
                "JSAPI",
                "payload",
                Map.of(
                    "appId",
                    settings.appId,
                    "timeStamp",
                    stamp,
                    "nonceStr",
                    nonce,
                    "package",
                    pack,
                    "signType",
                    "RSA",
                    "paySign",
                    provider.sign(
                        settings.appId + "\n" + stamp + "\n" + nonce + "\n" + pack + "\n")));
      } else
        action =
            Map.of(
                "type",
                "NATIVE".equals(scene) ? "QR" : "REDIRECT",
                "payload",
                required(response, "NATIVE".equals(scene) ? "code_url" : "h5_url"));
      db.update(
          "UPDATE provider_payment SET"
              + " status=IF(close_requested,'CLOSING','PENDING'),action_json=?,lease_until=NULL,last_error=NULL,next_attempt_at=DATE_ADD(NOW(),INTERVAL"
              + " 30 SECOND) WHERE id=? AND status NOT IN"
              + " ('PAID','CLOSED','REFUNDING','REFUNDED','REVIEW')",
          json(action),
          id);
    } catch (RuntimeException e) {
      failed("provider_payment", id, e);
    }
  }

  public Map<String, Object> session(String id, long user) {
    var p = payment(id);
    check(id(p, "user_id") == user, "无权访问支付");
    return view(p);
  }

  private Map<String, Object> view(Map<String, Object> p) {
    return Map.of(
        "id",
        p.get("id"),
        "mode",
        p.get("mode"),
        "status",
        p.get("status"),
        "expiresAt",
        time(p, "expires_at"),
        "action",
        p.get("action_json") == null
                || Set.of("PAID", "CLOSED", "REVIEW", "REFUNDING", "REFUNDED")
                    .contains(p.get("status"))
            ? Map.of("type", "NONE", "payload", "")
            : JSON.parseObject(p.get("action_json").toString()));
  }

  public void query(String id) {
    var p = payment(id);
    if (!"WECHAT_PARTNER".equals(p.get("mode"))
        || Set.of("PAID", "CLOSED", "REFUNDING", "REFUNDED").contains(p.get("status"))) return;
    if (!claim("provider_payment", id)) return;
    try {
      if (id(p, "amount") == 0) {
        tx.executeWithoutResult(s -> completePayment(payment(id)));
        return;
      }
      JSONObject result;
      try {
        result =
            provider.request(
                "GET",
                "/v3/combine-transactions/out-trade-no/" + id + "?combine_mchid=" + settings.mchId,
                null);
      } catch (com.wechat.pay.java.core.exception.ServiceException e) {
        if (!Set.of("ORDER_NOT_EXIST", "RESOURCE_NOT_EXISTS").contains(e.getErrorCode())) throw e;
        if (Boolean.TRUE.equals(p.get("close_requested"))
            || !time(p, "expires_at").isAfter(BusinessTime.now())) {
          tx.executeWithoutResult(s -> completeClose(payment(id)));
          return;
        }
        if (db.update(
                "UPDATE provider_payment SET status='CREATED',lease_until=NULL WHERE id=? AND"
                    + " status NOT IN ('PAID','CLOSED','REFUNDING','REFUNDED','REVIEW')",
                id)
            == 1) submit(id);
        return;
      }
      tx.executeWithoutResult(s -> applyStatus(payment(id), result));
      p = payment(id);
      if ("PENDING".equals(p.get("status"))
          && p.get("action_json") == null
          && !Boolean.TRUE.equals(p.get("close_requested"))
          && time(p, "expires_at").isAfter(BusinessTime.now())) {
        if (db.update(
                "UPDATE provider_payment SET status='CREATED',lease_until=NULL WHERE id=? AND"
                    + " status='PENDING'",
                id)
            == 1) submit(id);
        return;
      }
      if (!Set.of("PAID", "CLOSED", "REFUNDING", "REVIEW").contains(p.get("status"))
          && (Boolean.TRUE.equals(p.get("close_requested"))
              || !time(p, "expires_at").isAfter(BusinessTime.now()))) {
        var children = new JSONArray();
        for (var part : parts(id))
          if (id(part, "amount") > 0)
            children.add(
                JSONObject.of(
                    "mchid",
                    settings.mchId,
                    "sub_mchid",
                    part.get("sub_mch_id"),
                    "out_trade_no",
                    part.get("out_trade_no")));
        provider.request(
            "POST",
            "/v3/combine-transactions/out-trade-no/" + id + "/close",
            JSONObject.of("combine_appid", settings.appId, "sub_orders", children));
        tx.executeWithoutResult(s -> completeClose(payment(id)));
      }
      db.update(
          "UPDATE provider_payment SET lease_until=NULL,next_attempt_at=DATE_ADD(NOW(),INTERVAL 30"
              + " SECOND) WHERE id=?",
          id);
    } catch (RuntimeException e) {
      failed("provider_payment", id, e);
    }
  }

  private void applyStatus(Map<String, Object> p, JSONObject result) {
    check(
        settings.mchId.equals(result.getString("combine_mchid"))
            && settings.appId.equals(result.getString("combine_appid"))
            && p.get("id").equals(result.getString("combine_out_trade_no")),
        "渠道合单身份不符");
    safety.lockUser(id(p, "user_id"));
    p = one(db, "SELECT * FROM provider_payment WHERE id=? FOR UPDATE", p.get("id"));
    var children = result.getJSONArray("sub_orders");
    var expected = parts((String) p.get("id"));
    check(
        children != null
            && children.size() == expected.stream().filter(x -> id(x, "amount") > 0).count(),
        "渠道子单数量不符");
    var seen = new HashSet<String>();
    boolean paid = true, closed = true;
    for (Object child : children) {
      JSONObject row = (JSONObject) child;
      String out = row.getString("out_trade_no");
      check(seen.add(out), "渠道子单重复");
      var part =
          expected.stream()
              .filter(x -> x.get("out_trade_no").equals(out))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("渠道子单未知"));
      JSONObject amount = row.getJSONObject("amount");
      String state = row.getString("trade_state");
      check(
          settings.mchId.equals(row.getString("mchid"))
              && part.get("sub_mch_id").equals(row.getString("sub_mchid"))
              && state != null,
          "渠道子单商户或状态不符");
      if ("SUCCESS".equals(state) || amount != null)
        check(
            amount != null
                && amount.containsKey("total_amount")
                && channelCents(amount, "total_amount") == id(part, "amount")
                && "CNY".equals(amount.getString("currency")),
            "渠道子单金额或币种不符");
      paid &= "SUCCESS".equals(state);
      closed &= "CLOSED".equals(state);
      if ("SUCCESS".equals(state)) {
        text(row.getString("transaction_id"), 64, "渠道交易号");
        String successTime = row.getString("success_time");
        check(successTime != null, "渠道支付时间缺失");
        LocalDateTime paidAt =
            OffsetDateTime.parse(successTime)
                .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                .toLocalDateTime();
        db.update(
            "UPDATE provider_payment_part SET status='PAID',transaction_id=?,paid_at=? WHERE"
                + " payment_id=? AND order_id=?",
            row.getString("transaction_id"),
            paidAt,
            p.get("id"),
            part.get("order_id"));
      }
    }
    if (paid) completePayment(p);
    else if (closed) completeClose(p);
    else
      db.update(
          "UPDATE provider_payment SET status=IF(close_requested,'CLOSING','PENDING') WHERE id=?"
              + " AND status NOT IN ('PAID','CLOSED','REFUNDING','REFUNDED')",
          p.get("id"));
  }

  private void completePayment(Map<String, Object> p) {
    safety.lockUser(id(p, "user_id"));
    p = one(db, "SELECT * FROM provider_payment WHERE id=? FOR UPDATE", p.get("id"));
    if (Set.of("PAID", "REFUNDING", "REFUNDED").contains(p.get("status"))) return;
    db.update("UPDATE provider_payment_part SET status='PAID' WHERE payment_id=?", p.get("id"));
    final var payment = p;
    UserContext.asCustomer(
        id(p, "user_id"),
        () -> {
          var orders = sourceOrders(payment);
          if (orders.stream().anyMatch(o -> num(o, "status") != 0)) {
            for (var order : orders) {
              db.update(
                  "INSERT INTO payment_record(merchant_id,order_id,kind,amount)"
                      + " VALUES(?,?,'PAYMENT',?)",
                  order.get("merchant_id"),
                  order.get("id"),
                  order.get("total_amount"));
              db.update(
                  "UPDATE order_unit SET status='REFUNDING' WHERE order_id=? AND"
                      + " status='AVAILABLE'",
                  order.get("id"));
              refunds.reserve(
                  id(order, "id"), amount(order, "total_amount"), "late:" + order.get("id"));
            }
            if ("CHECKOUT".equals(payment.get("source_type")))
              db.update(
                  "INSERT INTO commerce_payment(checkout_id,operation_key,kind,amount)"
                      + " VALUES(?,?,'PAYMENT',?)",
                  payment.get("source_id"),
                  "late-pay:" + payment.get("id"),
                  BigDecimal.valueOf(id(payment, "amount"), 2));
            db.update(
                "UPDATE provider_payment SET status='REFUNDING',lease_until=NULL WHERE id=?",
                payment.get("id"));
          } else {
            if ("CHECKOUT".equals(payment.get("source_type")))
              checkouts.getObject().settleVerifiedPayment((String) payment.get("source_id"));
            else {
              var order = orders.getFirst();
              UserContext.within(
                  id(order, "merchant_id"),
                  () ->
                      legacy
                          .getObject()
                          .settleVerifiedPayment(id(payment, "user_id"), id(order, "user_seq")));
            }
            db.update(
                "UPDATE provider_payment SET status='PAID',lease_until=NULL,last_error=NULL WHERE"
                    + " id=?",
                payment.get("id"));
          }
          return null;
        });
  }

  private void completeClose(Map<String, Object> p) {
    safety.lockUser(id(p, "user_id"));
    p = one(db, "SELECT * FROM provider_payment WHERE id=? FOR UPDATE", p.get("id"));
    if (Set.of("PAID", "REFUNDING", "CLOSED", "REFUNDED").contains(p.get("status"))) return;
    check(
        parts((String) p.get("id")).stream()
            .noneMatch(x -> "PAID".equals(x.get("status")) && id(x, "amount") > 0),
        "部分子单已付款，需人工核对");
    // The channel is now known closed. This flag authorizes the normal inventory compensation.
    db.update(
        "UPDATE provider_payment SET status='CLOSED',lease_until=NULL WHERE id=?", p.get("id"));
    final var payment = p;
    UserContext.asCustomer(
        id(p, "user_id"),
        () -> {
          if ("CHECKOUT".equals(payment.get("source_type")))
            checkouts.getObject().close((String) payment.get("source_id"));
          else {
            var order = sourceOrders(payment).getFirst();
            UserContext.within(
                id(order, "merchant_id"),
                () -> {
                  if (time(order, "payment_expires_at").isAfter(BusinessTime.now()))
                    legacy.getObject().cancel(id(payment, "user_id"), id(order, "user_seq"));
                  else legacy.getObject().expire(id(order, "id"));
                  return null;
                });
          }
          return null;
        });
  }

  public void callback(String kind, Map<String, String> headers, String body) {
    JSONObject data = provider.notification(headers, body);
    JSONObject envelope = JSON.parseObject(body);
    String callbackId = envelope.getString("id");
    text(callbackId, 64, "回调标识");
    tx.executeWithoutResult(
        s -> {
          var old = db.queryForList("SELECT * FROM provider_callback WHERE id=?", callbackId);
          if (!old.isEmpty()) {
            check(hash(body).equals(old.getFirst().get("payload_hash")), "重复回调内容不一致");
            return;
          }
          if ("PAYMENT".equals(kind))
            applyStatus(payment(required(data, "combine_out_trade_no")), data);
          else completeRefund(data, true);
          db.update(
              "INSERT INTO provider_callback(id,kind,payload_hash) VALUES(?,?,?)",
              callbackId,
              kind,
              hash(body));
        });
  }

  private void refund(String id) {
    if (!claim("provider_refund", id)) return;
    var r = one(db, "SELECT * FROM provider_refund WHERE id=?", id);
    try {
      if (id(r, "amount") == 0) {
        tx.executeWithoutResult(s -> settleRefund(r));
        return;
      }
      var part = one(db, "SELECT * FROM provider_payment_part WHERE order_id=?", r.get("order_id"));
      JSONObject response;
      try {
        response =
            provider.request(
                "GET",
                "/v3/refund/domestic/refunds/" + id + "?sub_mchid=" + part.get("sub_mch_id"),
                null);
      } catch (com.wechat.pay.java.core.exception.ServiceException e) {
        if (!Set.of("RESOURCE_NOT_EXISTS", "REFUND_NOT_EXIST").contains(e.getErrorCode())) throw e;
        response =
            provider.request(
                "POST",
                "/v3/refund/domestic/refunds",
                JSONObject.of(
                    "sub_mchid",
                    part.get("sub_mch_id"),
                    "out_trade_no",
                    part.get("out_trade_no"),
                    "out_refund_no",
                    id,
                    "reason",
                    "订单取消或售后退款",
                    "notify_url",
                    settings.notifyBase + "/callbacks/wechatpay/refund",
                    "amount",
                    JSONObject.of(
                        "refund",
                        r.get("amount"),
                        "total",
                        part.get("amount"),
                        "currency",
                        "CNY")));
      }
      JSONObject result = response;
      check(id.equals(result.getString("out_refund_no")), "退款查询编号不符");
      tx.executeWithoutResult(s -> completeRefund(result, false));
      db.update(
          "UPDATE provider_refund SET lease_until=NULL,next_attempt_at=DATE_ADD(NOW(),INTERVAL 30"
              + " SECOND) WHERE id=?",
          id);
    } catch (RuntimeException e) {
      failed("provider_refund", id, e);
    }
  }

  private void completeRefund(JSONObject result, boolean callback) {
    var r = one(db, "SELECT * FROM provider_refund WHERE id=?", required(result, "out_refund_no"));
    var order = one(db, "SELECT * FROM orders WHERE id=?", r.get("order_id"));
    safety.lockUser(id(order, "user_id"));
    if (order.get("checkout_id") != null)
      one(db, "SELECT id FROM checkout WHERE id=? FOR UPDATE", order.get("checkout_id"));
    one(db, "SELECT id FROM orders WHERE id=? FOR UPDATE", order.get("id"));
    r = one(db, "SELECT * FROM provider_refund WHERE id=? FOR UPDATE", r.get("id"));
    var part = one(db, "SELECT * FROM provider_payment_part WHERE order_id=?", r.get("order_id"));
    var amount = result.getJSONObject("amount");
    check(
        part.get("out_trade_no").equals(result.getString("out_trade_no"))
            && amount != null
            && channelCents(amount, "refund") == id(r, "amount")
            && channelCents(amount, "total") == id(part, "amount")
            && Objects.equals(part.get("transaction_id"), result.getString("transaction_id"))
            && (callback
                ? (settings.mchId.equals(result.getString("sp_mchid"))
                    && part.get("sub_mch_id").equals(result.getString("sub_mchid")))
                : "CNY".equals(amount.getString("currency"))),
        "退款渠道身份、金额或币种不符");
    String channelId = required(result, "refund_id");
    check(
        r.get("provider_refund_id") == null || r.get("provider_refund_id").equals(channelId),
        "退款渠道编号变化");
    if ("SUCCESS".equals(r.get("status"))) return;
    db.update("UPDATE provider_refund SET provider_refund_id=? WHERE id=?", channelId, r.get("id"));
    String state = result.getString(callback ? "refund_status" : "status");
    String created = result.getString("create_time");
    LocalDateTime accepted =
        created == null
            ? BusinessTime.now()
            : OffsetDateTime.parse(created)
                .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                .toLocalDateTime();
    db.update(
        "UPDATE provider_refund SET accepted_at=COALESCE(accepted_at,?) WHERE id=?",
        accepted,
        r.get("id"));
    if ("SUCCESS".equals(state)) settleRefund(r);
    else if (Set.of("ABNORMAL", "CLOSED").contains(Objects.toString(state, "")))
      db.update(
          "UPDATE provider_refund SET status='REVIEW',last_error=? WHERE id=? AND"
              + " status<>'SUCCESS'",
          state,
          r.get("id"));
    else
      db.update(
          "UPDATE provider_refund SET status='PROCESSING' WHERE id=? AND status<>'SUCCESS'",
          r.get("id"));
  }

  private void settleRefund(Map<String, Object> r) {
    var order = one(db, "SELECT * FROM orders WHERE id=?", r.get("order_id"));
    safety.lockUser(id(order, "user_id"));
    if (order.get("checkout_id") != null)
      one(db, "SELECT id FROM checkout WHERE id=? FOR UPDATE", order.get("checkout_id"));
    order = one(db, "SELECT * FROM orders WHERE id=? FOR UPDATE", order.get("id"));
    r = one(db, "SELECT * FROM provider_refund WHERE id=? FOR UPDATE", r.get("id"));
    if ("SUCCESS".equals(r.get("status"))) return;
    BigDecimal value = BigDecimal.valueOf(id(r, "amount"), 2);
    db.update(
        "UPDATE provider_refund SET status='SUCCESS',lease_until=NULL,last_error=NULL WHERE id=?",
        r.get("id"));
    db.update(
        "UPDATE orders SET payment_status=CASE WHEN refund_pending_amount-?>0 THEN 'REFUNDING' WHEN"
            + " refunded_amount+?>=total_amount THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED'"
            + " END,refund_pending_amount=refund_pending_amount-?,refunded_amount=refunded_amount+?"
            + " WHERE id=?",
        value,
        value,
        value,
        value,
        order.get("id"));
    db.update(
        "INSERT INTO payment_record(merchant_id,order_id,kind,amount) VALUES(?,?,'REFUND',?) ON"
            + " DUPLICATE KEY UPDATE amount=payment_record.amount+VALUES(amount)",
        order.get("merchant_id"),
        order.get("id"),
        value);
    String key = (String) r.get("operation_key");
    if (order.get("checkout_id") != null) {
      db.update(
          "UPDATE checkout SET refunded_amount=refunded_amount+? WHERE id=?",
          value,
          order.get("checkout_id"));
      db.update(
          "INSERT INTO commerce_payment(checkout_id,order_id,operation_key,kind,amount)"
              + " VALUES(?,?,?,'REFUND',?)",
          order.get("checkout_id"),
          order.get("id"),
          key,
          value);
      if (key.startsWith("aftersale:")) {
        String aftersale = key.substring("aftersale:".length());
        db.update(
            "UPDATE order_unit SET status='REFUNDED' WHERE aftersale_id=? AND status='REFUNDING'",
            aftersale);
      } else if (key.startsWith("cancel:") || key.startsWith("late:"))
        db.update(
            "UPDATE order_unit SET status='REFUNDED' WHERE order_id=? AND status='REFUNDING'",
            order.get("id"));
      String sale =
          key.startsWith("aftersale:")
              ? key.substring("aftersale:".length())
              : key.startsWith("refund-delivery:")
                  ? key.substring("refund-delivery:".length())
                  : null;
      if (sale != null)
        db.update(
            "UPDATE aftersale SET status='APPROVED' WHERE id=? AND status='REFUNDING' AND NOT"
                + " EXISTS (SELECT 1 FROM provider_refund WHERE operation_key IN (?,?) AND"
                + " status<>'SUCCESS')",
            sale,
            "aftersale:" + sale,
            "refund-delivery:" + sale);
      events.record(
          id(order, "user_id"),
          id(order, "merchant_id"),
          null,
          "REFUND_SUCCEEDED",
          order.get("id"));
    } else {
      final var refunded = order;
      UserContext.asCustomer(
          id(order, "user_id"),
          () ->
              UserContext.within(
                  id(refunded, "merchant_id"),
                  () -> legacy.getObject().recordProviderRefund(id(refunded, "id"))));
    }
    audit.record(id(order, "merchant_id"), "PROVIDER_REFUND_SUCCEEDED", r.get("id"));
    db.update(
        "UPDATE provider_payment p SET status='REFUNDED' WHERE id=? AND status='REFUNDING' AND NOT"
            + " EXISTS (SELECT 1 FROM provider_refund r WHERE r.payment_id=p.id AND"
            + " r.status<>'SUCCESS')",
        r.get("payment_id"));
  }

  @Scheduled(fixedDelay = 10000, initialDelay = 30000)
  public void sweep() {
    if (!sweeping.compareAndSet(false, true)) return;
    worker.execute(
        () -> {
          try {
            processDue();
          } finally {
            sweeping.set(false);
          }
        });
  }

  @jakarta.annotation.PreDestroy
  public void stopWorker() {
    worker.shutdownNow();
  }

  void processDue() {
    for (var row :
        db.queryForList(
            "SELECT id,status FROM provider_payment WHERE mode='WECHAT_PARTNER' AND status IN"
                + " ('CREATED','PENDING','UNKNOWN','CLOSING') AND next_attempt_at<=NOW() AND"
                + " (lease_until IS NULL OR lease_until<NOW()) ORDER BY next_attempt_at LIMIT 20"))
      BackgroundTask.run("payment-" + row.get("id"), () -> query((String) row.get("id")));
    for (var row :
        db.queryForList(
            "SELECT id FROM provider_refund WHERE status IN ('PENDING','UNKNOWN','PROCESSING') AND"
                + " next_attempt_at<=NOW() AND (lease_until IS NULL OR lease_until<NOW()) ORDER BY"
                + " next_attempt_at LIMIT 20"))
      BackgroundTask.run("refund-" + row.get("id"), () -> refund((String) row.get("id")));
  }

  private boolean claim(String table, String id) {
    db.update(
        "UPDATE "
            + table
            + " SET status='REVIEW',last_error='QUERY_LIMIT_REACHED' WHERE id=? AND attempts>=120"
            + " AND status IN ('PENDING','UNKNOWN','PROCESSING','CLOSING','CREATED')",
        id);
    return db.update(
            "UPDATE "
                + table
                + " SET lease_until=DATE_ADD(NOW(),INTERVAL 45 SECOND),attempts=attempts+1 WHERE"
                + " id=? AND status IN ('CREATED','PENDING','UNKNOWN','PROCESSING','CLOSING') AND"
                + " (lease_until IS NULL OR lease_until<NOW()) AND attempts<120",
            id)
        == 1;
  }

  private void failed(String table, String id, RuntimeException failure) {
    boolean rejected =
        failure instanceof com.wechat.pay.java.core.exception.ServiceException error
            && error.getHttpStatusCode() >= 400
            && error.getHttpStatusCode() < 500
            && error.getHttpStatusCode() != 429
            && error.getHttpStatusCode() != 408;
    db.update(
        "UPDATE "
            + table
            + " SET status=IF(attempts>=120 OR"
            + " ?,'REVIEW','UNKNOWN'),last_error=?,lease_until=NULL,next_attempt_at=DATE_ADD(NOW(),INTERVAL"
            + " 30 SECOND) WHERE id=? AND status NOT IN"
            + " ('PAID','SUCCESS','CLOSED','REFUNDING','REFUNDED')",
        rejected,
        failure instanceof IllegalArgumentException
            ? "INVALID_CHANNEL_RESULT"
            : rejected ? "CHANNEL_REJECTED" : "CHANNEL_REQUEST_UNCONFIRMED",
        id);
  }

  private Map<String, Object> payment(String id) {
    return one(db, "SELECT * FROM provider_payment WHERE id=?", id);
  }

  private List<Map<String, Object>> parts(String id) {
    return db.queryForList(
        "SELECT * FROM provider_payment_part WHERE payment_id=? ORDER BY order_id", id);
  }

  private List<Map<String, Object>> sourceOrders(Map<String, Object> p) {
    return "CHECKOUT".equals(p.get("source_type"))
        ? db.queryForList(
            "SELECT * FROM orders WHERE checkout_id=? ORDER BY id", p.get("source_id"))
        : db.queryForList("SELECT * FROM orders WHERE id=?", p.get("source_id"));
  }

  private static String required(JSONObject body, String key) {
    String s = body.getString(key);
    text(s, 2048, key);
    return s;
  }

  private static long channelCents(JSONObject amount, String key) {
    check(amount.get(key) instanceof Number, "渠道金额必须为整数分");
    try {
      return new BigDecimal(amount.get(key).toString()).longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("渠道金额必须为整数分");
    }
  }

  private String description(long order) {
    String names =
        String.join(
            "、",
            db.queryForList(
                "SELECT dish_name FROM order_item WHERE order_id=? ORDER BY id LIMIT 20",
                String.class,
                order));
    return ("校园点餐：" + names)
        .codePoints()
        .limit(120)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  public void retryRefund(String id) {
    UserContext.requireRole("PLATFORM_ADMIN");
    db.update(
        "UPDATE provider_refund SET status='UNKNOWN',attempts=0,next_attempt_at=NOW() WHERE id=?"
            + " AND status<>'SUCCESS' AND (lease_until IS NULL OR lease_until<NOW())",
        id);
    audit.record(null, "REFUND_REQUERY_REQUESTED", id);
    refund(id);
  }
}
