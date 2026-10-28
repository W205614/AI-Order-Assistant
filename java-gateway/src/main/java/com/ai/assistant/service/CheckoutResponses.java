package com.ai.assistant.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** Explicit response fields keep future storage columns outside the public checkout contract. */
public final class CheckoutResponses {
  static final String CHECKOUT_COLUMNS = "id,user_id,quote_id,idempotency_key,request_fingerprint,"
      + "status,total_amount,refunded_amount,expires_at,created_at";
  static final String ORDER_COLUMNS = "id,user_id,user_seq,total_amount,status,remark,create_time,"
      + "deliver_at,deliver_time,remind_count,remind_time,idempotency_key,merchant_id,payment_status,"
      + "payment_expires_at,inventory_released,recipient_name,recipient_phone,delivery_address,"
      + "delivery_region,draft_id,draft_version,checkout_id,commerce_version,campus_id,fulfillment_mode,"
      + "fulfillment_status,goods_amount,packaging_amount,delivery_fee,shop_discount,platform_discount,"
      + "refunded_amount,reservation_slot_id,capacity_released,production_started,accepted_at,ready_at";
  static final String ITEM_COLUMNS = "id,order_id,dish_id,dish_name,quantity,price,amount,merchant_id,"
      + "product_type,product_id,remark,packaging_amount,shop_discount,platform_discount";
  static final String AFTERSALE_COLUMNS = "id,order_id,user_id,reason,status,amount,decision_reason,"
      + "disputed,request_key,request_fingerprint,created_at";

  private CheckoutResponses() {}

  static String columns(String alias, String columns) {
    return Arrays.stream(columns.split(",")).map(column -> alias + "." + column)
        .collect(java.util.stream.Collectors.joining(","));
  }

  public record Summary(String id, Long user_id, String quote_id, String idempotency_key,
      String request_fingerprint, String status, BigDecimal total_amount, BigDecimal refunded_amount,
      LocalDateTime expires_at, LocalDateTime created_at) {}

  public record Detail(String id, Long user_id, String quote_id, String idempotency_key,
      String request_fingerprint, String status, BigDecimal total_amount, BigDecimal refunded_amount,
      LocalDateTime expires_at, LocalDateTime created_at, List<Order> orders) {}

  public record Order(Long id, Long user_id, Long user_seq, BigDecimal total_amount, Integer status,
      String remark, LocalDateTime create_time, LocalDateTime deliver_at, LocalDateTime deliver_time,
      Integer remind_count, LocalDateTime remind_time, String idempotency_key, Long merchant_id,
      String payment_status, LocalDateTime payment_expires_at, Boolean inventory_released,
      String recipient_name, String recipient_phone, String delivery_address, String delivery_region,
      String draft_id, Long draft_version, String checkout_id, Integer commerce_version, Long campus_id,
      String fulfillment_mode, String fulfillment_status, BigDecimal goods_amount,
      BigDecimal packaging_amount, BigDecimal delivery_fee, BigDecimal shop_discount,
      BigDecimal platform_discount, BigDecimal refunded_amount, Long reservation_slot_id,
      Boolean capacity_released, Boolean production_started, LocalDateTime accepted_at,
      LocalDateTime ready_at, String merchant_name, List<Item> items, List<Aftersale> aftersales) {}

  public record Item(Long id, Long order_id, Long dish_id, String dish_name, Integer quantity,
      BigDecimal price, BigDecimal amount, Long merchant_id, String product_type, Long product_id,
      String remark, BigDecimal packaging_amount, BigDecimal shop_discount, BigDecimal platform_discount,
      Long refundable_quantity) {}

  public record Aftersale(String id, Long order_id, Long user_id, String reason, String status,
      BigDecimal amount, String decision_reason, Boolean disputed, String request_key,
      String request_fingerprint, LocalDateTime created_at) {}

  public static Summary summary(Map<String, Object> row) {
    return new Summary(s(row, "id"), l(row, "user_id"), s(row, "quote_id"), s(row, "idempotency_key"),
        s(row, "request_fingerprint"), s(row, "status"), m(row, "total_amount"),
        m(row, "refunded_amount"), t(row, "expires_at"), t(row, "created_at"));
  }

  public static Detail detail(Map<String, Object> row) {
    var s = summary(row);
    return new Detail(s.id(), s.user_id(), s.quote_id(), s.idempotency_key(), s.request_fingerprint(),
        s.status(), s.total_amount(), s.refunded_amount(), s.expires_at(), s.created_at(),
        rows(row, "orders").stream().map(CheckoutResponses::order).toList());
  }

  private static Order order(Map<String, Object> row) {
    return new Order(l(row, "id"), l(row, "user_id"), l(row, "user_seq"), m(row, "total_amount"),
        i(row, "status"), s(row, "remark"), t(row, "create_time"), t(row, "deliver_at"),
        t(row, "deliver_time"), i(row, "remind_count"), t(row, "remind_time"), s(row, "idempotency_key"),
        l(row, "merchant_id"), s(row, "payment_status"), t(row, "payment_expires_at"),
        b(row, "inventory_released"), s(row, "recipient_name"), s(row, "recipient_phone"),
        s(row, "delivery_address"), s(row, "delivery_region"), s(row, "draft_id"), l(row, "draft_version"),
        s(row, "checkout_id"), i(row, "commerce_version"), l(row, "campus_id"),
        s(row, "fulfillment_mode"), s(row, "fulfillment_status"), m(row, "goods_amount"),
        m(row, "packaging_amount"), m(row, "delivery_fee"), m(row, "shop_discount"),
        m(row, "platform_discount"), m(row, "refunded_amount"), l(row, "reservation_slot_id"),
        b(row, "capacity_released"), b(row, "production_started"), t(row, "accepted_at"),
        t(row, "ready_at"), s(row, "merchant_name"),
        rows(row, "items").stream().map(CheckoutResponses::item).toList(),
        rows(row, "aftersales").stream().map(CheckoutResponses::aftersale).toList());
  }

  private static Item item(Map<String, Object> row) {
    return new Item(l(row, "id"), l(row, "order_id"), l(row, "dish_id"), s(row, "dish_name"),
        i(row, "quantity"), m(row, "price"), m(row, "amount"), l(row, "merchant_id"),
        s(row, "product_type"), l(row, "product_id"), s(row, "remark"), m(row, "packaging_amount"),
        m(row, "shop_discount"), m(row, "platform_discount"), l(row, "refundable_quantity"));
  }

  private static Aftersale aftersale(Map<String, Object> row) {
    return new Aftersale(s(row, "id"), l(row, "order_id"), l(row, "user_id"), s(row, "reason"),
        s(row, "status"), m(row, "amount"), s(row, "decision_reason"), b(row, "disputed"),
        s(row, "request_key"), s(row, "request_fingerprint"), t(row, "created_at"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> row, String key) {
    return (List<Map<String, Object>>) row.getOrDefault(key, List.of());
  }
  private static String s(Map<String, Object> row, String key) { return (String) row.get(key); }
  private static Long l(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : ((Number) row.get(key)).longValue();
  }
  private static Integer i(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : ((Number) row.get(key)).intValue();
  }
  private static BigDecimal m(Map<String, Object> row, String key) { return (BigDecimal) row.get(key); }
  private static Boolean b(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : value instanceof Boolean bool ? bool : ((Number) value).intValue() != 0;
  }
  private static LocalDateTime t(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
  }
}
