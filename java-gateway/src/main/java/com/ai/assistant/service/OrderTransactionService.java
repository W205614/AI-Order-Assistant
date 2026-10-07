package com.ai.assistant.service;

import com.ai.assistant.dto.ConfirmDraftDTO;
import com.ai.assistant.model.*;
import com.ai.assistant.security.UserContext;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderTransactionService {
  private final JdbcTemplate jdbc;
  private final DraftService drafts;
  private final MenuService menu;
  private final MerchantService merchants;
  private final OrderSafetyService safety;
  private final InventoryService inventory;
  private final OrderQueryService queries;
  private final AuditService audit;
  private final OrderStatusEventBroker events;

  public OrderTransactionService(
      JdbcTemplate jdbc,
      DraftService drafts,
      MenuService menu,
      MerchantService merchants,
      OrderSafetyService safety,
      InventoryService inventory,
      OrderQueryService queries,
      AuditService audit,
      OrderStatusEventBroker events) {
    this.jdbc = jdbc;
    this.drafts = drafts;
    this.menu = menu;
    this.merchants = merchants;
    this.safety = safety;
    this.inventory = inventory;
    this.queries = queries;
    this.audit = audit;
    this.events = events;
  }

  @Transactional(noRollbackFor = RequoteException.class)
  public Order confirm(long user, String id, String key, ConfirmDraftDTO input) {
    DraftService.validKey(key);
    if (input == null) throw new IllegalArgumentException("需要版本和收货信息");
    safety.lockUser(user);
    var draft = drafts.lock(user, id);
    var existing = queries.byKey(user, key);
    if (existing.isPresent()) {
      var order = existing.get();
      Long boundVersion =
          jdbc.queryForObject(
              "SELECT draft_version FROM orders WHERE id=?", Long.class, order.getId());
      String boundDraft =
          jdbc.queryForObject(
              "SELECT draft_id FROM orders WHERE id=?", String.class, order.getId());
      if (!Objects.equals(order.getMerchantId(), UserContext.merchantId())
          || !Objects.equals(boundDraft, id)
          || !Objects.equals(boundVersion, input.expectedVersion()))
        throw BusinessException.conflict("幂等键已绑定其他商户、草稿或版本", null);
      // 地址也是请求内容：同一键不能复用为另一份收货请求。
      if (!Objects.equals(order.getRecipientName(), input.recipientName())
          || !Objects.equals(order.getRecipientPhone(), input.recipientPhone())
          || !Objects.equals(order.getDeliveryAddress(), input.deliveryAddress())
          || !Objects.equals(order.getDeliveryRegion(), input.deliveryRegion()))
        throw BusinessException.conflict("幂等键请求内容不一致", null);
      return order;
    }
    drafts.version(draft, input.expectedVersion());
    drafts.editable(draft);
    var merchant = merchants.lock(true);
    if (input.recipientName() == null
        || input.recipientName().isBlank()
        || input.recipientName().length() > 50
        || input.recipientPhone() == null
        || !input.recipientPhone().matches("[0-9+ -]{7,30}")
        || input.deliveryAddress() == null
        || input.deliveryAddress().isBlank()
        || input.deliveryAddress().length() > 255
        || !merchant.deliveryRegions().contains(input.deliveryRegion()))
      throw new IllegalArgumentException("收货信息或配送区域无效");
    var context = safety.current(user);
    var resolved =
        menu.resolve(draft.getItems(), drafts.allergens(user, draft), context.needsClarification());
    boolean changed = resolved.size() != draft.getItems().size();
    for (var item : resolved) {
      var old =
          draft.getItems().stream().filter(i -> i.getDishId().equals(item.getDishId())).findFirst();
      if (old.isEmpty()
          || !Objects.equals(old.get().getDishVersion(), item.getDishVersion())
          || old.get().getPrice().compareTo(item.getPrice()) != 0) changed = true;
    }
    if (changed) {
      drafts.replace(draft, resolved, draft.getRemark(), drafts.allergens(user, draft));
      throw new RequoteException(draft);
    }
    jdbc.update(
        "INSERT INTO user_order_sequence(user_id,next_seq) VALUES(?,1) ON DUPLICATE KEY UPDATE"
            + " user_id=VALUES(user_id)",
        user);
    long seq =
        jdbc.queryForObject(
            "SELECT next_seq FROM user_order_sequence WHERE user_id=? FOR UPDATE",
            Long.class,
            user);
    jdbc.update("UPDATE user_order_sequence SET next_seq=? WHERE user_id=?", seq + 1, user);
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var ps =
              c.prepareStatement(
                  "INSERT INTO"
                      + " orders(merchant_id,user_id,user_seq,total_amount,remark,status,payment_status,payment_expires_at,create_time,idempotency_key,recipient_name,recipient_phone,delivery_address,delivery_region,draft_id,draft_version)"
                      + " VALUES(?,?,?,?,?,0,'UNPAID',?,?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setLong(1, UserContext.merchantId());
          ps.setLong(2, user);
          ps.setLong(3, seq);
          ps.setBigDecimal(4, draft.getTotalAmount());
          ps.setString(5, draft.getRemark());
          ps.setObject(6, BusinessTime.now().plusMinutes(10));
          ps.setObject(7, BusinessTime.now());
          ps.setString(8, key);
          ps.setString(9, input.recipientName());
          ps.setString(10, input.recipientPhone());
          ps.setString(11, input.deliveryAddress());
          ps.setString(12, input.deliveryRegion());
          ps.setString(13, id);
          ps.setLong(14, input.expectedVersion());
          return ps;
        },
        keys);
    long orderId = Objects.requireNonNull(keys.getKey()).longValue();
    jdbc.batchUpdate(
        "INSERT INTO order_item(order_id,merchant_id,dish_id,dish_name,quantity,price,amount)"
            + " VALUES(?,?,?,?,?,?,?)",
        resolved,
        20,
        (ps, item) -> {
          ps.setLong(1, orderId);
          ps.setLong(2, UserContext.merchantId());
          ps.setLong(3, item.getDishId());
          ps.setString(4, item.getDishName());
          ps.setInt(5, item.getQuantity());
          ps.setBigDecimal(6, item.getPrice());
          ps.setBigDecimal(7, item.getAmount());
        });
    inventory.reserve(orderId, resolved);
    drafts.confirmed(draft, orderId);
    safety.clearLocked(user);
    var order = queries.get(orderId).orElseThrow();
    audit.record("ORDER_CONFIRMED", orderId);
    events.record(order);
    return order;
  }

  @Transactional
  public Order pay(long user, long seq) {
    var order = ownLock(user, seq);
    if ("SIMULATED_PAID".equals(order.getPaymentStatus()) && order.getStatus() < 5) return order;
    if (order.getStatus() != 0 || !order.getPaymentExpiresAt().isAfter(BusinessTime.now()))
      throw BusinessException.conflict("订单已关闭或超过模拟支付期限", null);
    merchants.lock(false);
    jdbc.update(
        "INSERT INTO payment_record(merchant_id,order_id,kind,amount) VALUES(?,?,'PAYMENT',?)",
        UserContext.merchantId(),
        order.getId(),
        order.getTotalAmount());
    jdbc.update(
        "UPDATE orders SET status=1,payment_status='SIMULATED_PAID' WHERE id=? AND merchant_id=?",
        order.getId(),
        UserContext.merchantId());
    return finish(order.getId(), "SIMULATED_PAYMENT");
  }

  @Transactional
  public Order cancel(long user, long seq) {
    var order = ownLock(user, seq);
    if (order.getStatus() == 5 || order.getStatus() == 6) return order;
    if (order.getStatus() > 1) throw BusinessException.conflict("制作开始后不能取消", null);
    close(order, 5);
    return finish(order.getId(), "ORDER_CANCELLED");
  }

  @Transactional
  public Order status(long id, int next) {
    UserContext.requireRole("OWNER", "STAFF");
    var order = queries.lock(id);
    if (order.getStatus() == next) return order;
    if (next == 5 && order.getStatus() <= 1) {
      close(order, 5);
      return finish(id, "MERCHANT_CANCELLED");
    }
    if (next < 2
        || next > 4
        || next != order.getStatus() + 1
        || !Set.of("SIMULATED_PAID", "NOT_APPLICABLE").contains(order.getPaymentStatus()))
      throw BusinessException.conflict("订单状态不允许此操作", null);
    jdbc.update(
        "UPDATE orders SET status=?,deliver_time=IF(?=4,NOW(),deliver_time) WHERE id=? AND"
            + " merchant_id=?",
        next,
        next,
        id,
        UserContext.merchantId());
    return finish(id, "ORDER_STATUS_" + next);
  }

  @Transactional
  public void expire(long id) {
    var order = queries.lock(id);
    if (order.getStatus() != 0
        || order.getPaymentExpiresAt() == null
        || order.getPaymentExpiresAt().isAfter(BusinessTime.now())) return;
    close(order, 6);
    finish(id, "PAYMENT_TIMEOUT");
  }

  @Transactional
  public Order remind(long user, long seq) {
    var order = ownLock(user, seq);
    if (order.getStatus() < 1 || order.getStatus() > 3)
      throw BusinessException.conflict("此状态不能催单", null);
    if (order.getRemindTime() != null
        && order.getRemindTime().isAfter(BusinessTime.now().minusSeconds(60)))
      throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "REMIND_COOLDOWN", "请在一分钟后再催单");
    jdbc.update(
        "UPDATE orders SET remind_time=?,remind_count=remind_count+1 WHERE id=? AND merchant_id=?",
        BusinessTime.now(),
        order.getId(),
        UserContext.merchantId());
    return finish(order.getId(), "ORDER_REMINDER");
  }

  private Order ownLock(long user, long seq) {
    var order =
        queries
            .own(user, seq)
            .orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
    return queries.lock(order.getId());
  }

  private void close(Order order, int status) {
    inventory.release(order);
    String payment = order.getPaymentStatus();
    if ("SIMULATED_PAID".equals(payment)) {
      jdbc.update(
          "INSERT INTO payment_record(merchant_id,order_id,kind,amount) VALUES(?,?,'REFUND',?)",
          UserContext.merchantId(),
          order.getId(),
          order.getTotalAmount());
      payment = "SIMULATED_REFUNDED";
    } else if ("UNPAID".equals(payment)) payment = "CLOSED";
    jdbc.update(
        "UPDATE orders SET status=?,payment_status=? WHERE id=? AND merchant_id=?",
        status,
        payment,
        order.getId(),
        UserContext.merchantId());
  }

  private Order finish(long id, String action) {
    var result = queries.get(id).orElseThrow();
    audit.record(action, id);
    events.record(result);
    return result;
  }
}
