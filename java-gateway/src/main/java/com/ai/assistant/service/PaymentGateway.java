package com.ai.assistant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Local payment ledger boundary. The only implementation is simulated and participates in the
 * caller's database transaction. A real provider must use separate, transaction-outside network
 * orchestration and verified reconciliation; it must not replace these methods with blocking HTTP.
 */
public interface PaymentGateway {
  record Entry(
      String operationKey, Long orderId, String kind, BigDecimal amount, LocalDateTime createdAt) {}

  void payment(String checkoutId, BigDecimal amount);

  void refund(String checkoutId, long orderId, String operationKey, BigDecimal amount);

  List<Entry> query(String checkoutId);
}
