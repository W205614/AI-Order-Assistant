package com.ai.assistant.service;

import com.ai.assistant.config.BackgroundTask;
import com.wechat.pay.java.core.exception.ServiceException;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** A bill is reconciliation evidence; it never directly settles an order or refund. */
@Service
public class PaymentReconciliationService {
  private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationService.class);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
  private static final Set<String> HEADERS =
      Set.of(
          "交易时间", "公众账号ID", "商户号", "特约商户号", "设备号", "微信订单号", "商户订单号", "用户标识", "交易类型", "交易状态", "付款银行",
          "货币种类", "应结订单金额", "代金券金额", "微信退款单号", "商户退款单号", "退款金额", "充值券退款金额", "退款类型", "退款状态", "商品名称",
          "商户数据包", "手续费", "费率", "订单金额", "申请退款金额", "费率备注");
  private static final Set<String> REQUIRED_HEADERS =
      Set.of(
          "交易时间", "公众账号ID", "商户号", "特约商户号", "微信订单号", "商户订单号", "交易状态", "货币种类", "应结订单金额", "微信退款单号",
          "商户退款单号", "退款金额", "充值券退款金额", "退款状态", "手续费", "订单金额", "申请退款金额");
  private static final List<String> SUMMARY =
      List.of("总交易单数", "应结订单总金额", "退款总金额", "充值券退款总金额", "手续费总金额", "订单总金额", "申请退款总金额");

  private final JdbcTemplate db;
  private final PaymentProvider provider;
  private final PaymentSettings settings;
  private final TransactionTemplate tx;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var thread = new Thread(r, "payment-reconciliation");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicBoolean scheduled = new AtomicBoolean();

  public PaymentReconciliationService(
      JdbcTemplate db, PaymentProvider provider, PaymentSettings settings, TransactionTemplate tx) {
    this.db = db;
    this.provider = provider;
    this.settings = settings;
    this.tx = tx;
  }

  public record BillRow(
      LocalDateTime time,
      String mchId,
      String subMchId,
      String appId,
      String kind,
      String tradeNo,
      String transactionId,
      String refundNo,
      String providerRefundId,
      String refundStatus,
      long settlement,
      long refundSettlement,
      long voucherRefund,
      long fee,
      long orderAmount,
      long refundAmount) {
    String resource() {
      return "REFUND".equals(kind) ? refundNo : tradeNo;
    }
  }

  public record Bill(List<BillRow> rows, long feeAmount) {}

  private record Difference(String resource, String kind, String detail) {}

  @Scheduled(cron = "0 30 10 * * *", zone = "Asia/Shanghai")
  public void yesterday() {
    if (!settings.real() || worker.isShutdown() || !scheduled.compareAndSet(false, true)) return;
    worker.execute(
        () -> {
          try {
            LocalDate date = BusinessTime.now().toLocalDate().minusDays(1);
            BackgroundTask.run("reconciliation-" + date, () -> reconcile(date));
          } catch (RuntimeException error) {
            LOG.warn("Scheduled payment reconciliation could not start");
          } finally {
            scheduled.set(false);
          }
        });
  }

  @PreDestroy
  public void stop() {
    worker.shutdownNow();
  }

  public Map<String, Object> reconcile(LocalDate date) {
    if (!settings.real())
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE", "真实支付未启用");
    LocalDate today = BusinessTime.now().toLocalDate();
    if (date == null || !date.isBefore(today) || date.isBefore(today.minusMonths(3)))
      throw new IllegalArgumentException("对账日期须为过去三个月内已结束的一天");
    var merchants =
        db
            .queryForList(
                "SELECT sub_mch_id FROM merchant_payment_binding UNION SELECT DISTINCT sub_mch_id"
                    + " FROM provider_payment_part",
                String.class)
            .stream()
            .filter(value -> !value.isBlank())
            .toList();
    var results = new ArrayList<Map<String, Object>>();
    boolean passed = true;
    for (String merchant : merchants) {
      db.update(
          "INSERT INTO payment_reconciliation(bill_date,sub_mch_id,status) VALUES(?,?,'RUNNING')"
              + " ON DUPLICATE KEY UPDATE status='RUNNING',last_error=NULL",
          date,
          merchant);
      long batch =
          db.queryForObject(
              "SELECT id FROM payment_reconciliation WHERE bill_date=? AND sub_mch_id=?",
              Long.class,
              date,
              merchant);
      try {
        byte[] content;
        try {
          var request =
              provider.request(
                  "GET",
                  "/v3/bill/tradebill?bill_date=" + date + "&bill_type=ALL&sub_mchid=" + merchant,
                  null);
          content = provider.download(request.getString("download_url"));
          verifyHash(content, request.getString("hash_type"), request.getString("hash_value"));
        } catch (ServiceException error) {
          // The provider explicitly distinguishes a day with no bill from one still generating.
          if (!"NO_STATEMENT_EXIST".equals(error.getErrorCode())) throw error;
          content = null;
        }
        Bill bill =
            content == null
                ? new Bill(List.of(), 0)
                : parse(content, date, settings.mchId, merchant);
        String checksum = content == null ? null : digest(content, "SHA-256");
        var outcome = tx.execute(ignored -> compareAndSave(batch, date, merchant, bill, checksum));
        results.add(outcome);
        passed &= "MATCHED".equals(outcome.get("status"));
      } catch (RuntimeException error) {
        db.update(
            "UPDATE payment_reconciliation SET status='ERROR',last_error='RECONCILIATION_FAILED'"
                + " WHERE id=?",
            batch);
        LOG.warn("Payment reconciliation failed for batch {}", batch);
        results.add(Map.of("id", batch, "status", "ERROR", "errorCode", "RECONCILIATION_FAILED"));
        passed = false;
      }
    }
    return Map.of("date", date, "passed", passed, "batches", results);
  }

  private Map<String, Object> compareAndSave(
      long batch, LocalDate date, String merchant, Bill bill, String checksum) {
    db.queryForObject(
        "SELECT id FROM payment_reconciliation WHERE id=? FOR UPDATE", Long.class, batch);
    var payments =
        db.queryForList(
            "SELECT p.*,h.mode FROM provider_payment_part p JOIN provider_payment h ON"
                + " h.id=p.payment_id WHERE p.sub_mch_id=? AND h.mode='WECHAT_PARTNER'",
            merchant);
    var refunds =
        db.queryForList(
            "SELECT r.*,p.out_trade_no,p.sub_mch_id,p.transaction_id FROM provider_refund r JOIN"
                + " provider_payment_part p ON p.order_id=r.order_id WHERE p.sub_mch_id=? AND"
                + " r.amount>0",
            merchant);
    var byTrade = new HashMap<String, Map<String, Object>>();
    for (var payment : payments) byTrade.put((String) payment.get("out_trade_no"), payment);
    var byRefund = new HashMap<String, Map<String, Object>>();
    for (var refund : refunds) byRefund.put((String) refund.get("id"), refund);
    var seenPayments = new HashSet<String>();
    var seenRefunds = new HashSet<String>();
    var differences = new ArrayList<Difference>();
    for (var row : bill.rows()) {
      if ("SUCCESS".equals(row.kind())) {
        if (!seenPayments.add(row.tradeNo())) throw new IllegalArgumentException("账单支付记录重复");
        var local = byTrade.get(row.tradeNo());
        if (local == null) add(differences, row.resource(), "CHANNEL_PAYMENT_ONLY", "渠道账单支付缺少本地子单");
        else {
          if (number(local, "amount") != row.orderAmount())
            add(
                differences,
                row.resource(),
                "PAYMENT_AMOUNT",
                "金额分：本地=" + number(local, "amount") + ",渠道=" + row.orderAmount());
          if (!"PAID".equals(local.get("status")))
            add(differences, row.resource(), "PAYMENT_STATE", "渠道支付成功，本地尚未确认成功；须查单");
          if (!Objects.equals(local.get("transaction_id"), row.transactionId()))
            add(differences, row.resource(), "PAYMENT_IDENTITY", "渠道交易号与本地记录不同");
          if (!sameDay(local.get("paid_at"), date))
            add(differences, row.resource(), "PAYMENT_DATE", "渠道支付日期与本地确认日期不同");
        }
      } else if ("REFUND".equals(row.kind())) {
        if (!seenRefunds.add(row.refundNo())) throw new IllegalArgumentException("账单退款记录重复");
        var local = byRefund.get(row.refundNo());
        if (local == null) add(differences, row.resource(), "CHANNEL_REFUND_ONLY", "渠道退款申请缺少本地退款单");
        else {
          if (number(local, "amount") != row.refundAmount())
            add(
                differences,
                row.resource(),
                "REFUND_AMOUNT",
                "申请退款分：本地=" + number(local, "amount") + ",渠道=" + row.refundAmount());
          if (!Objects.equals(local.get("out_trade_no"), row.tradeNo()))
            add(differences, row.resource(), "REFUND_PAYMENT", "退款对应渠道支付子单不符");
          if (local.get("provider_refund_id") != null
              && !Objects.equals(local.get("provider_refund_id"), row.providerRefundId()))
            add(differences, row.resource(), "REFUND_IDENTITY", "渠道退款单号不符");
          // An ALL bill preserves refund-request-time state. PROCESSING cannot override a later
          // success.
          if ("SUCCESS".equals(row.refundStatus()) && !"SUCCESS".equals(local.get("status")))
            add(differences, row.resource(), "REFUND_STATE", "账单退款成功而本地未确认，须查询当前退款状态");
          if (!sameDay(local.get("accepted_at"), date))
            add(differences, row.resource(), "REFUND_DATE", "渠道退款受理日期与本地记录不同");
        }
      } else add(differences, row.resource(), "UNSUPPORTED_REVOKED", "出现本项目未发起的撤销交易，需核对渠道账单");
      var fees =
          db.queryForList(
              "SELECT fee_amount FROM payment_provider_fee WHERE bill_date=? AND sub_mch_id=? AND"
                  + " resource_id=? AND kind=?",
              date,
              merchant,
              row.resource(),
              row.kind());
      if (!fees.isEmpty() && number(fees.getFirst(), "fee_amount") != row.fee())
        add(
            differences,
            row.resource(),
            "FEE_CHANGED",
            "手续费分：原导入=" + number(fees.getFirst(), "fee_amount") + ",新账单=" + row.fee());
      db.update(
          "INSERT IGNORE INTO"
              + " payment_provider_fee(bill_date,sub_mch_id,resource_id,kind,fee_amount,batch_id)"
              + " VALUES(?,?,?,?,?,?)",
          date,
          merchant,
          row.resource(),
          row.kind(),
          row.fee(),
          batch);
    }
    for (var local : payments) {
      if (number(local, "amount") <= 0 || !"PAID".equals(local.get("status"))) continue;
      if (local.get("paid_at") == null)
        add(
            differences,
            (String) local.get("out_trade_no"),
            "MISSING_PAYMENT_DATE",
            "本地支付成功缺少渠道业务时间，无法确认所属日账");
      else if (sameDay(local.get("paid_at"), date)
          && !seenPayments.contains(local.get("out_trade_no")))
        add(
            differences,
            (String) local.get("out_trade_no"),
            "LOCAL_PAYMENT_ONLY",
            "本地已支付子单缺少当日渠道账单记录");
    }
    for (var local : refunds) {
      if (Set.of("SUCCESS", "PROCESSING").contains(local.get("status"))
          && local.get("accepted_at") == null)
        add(differences, (String) local.get("id"), "MISSING_REFUND_DATE", "本地渠道退款缺少受理时间，无法确认所属日账");
      else if (sameDay(local.get("accepted_at"), date) && !seenRefunds.contains(local.get("id")))
        add(differences, (String) local.get("id"), "LOCAL_REFUND_ONLY", "本地已受理退款缺少当日渠道申请记录");
    }
    // Keep the history of old differences; only a fresh matching comparison may close them.
    db.update(
        "UPDATE payment_reconciliation_difference SET"
            + " resolution='重核已无此差异',resolved_by='SYSTEM_RECONCILIATION',resolved_at=NOW() WHERE"
            + " batch_id=? AND resolution IS NULL",
        batch);
    for (var difference : differences)
      db.update(
          "INSERT INTO payment_reconciliation_difference(batch_id,resource_id,kind,detail)"
              + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE resolution=IF(detail<>VALUES(detail) OR"
              + " resolved_by='SYSTEM_RECONCILIATION',NULL,resolution),"
              + " resolved_at=IF(detail<>VALUES(detail) OR"
              + " resolved_by='SYSTEM_RECONCILIATION',NULL,resolved_at),"
              + " resolved_by=IF(detail<>VALUES(detail) OR"
              + " resolved_by='SYSTEM_RECONCILIATION',NULL,resolved_by),detail=VALUES(detail)",
          batch,
          difference.resource(),
          difference.kind(),
          difference.detail());
    String state = differences.isEmpty() ? "MATCHED" : "DIFFERENCES";
    db.update(
        "UPDATE payment_reconciliation SET"
            + " status=?,checksum=?,payment_count=?,difference_count=?,fee_amount=?,last_error=NULL"
            + " WHERE id=?",
        state,
        checksum,
        seenPayments.size(),
        differences.size(),
        bill.feeAmount(),
        batch);
    return Map.of(
        "id",
        batch,
        "status",
        state,
        "payments",
        seenPayments.size(),
        "refundRequests",
        seenRefunds.size(),
        "differences",
        differences.size(),
        "feeAmount",
        bill.feeAmount());
  }

  private static void add(List<Difference> differences, String id, String kind, String detail) {
    differences.add(new Difference(id, kind, detail));
  }

  private static long number(Map<String, Object> value, String key) {
    return ((Number) value.get(key)).longValue();
  }

  private static boolean sameDay(Object value, LocalDate date) {
    if (value == null) return false;
    LocalDateTime time =
        value instanceof Timestamp stamp ? stamp.toLocalDateTime() : (LocalDateTime) value;
    return time.toLocalDate().equals(date);
  }

  static void verifyHash(byte[] content, String hashType, String expected) {
    // WeChat's bill protocol supplies a SHA1 checksum; the local audit fingerprint uses SHA256.
    // https://pay.wechatpay.cn/doc/v3/partner/4013080597
    String algorithm =
        switch (Objects.toString(hashType, "")) {
          case "SHA1" -> "SHA-1";
          case "SHA256" -> "SHA-256";
          default -> throw new IllegalArgumentException("不支持的渠道账单摘要算法");
        };
    if (expected == null || !digest(content, algorithm).equalsIgnoreCase(expected))
      throw new IllegalArgumentException("渠道账单摘要不符");
  }

  private static String digest(byte[] content, String algorithm) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  static long fen(String value, boolean signed) {
    if (value == null
        || !value.matches(signed ? "-?[0-9]+(?:\\.[0-9]{1,2})?" : "[0-9]+(?:\\.[0-9]{1,2})?"))
      throw new IllegalArgumentException("账单金额格式无效");
    return new BigDecimal(value).movePointRight(2).longValueExact();
  }

  static Bill parse(byte[] bytes, LocalDate date, String mchId, String subMchId) {
    if (bytes.length > 20 * 1024 * 1024) throw new IllegalArgumentException("账单过大");
    final String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("账单不是合法UTF-8", error);
    }
    var csv = csv(text);
    if (csv.size() < 3) throw new IllegalArgumentException("账单缺少表头或汇总");
    var headers = csv.getFirst();
    if (new HashSet<>(headers).size() != headers.size()
        || !HEADERS.containsAll(headers)
        || !headers.containsAll(REQUIRED_HEADERS))
      throw new IllegalArgumentException("不支持或不完整的交易账单表头");
    var indices = new HashMap<String, Integer>();
    for (int i = 0; i < headers.size(); i++) indices.put(headers.get(i), i);
    var rows = new ArrayList<BillRow>();
    long settlement = 0, refundSettlement = 0, voucherRefund = 0, fees = 0, order = 0, refund = 0;
    int summaryIndex = -1;
    for (int i = 1; i < csv.size(); i++) {
      var values = csv.get(i);
      if (SUMMARY.equals(values)) {
        summaryIndex = i;
        break;
      }
      if (values.size() != headers.size()) throw new IllegalArgumentException("交易账单字段数量不符");
      var row = new HashMap<String, String>();
      for (var entry : indices.entrySet()) row.put(entry.getKey(), values.get(entry.getValue()));
      LocalDateTime time = LocalDateTime.parse(row.get("交易时间"), TIME);
      if (!time.toLocalDate().equals(date)
          || !mchId.equals(row.get("商户号"))
          || !subMchId.equals(row.get("特约商户号"))
          || !"CNY".equals(row.get("货币种类"))) throw new IllegalArgumentException("账单日期、商户或币种不符");
      String kind = row.get("交易状态");
      if (!Set.of("SUCCESS", "REFUND", "REVOKED").contains(kind))
        throw new IllegalArgumentException("未知账单交易状态");
      String tradeNo = identifier(row.get("商户订单号"));
      String refundNo = "REFUND".equals(kind) ? identifier(row.get("商户退款单号")) : "";
      String refundStatus = row.get("退款状态");
      if ("REFUND".equals(kind)
          && !Set.of("SUCCESS", "PROCESSING", "FAIL", "CHANGE").contains(refundStatus))
        throw new IllegalArgumentException("未知账单退款状态");
      var item =
          new BillRow(
              time,
              mchId,
              subMchId,
              row.get("公众账号ID"),
              kind,
              tradeNo,
              identifier(row.get("微信订单号")),
              refundNo,
              row.get("微信退款单号"),
              refundStatus,
              fen(row.get("应结订单金额"), false),
              fen(row.get("退款金额"), false),
              fen(row.get("充值券退款金额"), false),
              fen(row.get("手续费"), true),
              fen(row.get("订单金额"), false),
              fen(row.get("申请退款金额"), false));
      if (("SUCCESS".equals(kind) && (item.fee() < 0 || item.refundAmount() != 0))
          || ("REFUND".equals(kind) && (item.fee() > 0 || item.orderAmount() != 0)))
        throw new IllegalArgumentException("账单支付与退款金额方向不符");
      rows.add(item);
      settlement = Math.addExact(settlement, item.settlement());
      refundSettlement = Math.addExact(refundSettlement, item.refundSettlement());
      voucherRefund = Math.addExact(voucherRefund, item.voucherRefund());
      fees = Math.addExact(fees, item.fee());
      order = Math.addExact(order, item.orderAmount());
      refund = Math.addExact(refund, item.refundAmount());
    }
    if (summaryIndex != csv.size() - 2) throw new IllegalArgumentException("账单汇总缺失或位置无效");
    var totals = csv.getLast();
    if (totals.size() != SUMMARY.size() || !totals.getFirst().matches("[0-9]+"))
      throw new IllegalArgumentException("账单汇总格式无效");
    long[] expected = {settlement, refundSettlement, voucherRefund, fees, order, refund};
    if (Long.parseLong(totals.getFirst()) != rows.size())
      throw new IllegalArgumentException("账单总笔数不符");
    for (int i = 0; i < expected.length; i++)
      if (fen(totals.get(i + 1), i == 3) != expected[i])
        throw new IllegalArgumentException("账单汇总金额不符");
    return new Bill(List.copyOf(rows), fees);
  }

  private static String identifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("账单交易标识无效");
    return value;
  }

  static List<List<String>> csv(String value) {
    if (value.startsWith("\uFEFF")) value = value.substring(1);
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var cell = new StringBuilder();
    boolean quoted = false, closedQuote = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < value.length() && value.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
            closedQuote = true;
          }
        } else cell.append(c);
      } else if (c == '"' && !closedQuote && (cell.isEmpty() || cell.toString().equals("`")))
        quoted = true;
      else if (c == ',' || c == '\r' || c == '\n') {
        row.add(cleanCell(cell));
        cell.setLength(0);
        closedQuote = false;
        if (c != ',') {
          if (row.size() > 1 || !row.getFirst().isEmpty()) rows.add(List.copyOf(row));
          row.clear();
          if (c == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
        }
      } else {
        if (closedQuote || c == '"') throw new IllegalArgumentException("CSV引号格式无效");
        cell.append(c);
      }
    }
    if (quoted) throw new IllegalArgumentException("CSV引号未闭合");
    if (!cell.isEmpty() || !row.isEmpty()) {
      row.add(cleanCell(cell));
      rows.add(List.copyOf(row));
    }
    return rows;
  }

  private static String cleanCell(StringBuilder cell) {
    String value = cell.toString();
    return value.startsWith("`") ? value.substring(1) : value;
  }
}
