package com.ai.assistant.service;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database-only simulation: no external funds move and no provider callbacks are accepted. */
@Service
public class LocalSimulatedPaymentGateway implements PaymentGateway {
  private final JdbcTemplate db;

  public LocalSimulatedPaymentGateway(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void payment(String checkoutId, BigDecimal amount) {
    db.update(
        "INSERT INTO commerce_payment(checkout_id,operation_key,kind,amount)"
            + " VALUES(?,?,'PAYMENT',?)",
        checkoutId,
        "pay:" + checkoutId,
        amount);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void refund(String checkoutId, long orderId, String operationKey, BigDecimal amount) {
    db.update(
        "INSERT INTO commerce_payment(checkout_id,order_id,operation_key,kind,amount)"
            + " VALUES(?,?,?,'REFUND',?)",
        checkoutId,
        orderId,
        operationKey,
        amount);
  }

  @Override
  public List<Entry> query(String checkoutId) {
    return db.query(
        "SELECT operation_key,order_id,kind,amount,created_at FROM commerce_payment"
            + " WHERE checkout_id=? ORDER BY id",
        (rs, i) ->
            new Entry(
                rs.getString("operation_key"),
                rs.getObject("order_id", Long.class),
                rs.getString("kind"),
                rs.getBigDecimal("amount"),
                rs.getTimestamp("created_at").toLocalDateTime()),
        checkoutId);
  }
}
