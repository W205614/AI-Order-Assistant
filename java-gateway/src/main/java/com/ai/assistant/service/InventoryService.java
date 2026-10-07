package com.ai.assistant.service;

import com.ai.assistant.model.*;
import com.ai.assistant.security.UserContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
  public record Adjustment(Integer delta, Long expectedVersion, String requestKey) {}

  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final MenuService menu;

  public InventoryService(JdbcTemplate jdbc, AuditService audit, MenuService menu) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.menu = menu;
  }

  public void reserve(long orderId, List<OrderItem> items) {
    for (var item : items.stream().sorted(Comparator.comparing(OrderItem::getDishId)).toList()) {
      if (jdbc.update(
              "UPDATE dish SET stock=stock-?,stock_version=stock_version+1 WHERE merchant_id=? AND"
                  + " id=? AND status=1 AND stock>=?",
              item.getQuantity(),
              UserContext.merchantId(),
              item.getDishId(),
              item.getQuantity())
          != 1) throw new IllegalArgumentException("库存不足或菜品停售");
      ledger(item.getDishId(), orderId, "reserve:" + orderId, "RESERVE", -item.getQuantity());
    }
  }

  public void release(Order order) {
    if (Boolean.TRUE.equals(order.getInventoryReleased())) return;
    for (var item :
        order.getItems().stream().sorted(Comparator.comparing(OrderItem::getDishId)).toList()) {
      if (jdbc.update(
              "UPDATE dish SET stock=stock+?,stock_version=stock_version+1 WHERE merchant_id=? AND"
                  + " id=?",
              item.getQuantity(),
              UserContext.merchantId(),
              item.getDishId())
          != 1) throw new IllegalStateException("库存归属缺失");
      ledger(
          item.getDishId(),
          order.getId(),
          "release:" + order.getId(),
          "RELEASE",
          item.getQuantity());
    }
    jdbc.update(
        "UPDATE orders SET inventory_released=TRUE WHERE merchant_id=? AND id=?",
        UserContext.merchantId(),
        order.getId());
  }

  private void ledger(long dishId, Long orderId, String key, String kind, int delta) {
    jdbc.update(
        "INSERT INTO inventory_ledger(merchant_id,dish_id,order_id,request_key,kind,delta,actor)"
            + " VALUES(?,?,?,?,?,?,?)",
        UserContext.merchantId(),
        dishId,
        orderId,
        key,
        kind,
        delta,
        UserContext.actor());
  }

  @Transactional
  public Dish adjust(long id, Adjustment input) {
    UserContext.requireRole("OWNER");
    if (input.delta() == null
        || input.delta() == 0
        || Math.abs((long) input.delta()) > 1000000
        || input.expectedVersion() == null) throw new IllegalArgumentException("库存调整量或版本无效");
    DraftService.validKey(input.requestKey());
    var existing =
        jdbc.queryForList(
            "SELECT delta FROM inventory_ledger WHERE merchant_id=? AND dish_id=? AND request_key=?"
                + " AND kind='ADJUST'",
            UserContext.merchantId(),
            id,
            input.requestKey());
    if (!existing.isEmpty()) {
      if (((Number) existing.get(0).get("delta")).intValue() != input.delta())
        throw BusinessException.conflict("幂等键已用于其他库存调整", null);
      return menu.find(id).orElseThrow();
    }
    var locked =
        jdbc.query(
            "SELECT * FROM dish WHERE merchant_id=? AND id=? FOR UPDATE",
            MenuService.MAPPER,
            UserContext.merchantId(),
            id);
    if (locked.isEmpty()) throw new IllegalArgumentException("菜品不存在");
    existing =
        jdbc.queryForList(
            "SELECT delta FROM inventory_ledger WHERE merchant_id=? AND dish_id=? AND request_key=?"
                + " AND kind='ADJUST'",
            UserContext.merchantId(),
            id,
            input.requestKey());
    if (!existing.isEmpty()) {
      if (((Number) existing.get(0).get("delta")).intValue() != input.delta())
        throw BusinessException.conflict("幂等键已用于其他库存调整", null);
      return menu.find(id).orElseThrow();
    }
    if (jdbc.update(
            "UPDATE dish SET stock=stock+?,stock_version=stock_version+1 WHERE merchant_id=? AND"
                + " id=? AND stock_version=? AND stock+? BETWEEN 0 AND 1000000",
            input.delta(),
            UserContext.merchantId(),
            id,
            input.expectedVersion(),
            input.delta())
        != 1) throw BusinessException.conflict("库存已变化或调整后数量越界，请刷新", menu.find(id).orElse(null));
    ledger(id, null, input.requestKey(), "ADJUST", input.delta());
    audit.record(UserContext.merchantId(), "STOCK_ADJUSTED", id);
    return menu.find(id).orElseThrow();
  }

  public List<Map<String, Object>> ledger(long before) {
    UserContext.requireRole("OWNER");
    return jdbc.queryForList(
        "SELECT * FROM inventory_ledger WHERE merchant_id=? AND id<? ORDER BY id DESC LIMIT 100",
        UserContext.merchantId(),
        before);
  }
}
