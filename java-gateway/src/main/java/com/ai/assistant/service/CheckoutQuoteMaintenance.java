package com.ai.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded cleanup of disposable quotes; transaction-linked evidence is retained. */
@Component
public class CheckoutQuoteMaintenance {
  private final JdbcTemplate db;
  private final int retentionHours;
  private final int batchSize;

  public CheckoutQuoteMaintenance(
      JdbcTemplate db,
      @Value("${commerce.quote-cleanup.retention-hours:24}") int retentionHours,
      @Value("${commerce.quote-cleanup.batch-size:500}") int batchSize) {
    if (retentionHours < 1 || retentionHours > 8760 || batchSize < 1 || batchSize > 10000)
      throw new IllegalArgumentException("报价清理保留时间或批量大小无效");
    this.db = db;
    this.retentionHours = retentionHours;
    this.batchSize = batchSize;
  }

  @Scheduled(
      fixedDelayString = "${commerce.quote-cleanup.delay-ms:3600000}",
      initialDelayString = "${commerce.quote-cleanup.delay-ms:3600000}")
  public void cleanup() {
    cleanupBatch();
  }

  int cleanupBatch() {
    return db.update(
        "DELETE FROM checkout_quote WHERE expires_at<? AND consumed_checkout_id IS NULL"
            + " AND NOT EXISTS(SELECT 1 FROM checkout c WHERE c.quote_id=checkout_quote.id)"
            + " ORDER BY expires_at,id LIMIT ?",
        BusinessTime.now().minusHours(retentionHours),
        batchSize);
  }
}
