package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ai.assistant.dto.ConfirmDraftDTO;
import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderItem;
import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.*;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderPaymentsMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("payment_contract")
          .withUsername("test")
          .withPassword("test");
  static final Path KEYS = keys();

  static Path keys() {
    try {
      Path dir = Files.createTempDirectory("payment-contract-");
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      Files.writeString(
          dir.resolve("private.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
      Files.writeString(
          dir.resolve("public.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
      return dir;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static String pem(String kind, byte[] bytes) {
    return "-----BEGIN "
        + kind
        + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {10}).encodeToString(bytes)
        + "\n-----END "
        + kind
        + "-----\n";
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    MYSQL.start();
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("app.demo-seed.enabled", () -> "true");
    r.add("DEMO_CUSTOMER_PASSWORD", () -> "ci-only-demo-customer-password");
    r.add("DEMO_OWNER_PASSWORD", () -> "ci-only-demo-owner-password");
    r.add("spring.cache.type", () -> "none");
    r.add("auth.user-secret-key", () -> "u".repeat(32));
    r.add("auth.admin-secret-key", () -> "a".repeat(32));
    r.add("auth.monitoring-key", () -> "m".repeat(32));
    r.add("ai.internal-api-key", () -> "i".repeat(32));
    r.add("payment.mode", () -> "WECHAT_PARTNER");
    r.add("payment.wechat.mch-id", () -> "1000000000");
    r.add("payment.wechat.app-id", () -> "wx-test");
    r.add("payment.wechat.app-secret", () -> "test-secret");
    r.add("payment.wechat.private-key-path", () -> KEYS.resolve("private.pem").toString());
    r.add("payment.wechat.public-key-path", () -> KEYS.resolve("public.pem").toString());
    r.add("payment.wechat.certificate-serial", () -> "TEST");
    r.add("payment.wechat.public-key-id", () -> "PUB_KEY_ID_TEST");
    r.add("payment.wechat.api-v3-key", () -> "k".repeat(32));
    r.add("payment.wechat.notify-base-url", () -> "https://payment.example");
  }

  @TestConfiguration
  static class ChannelConfiguration {
    @Bean
    @Primary
    Channel channel() {
      return new Channel();
    }
  }

  static class Channel implements PaymentProvider {
    final Map<String, JSONObject> orders = new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean paid, closed, refundSuccess, unavailable;
    volatile CountDownLatch prepayEntered, prepayRelease, queryEntered, queryRelease;
    int creates;

    @Override
    public synchronized JSONObject request(String method, String path, JSONObject body) {
      assertFalse(
          TransactionSynchronizationManager.isActualTransactionActive(),
          "Network must not hold database locks");
      if (unavailable) throw new IllegalStateException("controlled network failure");
      if (path.contains("/refund/domestic/")) {
        String rid =
            method.equals("POST")
                ? body.getString("out_refund_no")
                : path.substring(path.lastIndexOf('/') + 1).split("\\?")[0];
        if (method.equals("POST")) {
          orders.put(rid, body);
          return refund(rid);
        }
        if (!orders.containsKey(rid)) throw missing("RESOURCE_NOT_EXISTS");
        return refund(rid);
      }
      if (method.equals("POST") && !path.endsWith("/close")) {
        assertNotNull(body.getJSONArray("sub_orders"));
        for (Object entry : body.getJSONArray("sub_orders")) {
          JSONObject part = (JSONObject) entry;
          assertNotNull(part.getString("attach"), "Official combine prepay requires attach");
          assertFalse(part.getString("attach").isBlank());
          assertNotNull(part.getString("description"));
          assertFalse(part.getString("description").isBlank());
        }
        if (path.endsWith("/h5")) {
          assertNotNull(body.getJSONObject("scene_info"));
          assertNotNull(body.getJSONObject("scene_info").getString("device_id"));
          assertFalse(body.getJSONObject("scene_info").getString("device_id").isBlank());
        }
        creates++;
        orders.put(body.getString("combine_out_trade_no"), body);
        gate(prepayEntered, prepayRelease);
        return JSONObject.of(
            "code_url",
            "weixin://wxpay/test",
            "h5_url",
            "https://wx.tenpay.com/test",
            "prepay_id",
            "prepay-test");
      }
      String id = path.substring(path.indexOf("out-trade-no/") + 13).split("[/?]")[0];
      if (!orders.containsKey(id)) throw missing("ORDER_NOT_EXIST");
      if (path.endsWith("/close")) {
        JSONArray original = orders.get(id).getJSONArray("sub_orders");
        JSONArray requested = body.getJSONArray("sub_orders");
        assertEquals(original.size(), requested.size());
        for (Object entry : requested) {
          JSONObject closing = (JSONObject) entry;
          JSONObject expected =
              original.stream()
                  .map(value -> (JSONObject) value)
                  .filter(
                      value ->
                          value.getString("out_trade_no").equals(closing.getString("out_trade_no")))
                  .findFirst()
                  .orElseThrow();
          assertEquals(expected.getString("mchid"), closing.getString("mchid"));
          assertEquals(expected.getString("sub_mchid"), closing.getString("sub_mchid"));
        }
        closed = true;
        return new JSONObject();
      }
      gate(queryEntered, queryRelease);
      JSONObject input = orders.get(id),
          result =
              JSONObject.of(
                  "combine_mchid",
                  "1000000000",
                  "combine_appid",
                  "wx-test",
                  "combine_out_trade_no",
                  id);
      JSONArray parts = new JSONArray();
      for (Object entry : input.getJSONArray("sub_orders")) {
        JSONObject part = JSON.parseObject(entry.toString());
        part.put("trade_state", paid ? "SUCCESS" : closed ? "CLOSED" : "NOTPAY");
        if (paid) {
          part.put("transaction_id", "wx-" + part.getString("out_trade_no"));
          part.put("success_time", OffsetDateTime.now().toString());
        } else {
          part.remove("amount");
          part.remove("transaction_id");
          part.remove("success_time");
        }
        parts.add(part);
      }
      result.put("sub_orders", parts);
      return result;
    }

    JSONObject refund(String id) {
      JSONObject original = orders.get(id);
      return JSONObject.of(
          "out_refund_no",
          id,
          "out_trade_no",
          original.get("out_trade_no"),
          "transaction_id",
          "wx-" + original.getString("out_trade_no"),
          "refund_id",
          "channel-" + id,
          "status",
          refundSuccess ? "SUCCESS" : "PROCESSING",
          "create_time",
          OffsetDateTime.now().toString(),
          "amount",
          original.get("amount"));
    }

    JSONObject refundCallback(String id) {
      JSONObject result = refund(id);
      result.put("sub_mchid", orders.get(id).get("sub_mchid"));
      result.put("sp_mchid", "1000000000");
      result.put("refund_status", result.remove("status"));
      result.getJSONObject("amount").remove("currency");
      return result;
    }

    static ServiceException missing(String code) {
      return new ServiceException(
          new HttpRequest.Builder()
              .httpMethod(HttpMethod.GET)
              .url("https://api.mch.weixin.qq.com/v3/test")
              .build(),
          404,
          "{\"code\":\"" + code + "\",\"message\":\"not found\"}");
    }

    @Override
    public JSONObject notification(Map<String, String> headers, String body) {
      return JSON.parseObject(body).getJSONObject("data");
    }

    @Override
    public String sign(String text) {
      return "test-signature";
    }

    @Override
    public byte[] download(String url) {
      throw new UnsupportedOperationException();
    }

    private static void gate(CountDownLatch entered, CountDownLatch release) {
      if (entered == null) return;
      entered.countDown();
      try {
        assertTrue(release.await(15, TimeUnit.SECONDS), "Controlled channel gate timed out");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Controlled channel interrupted", e);
      }
    }
  }

  @Autowired JdbcTemplate db;
  @Autowired CommerceCartService cart;
  @Autowired CommerceQuoteService quotes;
  @Autowired CommerceCheckoutService checkouts;
  @Autowired CampusCatalogService catalog;
  @Autowired CommerceAftersaleService aftersales;
  @Autowired ProviderPaymentService payments;
  @Autowired DraftService drafts;
  @Autowired OrderTransactionService legacyTransactions;
  @Autowired MerchantService merchants;
  @Autowired OrderStatusEventBroker legacyEvents;
  @Autowired Channel channel;

  @BeforeEach
  void reset() {
    channel.orders.clear();
    channel.paid = false;
    channel.closed = false;
    channel.refundSuccess = false;
    channel.unavailable = false;
    channel.creates = 0;
    channel.prepayEntered = null;
    channel.prepayRelease = null;
    channel.queryEntered = null;
    channel.queryRelease = null;
    for (String table :
        List.of(
            "provider_callback",
            "provider_refund",
            "provider_payment_part",
            "provider_payment",
            "payment_wechat_identity",
            "notification_task",
            "notification",
            "commerce_event",
            "aftersale_unit",
            "aftersale",
            "commerce_payment",
            "customer_order_code",
            "order_unit",
            "order_stock_component",
            "sku_inventory_ledger",
            "payment_record",
            "order_item",
            "orders",
            "checkout",
            "checkout_quote",
            "cart_item",
            "customer_coupon",
            "coupon_campaign",
            "platform_coupon_participation",
            "merchant_payment_binding")) db.update("DELETE FROM " + table);
    db.update(
        "UPDATE merchant SET"
            + " enabled=TRUE,accepting_orders=TRUE,opens_at='00:00:00',closes_at='00:00:00' WHERE"
            + " id=1");
    db.update("INSERT IGNORE INTO delivery_rule(merchant_id,campus_id,fee) VALUES(1,1,0)");
    db.update(
        "UPDATE dish SET price=10,stock=100,status=1,allergen_reviewed=TRUE,allergens='' WHERE"
            + " merchant_id=1");
    catalog.ensureDefaultSkus();
    db.update("UPDATE product_sku SET price=10,stock=100,enabled=TRUE WHERE merchant_id=1");
    db.update(
        "INSERT INTO merchant_payment_binding(merchant_id,sub_mch_id,enabled)"
            + " VALUES(1,'2000000000',TRUE)");
    UserContext.setUser(1L, null);
  }

  @AfterEach
  void clear() {
    UserContext.clear();
  }

  @AfterAll
  static void stop() {
    try {
      new TestContextManager(ProviderPaymentsMySqlIntegrationTest.class)
          .getTestContext()
          .markApplicationContextDirty(null);
    } finally {
      MYSQL.stop();
    }
  }

  String checkout(int quantity) {
    long sku =
        db.queryForObject("SELECT id FROM product_sku WHERE merchant_id=1 LIMIT 1", Long.class);
    cart.save(new CartInput("SKU", sku, quantity, "", null));
    var quote =
        quotes.quote(new QuoteInput(List.of(1L), null, 1, "PICKUP", Map.of(), null, Map.of()));
    return (String)
        checkouts
            .confirm(new ConfirmInput(quote.id(), quote.version()), UUID.randomUUID().toString())
            .get("id");
  }

  String payment(String checkout) {
    return (String)
        payments
            .create(new ProviderPaymentService.Input("CHECKOUT", checkout, "NATIVE"), "127.0.0.1")
            .get("id");
  }

  void due() {
    db.update("UPDATE provider_refund SET next_attempt_at=NOW(),lease_until=NULL");
    payments.processDue();
  }

  long order(String checkout) {
    return db.queryForObject("SELECT id FROM orders WHERE checkout_id=?", Long.class, checkout);
  }

  Order legacyOrder(int quantity) {
    UserContext.set(1L, null, "CUSTOMER", 1L);
    long dish =
        db.queryForObject(
            "SELECT id FROM dish WHERE merchant_id=1 ORDER BY id LIMIT 1", Long.class);
    OrderItem item = new OrderItem();
    item.setDishId(dish);
    item.setQuantity(quantity);
    var draft = drafts.create(1, List.of(item), "原单店真实支付测试");
    String region = merchants.get(1).deliveryRegions().getFirst();
    return legacyTransactions.confirm(
        1,
        draft.getId(),
        UUID.randomUUID().toString(),
        new ConfirmDraftDTO(draft.getVersion(), "测试顾客", "13800138000", "校内一号楼", region));
  }

  @Test
  void confirmedPaymentIsIdempotentAndRefundWaitsForChannel() {
    String checkout = checkout(2), id = payment(checkout);
    assertEquals("PENDING", payments.session(id, 1).get("status"));
    assertEquals(id, payment(checkout));
    assertEquals(1, channel.creates);
    channel.paid = true;
    payments.query(id);
    payments.query(id);
    assertEquals(
        "PAID",
        db.queryForObject("SELECT status FROM checkout WHERE id=?", String.class, checkout));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE kind='PAYMENT'", Integer.class));
    checkouts.cancel(order(checkout));
    assertEquals(
        "REFUNDING",
        db.queryForObject(
            "SELECT payment_status FROM orders WHERE checkout_id=?", String.class, checkout));
    assertEquals(
        new java.math.BigDecimal("0.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM checkout WHERE id=?",
            java.math.BigDecimal.class,
            checkout));
    due();
    assertEquals(
        "PROCESSING",
        db.queryForObject("SELECT status FROM provider_refund LIMIT 1", String.class));
    channel.refundSuccess = true;
    due();
    due();
    assertEquals(
        new java.math.BigDecimal("20.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM checkout WHERE id=?",
            java.math.BigDecimal.class,
            checkout));
    assertEquals(
        new java.math.BigDecimal("0.00"),
        db.queryForObject(
            "SELECT refund_pending_amount FROM orders WHERE checkout_id=?",
            java.math.BigDecimal.class,
            checkout));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE kind='REFUND'", Integer.class));
  }

  @Test
  void foreignMerchantTransitionIsNotFoundAndCannotMutateAnOrder() {
    var order = legacyOrder(1);
    UserContext.set(1L, null, "OWNER", 999L);
    var failure =
        assertThrows(BusinessException.class, () -> legacyTransactions.status(order.getId(), 2));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, failure.status());
    assertEquals(
        0, db.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, order.getId()));
    assertEquals(0, channel.creates);
  }

  @Test
  void consecutivePartialRefundsAndZeroDeliveryRefundShareOneCompatibilitySummary() {
    String checkout = checkout(2), id = payment(checkout);
    channel.paid = true;
    payments.query(id);
    long order = order(checkout),
        item = db.queryForObject("SELECT id FROM order_item WHERE order_id=?", Long.class, order);
    String first =
        aftersales.apply(
            new RefundInput(
                order, List.of(new RefundItem(item, 1)), "第一份", UUID.randomUUID().toString()));
    String second =
        aftersales.apply(
            new RefundInput(
                order, List.of(new RefundItem(item, 1)), "第二份", UUID.randomUUID().toString()));
    channel.refundSuccess = true;
    due();
    due();
    assertEquals(
        3,
        db.queryForObject(
            "SELECT COUNT(*) FROM provider_refund WHERE status='SUCCESS'", Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE order_id=? AND kind='REFUND'",
            Integer.class,
            order));
    assertEquals(
        new java.math.BigDecimal("20.00"),
        db.queryForObject(
            "SELECT amount FROM payment_record WHERE order_id=? AND kind='REFUND'",
            java.math.BigDecimal.class,
            order));
    assertEquals(
        new java.math.BigDecimal("20.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM orders WHERE id=?", java.math.BigDecimal.class, order));
    assertEquals(
        new java.math.BigDecimal("0.00"),
        db.queryForObject(
            "SELECT refund_pending_amount FROM orders WHERE id=?",
            java.math.BigDecimal.class,
            order));
    for (String sale : List.of(first, second))
      assertEquals(
          "APPROVED",
          db.queryForObject("SELECT status FROM aftersale WHERE id=?", String.class, sale));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM order_unit WHERE order_id=? AND status='REFUNDED'",
            Integer.class,
            order));
  }

  @Test
  void verifiedLatePaymentIsRefundedWithoutReopeningFulfillmentOrRepeatingTheLedger() {
    String checkout = checkout(1), id = payment(checkout);
    checkouts.close(checkout);
    payments.query(id);
    assertEquals("CLOSED", payments.session(id, 1).get("status"));
    channel.paid = true;
    JSONObject data =
        channel.request(
            "GET",
            "/v3/combine-transactions/out-trade-no/" + id + "?combine_mchid=1000000000",
            null);
    payments.callback(
        "PAYMENT", Map.of(), JSONObject.of("id", "late-payment", "data", data).toJSONString());
    assertEquals("REFUNDING", payments.session(id, 1).get("status"));
    channel.refundSuccess = true;
    due();
    assertEquals("REFUNDED", payments.session(id, 1).get("status"));
    payments.callback(
        "PAYMENT",
        Map.of(),
        JSONObject.of("id", "late-payment-repeat", "data", data).toJSONString());
    assertEquals("REFUNDED", payments.session(id, 1).get("status"));
    assertEquals("NONE", ((Map<?, ?>) payments.session(id, 1).get("action")).get("type"));
    assertEquals(
        6,
        db.queryForObject(
            "SELECT status FROM orders WHERE checkout_id=?", Integer.class, checkout));
    assertTrue(
        db.queryForObject(
            "SELECT inventory_released FROM orders WHERE checkout_id=?", Boolean.class, checkout));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE kind='PAYMENT'", Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE kind='REFUND'", Integer.class));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM customer_order_code", Integer.class));
  }

  @Test
  void channelDeadlineAlwaysContainsSecondsEvenAtAnExactMinute() {
    String checkout = checkout(1);
    var deadline = BusinessTime.now().plusMinutes(10).withSecond(0).withNano(0);
    db.update("UPDATE checkout SET expires_at=? WHERE id=?", deadline, checkout);
    String id = payment(checkout);
    assertTrue(
        channel
            .orders
            .get(id)
            .getString("time_expire")
            .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\+08:00"));
  }

  @Test
  void closeDoesNotReleaseStockUntilChannelConfirms() {
    String checkout = checkout(1), id = payment(checkout);
    checkouts.close(checkout);
    assertEquals(
        "UNPAID",
        db.queryForObject("SELECT status FROM checkout WHERE id=?", String.class, checkout));
    assertEquals(
        false,
        db.queryForObject(
            "SELECT inventory_released FROM orders WHERE checkout_id=?", Boolean.class, checkout));
    channel.unavailable = true;
    payments.query(id);
    assertEquals("UNKNOWN", payments.session(id, 1).get("status"));
    channel.unavailable = false;
    payments.query(id);
    assertEquals(
        "CLOSED",
        db.queryForObject("SELECT status FROM checkout WHERE id=?", String.class, checkout));
    assertEquals(
        true,
        db.queryForObject(
            "SELECT inventory_released FROM orders WHERE checkout_id=?", Boolean.class, checkout));
  }

  @Test
  void successfulPaymentWinsCancellationRaceWithoutInventingRefund() {
    String checkout = checkout(1), id = payment(checkout);
    checkouts.close(checkout);
    channel.paid = true;
    payments.query(id);
    assertEquals("PAID", payments.session(id, 1).get("status"));
    assertEquals(
        false,
        db.queryForObject(
            "SELECT inventory_released FROM orders WHERE checkout_id=?", Boolean.class, checkout));
  }

  @Test
  void missingMerchantBindingAndOldSimulationCannotUseRealPayment() {
    String checkout = checkout(1);
    db.update("UPDATE checkout SET payment_mode='SIMULATED' WHERE id=?", checkout);
    assertThrows(IllegalArgumentException.class, () -> payment(checkout));
    assertThrows(IllegalArgumentException.class, () -> checkouts.pay(checkout));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            payments.create(
                new ProviderPaymentService.Input("CHECKOUT", checkout, "JSAPI"), "127.0.0.1"));
    assertEquals(0, channel.creates);
  }

  @Test
  void partialRefundReservationsPreventOverRefundAndSettleExactlyOnce() {
    String checkout = checkout(2), id = payment(checkout);
    channel.paid = true;
    payments.query(id);
    long oid = order(checkout);
    long item = db.queryForObject("SELECT id FROM order_item WHERE order_id=?", Long.class, oid);
    String sale =
        aftersales.apply(
            new RefundInput(
                oid, List.of(new RefundItem(item, 1)), "少了一份", UUID.randomUUID().toString()));
    assertEquals(
        "REFUNDING",
        db.queryForObject("SELECT status FROM aftersale WHERE id=?", String.class, sale));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            aftersales.apply(
                new RefundInput(
                    oid, List.of(new RefundItem(item, 2)), "重复", UUID.randomUUID().toString())));
    assertThrows(IllegalArgumentException.class, () -> checkouts.cancel(oid));
    channel.refundSuccess = true;
    due();
    assertEquals(
        new java.math.BigDecimal("10.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM orders WHERE id=?", java.math.BigDecimal.class, oid));
    String refund = db.queryForObject("SELECT id FROM provider_refund LIMIT 1", String.class);
    JSONObject data = channel.refundCallback(refund);
    payments.callback(
        "REFUND", Map.of(), JSONObject.of("id", "duplicate-success", "data", data).toJSONString());
    data.put("refund_status", "ABNORMAL");
    payments.callback(
        "REFUND", Map.of(), JSONObject.of("id", "late-abnormal", "data", data).toJSONString());
    assertEquals(
        "SUCCESS",
        db.queryForObject("SELECT status FROM provider_refund WHERE id=?", String.class, refund));
    assertEquals(
        new java.math.BigDecimal("10.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM orders WHERE id=?", java.math.BigDecimal.class, oid));
  }

  @Test
  void zeroTotalNeverContactsChannelAndRestartsSafely() {
    String checkout = checkout(1);
    db.update("UPDATE orders SET total_amount=0 WHERE checkout_id=?", checkout);
    db.update("UPDATE checkout SET total_amount=0 WHERE id=?", checkout);
    channel.unavailable = true;
    String id = payment(checkout);
    assertEquals("PAID", payments.session(id, 1).get("status"));
    assertEquals(0, channel.creates);
    assertEquals(
        "NO_CHARGE",
        db.queryForObject(
            "SELECT payment_status FROM orders WHERE checkout_id=?", String.class, checkout));
    checkouts.cancel(order(checkout));
    due();
    assertEquals(
        "SUCCESS", db.queryForObject("SELECT status FROM provider_refund LIMIT 1", String.class));
  }

  @Test
  void concurrentSessionCreationDoesNotDuplicatePrepayOrHoldBusinessLocksDuringNetwork()
      throws Exception {
    String checkout = checkout(1);
    ExecutorService executor = Executors.newFixedThreadPool(5);
    CountDownLatch release = new CountDownLatch(1);
    channel.prepayEntered = new CountDownLatch(1);
    channel.prepayRelease = release;
    try {
      Future<String> first = customerTask(executor, () -> payment(checkout));
      assertTrue(
          channel.prepayEntered.await(10, TimeUnit.SECONDS), "First prepay must reach the channel");
      String expected =
          db.queryForObject(
              "SELECT id FROM provider_payment WHERE source_type='CHECKOUT' AND source_id=?",
              String.class,
              checkout);
      List<Future<String>> repeated = new ArrayList<>();
      for (int i = 0; i < 4; i++) repeated.add(customerTask(executor, () -> payment(checkout)));
      // These requests must finish while the first network call remains blocked.
      // A transaction held across HTTP would retain the user/checkout locks here.
      for (Future<String> result : repeated)
        assertEquals(expected, result.get(10, TimeUnit.SECONDS));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM provider_payment WHERE source_id=?", Integer.class, checkout));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM provider_payment_part WHERE payment_id=?",
              Integer.class,
              expected));
      release.countDown();
      assertEquals(expected, first.get(10, TimeUnit.SECONDS));
      assertEquals(1, channel.creates);
      assertEquals("PENDING", payments.session(expected, 1).get("status"));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_record", Integer.class));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void queryRacingCancellationOrExpiryKeepsFundsAndInventoryConsistent(boolean paid, boolean expiry)
      throws Exception {
    String checkout = checkout(1), id = payment(checkout);
    long sku =
        db.queryForObject(
            "SELECT sku_id FROM order_stock_component WHERE order_item_id=(SELECT id FROM"
                + " order_item WHERE order_id=?)",
            Long.class,
            order(checkout));
    if (expiry) {
      db.update(
          "UPDATE checkout SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", checkout);
      db.update(
          "UPDATE provider_payment SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
          id);
    }
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch release = new CountDownLatch(1);
    channel.queryEntered = new CountDownLatch(1);
    channel.queryRelease = release;
    try {
      Future<Void> query =
          customerTask(
              executor,
              () -> {
                payments.query(id);
                return null;
              });
      assertTrue(channel.queryEntered.await(10, TimeUnit.SECONDS));
      Future<Void> closing =
          customerTask(
              executor,
              () -> {
                if (expiry) checkouts.expire();
                else checkouts.close(checkout);
                return null;
              });
      closing.get(10, TimeUnit.SECONDS);
      assertTrue(
          db.queryForObject(
              "SELECT close_requested FROM provider_payment WHERE id=?", Boolean.class, id));
      assertEquals(
          "UNPAID",
          db.queryForObject("SELECT status FROM checkout WHERE id=?", String.class, checkout));
      assertFalse(
          db.queryForObject(
              "SELECT inventory_released FROM orders WHERE checkout_id=?",
              Boolean.class,
              checkout));
      assertEquals(99, ((Number) catalog.stock(sku, false).get("available_stock")).intValue());
      channel.paid = paid;
      release.countDown();
      query.get(10, TimeUnit.SECONDS);
      payments.query(id);
      assertEquals(paid ? "PAID" : "CLOSED", payments.session(id, 1).get("status"));
      assertEquals(
          paid ? "PAID" : "CLOSED",
          db.queryForObject("SELECT status FROM checkout WHERE id=?", String.class, checkout));
      assertEquals(
          !paid,
          db.queryForObject(
              "SELECT inventory_released FROM orders WHERE checkout_id=?",
              Boolean.class,
              checkout));
      assertEquals(
          paid ? 99 : 100, ((Number) catalog.stock(sku, false).get("available_stock")).intValue());
      assertEquals(
          paid ? 1 : 0,
          db.queryForObject(
              "SELECT COUNT(*) FROM payment_record WHERE kind='PAYMENT'", Integer.class));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM provider_refund", Integer.class));
      assertEquals(
          new java.math.BigDecimal("0.00"),
          db.queryForObject(
              "SELECT refunded_amount+refund_pending_amount FROM orders WHERE checkout_id=?",
              java.math.BigDecimal.class,
              checkout));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentPartialRefundsReserveOnlyAvailableUnitsAndNeverExceedThePayment()
      throws Exception {
    String checkout = checkout(3), payment = payment(checkout);
    channel.paid = true;
    payments.query(payment);
    long oid = order(checkout);
    long item = db.queryForObject("SELECT id FROM order_item WHERE order_id=?", Long.class, oid);
    long sku =
        db.queryForObject(
            "SELECT sku_id FROM order_stock_component WHERE order_item_id=?", Long.class, item);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier start = new CyclicBarrier(2);
    try {
      List<Future<Object>> attempts = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        attempts.add(
            customerTask(
                executor,
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  try {
                    return aftersales.apply(
                        new RefundInput(
                            oid,
                            List.of(new RefundItem(item, 2)),
                            "并发部分退款",
                            UUID.randomUUID().toString()));
                  } catch (IllegalArgumentException rejected) {
                    return rejected;
                  }
                }));
      List<Object> results = new ArrayList<>();
      for (Future<Object> result : attempts) results.add(result.get(15, TimeUnit.SECONDS));
      assertEquals(1L, results.stream().filter(String.class::isInstance).count());
      assertEquals(1L, results.stream().filter(IllegalArgumentException.class::isInstance).count());
      IllegalArgumentException rejected =
          (IllegalArgumentException)
              results.stream()
                  .filter(IllegalArgumentException.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      assertTrue(rejected.getMessage().contains("退款数量超过剩余可退数量"));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM provider_refund WHERE order_id=?", Integer.class, oid));
      assertEquals(
          new java.math.BigDecimal("20.00"),
          db.queryForObject(
              "SELECT refund_pending_amount FROM orders WHERE id=?",
              java.math.BigDecimal.class,
              oid));
      assertEquals(
          new java.math.BigDecimal("0.00"),
          db.queryForObject(
              "SELECT refunded_amount FROM orders WHERE id=?", java.math.BigDecimal.class, oid));
      assertEquals(
          2,
          db.queryForObject(
              "SELECT COUNT(*) FROM order_unit WHERE order_id=? AND status='REFUNDING'",
              Integer.class,
              oid));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM order_unit WHERE order_id=? AND status='AVAILABLE'",
              Integer.class,
              oid));
      assertEquals(99, ((Number) catalog.stock(sku, false).get("available_stock")).intValue());
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM orders WHERE"
                  + " refunded_amount+refund_pending_amount>total_amount",
              Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void callbacksWithWrongMerchantAmountOrCurrencyCannotMutateBusinessState() {
    String checkout = checkout(2), id = payment(checkout);
    channel.paid = true;
    JSONObject canonical =
        channel.request(
            "GET",
            "/v3/combine-transactions/out-trade-no/" + id + "?combine_mchid=1000000000",
            null);
    List<java.util.function.Consumer<JSONObject>> corruptions =
        List.of(
            json -> json.put("combine_mchid", "9999999999"),
            json -> json.getJSONArray("sub_orders").getJSONObject(0).put("sub_mchid", "9999999999"),
            json ->
                json.getJSONArray("sub_orders")
                    .getJSONObject(0)
                    .getJSONObject("amount")
                    .put("total_amount", 1999),
            json ->
                json.getJSONArray("sub_orders")
                    .getJSONObject(0)
                    .getJSONObject("amount")
                    .put("currency", "USD"));
    for (var corrupt : corruptions) {
      JSONObject data = JSON.parseObject(canonical.toJSONString());
      corrupt.accept(data);
      String packet = JSONObject.of("id", "boundary-callback", "data", data).toJSONString();
      assertThrows(
          IllegalArgumentException.class, () -> payments.callback("PAYMENT", Map.of(), packet));
      assertEquals("PENDING", payments.session(id, 1).get("status"));
      assertEquals(
          "UNPAID",
          db.queryForObject("SELECT status FROM checkout WHERE id=?", String.class, checkout));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM provider_callback", Integer.class));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_record", Integer.class));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM provider_payment_part WHERE transaction_id IS NOT NULL OR"
                  + " status='PAID'",
              Integer.class));
    }
    String accepted = JSONObject.of("id", "boundary-callback", "data", canonical).toJSONString();
    payments.callback("PAYMENT", Map.of(), accepted);
    payments.callback("PAYMENT", Map.of(), accepted);
    assertEquals("PAID", payments.session(id, 1).get("status"));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM provider_callback", Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE kind='PAYMENT'", Integer.class));
  }

  @Test
  void legacyDraftPaymentAndRefundUseTheProviderAndPublishTheRefundEventOnce() {
    Order order = legacyOrder(2);
    long dish = order.getItems().getFirst().getDishId();
    assertEquals(98, db.queryForObject("SELECT stock FROM dish WHERE id=?", Integer.class, dish));
    ProviderPaymentService.Input input =
        new ProviderPaymentService.Input("ORDER", order.getId().toString(), "NATIVE");
    String id = (String) payments.create(input, "127.0.0.1").get("id");
    assertEquals(id, payments.create(input, "127.0.0.1").get("id"));
    assertEquals(1, channel.creates);
    channel.paid = true;
    payments.query(id);
    payments.query(id);
    assertEquals(
        "PAID",
        db.queryForObject(
            "SELECT payment_status FROM orders WHERE id=?", String.class, order.getId()));
    assertEquals(
        1, db.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, order.getId()));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE order_id=? AND kind='PAYMENT'",
            Integer.class,
            order.getId()));

    legacyTransactions.cancel(1, order.getUserSeq());
    long cancellationEvent =
        db.queryForObject(
            "SELECT MAX(id) FROM order_event WHERE order_id=?", Long.class, order.getId());
    assertEquals(
        "REFUNDING",
        db.queryForObject(
            "SELECT payment_status FROM orders WHERE id=?", String.class, order.getId()));
    assertEquals(
        new java.math.BigDecimal("20.00"),
        db.queryForObject(
            "SELECT refund_pending_amount FROM orders WHERE id=?",
            java.math.BigDecimal.class,
            order.getId()));
    assertEquals(
        new java.math.BigDecimal("0.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM orders WHERE id=?",
            java.math.BigDecimal.class,
            order.getId()));
    assertEquals(100, db.queryForObject("SELECT stock FROM dish WHERE id=?", Integer.class, dish));
    due();
    assertEquals(
        "PROCESSING",
        db.queryForObject(
            "SELECT status FROM provider_refund WHERE order_id=?", String.class, order.getId()));
    assertTrue(legacyEvents.replay(1L, cancellationEvent).isEmpty());

    channel.refundSuccess = true;
    due();
    due();
    legacyTransactions.cancel(1, order.getUserSeq());
    assertEquals(
        "REFUNDED",
        db.queryForObject(
            "SELECT payment_status FROM orders WHERE id=?", String.class, order.getId()));
    assertEquals(
        new java.math.BigDecimal("0.00"),
        db.queryForObject(
            "SELECT refund_pending_amount FROM orders WHERE id=?",
            java.math.BigDecimal.class,
            order.getId()));
    assertEquals(
        new java.math.BigDecimal("20.00"),
        db.queryForObject(
            "SELECT refunded_amount FROM orders WHERE id=?",
            java.math.BigDecimal.class,
            order.getId()));
    assertEquals(100, db.queryForObject("SELECT stock FROM dish WHERE id=?", Integer.class, dish));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND kind='RELEASE'",
            Integer.class,
            order.getId()));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE order_id=? AND kind='REFUND'",
            Integer.class,
            order.getId()));
    var refundEvents = legacyEvents.replay(1L, cancellationEvent);
    assertEquals(1, refundEvents.size());
    assertEquals(
        order.getId().longValue(), ((Number) refundEvents.getFirst().get("orderId")).longValue());
    assertEquals(5, ((Number) refundEvents.getFirst().get("status")).intValue());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action='PROVIDER_REFUND_SUCCEEDED' AND"
                + " resource_id=?",
            Integer.class,
            order.getId().toString()));
  }

  @Test
  void legacyExpiryCompetesWithQueryWithoutSimulatedPaymentBypassOrEarlyStockRelease()
      throws Exception {
    Order order = legacyOrder(1);
    long dish = order.getItems().getFirst().getDishId();
    assertThrows(
        IllegalArgumentException.class, () -> legacyTransactions.pay(1, order.getUserSeq()));
    assertEquals(
        "UNPAID",
        db.queryForObject(
            "SELECT payment_status FROM orders WHERE id=?", String.class, order.getId()));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, order.getId()));
    String id =
        (String)
            payments
                .create(
                    new ProviderPaymentService.Input("ORDER", order.getId().toString(), "NATIVE"),
                    "127.0.0.1")
                .get("id");
    db.update(
        "UPDATE orders SET payment_expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
        order.getId());
    db.update(
        "UPDATE provider_payment SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", id);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch release = new CountDownLatch(1);
    channel.queryEntered = new CountDownLatch(1);
    channel.queryRelease = release;
    try {
      Future<Void> query =
          customerTask(
              executor,
              () -> {
                payments.query(id);
                return null;
              });
      assertTrue(channel.queryEntered.await(10, TimeUnit.SECONDS));
      Future<Void> expiry =
          customerTask(
              executor,
              () ->
                  UserContext.within(
                      1L,
                      () -> {
                        legacyTransactions.expire(order.getId());
                        return null;
                      }));
      expiry.get(10, TimeUnit.SECONDS);
      assertTrue(
          db.queryForObject(
              "SELECT close_requested FROM provider_payment WHERE id=?", Boolean.class, id));
      assertEquals(
          0,
          db.queryForObject("SELECT status FROM orders WHERE id=?", Integer.class, order.getId()));
      assertFalse(
          db.queryForObject(
              "SELECT inventory_released FROM orders WHERE id=?", Boolean.class, order.getId()));
      assertEquals(99, db.queryForObject("SELECT stock FROM dish WHERE id=?", Integer.class, dish));
      release.countDown();
      query.get(10, TimeUnit.SECONDS);
      payments.query(id);
      assertEquals("CLOSED", payments.session(id, 1).get("status"));
      assertEquals(
          "CLOSED",
          db.queryForObject(
              "SELECT payment_status FROM orders WHERE id=?", String.class, order.getId()));
      assertTrue(
          db.queryForObject(
              "SELECT inventory_released FROM orders WHERE id=?", Boolean.class, order.getId()));
      assertEquals(
          100, db.queryForObject("SELECT stock FROM dish WHERE id=?", Integer.class, dish));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND kind='RELEASE'",
              Integer.class,
              order.getId()));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM payment_record WHERE order_id=?",
              Integer.class,
              order.getId()));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM provider_refund WHERE order_id=?",
              Integer.class,
              order.getId()));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void h5PrepayReturnsRedirectAndIncludesTheOfficialSceneInformation() {
    String checkout = checkout(1);
    var session =
        payments.create(
            new ProviderPaymentService.Input("CHECKOUT", checkout, "H5"), "203.0.113.17");
    String id = (String) session.get("id");
    Map<?, ?> action = (Map<?, ?>) session.get("action");
    assertEquals("PENDING", session.get("status"));
    assertEquals("REDIRECT", action.get("type"));
    assertEquals("https://wx.tenpay.com/test", action.get("payload"));
    assertEquals(
        "H5", db.queryForObject("SELECT scene FROM provider_payment WHERE id=?", String.class, id));
    JSONObject scene = channel.orders.get(id).getJSONObject("scene_info");
    assertEquals("203.0.113.17", scene.getString("payer_client_ip"));
    assertFalse(scene.getString("device_id").isBlank());
    assertEquals("Wap", scene.getJSONObject("h5_info").getString("type"));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_record", Integer.class));
  }

  @Test
  void jsapiPrepayUsesTheVerifiedAppIdentityAndReturnsSignedInvocationParameters() {
    db.update(
        "INSERT INTO payment_wechat_identity(user_id,app_id,open_id)"
            + " VALUES(1,'wx-test','openid-user-1')");
    String checkout = checkout(1);
    var session =
        payments.create(
            new ProviderPaymentService.Input("CHECKOUT", checkout, "JSAPI"), "127.0.0.1");
    String id = (String) session.get("id");
    Map<?, ?> action = (Map<?, ?>) session.get("action");
    Map<?, ?> payload = (Map<?, ?>) action.get("payload");
    assertEquals("PENDING", session.get("status"));
    assertEquals("JSAPI", action.get("type"));
    assertEquals("wx-test", payload.get("appId"));
    assertEquals("prepay_id=prepay-test", payload.get("package"));
    assertEquals("RSA", payload.get("signType"));
    assertEquals("test-signature", payload.get("paySign"));
    assertTrue(String.valueOf(payload.get("timeStamp")).matches("[0-9]+"));
    assertFalse(String.valueOf(payload.get("nonceStr")).isBlank());
    assertEquals(
        "openid-user-1",
        channel.orders.get(id).getJSONObject("combine_payer_info").getString("openid"));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_record", Integer.class));
  }

  private static <T> Future<T> customerTask(ExecutorService executor, Callable<T> action) {
    return executor.submit(
        () -> {
          UserContext.setUser(1L, null);
          try {
            return action.call();
          } finally {
            UserContext.clear();
          }
        });
  }
}
