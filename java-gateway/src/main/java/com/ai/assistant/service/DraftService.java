package com.ai.assistant.service;

import com.ai.assistant.model.*;
import com.ai.assistant.security.UserContext;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户、商户、版本共同限定草稿；菜单行锁贯穿安全校验和确认。 */
@Service
public class DraftService {
  public static final int PENDING = 1, CONFIRMED = 2, CANCELLED = 3, EXPIRED = 4;
  private final JdbcTemplate jdbc;
  private final MenuService menu;
  private final MerchantService merchants;
  private final OrderSafetyService safety;

  public DraftService(
      JdbcTemplate jdbc, MenuService menu, MerchantService merchants, OrderSafetyService safety) {
    this.jdbc = jdbc;
    this.menu = menu;
    this.merchants = merchants;
    this.safety = safety;
  }

  public static void validKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,100}"))
      throw new IllegalArgumentException("幂等键须为 8–100 位字母、数字或 ._:-");
  }

  static String remark(String value) {
    if (value != null && value.length() > 255) throw new IllegalArgumentException("备注不能超过 255 字");
    return value;
  }

  public List<String> allergens(long user, OrderDraft draft) {
    var result = new LinkedHashSet<String>();
    jdbc.query(
        "SELECT allergens FROM user_food_preference WHERE user_id=?",
        rs -> {
          result.addAll(FoodSafety.splitTags(rs.getString(1)));
        },
        user);
    result.addAll(safety.current(user).allergens());
    if (draft != null) result.addAll(FoodSafety.splitTags(draft.getSafetyAllergens()));
    return List.copyOf(result);
  }

  @Transactional
  public OrderDraft create(long user, List<OrderItem> items, String note) {
    safety.lockUser(user);
    merchants.lock(true);
    var context = safety.current(user);
    var resolved = menu.resolve(items, allergens(user, null), context.needsClarification());
    jdbc.update(
        "UPDATE order_draft SET status=?,version=version+1 WHERE user_id=? AND merchant_id=? AND"
            + " status=?",
        CANCELLED,
        user,
        UserContext.merchantId(),
        PENDING);
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO"
            + " order_draft(id,user_id,merchant_id,total_amount,remark,status,expires_at,create_time,safety_allergens)"
            + " VALUES(?,?,?,?,?,?,?, ?,?)",
        id,
        user,
        UserContext.merchantId(),
        total(resolved),
        remark(note),
        PENDING,
        BusinessTime.now().plusMinutes(5),
        BusinessTime.now(),
        String.join(",", context.allergens()));
    insert(id, resolved);
    var draft = lock(user, id);
    safety.alignWithDraftLocked(user, draft.getExpiresAt());
    return draft;
  }

  @Transactional
  public OrderDraft update(
      long user, String id, List<OrderItem> items, String note, Long expected) {
    safety.lockUser(user);
    merchants.lock(true);
    var draft = lock(user, id);
    version(draft, expected);
    editable(draft);
    var context = safety.current(user);
    var resolved = menu.resolve(items, allergens(user, draft), context.needsClarification());
    replace(draft, resolved, remark(note), allergens(user, draft));
    safety.alignWithDraftLocked(user, draft.getExpiresAt());
    return draft;
  }

  @Transactional
  public OrderDraft cancel(long user, String id) {
    safety.lockUser(user);
    var draft = lock(user, id);
    if (draft.getStatus() == CANCELLED) return draft;
    editable(draft);
    jdbc.update(
        "UPDATE order_draft SET status=?,version=version+1 WHERE id=? AND merchant_id=?",
        CANCELLED,
        id,
        UserContext.merchantId());
    safety.clearLocked(user);
    draft.setStatus(CANCELLED);
    draft.setVersion(draft.getVersion() + 1);
    return draft;
  }

  @Transactional
  public List<OrderDraft> pending(long user) {
    safety.lockUser(user);
    jdbc.update(
        "UPDATE order_draft SET status=?,version=version+1 WHERE user_id=? AND merchant_id=? AND"
            + " status=? AND expires_at<=?",
        EXPIRED,
        user,
        UserContext.merchantId(),
        PENDING,
        BusinessTime.now());
    List<String> ids =
        jdbc.query(
            "SELECT id FROM order_draft WHERE user_id=? AND merchant_id=? AND status=? ORDER BY"
                + " create_time DESC",
            (rs, i) -> rs.getString(1),
            user,
            UserContext.merchantId(),
            PENDING);
    return ids.stream().map(id -> lock(user, id)).toList();
  }

  public OrderDraft lock(long user, String id) {
    var rows =
        jdbc.query(
            "SELECT * FROM order_draft WHERE id=? AND user_id=? AND merchant_id=? FOR UPDATE",
            (rs, i) -> {
              var d = new OrderDraft();
              d.setId(rs.getString("id"));
              d.setMerchantId(rs.getLong("merchant_id"));
              d.setVersion(rs.getLong("version"));
              d.setStatus(rs.getInt("status"));
              d.setRemark(rs.getString("remark"));
              d.setTotalAmount(rs.getBigDecimal("total_amount"));
              d.setExpiresAt(rs.getObject("expires_at", java.time.LocalDateTime.class));
              d.setSafetyAllergens(rs.getString("safety_allergens"));
              d.setConfirmedOrderId(rs.getObject("confirmed_order_id", Long.class));
              return d;
            },
            id,
            user,
            UserContext.merchantId());
    if (rows.isEmpty())
      throw new BusinessException(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "确认单不存在");
    var draft = rows.getFirst();
    draft.setItems(
        jdbc.query(
            "SELECT * FROM order_draft_item WHERE draft_id=? AND merchant_id=? ORDER BY dish_id",
            (rs, i) -> {
              var item = new OrderItem();
              item.setDishId(rs.getLong("dish_id"));
              item.setDishName(rs.getString("dish_name"));
              item.setDishVersion(rs.getLong("dish_version"));
              item.setQuantity(rs.getInt("quantity"));
              item.setPrice(rs.getBigDecimal("price"));
              item.setAmount(rs.getBigDecimal("amount"));
              return item;
            },
            id,
            UserContext.merchantId()));
    return draft;
  }

  public void version(OrderDraft draft, Long expected) {
    if (expected == null) throw new IllegalArgumentException("必须提交 expectedVersion");
    if (!expected.equals(draft.getVersion()))
      throw BusinessException.conflict("确认单已变化，请重新查看", draft);
  }

  public void editable(OrderDraft draft) {
    if (draft.getStatus() != PENDING || !draft.getExpiresAt().isAfter(BusinessTime.now()))
      throw BusinessException.conflict("确认单已结束或过期，请重新点餐", draft);
  }

  public void replace(
      OrderDraft draft, List<OrderItem> items, String note, List<String> allergens) {
    jdbc.update(
        "DELETE FROM order_draft_item WHERE draft_id=? AND merchant_id=?",
        draft.getId(),
        UserContext.merchantId());
    insert(draft.getId(), items);
    draft.setItems(items);
    draft.setVersion(draft.getVersion() + 1);
    draft.setTotalAmount(total(items));
    draft.setRemark(note);
    draft.setSafetyAllergens(String.join(",", allergens));
    draft.setExpiresAt(BusinessTime.now().plusMinutes(5));
    jdbc.update(
        "UPDATE order_draft SET version=?,total_amount=?,remark=?,safety_allergens=?,expires_at=?"
            + " WHERE id=? AND merchant_id=?",
        draft.getVersion(),
        draft.getTotalAmount(),
        note,
        draft.getSafetyAllergens(),
        draft.getExpiresAt(),
        draft.getId(),
        UserContext.merchantId());
  }

  public void confirmed(OrderDraft draft, long order) {
    jdbc.update(
        "UPDATE order_draft SET status=?,confirmed_order_id=? WHERE id=? AND merchant_id=?",
        CONFIRMED,
        order,
        draft.getId(),
        UserContext.merchantId());
  }

  static BigDecimal total(List<OrderItem> items) {
    return items.stream().map(OrderItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private void insert(String id, List<OrderItem> items) {
    jdbc.batchUpdate(
        "INSERT INTO"
            + " order_draft_item(draft_id,merchant_id,dish_id,dish_name,quantity,price,amount,dish_version)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        items,
        20,
        (ps, item) -> {
          ps.setString(1, id);
          ps.setLong(2, UserContext.merchantId());
          ps.setLong(3, item.getDishId());
          ps.setString(4, item.getDishName());
          ps.setInt(5, item.getQuantity());
          ps.setBigDecimal(6, item.getPrice());
          ps.setBigDecimal(7, item.getAmount());
          ps.setLong(8, item.getDishVersion());
        });
  }
}
