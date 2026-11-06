package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentReconciliationTest {
  private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
  private static final String HEADER =
      "交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注";
  private static final String SUMMARY = "总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额";
  private static final String PAYMENT =
      "`2026-11-05 12:03:04,`wx001,`190000001,`190000002,`,`420001,`order001,`openid,`NATIVE,`SUCCESS,`OTHERS,`CNY,`10.00,`0.00,`0,`0,`0.00,`0.00,`,`,\"`饭,饮品\",`, `0.06,`0.60%,`10.00,`0.00,`"
          .replace(", `", ",`");
  private static final String REFUND =
      "`2026-11-05"
          + " 12:05:04,`wx001,`190000001,`190000002,`,`420001,`order001,`openid,`NATIVE,`REFUND,`OTHERS,`CNY,`0.00,`0.00,`504001,`refund001,`2.00,`0.00,`ORIGINAL,`PROCESSING,`退款,`,`-0.01,`0.60%,`0.00,`2.00,`";

  private byte[] bill(String rows, String totals) {
    return (HEADER + "\r\n" + rows + "\r\n" + SUMMARY + "\r\n" + totals + "\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void parsesOfficialAllBillAndKeepsRefundRequestSeparateFromCompletion() {
    var actual =
        PaymentReconciliationService.parse(
            bill(PAYMENT + "\r\n" + REFUND, "`2,`10.00,`2.00,`0.00,`0.05,`10.00,`2.00"),
            DATE,
            "190000001",
            "190000002");
    assertEquals(2, actual.rows().size());
    assertEquals(1000, actual.rows().getFirst().orderAmount());
    assertEquals(200, actual.rows().getLast().refundAmount());
    assertEquals("PROCESSING", actual.rows().getLast().refundStatus());
    assertEquals(0, actual.rows().getLast().orderAmount());
    assertEquals(5, actual.feeAmount());
  }

  @Test
  void rejectsWrongMerchantCurrencyDateSummaryAndUnknownHeader() {
    byte[] valid = bill(PAYMENT, "`1,`10.00,`0.00,`0.00,`0.06,`10.00,`0.00");
    String text = new String(valid, StandardCharsets.UTF_8);
    for (String invalid :
        List.of(
            text.replace("`CNY", "`USD"),
            text.replace("`190000002", "`190000003"),
            text.replace("2026-11-05", "2026-11-04"),
            text.replace("费率备注", "未知金额字段"),
            text.replace(
                "`1,`10.00,`0.00,`0.00,`0.06,`10.00,`0.00",
                "`2,`10.00,`0.00,`0.00,`0.06,`10.00,`0.00")))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PaymentReconciliationService.parse(
                  invalid.getBytes(StandardCharsets.UTF_8), DATE, "190000001", "190000002"));
  }

  @Test
  void refusesFractionsBelowOneFenAndFloatingPointNotation() {
    assertEquals(1234, PaymentReconciliationService.fen("12.34", false));
    assertEquals(-1, PaymentReconciliationService.fen("-0.01", true));
    for (String invalid : List.of("0.001", "1e2", "NaN", "-1", "+1", " 1"))
      assertThrows(
          IllegalArgumentException.class, () -> PaymentReconciliationService.fen(invalid, false));
  }

  @Test
  void validatesChannelHashBeforeTrustingDownloadedBytes() throws Exception {
    byte[] bytes = "signed bill fixture".getBytes(StandardCharsets.UTF_8);
    for (String algorithm : List.of("SHA1", "SHA256")) {
      String jca = algorithm.equals("SHA1") ? "SHA-1" : "SHA-256";
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance(jca).digest(bytes));
      assertDoesNotThrow(() -> PaymentReconciliationService.verifyHash(bytes, algorithm, digest));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PaymentReconciliationService.verifyHash(
                  "changed".getBytes(StandardCharsets.UTF_8), algorithm, digest));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> PaymentReconciliationService.verifyHash(bytes, "MD5", "unused"));
  }

  @Test
  void handlesQuotedCommaEscapedQuoteAndMultilineCellsWithoutLosingDigits() {
    var rows =
        PaymentReconciliationService.csv(
            "\uFEFFid,name\r\n`0001,\"`饭,饮品\"\r\n`0002,\"`说明\"\"文字\n下一行\"\r\n");
    assertEquals("0001", rows.get(1).getFirst());
    assertEquals("饭,饮品", rows.get(1).getLast());
    assertEquals("说明\"文字\n下一行", rows.get(2).getLast());
    assertThrows(
        IllegalArgumentException.class, () -> PaymentReconciliationService.csv("a,\"unfinished"));
    assertThrows(
        IllegalArgumentException.class,
        () -> PaymentReconciliationService.csv("a,\"done\"illegal"));
  }

  @Test
  void rejectsMalformedUtf8InsteadOfReplacingBytes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PaymentReconciliationService.parse(
                new byte[] {(byte) 0xc3, 0x28}, DATE, "190000001", "190000002"));
  }
}
