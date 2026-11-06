package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional reservation only. The durable worker owns all channel network calls. */
@Service
public class ProviderRefundLedger {
  private final JdbcTemplate db;

  public ProviderRefundLedger(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reserve(long orderId, BigDecimal amount, String operationKey) {
    var old =
        db.queryForList(
            "SELECT order_id,amount FROM provider_refund WHERE operation_key=?", operationKey);
    long cents = amount.movePointRight(2).longValueExact();
    if (!old.isEmpty()) {
      check(
          id(old.getFirst(), "order_id") == orderId && id(old.getFirst(), "amount") == cents,
          "退款幂等键内容不一致");
      return;
    }
    var order = one(db, "SELECT * FROM orders WHERE id=? FOR UPDATE", orderId);
    check(
        amount.signum() >= 0
            && amount.compareTo(
                    amount(order, "total_amount")
                        .subtract(amount(order, "refunded_amount"))
                        .subtract(amount(order, "refund_pending_amount")))
                <= 0,
        "退款超过剩余实付（含处理中退款）");
    var part = one(db, "SELECT * FROM provider_payment_part WHERE order_id=?", orderId);
    check("PAID".equals(part.get("status")), "支付尚未确认");
    db.update(
        "INSERT INTO provider_refund(id,operation_key,payment_id,order_id,amount)"
            + " VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString().replace("-", ""),
        operationKey,
        part.get("payment_id"),
        orderId,
        cents);
    db.update(
        "UPDATE orders SET refund_pending_amount=refund_pending_amount+?,payment_status='REFUNDING'"
            + " WHERE id=?",
        amount,
        orderId);
  }
}
