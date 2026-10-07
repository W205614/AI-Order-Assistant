package com.ai.assistant.service;

import com.ai.assistant.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderMaintenance {
  private final JdbcTemplate jdbc;
  private final OrderTransactionService transactions;

  public OrderMaintenance(JdbcTemplate jdbc, OrderTransactionService transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Scheduled(fixedDelay = 15000, initialDelay = 20000)
  public void sweep() {
    var rows =
        jdbc.queryForList(
            "SELECT id,merchant_id FROM orders WHERE status=0 AND payment_expires_at<=NOW() ORDER"
                + " BY id LIMIT 100");
    for (var row : rows) {
      try {
        UserContext.within(
            ((Number) row.get("merchant_id")).longValue(),
            () -> {
              transactions.expire(((Number) row.get("id")).longValue());
              return null;
            });
      } catch (RuntimeException failure) {
        log.warn("Payment timeout sweep will retry order {}", row.get("id"));
      }
    }
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  public void cleanup() {
    jdbc.update("DELETE FROM revoked_token WHERE expires_at<NOW() LIMIT 10000");
    jdbc.update("DELETE FROM auth_rate_window WHERE expires_at<NOW() LIMIT 10000");
    // 安全状态按用户显式操作清理；审计和交易流水永久保留。
    jdbc.update("DELETE FROM order_safety_context WHERE expires_at<NOW() LIMIT 10000");
  }
}
