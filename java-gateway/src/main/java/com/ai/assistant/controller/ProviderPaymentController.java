package com.ai.assistant.controller;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProviderPaymentController {
  private final ProviderPaymentService payments;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final IntegrationCapabilities capabilities;
  private final AuditService audit;
  private final ObjectProvider<PaymentReconciliationService> reconciliation;

  public ProviderPaymentController(
      ProviderPaymentService payments,
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      IntegrationCapabilities capabilities,
      AuditService audit,
      ObjectProvider<PaymentReconciliationService> reconciliation) {
    this.payments = payments;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.capabilities = capabilities;
    this.audit = audit;
    this.reconciliation = reconciliation;
  }

  @GetMapping("/integrations/capabilities")
  public Result<?> capabilities() {
    return Result.success(capabilities.snapshot());
  }

  @PostMapping("/payments")
  public Result<?> create(
      @RequestBody ProviderPaymentService.Input input, HttpServletRequest request) {
    return Result.success(payments.create(input, request.getRemoteAddr()));
  }

  @GetMapping("/payments/{id}")
  public Result<?> session(@PathVariable String id) {
    UserContext.requireRole("CUSTOMER");
    return Result.success(payments.session(id, UserContext.getCurrentId()));
  }

  @PostMapping({"/callbacks/wechatpay/payment", "/callbacks/wechatpay/refund"})
  public ResponseEntity<Void> callback(
      @RequestHeader Map<String, String> headers,
      @RequestBody String body,
      HttpServletRequest request) {
    var normalized = new HashMap<String, String>();
    headers.forEach((k, v) -> normalized.put(k.toLowerCase(Locale.ROOT), v));
    payments.callback(
        request.getRequestURI().endsWith("/payment") ? "PAYMENT" : "REFUND", normalized, body);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/platform/payments")
  public Result<?> list(
      @RequestParam(defaultValue = "9223372036854775807") long before,
      @RequestParam(defaultValue = "100") int limit) {
    UserContext.requireRole("PLATFORM_ADMIN");
    checkLimit(limit);
    if (before <= 0) throw new IllegalArgumentException("时间游标无效");
    java.time.LocalDateTime cursor =
        java.time.Instant.ofEpochMilli(Math.min(before, 4102444800000L))
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toLocalDateTime();
    return Result.success(
        db.queryForList(
            "SELECT"
                + " id,source_type,source_id,mode,scene,status,amount,expires_at,created_at,close_requested,attempts,last_error"
                + " FROM provider_payment WHERE created_at<? ORDER BY created_at DESC,id DESC LIMIT"
                + " ?",
            cursor,
            limit));
  }

  @PostMapping("/platform/payments/{id}/query")
  public Result<?> query(@PathVariable String id) {
    UserContext.requireRole("PLATFORM_ADMIN");
    db.update(
        "UPDATE provider_payment SET"
            + " attempts=0,next_attempt_at=NOW(),status=IF(status='REVIEW','UNKNOWN',status) WHERE"
            + " id=? AND (lease_until IS NULL OR lease_until<NOW())",
        id);
    audit.record(null, "PAYMENT_REQUERY_REQUESTED", id);
    payments.query(id);
    return Result.success(null);
  }

  @GetMapping("/platform/refunds")
  public Result<?> refunds(@RequestParam(defaultValue = "100") int limit) {
    UserContext.requireRole("PLATFORM_ADMIN");
    checkLimit(limit);
    return Result.success(
        db.queryForList(
            "SELECT"
                + " id,operation_key,payment_id,order_id,amount,status,created_at,attempts,last_error"
                + " FROM provider_refund ORDER BY created_at DESC,id DESC LIMIT ?",
            limit));
  }

  @PostMapping("/platform/refunds/{id}/retry")
  public Result<?> retry(@PathVariable String id) {
    payments.retryRefund(id);
    return Result.success(null);
  }

  @GetMapping("/platform/reconciliation")
  public Result<?> reconciliation() {
    UserContext.requireRole("PLATFORM_ADMIN");
    return Result.success(
        Map.of(
            "batches",
            db.queryForList(
                "SELECT * FROM payment_reconciliation ORDER BY bill_date DESC,id DESC LIMIT 100"),
            "differences",
            db.queryForList(
                "SELECT * FROM payment_reconciliation_difference ORDER BY id DESC LIMIT 200")));
  }

  @PostMapping("/platform/reconciliation")
  public Result<?> reconcile(@RequestParam LocalDate date) {
    UserContext.requireRole("PLATFORM_ADMIN");
    reconciliation.getObject().reconcile(date);
    audit.record(null, "RECONCILIATION_REQUESTED", date);
    return reconciliation();
  }

  public record Resolution(String reason) {}

  @PostMapping("/platform/reconciliation/differences/{id}/resolve")
  public Result<?> resolve(@PathVariable long id, @RequestBody Resolution input) {
    UserContext.requireRole("PLATFORM_ADMIN");
    if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 500)
      throw new IllegalArgumentException("处理说明需1-500字");
    db.update(
        "UPDATE payment_reconciliation_difference SET resolution=?,resolved_by=?,resolved_at=NOW()"
            + " WHERE id=? AND resolved_at IS NULL",
        input.reason(),
        UserContext.actor(),
        id);
    audit.record(null, "RECONCILIATION_DIFFERENCE_RESOLVED", id);
    return Result.success(null);
  }

  @GetMapping("/platform/merchant-payments")
  public Result<?> bindings() {
    UserContext.requireRole("PLATFORM_ADMIN");
    return Result.success(
        db.queryForList(
            "SELECT m.id merchant_id,m.name,b.sub_mch_id,b.enabled,b.updated_at FROM merchant m"
                + " LEFT JOIN merchant_payment_binding b ON b.merchant_id=m.id ORDER BY m.id LIMIT"
                + " 500"));
  }

  public record Binding(String subMchId, boolean enabled) {}

  @PutMapping("/platform/merchant-payments/{id}")
  public Result<?> binding(@PathVariable long id, @RequestBody Binding input) {
    UserContext.requireRole("PLATFORM_ADMIN");
    if (input.subMchId() == null || !input.subMchId().matches("[0-9]{6,32}"))
      throw new IllegalArgumentException("子商户号无效");
    tx.executeWithoutResult(
        s -> {
          if (db.queryForList("SELECT id FROM merchant WHERE id=? FOR UPDATE", id).isEmpty())
            throw new IllegalArgumentException("商户不存在");
          var duplicate =
              db.queryForList(
                  "SELECT merchant_id FROM merchant_payment_binding WHERE sub_mch_id=?",
                  input.subMchId());
          if (!duplicate.isEmpty()
              && ((Number) duplicate.getFirst().get("merchant_id")).longValue() != id)
            throw BusinessException.conflict("子商户号已绑定其他店铺", null);
          if (db.queryForList(
                  "SELECT merchant_id FROM merchant_payment_binding WHERE merchant_id=?", id)
              .isEmpty())
            db.update(
                "INSERT INTO merchant_payment_binding(merchant_id,sub_mch_id,enabled)"
                    + " VALUES(?,?,?)",
                id,
                input.subMchId(),
                input.enabled());
          else
            db.update(
                "UPDATE merchant_payment_binding SET sub_mch_id=?,enabled=?,updated_at=NOW() WHERE"
                    + " merchant_id=?",
                input.subMchId(),
                input.enabled(),
                id);
          audit.record(id, "PAYMENT_BINDING_UPDATED", id);
        });
    return Result.success(null);
  }

  @GetMapping("/admin/platform-coupons")
  public Result<?> participation() {
    UserContext.requireRole("OWNER");
    return Result.success(
        db.queryForList(
            "SELECT c.*,COALESCE(p.participating,FALSE) participating FROM coupon_campaign c LEFT"
                + " JOIN platform_coupon_participation p ON p.campaign_id=c.id AND p.merchant_id=?"
                + " WHERE c.merchant_id IS NULL ORDER BY c.id DESC LIMIT 200",
            UserContext.merchantId()));
  }

  public record Participation(boolean participating) {}

  @PutMapping("/admin/platform-coupons/{id}/participation")
  public Result<?> participation(@PathVariable long id, @RequestBody Participation input) {
    UserContext.requireRole("OWNER");
    tx.executeWithoutResult(
        s -> {
          if (db.queryForList(
                  "SELECT id FROM coupon_campaign WHERE id=? AND merchant_id IS NULL FOR SHARE", id)
              .isEmpty()) throw new IllegalArgumentException("平台活动不存在");
          db.update(
              "INSERT INTO platform_coupon_participation(campaign_id,merchant_id,participating)"
                  + " VALUES(?,?,?) ON DUPLICATE KEY UPDATE"
                  + " participating=VALUES(participating),updated_at=NOW()",
              id,
              UserContext.merchantId(),
              input.participating());
          audit.record("PLATFORM_COUPON_PARTICIPATION", id);
        });
    return Result.success(null);
  }

  private static void checkLimit(int limit) {
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit需1-200");
  }
}
