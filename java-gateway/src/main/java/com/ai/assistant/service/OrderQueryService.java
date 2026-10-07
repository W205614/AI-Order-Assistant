package com.ai.assistant.service;

import com.ai.assistant.model.*;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.vo.OrderPage;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

@Service
public class OrderQueryService {
  private final JdbcTemplate jdbc;

  public OrderQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  static final RowMapper<Order> MAPPER =
      (rs, i) -> {
        Order o = new Order();
        o.setId(rs.getLong("id"));
        o.setMerchantId(rs.getLong("merchant_id"));
        o.setUserId(rs.getLong("user_id"));
        o.setUserSeq(rs.getLong("user_seq"));
        o.setTotalAmount(rs.getBigDecimal("total_amount"));
        o.setStatus(rs.getInt("status"));
        o.setRemark(rs.getString("remark"));
        o.setPaymentStatus(rs.getString("payment_status"));
        o.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
        o.setDeliverAt(rs.getObject("deliver_at", LocalDateTime.class));
        o.setDeliverTime(rs.getObject("deliver_time", LocalDateTime.class));
        o.setPaymentExpiresAt(rs.getObject("payment_expires_at", LocalDateTime.class));
        o.setInventoryReleased(rs.getBoolean("inventory_released"));
        o.setRemindCount(rs.getInt("remind_count"));
        o.setRemindTime(rs.getObject("remind_time", LocalDateTime.class));
        o.setRecipientName(rs.getString("recipient_name"));
        o.setRecipientPhone(rs.getString("recipient_phone"));
        o.setDeliveryAddress(rs.getString("delivery_address"));
        o.setDeliveryRegion(rs.getString("delivery_region"));
        return o;
      };

  public Optional<Order> get(long id) {
    return hydrate(
            jdbc.query(
                "SELECT * FROM orders WHERE merchant_id=? AND id=?",
                MAPPER,
                UserContext.merchantId(),
                id))
        .stream()
        .findFirst();
  }

  public Optional<Order> own(long user, long seq) {
    return hydrate(
            jdbc.query(
                "SELECT * FROM orders WHERE merchant_id=? AND user_id=? AND user_seq=?",
                MAPPER,
                UserContext.merchantId(),
                user,
                seq))
        .stream()
        .findFirst();
  }

  public Optional<Order> byKey(long user, String key) {
    return hydrate(
            jdbc.query(
                "SELECT * FROM orders WHERE user_id=? AND idempotency_key=?", MAPPER, user, key))
        .stream()
        .findFirst();
  }

  public Order lock(long id) {
    var list =
        jdbc.query(
            "SELECT * FROM orders WHERE merchant_id=? AND id=? FOR UPDATE",
            MAPPER,
            UserContext.merchantId(),
            id);
    if (list.isEmpty()) throw new IllegalArgumentException("订单不存在或无权访问");
    return hydrate(list).get(0);
  }

  public List<Order> hydrate(List<Order> orders) {
    if (orders.isEmpty()) return orders;
    var params = new ArrayList<Object>();
    params.add(UserContext.merchantId());
    for (var o : orders) params.add(o.getId());
    var byId = new HashMap<Long, List<OrderItem>>();
    jdbc.query(
        "SELECT * FROM order_item WHERE merchant_id=? AND order_id IN ("
            + String.join(",", Collections.nCopies(orders.size(), "?"))
            + ") ORDER BY id",
        (RowCallbackHandler)
            rs -> {
              OrderItem item = new OrderItem();
              item.setDishId(rs.getLong("dish_id"));
              item.setDishName(rs.getString("dish_name"));
              item.setPrice(rs.getBigDecimal("price"));
              item.setQuantity(rs.getInt("quantity"));
              item.setAmount(rs.getBigDecimal("amount"));
              byId.computeIfAbsent(rs.getLong("order_id"), x -> new ArrayList<>()).add(item);
            },
        params.toArray());
    for (var o : orders) o.setItems(byId.getOrDefault(o.getId(), List.of()));
    return orders;
  }

  public OrderPage list(
      Long user, Integer status, String start, String end, Integer page, Integer size) {
    int p = page == null ? 1 : page, s = size == null ? 20 : size;
    if (p < 1 || p > 1000000 || s < 1 || s > 100 || status != null && (status < 0 || status > 6))
      throw new IllegalArgumentException("订单筛选参数无效");
    var args = new ArrayList<Object>();
    args.add(UserContext.merchantId());
    String where = " WHERE merchant_id=?";
    if (user != null) {
      where += " AND user_id=?";
      args.add(user);
    }
    LocalDate begin = parse(start), finish = parse(end);
    if (begin != null && finish != null && begin.isAfter(finish))
      throw new IllegalArgumentException("日期范围无效");
    if (begin != null) {
      where += " AND create_time>=?";
      args.add(begin.atStartOfDay());
    }
    if (finish != null) {
      where += " AND create_time<?";
      args.add(finish.plusDays(1).atStartOfDay());
    }
    var counts = new LinkedHashMap<Integer, Integer>();
    for (int x = 0; x <= 6; x++) counts.put(x, 0);
    jdbc.query(
        "SELECT status,COUNT(*) n FROM orders" + where + " GROUP BY status",
        (RowCallbackHandler) rs -> counts.put(rs.getInt("status"), rs.getInt("n")),
        args.toArray());
    if (status != null) {
      where += " AND status=?";
      args.add(status);
    }
    long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM orders" + where, Long.class, args.toArray());
    args.add(s);
    args.add((long) (p - 1) * s);
    var rows =
        jdbc.query(
            "SELECT * FROM orders" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            MAPPER,
            args.toArray());
    OrderPage result = new OrderPage();
    result.setItems(hydrate(rows));
    result.setTotal(total);
    result.setPage(p);
    result.setSize(s);
    result.setStatusCounts(counts);
    return result;
  }

  private LocalDate parse(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return LocalDate.parse(s);
    } catch (Exception e) {
      throw new IllegalArgumentException("日期格式应为yyyy-MM-dd");
    }
  }

  public Map<String, Object> stats() {
    var rows =
        jdbc.queryForList(
            "SELECT status,COUNT(*) count,COALESCE(SUM(CASE WHEN status=4 AND"
                + " payment_status='SIMULATED_PAID' THEN total_amount ELSE 0 END),0)"
                + " completed_amount FROM orders WHERE merchant_id=? GROUP BY status",
            UserContext.merchantId());
    long total = 0;
    var counts = new LinkedHashMap<Integer, Long>();
    for (int i = 0; i <= 6; i++) counts.put(i, 0L);
    java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
    for (var row : rows) {
      long n = ((Number) row.get("count")).longValue();
      total += n;
      counts.put(((Number) row.get("status")).intValue(), n);
      amount = amount.add((java.math.BigDecimal) row.get("completed_amount"));
    }
    return Map.of(
        "orders",
        total,
        "ordersByStatus",
        counts,
        "completedSimulatedAmount",
        amount,
        "cancelledOrders",
        counts.get(5),
        "paymentMode",
        "simulated");
  }
}
