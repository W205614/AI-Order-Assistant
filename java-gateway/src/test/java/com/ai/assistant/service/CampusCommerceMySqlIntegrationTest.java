package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ai.assistant.security.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class CampusCommerceMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("campus_test")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    MYSQL.start();
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("app.demo-seed.enabled", () -> "true");
    r.add("spring.cache.type", () -> "none");
    r.add("auth.user-secret-key", () -> "u".repeat(32));
    r.add("auth.admin-secret-key", () -> "a".repeat(32));
    r.add("ai.internal-api-key", () -> "i".repeat(32));
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Autowired JdbcTemplate db;
  @MockitoSpyBean CampusCatalogService catalog;
  @Autowired CommerceCartService cart;
  @Autowired CommerceQuoteService quotes;
  @MockitoSpyBean CommerceCheckoutService checkouts;
  @MockitoSpyBean CommerceCouponService coupons;
  @Autowired CommerceAftersaleService refunds;
  @Autowired CommerceFulfillmentService delivery;
  @Autowired CommerceExperienceService experience;
  @Autowired CommerceEventService events;
  @Autowired OrderTransactionService legacy;
  @Autowired OrderQueryService orderQueries;
  @Autowired CampusRecommendationService recommendations;
  @Autowired AuthService auth;
  @Autowired MockMvc mvc;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  long address;

  void customer() {
    UserContext.set(1L, null, "CUSTOMER", null);
  }

  void owner(long m) {
    UserContext.set(m, null, "OWNER", m);
  }

  void platform() {
    UserContext.set(1L, null, "PLATFORM_ADMIN", null);
  }

  long sku(long dish) {
    return id(
        one(db, "SELECT id FROM product_sku WHERE dish_id=? AND is_default=TRUE", dish), "id");
  }

  BigDecimal money(String v) {
    return new BigDecimal(v);
  }

  @BeforeEach
  void reset() {
    for (String t :
        List.of(
            "commerce_event",
            "feedback",
            "delivery_task",
            "rider_campus",
            "aftersale_unit",
            "aftersale",
            "commerce_payment",
            "sku_inventory_ledger",
            "order_stock_component",
            "customer_order_code",
            "order_unit",
            "checkout",
            "checkout_quote",
            "reservation_slot",
            "coupon_ledger",
            "customer_coupon",
            "coupon_campaign",
            "favorite",
            "cart_item",
            "bundle_component",
            "product_bundle",
            "product_sku",
            "customer_address",
            "delivery_rule",
            "payment_record",
            "inventory_ledger",
            "order_event",
            "audit_log",
            "order_item",
            "order_draft_item",
            "order_draft",
            "orders",
            "user_order_sequence",
            "order_safety_context",
            "user_food_preference")) {
      db.update("DELETE FROM " + t);
    }
    db.update("DELETE FROM merchant WHERE id<>1");
    db.update("DELETE FROM campus WHERE id<>1");
    db.update(
        "UPDATE merchant SET"
            + " enabled=TRUE,accepting_orders=TRUE,opens_at='00:00:00',closes_at='00:00:00',version=1,campus_id=1"
            + " WHERE id=1");
    db.update("INSERT INTO campus(id,name) VALUES(2,'东校区')");
    db.update("INSERT INTO merchant(id,name,campus_id) VALUES(2,'茶店',2),(3,'第三店',1)");
    db.update("DELETE FROM dish");
    db.update(
        "INSERT INTO dish(id,merchant_id,name,price,stock,status,allergen_reviewed)"
            + " VALUES(101,1,'米饭',10,10,1,TRUE),(102,1,'鸡蛋',2,10,1,TRUE),(201,2,'奶茶',15,10,1,TRUE),(301,3,'面条',8,10,1,TRUE)");
    db.update("UPDATE dish SET allergens='鸡蛋' WHERE id=102");
    db.update(
        "INSERT INTO delivery_rule(merchant_id,campus_id,fee)"
            + " VALUES(1,2,3),(2,2,2),(3,2,1),(1,1,0)");
    db.update(
        "INSERT IGNORE INTO user(id,username,password,nickname,created_at) SELECT"
            + " 2,'other',password,'其他用户',NOW() FROM user WHERE id=1");
    db.update("UPDATE user SET enabled=TRUE,token_version=1");
    db.update("DELETE FROM admin_user WHERE role='RIDER'");
    customer();
    catalog.ensureDefaultSkus();
    address = catalog.saveAddress(null, new AddressInput(2, "同学", "13800000000", "一号楼", null));
  }

  @AfterEach
  void clear() {
    UserContext.clear();
  }

  void add(long dish, int quantity) {
    cart.save(new CartInput("SKU", sku(dish), quantity, "", null));
  }

  Quote quote(
      List<Long> shops, Map<Long, Long> shopCoupons, Long platformCoupon, Map<Long, Long> slots) {
    return quotes.quote(
        new QuoteInput(shops, address, 2, "DELIVERY", shopCoupons, platformCoupon, slots));
  }

  Map<String, Object> confirm(Quote q) {
    return checkouts.confirm(new ConfirmInput(q.id(), q.version()), "key-" + q.id());
  }

  Map<String, Object> paid() {
    add(101, 3);
    add(201, 1);
    var c = confirm(quote(List.of(1L, 2L), Map.of(), null, Map.of()));
    checkouts.pay((String) c.get("id"));
    return checkouts.detail((String) c.get("id"));
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> orders(Map<String, Object> c) {
    return (List<Map<String, Object>>) c.get("orders");
  }

  long campaign(Long m, String threshold, String discount) {
    if (m == null) platform();
    else owner(m);
    var now = BusinessTime.now();
    long c =
        coupons.create(
            new CouponInput(
                "优惠",
                money(threshold),
                money(discount),
                20,
                now.minusDays(1),
                now.plusDays(1),
                now.minusDays(1),
                now.plusDays(2)));
    customer();
    return coupons.claim(c);
  }

  @Test
  void replenishmentToManualCapDoesNotBlockUnpaidCompensation() {
    db.update("UPDATE dish SET stock=1000000 WHERE id=101");
    long coupon = campaign(1L, "0", "1");
    add(101, 1);
    var c = confirm(quote(List.of(1L), Map.of(1L, coupon), null, Map.of()));
    owner(1);
    catalog.adjustStock(sku(101), 1, 1, "replenish-unpaid");
    customer();
    checkouts.close((String) c.get("id"));
    assertEquals(1000001, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(
        "AVAILABLE",
        one(db, "SELECT status FROM customer_coupon WHERE id=?", coupon).get("status"));
    owner(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> catalog.adjustStock(sku(101), 2, 1, "above-manual-cap"));
  }

  @Test
  void namedSkuReplenishmentDoesNotBlockPaidQuantityRefund() {
    owner(1);
    long sku = catalog.sku(null, new SkuInput(101, "大份", money("12"), ZERO, 1000000, true, null));
    customer();
    cart.save(new CartInput("SKU", sku, 2, "", null));
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    checkouts.pay((String) c.get("id"));
    long order = id(orders(c).getFirst(), "id");
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", order), "id");
    owner(1);
    catalog.adjustStock(sku, 1, 2, "replenish-paid");
    customer();
    refunds.apply(new RefundInput(order, List.of(new RefundItem(item, 1)), "少一份", "cap-refund"));
    checkouts.cancel(order);
    assertEquals(
        1000002, db.queryForObject("SELECT stock FROM product_sku WHERE id=?", Integer.class, sku));
    assertEquals(
        money("27.00"),
        amount(one(db, "SELECT * FROM orders WHERE id=?", order), "refunded_amount"));
  }

  @Test
  void zeroPaidUnitsCanBeRefundedInSeparateRequests() {
    long coupon = campaign(1L, "0", "20");
    add(101, 2);
    var c =
        confirm(
            quotes.quote(
                new QuoteInput(
                    List.of(1L), null, 1, "PICKUP", Map.of(1L, coupon), null, Map.of())));
    checkouts.pay((String) c.get("id"));
    long order = id(orders(c).getFirst(), "id");
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", order), "id");
    refunds.apply(new RefundInput(order, List.of(new RefundItem(item, 1)), "退一份", "free-first"));
    refunds.apply(new RefundInput(order, List.of(new RefundItem(item, 1)), "退另一份", "free-second"));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(
        "CANCELLED",
        one(db, "SELECT fulfillment_status FROM orders WHERE id=?", order)
            .get("fulfillment_status"));
    assertEquals(
        "AVAILABLE",
        one(db, "SELECT status FROM customer_coupon WHERE id=?", coupon).get("status"));
  }

  @Test
  void expiredAgentCartWriteRollsBackAfterWaitingForUserLock() throws Exception {
    long sku = sku(101);
    var started = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    var mutations = new ArrayList<Future<?>>();
    try {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                one(db, "SELECT id FROM user WHERE id=1 FOR UPDATE");
                mutations.add(
                    executor.submit(
                        () -> {
                          customer();
                          try {
                            UserContext.setTrustedDeadline(System.currentTimeMillis() + 300);
                            started.countDown();
                            cart.save(new CartInput("SKU", sku, 1, "", null));
                          } finally {
                            UserContext.clear();
                          }
                        }));
                assertTrue(await(started));
                try {
                  Thread.sleep(600);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                status.setRollbackOnly();
              });
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      var error = assertThrows(ExecutionException.class, () -> mutations.getFirst().get());
      assertInstanceOf(BusinessException.class, error.getCause());
      assertEquals("AI_TIMEOUT", ((BusinessException) error.getCause()).errorCode());
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cart_item", Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  private boolean await(CountDownLatch latch) {
    try {
      return latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void merchantReadModelsSeparateLegacyActionsAndProvidePreparationDetails() {
    owner(1);
    var now = BusinessTime.now();
    long slot =
        delivery.slot(
            new SlotInput(now.plusMinutes(20), now.plusMinutes(40), now.plusMinutes(10), 30, 1));
    customer();
    cart.save(new CartInput("SKU", sku(101), 2, "不要葱", null));
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of(1L, slot)));
    long order = id(orders(c).getFirst(), "id");
    owner(1);
    assertTrue(orderQueries.list(null, null, null, null, 1, 20).getItems().isEmpty());
    var rows = delivery.orders();
    assertEquals(1, rows.size());
    assertNotNull(rows.getFirst().get("reservation_starts_at"));
    assertNotNull(rows.getFirst().get("reservation_ends_at"));
    assertEquals(30, num(rows.getFirst(), "prepare_minutes"));
    var items = (List<Map<String, Object>>) rows.getFirst().get("items");
    assertEquals(2, num(items.getFirst(), "quantity"));
    assertEquals("不要葱", items.getFirst().get("remark"));
    assertEquals(1L, id(catalog.homeCampus(), "campus_id"));
    UserContext.set(1L, null, "CUSTOMER", 1L);
    var historical = orderQueries.get(order).orElseThrow();
    assertEquals(2, historical.getCommerceVersion());
    assertEquals(c.get("id"), historical.getCheckoutId());
  }

  @Test
  void compensationChecksIntegerStorageBound() {
    db.update("UPDATE dish SET stock=? WHERE id=101", Integer.MAX_VALUE - 1);
    long sku = sku(101);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status ->
                catalog.stockChange(catalog.stock(sku, true), 1, null, "storage-bound-first"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TransactionTemplate(manager)
                .executeWithoutResult(
                    status ->
                        catalog.stockChange(
                            catalog.stock(sku, true), 1, null, "storage-bound-overflow")));
    assertEquals(
        Integer.MAX_VALUE, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
  }

  @Test
  void checkoutPaginationKeepsOlderOrdersAccessibleAndCustomerScoped() {
    for (int i = 0; i < 106; i++)
      db.update(
          "INSERT INTO"
              + " checkout(id,user_id,quote_id,idempotency_key,request_fingerprint,status,total_amount,expires_at)"
              + " VALUES(?,?,?,?,?,'PAID',10,NOW())",
          UUID.randomUUID().toString(),
          i == 105 ? 2 : 1,
          UUID.randomUUID().toString(),
          "page-" + i,
          "test");
    var first = checkouts.list(1, 20);
    var second = checkouts.list(2, 20);
    var sixth = checkouts.list(6, 20);
    assertEquals(20, first.size());
    assertEquals(20, second.size());
    assertEquals(5, sixth.size());
    assertEquals(100, checkouts.list().size());
    var firstIds = first.stream().map(row -> row.get("id")).toList();
    assertTrue(second.stream().noneMatch(row -> firstIds.contains(row.get("id"))));
    assertTrue(sixth.stream().allMatch(row -> id(row, "user_id") == 1));
    UserContext.set(2L, null, "CUSTOMER", null);
    assertEquals(1, checkouts.list(1, 20).size());
    assertTrue(checkouts.list(2, 20).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> checkouts.list(0, 20));
    assertThrows(IllegalArgumentException.class, () -> checkouts.list(1, 101));
  }

  @Test
  void refundDoesNotHoldEventCursorWhileWaitingForStock() throws Exception {
    add(101, 1);
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    checkouts.pay((String) c.get("id"));
    long order = id(orders(c).getFirst(), "id");
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", order), "id");
    long sku = sku(101);
    var waitingForStock = new CountDownLatch(1);
    var refundThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    doAnswer(
            invocation -> {
              if (Thread.currentThread() == refundThread.get()) waitingForStock.countDown();
              return invocation.callRealMethod();
            })
        .when(catalog)
        .lockStocks(org.mockito.ArgumentMatchers.anyCollection());
    var executor = Executors.newSingleThreadExecutor();
    var mutation = new ArrayList<Future<String>>();
    try {
      UserContext.set(2L, null, "CUSTOMER", null);
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                catalog.stock(sku, true);
                mutation.add(
                    executor.submit(
                        () -> {
                          customer();
                          refundThread.set(Thread.currentThread());
                          try {
                            return refunds.apply(
                                new RefundInput(
                                    order,
                                    List.of(new RefundItem(item, 1)),
                                    "退餐",
                                    "event-stock-refund"));
                          } finally {
                            UserContext.clear();
                          }
                        }));
                assertTrue(await(waitingForStock));
                // Model another checkout: reserve the shared SKU first, then append its event.
                // The refund must wait for this SKU without already owning the global cursor.
                events.record(2L, null, null, "CONCURRENT_STOCK_HOLDER", "test");
              });
      assertNotNull(mutation.getFirst().get(10, TimeUnit.SECONDS));
      assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM aftersale WHERE status='APPROVED'", Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void multiShopCloseLocksInterleavedSkuIdsBeforeReleasingAnyShop() {
    owner(1);
    long laterSku = catalog.sku(null, new SkuInput(101, "后建大份", money("12"), ZERO, 10, true, null));
    long earlierSku = sku(201);
    assertTrue(earlierSku < laterSku);
    customer();
    cart.save(new CartInput("SKU", laterSku, 1, "", null));
    add(201, 1);
    var c = confirm(quote(List.of(1L, 2L), Map.of(), null, Map.of()));
    var acquired = new ArrayList<List<Long>>();
    doAnswer(
            invocation -> {
              acquired.add(new ArrayList<>(invocation.<Collection<Long>>getArgument(0)));
              return invocation.callRealMethod();
            })
        .when(catalog)
        .lockStocks(org.mockito.ArgumentMatchers.anyCollection());
    checkouts.close((String) c.get("id"));
    assertEquals(List.of(earlierSku, laterSku), acquired.getFirst());
    assertEquals(
        10, db.queryForObject("SELECT stock FROM product_sku WHERE id=?", Integer.class, laterSku));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=201", Integer.class));
  }

  @Test
  void deadlineStillRollsBackWhenCommitWaitsForEventCursor() throws Exception {
    var queued = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    var mutation = new ArrayList<Future<?>>();
    try {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                one(db, "SELECT id FROM commerce_event_cursor WHERE id=1 FOR UPDATE");
                mutation.add(
                    executor.submit(
                        () -> {
                          customer();
                          try {
                            UserContext.setTrustedDeadline(System.currentTimeMillis() + 300);
                            new TransactionTemplate(manager)
                                .executeWithoutResult(
                                    inner -> {
                                      db.update("UPDATE dish SET stock=stock-1 WHERE id=101");
                                      events.record(1L, null, null, "EXPIRED_CURSOR_WAIT", "test");
                                      queued.countDown();
                                    });
                          } finally {
                            UserContext.clear();
                          }
                        }));
                assertTrue(await(queued));
                try {
                  Thread.sleep(600);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              });
      var error =
          assertThrows(
              ExecutionException.class, () -> mutation.getFirst().get(10, TimeUnit.SECONDS));
      assertInstanceOf(BusinessException.class, error.getCause());
      assertEquals("AI_TIMEOUT", ((BusinessException) error.getCause()).errorCode());
      assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM commerce_event WHERE kind='EXPIRED_CURSOR_WAIT'",
              Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void namedSkuWaitsForParentDishBeforeTakingItsOwnRow() throws Exception {
    owner(1);
    long named = catalog.sku(null, new SkuInput(101, "共用菜品大份", money("12"), ZERO, 10, true, null));
    long defaultSku = sku(101);
    var executor = Executors.newSingleThreadExecutor();
    var worker = new ArrayList<Future<?>>();
    try (var observer =
        java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                one(db, "SELECT id FROM dish WHERE id=101 FOR UPDATE");
                worker.add(
                    executor.submit(
                        () ->
                            new TransactionTemplate(manager)
                                .executeWithoutResult(inner -> catalog.stock(named, true))));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waitingForDish = false;
                while (System.nanoTime() < deadline) {
                  try (var statement = observer.createStatement();
                      var result =
                          statement.executeQuery(
                              "SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN"
                                  + " performance_schema.data_locks l ON"
                                  + " l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE"
                                  + " l.OBJECT_SCHEMA='campus_test' AND l.OBJECT_NAME='dish'")) {
                    result.next();
                    if (result.getInt(1) > 0) {
                      waitingForDish = true;
                      break;
                    }
                    Thread.sleep(10);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }
                assertTrue(
                    waitingForDish, "worker must be waiting on the real parent-dish row lock");
                // This is the exact cycle from the MySQL reproduction: the other transaction
                // must not already own named-SKU while waiting for our parent dish.
                catalog.stock(defaultSku, true);
                catalog.stock(named, true);
              });
      worker.getFirst().get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentConfirmationAndCancellationDoNotInvertCouponAndSlotLocks() throws Exception {
    long coupon = campaign(1L, "0", "1");
    long campaign =
        id(one(db, "SELECT campaign_id FROM customer_coupon WHERE id=?", coupon), "campaign_id");
    owner(1);
    var now = BusinessTime.now();
    long slot =
        delivery.slot(
            new SlotInput(now.plusMinutes(20), now.plusMinutes(40), now.plusMinutes(10), 30, 10));
    customer();
    add(102, 1);
    var old = confirm(quote(List.of(1L), Map.of(1L, coupon), null, Map.of(1L, slot)));
    checkouts.pay((String) old.get("id"));
    long order = id(orders(old).getFirst(), "id");
    UserContext.set(2L, null, "CUSTOMER", null);
    long otherCoupon = coupons.claim(campaign);
    long otherAddress =
        catalog.saveAddress(null, new AddressInput(2, "另一同学", "13800000001", "二号楼", null));
    add(101, 1);
    var quote =
        quotes.quote(
            new QuoteInput(
                List.of(1L),
                otherAddress,
                2,
                "DELIVERY",
                Map.of(1L, otherCoupon),
                null,
                Map.of(1L, slot)));
    var couponHeld = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    var confirmThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (Thread.currentThread() == confirmThread.get()) {
                couponHeld.countDown();
                check(await(proceed), "等待并发取消超时");
              }
              return result;
            })
        .when(coupons)
        .lock(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
    var executor = Executors.newFixedThreadPool(2);
    try (var observer =
        java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
      var confirmation =
          executor.submit(
              () -> {
                UserContext.set(2L, null, "CUSTOMER", null);
                confirmThread.set(Thread.currentThread());
                try {
                  return confirm(quote);
                } finally {
                  UserContext.clear();
                }
              });
      assertTrue(await(couponHeld));
      var cancellation =
          executor.submit(
              () -> {
                customer();
                try {
                  return checkouts.cancel(order);
                } finally {
                  UserContext.clear();
                }
              });
      assertTrue(waitingOnTable(observer, "coupon_campaign"));
      proceed.countDown();
      assertNotNull(confirmation.get(10, TimeUnit.SECONDS));
      assertNotNull(cancellation.get(10, TimeUnit.SECONDS));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT reserved_count FROM reservation_slot WHERE id=?", Integer.class, slot));
      assertEquals(
          "AVAILABLE",
          one(db, "SELECT status FROM customer_coupon WHERE id=?", coupon).get("status"));
      assertEquals(
          "LOCKED",
          one(db, "SELECT status FROM customer_coupon WHERE id=?", otherCoupon).get("status"));
    } catch (ExecutionException failure) {
      try (var observer =
              java.sql.DriverManager.getConnection(
                  MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
          var statement = observer.createStatement();
          var status = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
        status.next();
        System.err.println(status.getString("Status"));
      }
      throw failure;
    } finally {
      proceed.countDown();
      executor.shutdownNow();
    }
  }

  private boolean waitingOnTable(java.sql.Connection observer, String table) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      try (var statement =
          observer.prepareStatement(
              "SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN"
                  + " performance_schema.data_locks l ON"
                  + " l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND"
                  + " l.OBJECT_NAME=?")) {
        statement.setString(1, MYSQL.getDatabaseName());
        statement.setString(2, table);
        try (var result = statement.executeQuery()) {
          result.next();
          if (result.getInt(1) > 0) return true;
        }
      }
      Thread.sleep(10);
    }
    return false;
  }

  @Test
  void merchantCancellationAfterWaitingForRefundUsesCurrentUnits() throws Exception {
    add(101, 2);
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    checkouts.pay((String) c.get("id"));
    long order = id(orders(c).getFirst(), "id");
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", order), "id");
    var authorized = new CountDownLatch(1);
    var cancellationThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (Thread.currentThread() == cancellationThread.get()) authorized.countDown();
              return result;
            })
        .when(checkouts)
        .authorizedOrder(order);
    var executor = Executors.newSingleThreadExecutor();
    var cancellation = new ArrayList<Future<?>>();
    try {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                one(db, "SELECT id FROM user WHERE id=1 FOR UPDATE");
                cancellation.add(
                    executor.submit(
                        () -> {
                          owner(1);
                          cancellationThread.set(Thread.currentThread());
                          try {
                            return checkouts.cancel(order);
                          } finally {
                            UserContext.clear();
                          }
                        }));
                assertTrue(await(authorized));
                refunds.apply(
                    new RefundInput(
                        order, List.of(new RefundItem(item, 1)), "先退一份", "snapshot-refund"));
              });
      cancellation.getFirst().get(10, TimeUnit.SECONDS);
      assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
      assertEquals(
          money("23.00"),
          amount(one(db, "SELECT * FROM orders WHERE id=?", order), "refunded_amount"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentSameKeyRefundReturnsOneApplication() throws Exception {
    add(101, 2);
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    checkouts.pay((String) c.get("id"));
    long order = id(orders(c).getFirst(), "id");
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", order), "id");
    var request =
        new RefundInput(order, List.of(new RefundItem(item, 1)), "退一份", "same-key-refund");
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var results = new ArrayList<Future<String>>();
      for (int i = 0; i < 2; i++)
        results.add(
            executor.submit(
                () -> {
                  customer();
                  try {
                    check(await(start), "等待同时退款超时");
                    return refunds.apply(request);
                  } finally {
                    UserContext.clear();
                  }
                }));
      start.countDown();
      assertEquals(
          results.getFirst().get(10, TimeUnit.SECONDS),
          results.getLast().get(10, TimeUnit.SECONDS));
      assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM aftersale", Integer.class));
      assertEquals(9, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void crossCampusCheckoutIsAtomicAndIdempotent() {
    add(101, 2);
    add(201, 1);
    var q = quote(List.of(1L, 2L), Map.of(), null, Map.of());
    assertEquals(money("40.00"), q.total());
    var c = confirm(q);
    assertEquals(c.get("id"), confirm(q).get("id"));
    assertEquals(2, orders(c).size());
    assertEquals(8, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(0, cart.list().size());
    checkouts.pay((String) c.get("id"));
    checkouts.pay((String) c.get("id"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM commerce_payment WHERE kind='PAYMENT'", Integer.class));
  }

  @Test
  void unavailableShopMustBeExplicitlyExcluded() {
    add(101, 1);
    add(201, 1);
    add(301, 1);
    db.update("UPDATE delivery_rule SET enabled=FALSE WHERE merchant_id=3");
    var q = quote(List.of(1L, 2L, 3L), Map.of(), null, Map.of());
    assertTrue(q.unavailable().containsKey(3L));
    assertThrows(IllegalArgumentException.class, () -> confirm(q));
    var c = confirm(quote(List.of(1L, 2L), Map.of(), null, Map.of()));
    assertEquals(2, orders(c).size());
    assertEquals(3L, cart.list().getFirst().get("merchant_id"));
  }

  @Test
  void changeAfterQuoteDoesNotSilentlyCommitSubset() {
    add(101, 1);
    add(201, 1);
    var q = quote(List.of(1L, 2L), Map.of(), null, Map.of());
    db.update("UPDATE dish SET price=11,version=version+1 WHERE id=101");
    assertThrows(BusinessException.class, () -> confirm(q));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM checkout", Integer.class));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=201", Integer.class));
  }

  @Test
  void addressAndDeliveryVersionChangesRequireRequote() {
    add(101, 1);
    var q = quote(List.of(1L), Map.of(), null, Map.of());
    catalog.saveAddress(address, new AddressInput(2, "同学", "13800000000", "二号楼", 1L));
    assertThrows(BusinessException.class, () -> confirm(q));
    var q2 = quote(List.of(1L), Map.of(), null, Map.of());
    db.update(
        "UPDATE delivery_rule SET fee=4,version=version+1 WHERE merchant_id=1 AND campus_id=2");
    assertThrows(BusinessException.class, () -> confirm(q2));
  }

  @Test
  void stackedCouponsAndPartialRefundPreserveOriginalAllocation() {
    long shop = campaign(1L, "20", "3"), platform = campaign(null, "30", "5");
    add(101, 3);
    add(201, 1);
    var q = quote(List.of(1L, 2L), Map.of(1L, shop), platform, Map.of());
    assertEquals(money("42.00"), q.total());
    var c = confirm(q);
    checkouts.pay((String) c.get("id"));
    var o = orders(c).getFirst();
    long item = id(one(db, "SELECT * FROM order_item WHERE order_id=?", o.get("id")), "id");
    var snapshot = one(db, "SELECT * FROM order_unit WHERE order_item_id=? ORDER BY unit_no", item);
    var request =
        new RefundInput(id(o, "id"), List.of(new RefundItem(item, 1)), "少一份", "refund-partial-01");
    String a = refunds.apply(request);
    assertEquals(a, refunds.apply(request));
    assertEquals(
        amount(snapshot, "goods_paid"),
        amount(one(db, "SELECT * FROM aftersale WHERE id=?", a), "amount"));
    assertEquals(8, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(
        "USED", one(db, "SELECT status FROM customer_coupon WHERE id=?", shop).get("status"));
    checkouts.cancel(id(o, "id"));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(
        "AVAILABLE", one(db, "SELECT status FROM customer_coupon WHERE id=?", shop).get("status"));
    assertEquals(
        "USED", one(db, "SELECT status FROM customer_coupon WHERE id=?", platform).get("status"));
    checkouts.cancel(id(orders(c).get(1), "id"));
    assertEquals(
        "AVAILABLE",
        one(db, "SELECT status FROM customer_coupon WHERE id=?", platform).get("status"));
    assertEquals(
        q.total(),
        amount(one(db, "SELECT * FROM checkout WHERE id=?", c.get("id")), "refunded_amount"));
  }

  @Test
  void postProductionRefundDoesNotRestockAndCannotOverRefund() {
    var c = paid();
    var o = orders(c).getFirst();
    long id = id(o, "id");
    owner(1);
    delivery.transition(id, new Transition("ACCEPT", null));
    delivery.transition(id, new Transition("START", null));
    customer();
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", id), "id");
    String a =
        refunds.apply(
            new RefundInput(id, List.of(new RefundItem(item, 2)), "质量问题", "quality-refund-01"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            refunds.apply(
                new RefundInput(
                    id, List.of(new RefundItem(item, 2)), "重复数量", "quality-refund-02")));
    owner(2);
    assertThrows(
        IllegalArgumentException.class, () -> refunds.decide(a, new DecisionInput(true, "同意")));
    owner(1);
    refunds.decide(a, new DecisionInput(true, "同意"));
    refunds.decide(a, new DecisionInput(true, "同意"));
    assertEquals(7, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM commerce_payment WHERE operation_key=?",
            Integer.class,
            "aftersale:" + a));
  }

  @Test
  void rejectedRefundCanBeDisputedOnlyByOwnerAndPlatformDecides() {
    var c = paid();
    long o = id(orders(c).getFirst(), "id");
    owner(1);
    delivery.transition(o, new Transition("ACCEPT", null));
    delivery.transition(o, new Transition("START", null));
    customer();
    long item = id(one(db, "SELECT id FROM order_item WHERE order_id=?", o), "id");
    String a =
        refunds.apply(
            new RefundInput(o, List.of(new RefundItem(item, 1)), "问题", "dispute-refund-01"));
    owner(1);
    refunds.decide(a, new DecisionInput(false, "不同意"));
    customer();
    refunds.dispute(a, "申请平台核实");
    owner(1);
    assertThrows(BusinessException.class, () -> refunds.decide(a, new DecisionInput(true, "同意")));
    platform();
    refunds.decide(a, new DecisionInput(true, "核实通过"));
    assertEquals("APPROVED", one(db, "SELECT status FROM aftersale WHERE id=?", a).get("status"));
  }

  @Test
  void closingUnpaidCheckoutReleasesAllResourcesAndExpiredCouponStaysExpired() {
    long coupon = campaign(1L, "0", "1");
    add(101, 1);
    var c = confirm(quote(List.of(1L), Map.of(1L, coupon), null, Map.of()));
    db.update("UPDATE coupon_campaign SET valid_end=DATE_SUB(NOW(),INTERVAL 1 MINUTE)");
    checkouts.close((String) c.get("id"));
    checkouts.close((String) c.get("id"));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(
        "EXPIRED", one(db, "SELECT status FROM customer_coupon WHERE id=?", coupon).get("status"));
    assertThrows(IllegalArgumentException.class, () -> checkouts.pay((String) c.get("id")));
  }

  @Test
  void legacyEndpointsCannotBypassUnifiedPaymentAndCancel() {
    var c = paid();
    var o = orders(c).getFirst();
    customer();
    UserContext.within(
        1L,
        () -> {
          assertThrows(BusinessException.class, () -> legacy.pay(1, id(o, "user_seq")));
          assertThrows(BusinessException.class, () -> legacy.cancel(1, id(o, "user_seq")));
          return null;
        });
    owner(1);
    assertThrows(BusinessException.class, () -> legacy.status(id(o, "id"), 2));
  }

  @Test
  void bundleAndIndividualShareStockAndRefundUnits() {
    owner(1);
    long bundle =
        catalog.bundle(
            null,
            new BundleInput(
                "米饭套餐", money("18"), money("1"), true, List.of(new Component(sku(101), 2)), null));
    customer();
    cart.save(new CartInput("BUNDLE", bundle, 4, "", null));
    add(101, 3);
    var q = quote(List.of(1L), Map.of(), null, Map.of());
    assertFalse(q.unavailable().isEmpty());
    cart.save(new CartInput("BUNDLE", bundle, 3, "", 1L));
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    assertEquals(1, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    checkouts.pay((String) c.get("id"));
    checkouts.cancel(id(orders(c).getFirst(), "id"));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
  }

  @Test
  void allergySnapshotSurvivesTransientContextExpiry() {
    db.update(
        "INSERT INTO"
            + " order_safety_context(user_id,merchant_id,allergens,needs_clarification,expires_at)"
            + " VALUES(1,1,'鸡蛋',FALSE,DATE_ADD(NOW(),INTERVAL 10 MINUTE))");
    assertThrows(IllegalArgumentException.class, () -> add(102, 1));
    add(101, 1);
    db.update("DELETE FROM order_safety_context");
    db.update("UPDATE dish SET allergens='鸡蛋',version=version+1 WHERE id=101");
    assertFalse(quote(List.of(1L), Map.of(), null, Map.of()).unavailable().isEmpty());
  }

  @Test
  void reservationCapacityCompetitionAndPickupCodeAreProtected() throws Exception {
    owner(1);
    var now = BusinessTime.now();
    long slot =
        delivery.slot(
            new SlotInput(now.plusMinutes(20), now.plusMinutes(40), now.plusMinutes(10), 30, 1));
    customer();
    add(101, 1);
    var q = quote(List.of(1L), Map.of(), null, Map.of(1L, slot));
    var c = confirm(q);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT reserved_count FROM reservation_slot WHERE id=?", Integer.class, slot));
    checkouts.close((String) c.get("id"));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT reserved_count FROM reservation_slot WHERE id=?", Integer.class, slot));
    add(101, 1);
    var pickup =
        quotes.quote(new QuoteInput(List.of(1L), null, 1, "PICKUP", Map.of(), null, Map.of()));
    assertEquals(ZERO, pickup.shops().getFirst().delivery());
    var p = confirm(pickup);
    checkouts.pay((String) p.get("id"));
    long o = id(orders(p).getFirst(), "id");
    String code = checkouts.code(o);
    owner(1);
    delivery.transition(o, new Transition("ACCEPT", null));
    delivery.transition(o, new Transition("START", null));
    delivery.transition(o, new Transition("READY", null));
    assertThrows(
        BusinessException.class, () -> delivery.transition(o, new Transition("PICKUP", "bad")));
    delivery.transition(o, new Transition("PICKUP", code));
    delivery.transition(o, new Transition("PICKUP", code));
    assertEquals(
        0,
        db.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE order_id=?", Integer.class, o));
  }

  @Test
  void riderTaskVisibilityClaimReassignmentAndCode() {
    var c = paid();
    long o = id(orders(c).getFirst(), "id");
    String code = checkouts.code(o);
    owner(1);
    delivery.transition(o, new Transition("ACCEPT", null));
    delivery.transition(o, new Transition("START", null));
    delivery.transition(o, new Transition("READY", null));
    platform();
    long rider = catalog.rider(new RiderInput("rider_test", "rider-test-password", List.of(2L)));
    long other = catalog.rider(new RiderInput("rider_other", "rider-other-password", List.of(2L)));
    UserContext.set(rider, null, "RIDER", null);
    var t = delivery.tasks().getFirst();
    assertFalse(t.containsKey("recipient"));
    long task = id(t, "id");
    delivery.claim(task, 1);
    assertTrue(delivery.tasks().getFirst().containsKey("recipient"));
    delivery.riderTransition(task, new Transition("COLLECT", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> delivery.riderTransition(task, new Transition("RELEASE", null)));
    delivery.riderTransition(task, new Transition("EXCEPTION", "车辆故障"));
    platform();
    long version = id(one(db, "SELECT version FROM delivery_task WHERE id=?", task), "version");
    delivery.assign(task, new AssignInput(other, version, "接续配送"));
    UserContext.set(rider, null, "RIDER", null);
    assertThrows(
        BusinessException.class,
        () -> delivery.riderTransition(task, new Transition("DELIVER", code)));
    UserContext.set(other, null, "RIDER", null);
    delivery.riderTransition(task, new Transition("DELIVER", code));
    assertEquals(
        "COMPLETED",
        one(db, "SELECT fulfillment_status FROM orders WHERE id=?", o).get("fulfillment_status"));
  }

  @Test
  void customerOwnershipAndHttpRolesAreEnforced() throws Exception {
    add(101, 1);
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    UserContext.set(2L, null, "CUSTOMER", null);
    assertThrows(BusinessException.class, () -> checkouts.detail((String) c.get("id")));
    assertThrows(
        IllegalArgumentException.class, () -> checkouts.cancel(id(orders(c).getFirst(), "id")));
    customer();
    String token = (String) auth.login("demo", "123456").get("token");
    mvc.perform(get("/cart").header("Authorization", token)).andExpect(status().isOk());
    mvc.perform(get("/delivery-tasks").header("Authorization", token))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/platform/commerce-report").header("Authorization", token))
        .andExpect(status().isUnauthorized());
    platform();
    catalog.rider(new RiderInput("rider_http", "http-rider-password", List.of(2L)));
    String riderToken = (String) auth.adminLogin("rider_http", "http-rider-password").get("token");
    mvc.perform(get("/admin/commerce-report").header("Authorization", riderToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void concurrentConfirmationLeavesOneCheckoutAndNoNegativeStock() throws Exception {
    add(101, 1);
    db.update("UPDATE dish SET stock=1 WHERE id=101");
    var q = quote(List.of(1L), Map.of(), null, Map.of());
    var executor = Executors.newFixedThreadPool(8);
    try {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  customer();
                  try {
                    return (String) confirm(q).get("id");
                  } finally {
                    UserContext.clear();
                  }
                }));
      start.countDown();
      var ids = new HashSet<String>();
      for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS));
      assertEquals(1, ids.size());
      assertEquals(0, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
      assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM checkout", Integer.class));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void centAllocationAndRecommendationUseActualFees() {
    assertEquals(
        List.of(money("0.34"), money("0.33"), money("0.33")),
        allocate(money("1"), List.of(money("1"), money("1"), money("1"))));
    var r =
        recommendations.recommend(
            new CampusRecommendationService.Request(
                2, address, "DELIVERY", money("30"), List.of("米饭", "奶茶"), Map.of(), null));
    assertFalse(((List<?>) r.get("candidates")).isEmpty());
    var r2 =
        recommendations.recommend(
            new CampusRecommendationService.Request(
                2, address, "DELIVERY", money("29.99"), List.of("米饭", "奶茶"), Map.of(), null));
    assertTrue(((List<?>) r2.get("candidates")).isEmpty());
    assertTrue(cart.list().isEmpty());
  }

  @Test
  void campusAgentPreparationChecksServiceAreaAndOwnedAddress() {
    var item = new CartInput("SKU", sku(101), 1, "", null);
    db.update("DELETE FROM delivery_rule WHERE merchant_id=1 AND campus_id=2");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            recommendations.prepareCart(
                new CampusRecommendationService.PrepareRequest(2, address, "DELIVERY", item)));
    assertTrue(cart.list().isEmpty());
    db.update("INSERT INTO delivery_rule(merchant_id,campus_id,fee) VALUES(1,2,3)");
    UserContext.set(2L, null, "CUSTOMER", null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            recommendations.prepareCart(
                new CampusRecommendationService.PrepareRequest(2, address, "DELIVERY", item)));
    assertTrue(cart.list().isEmpty());
    customer();
    recommendations.prepareCart(
        new CampusRecommendationService.PrepareRequest(2, address, "DELIVERY", item));
    assertEquals(1, cart.list().size());
  }

  @Test
  void campusAgentPreparationRejectsStaleVersionAfterWaitingForConcurrentCartWrite()
      throws Exception {
    long product = sku(101);
    add(101, 1);
    long version = id(cart.list().getFirst(), "version");
    var executor = Executors.newSingleThreadExecutor();
    var preparation = new ArrayList<Future<Long>>();
    try (var observer =
        java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                one(db, "SELECT id FROM user WHERE id=1 FOR UPDATE");
                preparation.add(
                    executor.submit(
                        () -> {
                          customer();
                          try {
                            return recommendations.prepareCart(
                                new CampusRecommendationService.PrepareRequest(
                                    2,
                                    address,
                                    "DELIVERY",
                                    new CartInput("SKU", product, 2, "", version)));
                          } finally {
                            UserContext.clear();
                          }
                        }));
                assertTrue(
                    assertDoesNotThrow(() -> waitingOnTable(observer, "user")),
                    "Agent preparation must be waiting on the real user row lock");
                // Commit a newer cart while preparation is blocked. Its pre-lock reads must
                // not establish an older REPEATABLE READ snapshot that bypasses version checks.
                cart.save(new CartInput("SKU", product, 3, "concurrent update", version));
              });
      var failure =
          assertThrows(
              ExecutionException.class,
              () -> preparation.getFirst().get(10, TimeUnit.SECONDS));
      assertInstanceOf(IllegalArgumentException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("版本"));
      var item = cart.list().getFirst();
      assertEquals(3, num(item, "quantity"));
      assertEquals("concurrent update", item.get("remark"));
      assertEquals(version + 1, id(item, "version"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void campusAgentPreparationChecksPickupCampusAndExistingCartVersion() {
    var item = new CartInput("SKU", sku(101), 1, "", null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            recommendations.prepareCart(
                new CampusRecommendationService.PrepareRequest(2, null, "PICKUP", item)));
    assertTrue(cart.list().isEmpty());
    recommendations.prepareCart(
        new CampusRecommendationService.PrepareRequest(1, null, "PICKUP", item));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            recommendations.prepareCart(
                new CampusRecommendationService.PrepareRequest(1, null, "PICKUP", item)));
    assertEquals(1, cart.list().size());
  }

  private List<Boolean> race(List<Callable<Boolean>> actions) throws Exception {
    var pool = Executors.newFixedThreadPool(actions.size());
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Boolean>>();
      for (var a : actions)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return a.call();
                  } finally {
                    UserContext.clear();
                  }
                }));
      start.countDown();
      var results = new ArrayList<Boolean>();
      for (var f : futures) results.add(f.get(20, TimeUnit.SECONDS));
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void unrelatedCustomersCanInsertTheirFirstCartConcurrently() throws Exception {
    var actions = new ArrayList<Callable<Boolean>>();
    for (long i = 1; i <= 8; i++) {
      long u = i;
      if (i > 2)
        db.update(
            "INSERT IGNORE INTO user(id,username,password,nickname,created_at) SELECT"
                + " ?,?,password,'并发购物车',NOW() FROM user WHERE id=1",
            i,
            "cart_race_" + i);
      actions.add(
          () -> {
            UserContext.set(u, null, "CUSTOMER", null);
            cart.save(new CartInput("SKU", sku(101), 1, "", null));
            return true;
          });
    }
    assertTrue(race(actions).stream().allMatch(Boolean::booleanValue));
    assertEquals(8, db.queryForObject("SELECT COUNT(*) FROM cart_item", Integer.class));
  }

  private boolean confirmAs(long u, Quote q) {
    UserContext.set(u, null, "CUSTOMER", null);
    try {
      confirm(q);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private Quote secondUserQuote(Long slot) {
    UserContext.set(2L, null, "CUSTOMER", null);
    long a = catalog.saveAddress(null, new AddressInput(2, "另一位同学", "13900000000", "二号楼", null));
    add(101, 1);
    var q =
        quotes.quote(
            new QuoteInput(
                List.of(1L),
                a,
                2,
                "DELIVERY",
                Map.of(),
                null,
                slot == null ? Map.of() : Map.of(1L, slot)));
    customer();
    return q;
  }

  @Test
  void differentUsersCompeteForLastSkuWithoutOverselling() throws Exception {
    db.update("UPDATE dish SET stock=1 WHERE id=101");
    add(101, 1);
    var first = quote(List.of(1L), Map.of(), null, Map.of());
    var second = secondUserQuote(null);
    var result = race(List.of(() -> confirmAs(1, first), () -> confirmAs(2, second)));
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
    assertEquals(0, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM checkout", Integer.class));
  }

  @Test
  void lastReservationCapacityIsReservedByOneCheckout() throws Exception {
    owner(1);
    var now = BusinessTime.now();
    long slot =
        delivery.slot(
            new SlotInput(now.plusMinutes(20), now.plusMinutes(40), now.plusMinutes(10), 30, 1));
    customer();
    add(101, 1);
    var first = quote(List.of(1L), Map.of(), null, Map.of(1L, slot));
    var second = secondUserQuote(slot);
    var result = race(List.of(() -> confirmAs(1, first), () -> confirmAs(2, second)));
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT reserved_count FROM reservation_slot WHERE id=?", Integer.class, slot));
    assertEquals(9, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
  }

  @Test
  void lastCouponAndRiderTaskCannotBeClaimedTwice() throws Exception {
    platform();
    var now = BusinessTime.now();
    long campaign =
        coupons.create(
            new CouponInput(
                "最后一张",
                ZERO,
                money("1"),
                1,
                now.minusMinutes(1),
                now.plusHours(1),
                now.minusMinutes(1),
                now.plusHours(2)));
    var result =
        race(
            List.of(
                () -> {
                  customer();
                  try {
                    coupons.claim(campaign);
                    return true;
                  } catch (IllegalArgumentException e) {
                    return false;
                  }
                },
                () -> {
                  UserContext.set(2L, null, "CUSTOMER", null);
                  try {
                    coupons.claim(campaign);
                    return true;
                  } catch (IllegalArgumentException e) {
                    return false;
                  }
                }));
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT claimed_count FROM coupon_campaign WHERE id=?", Integer.class, campaign));
    customer();
    var c = paid();
    long order = id(orders(c).getFirst(), "id");
    owner(1);
    for (String action : List.of("ACCEPT", "START", "READY"))
      delivery.transition(order, new Transition(action, null));
    platform();
    long a = catalog.rider(new RiderInput("rider_race_a", "rider-race-password", List.of(2L))),
        b = catalog.rider(new RiderInput("rider_race_b", "rider-race-password", List.of(2L)));
    long task = id(one(db, "SELECT id FROM delivery_task WHERE order_id=?", order), "id");
    result =
        race(
            List.of(
                () -> {
                  UserContext.set(a, null, "RIDER", null);
                  try {
                    delivery.claim(task, 1);
                    return true;
                  } catch (IllegalArgumentException e) {
                    return false;
                  }
                },
                () -> {
                  UserContext.set(b, null, "RIDER", null);
                  try {
                    delivery.claim(task, 1);
                    return true;
                  } catch (IllegalArgumentException e) {
                    return false;
                  }
                }));
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        "ASSIGNED", one(db, "SELECT status FROM delivery_task WHERE id=?", task).get("status"));
  }

  @Test
  void timeoutPaymentRaceAndCommittedEventReplayAreConsistent() throws Exception {
    add(101, 1);
    var c = confirm(quote(List.of(1L), Map.of(), null, Map.of()));
    String id = (String) c.get("id");
    db.update("UPDATE checkout SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?", id);
    race(
        List.of(
            () -> {
              customer();
              try {
                checkouts.pay(id);
                return true;
              } catch (IllegalArgumentException e) {
                return false;
              }
            },
            () -> {
              checkouts.expire();
              return true;
            }));
    assertEquals("CLOSED", one(db, "SELECT status FROM checkout WHERE id=?", id).get("status"));
    assertEquals(10, db.queryForObject("SELECT stock FROM dish WHERE id=101", Integer.class));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM commerce_payment", Integer.class));
    long before = db.queryForObject("SELECT COALESCE(MAX(id),0) FROM commerce_event", Long.class);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            s -> {
              events.record(1L, null, null, "ROLLBACK_TEST", "none");
              s.setRollbackOnly();
            });
    assertTrue(events.replay(1, false, before).isEmpty());
    assertTrue(events.replay(2, false, 0).isEmpty());
  }
}
