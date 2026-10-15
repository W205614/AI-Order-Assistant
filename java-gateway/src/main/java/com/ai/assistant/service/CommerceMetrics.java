package com.ai.assistant.service;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Low-cardinality business invariants; no customer, order or address labels. */
@Component
public class CommerceMetrics {
  private final MeterRegistry registry;

  public CommerceMetrics(MeterRegistry registry, JdbcTemplate db) {
    this.registry = registry;
    gauge(
        registry,
        db,
        "campus_inventory_negative",
        "SELECT (SELECT COUNT(*) FROM product_sku WHERE stock<0)+(SELECT COUNT(*) FROM dish WHERE"
            + " stock<0)");
    gauge(
        registry,
        db,
        "campus_reservation_invalid",
        "SELECT COUNT(*) FROM reservation_slot WHERE reserved_count<0 OR reserved_count>capacity");
    gauge(
        registry,
        db,
        "campus_refund_invalid",
        "SELECT COUNT(*) FROM orders WHERE commerce_version=2 AND (refunded_amount<0 OR"
            + " refunded_amount>total_amount)");
    gauge(
        registry,
        db,
        "campus_delivery_waiting",
        "SELECT COUNT(*) FROM delivery_task WHERE status='WAITING'");
    gauge(
        registry,
        db,
        "campus_delivery_exceptions",
        "SELECT COUNT(*) FROM delivery_task WHERE status='EXCEPTION'");
    gauge(
        registry,
        db,
        "campus_coupon_stale_locks",
        "SELECT COUNT(*) FROM customer_coupon cc JOIN checkout c ON c.id=cc.checkout_id WHERE"
            + " cc.status='LOCKED' AND c.status='CLOSED'");
  }

  private static void gauge(MeterRegistry r, JdbcTemplate db, String name, String sql) {
    Gauge.builder(name, db, j -> j.queryForObject(sql, Double.class)).register(r);
  }

  public void failure(String operation) {
    registry.counter("campus_operation_failures_total", "operation", operation).increment();
  }
}
