package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;
import static org.junit.jupiter.api.Assertions.*;
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
  @Autowired CampusCatalogService catalog;
  @Autowired CommerceCartService cart;
  @Autowired CommerceQuoteService quotes;
  @Autowired CommerceCheckoutService checkouts;
  @Autowired CommerceCouponService coupons;
  @Autowired CommerceAftersaleService refunds;
  @Autowired CommerceFulfillmentService delivery;
  @Autowired CommerceExperienceService experience;
  @Autowired CommerceEventService events;
  @Autowired OrderTransactionService legacy;
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
  @Test void unrelatedCustomersCanInsertTheirFirstCartConcurrently() throws Exception {
    var actions=new ArrayList<Callable<Boolean>>();
    for(long i=1;i<=8;i++){long u=i;if(i>2)db.update("INSERT IGNORE INTO user(id,username,password,nickname,created_at) SELECT ?,?,password,'并发购物车',NOW() FROM user WHERE id=1",i,"cart_race_"+i);actions.add(()->{UserContext.set(u,null,"CUSTOMER",null);cart.save(new CartInput("SKU",sku(101),1,"",null));return true;});}
    assertTrue(race(actions).stream().allMatch(Boolean::booleanValue));assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM cart_item",Integer.class));
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
