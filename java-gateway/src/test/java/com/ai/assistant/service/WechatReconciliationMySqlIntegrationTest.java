package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatReconciliationMySqlIntegrationTest {
  private final MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("bill_test")
          .withUsername("test")
          .withPassword("test");
  private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
  private final LocalDate date = BusinessTime.now().toLocalDate().minusDays(1);
  private HttpServer server;
  private JdbcTemplate db;
  private PaymentReconciliationService service;
  private boolean corruptHash, corruptSignature;
  private byte[] bill;

  @BeforeAll
  void start() throws Exception {
    mysql.start();
    var source =
        new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(source);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var merchant = generator.generateKeyPair();
    var platform = generator.generateKeyPair();
    var config =
        new RSAPublicKeyConfig.Builder()
            .merchantId("190000001")
            .merchantSerialNumber("merchant-test")
            .privateKey(merchant.getPrivate())
            .publicKey(platform.getPublic())
            .publicKeyId("PUB_KEY_ID_TEST")
            .apiV3Key("k".repeat(32))
            .build();
    bill =
        ("交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额,申请退款金额,费率备注\r\n"
             + "`"
                + date
                + " 12:03:04,`wx001,`190000001,`190000002,`,`420001,`order001,`openid,`NATIVE,`SUCCESS,`OTHERS,`CNY,`10.00,`0.00,`0,`0,`0.00,`0.00,`,`,`饭,`,`0.06,`0.60%,`10.00,`0.00,`\r\n"
                + "`"
                + date
                + " 12:05:04,`wx001,`190000001,`190000002,`,`420001,`order001,`openid,`NATIVE,`REFUND,`OTHERS,`CNY,`0.00,`0.00,`504001,`refund001,`2.00,`0.00,`ORIGINAL,`PROCESSING,`退款,`,`-0.01,`0.60%,`0.00,`2.00,`\r\n"
                + "总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额\r\n"
                + "`2,`10.00,`2.00,`0.00,`0.05,`10.00,`2.00\r\n")
            .getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            assertNotNull(authorization);
            var fields = new HashMap<String, String>();
            var matcher =
                java.util.regex.Pattern.compile("(\\w+)=\"([^\"]+)\"").matcher(authorization);
            while (matcher.find()) fields.put(matcher.group(1), matcher.group(2));
            assertEquals("190000001", fields.get("mchid"));
            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(merchant.getPublic());
            verifier.update(
                ("GET\n"
                        + exchange.getRequestURI()
                        + "\n"
                        + fields.get("timestamp")
                        + "\n"
                        + fields.get("nonce_str")
                        + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            assertTrue(verifier.verify(Base64.getDecoder().decode(fields.get("signature"))));
            byte[] response;
            if (exchange.getRequestURI().getPath().equals("/v3/bill/tradebill")) {
              assertTrue(exchange.getRequestURI().getRawQuery().contains("sub_mchid=190000002"));
              String hash =
                  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bill));
              response =
                  JSONObject.of(
                          "download_url",
                          "https://api.mch.weixin.qq.com/fixture-bill",
                          "hash_type",
                          "SHA256",
                          "hash_value",
                          corruptHash ? "0".repeat(64) : hash)
                      .toJSONString()
                      .getBytes(StandardCharsets.UTF_8);
            } else {
              assertEquals("/fixture-bill", exchange.getRequestURI().getPath());
              response = bill;
            }
            String stamp = Long.toString(Instant.now().getEpochSecond()), nonce = "bill-response";
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(platform.getPrivate());
            signer.update(
                (stamp + "\n" + nonce + "\n" + new String(response, StandardCharsets.UTF_8) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Wechatpay-Timestamp", stamp);
            exchange.getResponseHeaders().set("Wechatpay-Nonce", nonce);
            exchange.getResponseHeaders().set("Wechatpay-Serial", "PUB_KEY_ID_TEST");
            exchange
                .getResponseHeaders()
                .set(
                    "Wechatpay-Signature",
                    Base64.getEncoder()
                        .encodeToString(corruptSignature ? new byte[256] : signer.sign()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          } catch (Throwable failure) {
            serverFailure.set(failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var network =
        new okhttp3.OkHttpClient.Builder()
            .addInterceptor(WechatPaymentProvider.responseHeaders())
            .addInterceptor(
                chain -> {
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                  var request = chain.request();
                  return chain.proceed(
                      request
                          .newBuilder()
                          .url(
                              request
                                  .url()
                                  .newBuilder()
                                  .scheme("http")
                                  .host("127.0.0.1")
                                  .port(server.getAddress().getPort())
                                  .build())
                          .build());
                })
            .build();
    var client =
        new DefaultHttpClientBuilder()
            .config(config)
            .okHttpClient(network)
            .disableRetryOnConnectionFailure()
            .build();
    var settings =
        new MockEnvironment()
            .withProperty("payment.mode", "WECHAT_PARTNER")
            .withProperty("payment.wechat.mch-id", "190000001")
            .withProperty("payment.wechat.app-id", "wx001")
            .withProperty("payment.wechat.app-secret", "fixture-secret")
            .withProperty("payment.wechat.private-key-path", "not-used-by-injected-sdk")
            .withProperty("payment.wechat.public-key-path", "not-used-by-injected-sdk")
            .withProperty("payment.wechat.certificate-serial", "merchant-test")
            .withProperty("payment.wechat.public-key-id", "PUB_KEY_ID_TEST")
            .withProperty("payment.wechat.api-v3-key", "k".repeat(32))
            .withProperty("payment.wechat.notify-base-url", "https://fixture.invalid");
    service =
        new PaymentReconciliationService(
            db,
            new WechatPaymentProvider(config, client),
            new PaymentSettings(settings),
            new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  @BeforeEach
  void seed() {
    corruptHash = corruptSignature = false;
    serverFailure.set(null);
    for (String table :
        List.of(
            "payment_reconciliation_difference",
            "payment_provider_fee",
            "payment_reconciliation",
            "provider_refund",
            "provider_payment_part",
            "provider_payment")) db.update("DELETE FROM " + table);
    db.update(
        "INSERT INTO"
            + " provider_payment(id,source_type,source_id,user_id,mode,scene,status,amount,expires_at,client_ip)"
            + " VALUES('payment1','ORDER','1',1,'WECHAT_PARTNER','NATIVE','PAID',1000,NOW(),'127.0.0.1')");
    db.update(
        "INSERT INTO"
            + " provider_payment_part(payment_id,order_id,merchant_id,out_trade_no,sub_mch_id,amount,status,transaction_id,paid_at)"
            + " VALUES('payment1',1,1,'order001','190000002',1000,'PAID','420001',?)",
        date.atTime(12, 3, 4));
    db.update(
        "INSERT INTO"
            + " provider_refund(id,operation_key,payment_id,order_id,amount,status,provider_refund_id,accepted_at)"
            + " VALUES('refund001','partial1','payment1',1,200,'SUCCESS','504001',?)",
        date.atTime(12, 5, 4));
  }

  @AfterAll
  void stop() {
    if (service != null) service.stop();
    if (server != null) server.stop(0);
    mysql.stop();
  }

  @Test
  void signedDownloadedBillReconcilesFeesAndDifferencesIdempotentlyWithoutSettlingFunds() {
    assertEquals(true, service.reconcile(date).get("passed"));
    assertEquals(true, service.reconcile(date).get("passed"));
    assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM payment_provider_fee", Integer.class));
    assertEquals(
        5L, db.queryForObject("SELECT SUM(fee_amount) FROM payment_provider_fee", Long.class));
    assertEquals("SUCCESS", db.queryForObject("SELECT status FROM provider_refund", String.class));
    db.update("UPDATE provider_payment_part SET amount=900");
    assertEquals(false, service.reconcile(date).get("passed"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_reconciliation_difference WHERE kind='PAYMENT_AMOUNT' AND"
                + " resolved_at IS NULL",
            Integer.class));
    assertEquals("PAID", db.queryForObject("SELECT status FROM provider_payment", String.class));
    db.update("UPDATE provider_payment_part SET amount=1000");
    assertEquals(true, service.reconcile(date).get("passed"));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_reconciliation_difference WHERE resolved_at IS NULL",
            Integer.class));
    assertNull(serverFailure.get());
  }

  @Test
  void corruptDownloadedHashCannotImportFeesOrReportAMatch() {
    corruptHash = true;
    assertEquals(false, service.reconcile(date).get("passed"));
    assertEquals(
        "ERROR", db.queryForObject("SELECT status FROM payment_reconciliation", String.class));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_provider_fee", Integer.class));
    assertNull(serverFailure.get());
  }

  @Test
  void forgedMetadataSignatureCannotReachBillImport() {
    corruptSignature = true;
    assertEquals(false, service.reconcile(date).get("passed"));
    assertEquals(
        "ERROR", db.queryForObject("SELECT status FROM payment_reconciliation", String.class));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_provider_fee", Integer.class));
    assertNull(serverFailure.get());
  }
}
