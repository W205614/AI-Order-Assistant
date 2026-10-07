package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ai.assistant.dto.ConfirmDraftDTO;
import com.ai.assistant.model.*;
import com.ai.assistant.model.Order;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real MySQL/Flyway transactions; no mocks of stock, rows or rollback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = false)
class OrderServiceMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("orders_test")
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
    r.add("app.platform-bootstrap.username", () -> "");
    r.add("app.platform-bootstrap.password", () -> "");
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Autowired OrderService orders;
  @Autowired OrderSafetyService safety;
  @Autowired InventoryService inventory;
  @Autowired MenuService menu;
  @Autowired MerchantService merchants;
  @Autowired AuthService auth;
  @Autowired OrderTransactionService transactions;
  @Autowired OrderStatusEventBroker events;
  @Autowired AuditService audit;
  @Autowired UserPreferenceService preferences;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    UserContext.set(1L, null, "CUSTOMER", 1L);
    for (String table :
        List.of(
            "payment_record",
            "inventory_ledger",
            "order_event",
            "audit_log",
            "order_item",
            "order_draft_item",
            "order_draft",
            "orders",
            "user_order_sequence",
            "user_food_preference",
            "order_safety_context",
            "revoked_token",
            "auth_rate_window")) jdbc.update("DELETE FROM " + table);
    jdbc.update(
        "UPDATE dish SET status=1,stock=10,version=1,stock_version=1,allergen_reviewed=FALSE WHERE"
            + " merchant_id=1");
    jdbc.update("UPDATE dish SET price=18.00,allergens=NULL WHERE merchant_id=1 AND name='鱼香肉丝饭'");
    jdbc.update("DELETE FROM dish WHERE merchant_id<>1");
    jdbc.update("DELETE FROM admin_user WHERE username<>'admin'");
    jdbc.update(
        "UPDATE admin_user SET merchant_id=1,role='OWNER',enabled=TRUE,token_version=1 WHERE"
            + " username='admin'");
    jdbc.update("DELETE FROM merchant WHERE id<>1");
    jdbc.update(
        "UPDATE merchant SET"
            + " enabled=TRUE,accepting_orders=TRUE,opens_at='00:00:00',closes_at='00:00:00',delivery_regions='校园',version=1"
            + " WHERE id=1");
    jdbc.update("UPDATE user SET enabled=TRUE,token_version=1 WHERE id=1");
    jdbc.update(
        "INSERT IGNORE INTO user(id,username,password,nickname,created_at) SELECT"
            + " 2,'second',password,'用户二',NOW() FROM user WHERE id=1");
    jdbc.update("UPDATE user SET enabled=TRUE,token_version=1 WHERE id=2");
    jdbc.update("INSERT INTO merchant(id,name) VALUES(2,'第二商户')");
    jdbc.update(
        "INSERT INTO admin_user(username,password,merchant_id,role,created_at) SELECT"
            + " 'staff',password,1,'STAFF',NOW() FROM admin_user WHERE username='admin'");
    jdbc.update(
        "INSERT INTO admin_user(username,password,merchant_id,role,created_at) SELECT"
            + " 'owner2',password,2,'OWNER',NOW() FROM admin_user WHERE username='admin'");
    jdbc.update(
        "INSERT INTO dish(merchant_id,name,price,category,stock,status,allergen_reviewed)"
            + " VALUES(2,'另一家菜品',10,'热菜',10,1,TRUE)");
  }

  @AfterEach
  void clear() {
    UserContext.clear();
  }

  OrderItem item(String name, int quantity) {
    var i = new OrderItem();
    i.setDishName(name);
    i.setQuantity(quantity);
    return i;
  }

  OrderDraft draft(long user) {
    return orders.createOrderDraft(user, List.of(item("鱼香肉丝饭", 1)), "少辣");
  }

  ConfirmDraftDTO input(OrderDraft d) {
    return new ConfirmDraftDTO(d.getVersion(), "测试用户", "13800000000", "一号楼", "校园");
  }

  Order confirm(long user, OrderDraft d, String key) {
    return orders.confirmDraft(user, d.getId(), key, input(d));
  }

  void owner() {
    UserContext.set(1L, null, "OWNER", 1L);
  }

  String userToken() {
    return (String) auth.login("demo", "123456").get("token");
  }

  String staffToken(String name) {
    return (String) auth.adminLogin(name, "admin123").get("token");
  }

  <T> T in(long tenant, Callable<T> action) throws Exception {
    UserContext.set(1L, null, "CUSTOMER", tenant);
    try {
      return action.call();
    } finally {
      UserContext.clear();
    }
  }

  @Test
  void confirmsExactlyOnceAndRejectsDifferentKey() {
    var d = draft(1);
    var first = confirm(1, d, "same-key-001");
    assertEquals(0, first.getStatus());
    assertEquals(first.getId(), confirm(1, d, "same-key-001").getId());
    assertThrows(BusinessException.class, () -> confirm(1, d, "other-key-001"));
    assertFalse(orders.getOwnOrder(2L, first.getUserSeq()).isPresent());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
  }

  @Test
  void bindsKeyToDraftVersionTenantAndReceipt() {
    var d = draft(1);
    confirm(1, d, "bound-key-001");
    var other = draft(1);
    assertThrows(BusinessException.class, () -> confirm(1, other, "bound-key-001"));
    assertThrows(
        BusinessException.class,
        () ->
            orders.confirmDraft(
                1L,
                d.getId(),
                "bound-key-001",
                new ConfirmDraftDTO(1L, "别人", "13800000000", "一号楼", "校园")));
    UserContext.within(
        2L,
        () -> {
          var b = orders.createOrderDraft(1L, List.of(item("另一家菜品", 1)), null);
          assertThrows(BusinessException.class, () -> confirm(1, b, "bound-key-001"));
          return null;
        });
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
  }

  @Test
  void oldDraftVersionCannotUpdateOrConfirm() {
    var d = draft(1);
    var updated =
        orders.updateOrderDraft(1L, d.getId(), List.of(item("鱼香肉丝饭", 2)), null, d.getVersion());
    assertEquals(2L, updated.getVersion());
    assertThrows(BusinessException.class, () -> confirm(1, d, "stale-key-001"));
    assertThrows(
        BusinessException.class,
        () -> orders.updateOrderDraft(1L, d.getId(), d.getItems(), null, d.getVersion()));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
  }

  @Test
  void priceChangePersistsNewQuoteWithoutReservingStock() {
    var d = draft(1);
    owner();
    var dish = menu.find("鱼香肉丝饭").orElseThrow();
    dish.setPrice(new BigDecimal("19.00"));
    menu.update(dish.getId(), dish);
    var conflict = assertThrows(RequoteException.class, () -> confirm(1, d, "quote-key-001"));
    var latest = (OrderDraft) conflict.data();
    assertEquals(2L, latest.getVersion());
    assertEquals(new BigDecimal("19.00"), latest.getTotalAmount());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    assertEquals(10, menu.find(dish.getId()).orElseThrow().getStock());
    var persisted = orders.listPendingDrafts(1L).getFirst();
    assertEquals(2L, persisted.getVersion());
    assertEquals(new BigDecimal("19.00"), confirm(1, persisted, "quote-key-002").getTotalAmount());
  }

  @Test
  void allergenChangesAreRecheckedUnderMenuLock() {
    safety.set(1, List.of("花生"));
    jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='鱼香肉丝饭'");
    var d = draft(1);
    jdbc.update("UPDATE dish SET allergens='花生',version=version+1 WHERE name='鱼香肉丝饭'");
    assertThrows(IllegalArgumentException.class, () -> confirm(1, d, "allergy-key-001"));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
  }

  @Test
  void unsupportedAndUnreviewedAllergensFailClosed() {
    jdbc.update("INSERT INTO user_food_preference(user_id,allergens) VALUES(1,'芝麻')");
    jdbc.update("UPDATE dish SET allergen_reviewed=TRUE");
    assertTrue(
        ((List<?>) orders.listDishesForUser(1L, null, null, true, 1, 50).get("items")).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> draft(1));
    jdbc.update("UPDATE user_food_preference SET allergens='花生'");
    jdbc.update("UPDATE dish SET allergen_reviewed=FALSE");
    assertThrows(IllegalArgumentException.class, () -> draft(1));
  }

  @Test
  void temporaryConstraintNeedsSelectionAndIsTenantScoped() {
    assertTrue(safety.recordFromMessage(1, "花生过敏").needsClarification());
    assertThrows(IllegalArgumentException.class, () -> draft(1));
    UserContext.within(
        2L,
        () -> {
          assertFalse(safety.current(1).active());
          return null;
        });
    safety.set(1, List.of("花生"));
    jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='鱼香肉丝饭'");
    var d = draft(1);
    assertThrows(IllegalArgumentException.class, () -> safety.clearByUser(1));
    orders.cancelOrderDraft(1L, d.getId());
    assertFalse(safety.current(1).active());
  }

  @Test
  void snapshotProtectsConfirmationAfterContextExpiry() {
    safety.set(1, List.of("花生"));
    jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='鱼香肉丝饭'");
    var d = draft(1);
    jdbc.update("UPDATE order_safety_context SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE)");
    jdbc.update("UPDATE dish SET allergens='花生' WHERE name='鱼香肉丝饭'");
    assertThrows(IllegalArgumentException.class, () -> confirm(1, d, "snapshot-key-001"));
  }

  @Test
  void onePendingDraftPerUserAndMerchantUnderConcurrency() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var tasks = pool.invokeAll(List.of(() -> in(1, () -> draft(1)), () -> in(1, () -> draft(1))));
      for (var f : tasks) f.get();
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_draft WHERE merchant_id=1 AND status=1", Integer.class));
    UserContext.within(
        2L,
        () -> {
          orders.createOrderDraft(1L, List.of(item("另一家菜品", 1)), null);
          return null;
        });
    assertEquals(
        2, jdbc.queryForObject("SELECT COUNT(*) FROM order_draft WHERE status=1", Integer.class));
  }

  @Test
  void lastStockCannotBeOversold() throws Exception {
    var a = draft(1);
    var b = draft(2);
    jdbc.update("UPDATE dish SET stock=1 WHERE name='鱼香肉丝饭'");
    int successful = 0;
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Callable<Boolean>> calls =
          List.of(
              () ->
                  in(
                      1,
                      () -> {
                        try {
                          confirm(1, a, "last-stock-a");
                          return true;
                        } catch (IllegalArgumentException e) {
                          return false;
                        }
                      }),
              () ->
                  in(
                      1,
                      () -> {
                        try {
                          confirm(2, b, "last-stock-b");
                          return true;
                        } catch (IllegalArgumentException e) {
                          return false;
                        }
                      }));
      for (var f : pool.invokeAll(calls)) if (f.get()) successful++;
    }
    assertEquals(1, successful);
    assertEquals(0, menu.find("鱼香肉丝饭").orElseThrow().getStock());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
  }

  @Test
  void concurrentConfirmRetriesCreateOneOrder() throws Exception {
    var d = draft(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Callable<Order>> calls =
          List.of(
              () -> in(1, () -> confirm(1, d, "parallel-key-001")),
              () -> in(1, () -> confirm(1, d, "parallel-key-001")));
      var results = pool.invokeAll(calls);
      assertEquals(results.get(0).get().getId(), results.get(1).get().getId());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE kind='RESERVE'", Integer.class));
  }

  @Test
  void repeatedPaidCancellationReleasesAndRefundsOnce() {
    var o = confirm(1, draft(1), "refund-key-001");
    assertEquals("SIMULATED_PAID", orders.simulatePayment(o.getUserSeq(), 1L).getPaymentStatus());
    orders.simulatePayment(o.getUserSeq(), 1L);
    orders.cancelOrder(o.getUserSeq(), 1L);
    var retried = orders.cancelOrder(o.getUserSeq(), 1L);
    assertEquals("SIMULATED_REFUNDED", retried.getPaymentStatus());
    assertEquals(10, menu.find("鱼香肉丝饭").orElseThrow().getStock());
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM payment_record", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE kind='RELEASE'", Integer.class));
  }

  @Test
  void cancelAndTimeoutRaceReleaseOnlyOnce() throws Exception {
    var o = confirm(1, draft(1), "timeout-key-001");
    jdbc.update(
        "UPDATE orders SET payment_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?",
        o.getId());
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Callable<Void>> calls =
          List.of(
              () ->
                  in(
                      1,
                      () -> {
                        transactions.expire(o.getId());
                        return null;
                      }),
              () ->
                  in(
                      1,
                      () -> {
                        orders.cancelOrder(o.getUserSeq(), 1L);
                        return null;
                      }));
      for (var f : pool.invokeAll(calls)) f.get();
    }
    transactions.expire(o.getId());
    assertEquals(10, menu.find("鱼香肉丝饭").orElseThrow().getStock());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE kind='RELEASE'", Integer.class));
    assertThrows(BusinessException.class, () -> orders.simulatePayment(o.getUserSeq(), 1L));
  }

  @Test
  void paymentAndCancellationRaceHasOneLegalOutcome() throws Exception {
    var o = confirm(1, draft(1), "pay-race-key");
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Callable<Void>> calls =
          List.of(
              () ->
                  in(
                      1,
                      () -> {
                        try {
                          orders.simulatePayment(o.getUserSeq(), 1L);
                        } catch (BusinessException ignored) {
                        }
                        return null;
                      }),
              () ->
                  in(
                      1,
                      () -> {
                        orders.cancelOrder(o.getUserSeq(), 1L);
                        return null;
                      }));
      for (var f : pool.invokeAll(calls)) f.get();
    }
    var finalOrder = orders.getOrder(o.getId()).orElseThrow();
    assertEquals(5, finalOrder.getStatus());
    assertTrue(Set.of("CLOSED", "SIMULATED_REFUNDED").contains(finalOrder.getPaymentStatus()));
    assertEquals(10, menu.find("鱼香肉丝饭").orElseThrow().getStock());
  }

  @Test
  void expiredOrderRejectsLatePayment() {
    var o = confirm(1, draft(1), "late-pay-key");
    jdbc.update(
        "UPDATE orders SET payment_expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",
        o.getId());
    assertThrows(BusinessException.class, () -> orders.simulatePayment(o.getUserSeq(), 1L));
    transactions.expire(o.getId());
    assertEquals(6, orders.getOrder(o.getId()).orElseThrow().getStatus());
  }

  @Test
  void staffTransitionsCannotSkipAndPreparationBlocksCancellation() {
    var o = confirm(1, draft(1), "status-key-001");
    owner();
    assertThrows(BusinessException.class, () -> orders.updateOrderStatus(o.getId(), 2));
    orders.simulatePayment(o.getUserSeq(), 1L);
    orders.updateOrderStatus(o.getId(), 2);
    assertThrows(BusinessException.class, () -> orders.cancelOrder(o.getUserSeq(), 1L));
    assertThrows(BusinessException.class, () -> orders.updateOrderStatus(o.getId(), 4));
    orders.updateOrderStatus(o.getId(), 3);
    orders.updateOrderStatus(o.getId(), 4);
    assertEquals(new BigDecimal("18.00"), orders.stats().get("completedSimulatedAmount"));
  }

  @Test
  void independentStockVersionCannotOverwriteReservation() {
    owner();
    var dish = menu.find("鱼香肉丝饭").orElseThrow();
    var o = confirm(1, draft(1), "adjust-key-001");
    assertThrows(
        BusinessException.class,
        () ->
            inventory.adjust(
                dish.getId(), new InventoryService.Adjustment(5, 1L, "adjustment-key")));
    var fresh = menu.find(dish.getId()).orElseThrow();
    var adjusted =
        inventory.adjust(
            dish.getId(),
            new InventoryService.Adjustment(5, fresh.getStockVersion(), "adjustment-key"));
    assertEquals(14, adjusted.getStock());
    assertEquals(
        14,
        inventory
            .adjust(
                dish.getId(),
                new InventoryService.Adjustment(5, fresh.getStockVersion(), "adjustment-key"))
            .getStock());
    assertThrows(
        BusinessException.class,
        () ->
            inventory.adjust(
                dish.getId(),
                new InventoryService.Adjustment(6, fresh.getStockVersion(), "adjustment-key")));
    dish.setStock(100);
    assertThrows(IllegalArgumentException.class, () -> menu.update(dish.getId(), dish));
    orders.cancelOrder(o.getUserSeq(), 1L);
    assertEquals(15, menu.find(dish.getId()).orElseThrow().getStock());
  }

  @Test
  void eventsAuditStatisticsAndDetailsStayInsideMerchant() {
    var d = draft(1);
    var o = confirm(1, d, "isolation-key");
    assertEquals(1, events.replay(1L, 0).size());
    UserContext.within(
        2L,
        () -> {
          assertTrue(orders.getOrder(o.getId()).isEmpty());
          assertEquals(0L, ((Number) orders.stats().get("orders")).longValue());
          assertTrue(events.replay(null, 0).isEmpty());
          assertTrue(audit.list(2L, Long.MAX_VALUE).isEmpty());
          assertThrows(BusinessException.class, () -> confirm(1, d, "isolation-key"));
          assertTrue(menu.find(d.getItems().getFirst().getDishId()).isEmpty());
          return null;
        });
    assertTrue(events.replay(2L, 0).isEmpty());
  }

  @Test
  void roleAndTenantTamperingAreForbiddenOverHttp() throws Exception {
    String user = userToken(), staff = staffToken("staff"), owner2 = staffToken("owner2");
    mvc.perform(get("/admin/orders").header("Authorization", user))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/admin/staff").header("Authorization", staff))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/dishes").header("Authorization", staff))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/orders").header("Authorization", owner2).header("X-Merchant-Id", "1"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/dish/list").header("Authorization", user).header("X-Merchant-Id", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].name").value("另一家菜品"));
  }

  @Test
  void browserCookieIsHttpOnlyAndCsrfProtectsWrites() throws Exception {
    mvc.perform(
            post("/auth/login")
                .contentType("application/json")
                .content("{\"username\":\"demo\",\"password\":\"123456\"}"))
        .andExpect(status().isForbidden());
    var response =
        mvc.perform(
                post("/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"username\":\"demo\",\"password\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andReturn()
            .getResponse();
    String cookie =
        response.getHeaders("Set-Cookie").stream()
            .filter(c -> c.startsWith("ao_user="))
            .findFirst()
            .orElseThrow();
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Lax"));
    var session =
        new jakarta.servlet.http.Cookie("ao_user", cookie.split(";", 2)[0].split("=", 2)[1]);
    mvc.perform(
            post("/order/drafts")
                .cookie(session)
                .header("X-Merchant-Id", "1")
                .contentType("application/json")
                .content("{\"items\":[{\"dishId\":1,\"quantity\":1}]}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/order/list").cookie(session).header("X-Merchant-Id", "1"))
        .andExpect(status().isOk());
  }

  @Test
  void logoutPasswordAndDisabledAccountsRevokeTokens() {
    String token = userToken();
    UserContext.set(1L, token, "CUSTOMER", 1L);
    auth.logout();
    assertThrows(AuthException.class, () -> auth.validate(false, token));
    String old = userToken();
    auth.changePassword("123456", "NewPassword123!");
    assertThrows(AuthException.class, () -> auth.validate(false, old));
    // Restore fixture password for subsequent tests.
    jdbc.update(
        "UPDATE user SET password=? WHERE id=1",
        jdbc.queryForObject("SELECT password FROM user WHERE id=2", String.class));
    String fresh = userToken();
    jdbc.update("UPDATE user SET enabled=FALSE WHERE id=1");
    assertThrows(AuthException.class, () -> auth.validate(false, fresh));
  }

  @Test
  void realBrowserCsrfSurvivesRepeatedJwtAuthentication() throws Exception {
    var userCookie = new jakarta.servlet.http.Cookie("ao_user", userToken());
    var response =
        mvc.perform(get("/auth/csrf").cookie(userCookie))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String raw =
        com.alibaba
            .fastjson2
            .JSON
            .parseObject(response.getContentAsString())
            .getJSONObject("data")
            .getString("token");
    var csrfCookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", raw);
    for (int i = 0; i < 3; i++) {
      mvc.perform(get("/auth/me").cookie(userCookie, csrfCookie)).andExpect(status().isOk());
      mvc.perform(
              post("/order/drafts")
                  .cookie(userCookie, csrfCookie)
                  .header("X-XSRF-TOKEN", raw)
                  .header("X-Merchant-Id", "1")
                  .contentType("application/json")
                  .content("{\"items\":[{\"dishId\":1,\"quantity\":1}]}"))
          .andExpect(status().isOk());
    }
  }

  @Test
  void deadlineStopsInternalBusinessWriteOverHttp() throws Exception {
    mvc.perform(
            post("/order/drafts")
                .header("Authorization", userToken())
                .header("X-Merchant-Id", "1")
                .header("X-Agent-Internal-Key", "i".repeat(32))
                .header("X-Agent-Deadline", String.valueOf(System.currentTimeMillis() - 100))
                .contentType("application/json")
                .content("{\"items\":[{\"dishId\":1,\"quantity\":1}]}"))
        .andExpect(status().isGatewayTimeout());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_draft", Integer.class));
  }

  @Test
  void businessDayPaginationAndBatchItemsAreConsistent() {
    var o = confirm(1, draft(1), "page-key-001");
    jdbc.update("UPDATE orders SET create_time='2026-10-01 00:00:00' WHERE id=?", o.getId());
    var page = orders.listOrders(1L, 0, "2026-10-01", "2026-10-01", 1, 10);
    assertEquals(1L, page.getTotal());
    assertEquals(1, page.getItems().getFirst().getItems().size());
    assertEquals(0L, orders.listOrders(2L, null, null, null, 1, 10).getTotal());
  }

  @Test
  void closedMerchantAndInvalidRegionCannotCreateOrders() {
    var d = draft(1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            orders.confirmDraft(
                1L,
                d.getId(),
                "region-key-001",
                new ConfirmDraftDTO(1L, "用户", "13800000000", "楼", "其他区域")));
    jdbc.update("UPDATE merchant SET accepting_orders=FALSE WHERE id=1");
    assertThrows(IllegalArgumentException.class, () -> confirm(1, d, "closed-key-001"));
    assertThrows(IllegalArgumentException.class, () -> draft(1));
  }

  @Test
  void reminderHasCooldownAndPersistentEvent() {
    var o = confirm(1, draft(1), "remind-key-001");
    orders.simulatePayment(o.getUserSeq(), 1L);
    assertEquals(1, orders.remindOrder(o.getUserSeq(), 1L).getRemindCount());
    assertThrows(BusinessException.class, () -> orders.remindOrder(o.getUserSeq(), 1L));
    assertEquals(3, events.replay(1L, 0).size());
  }

  @Test
  void auditEventAndStockRollBackTogether() {
    var d = draft(1);
    new TransactionTemplate(manager)
        .execute(
            s -> {
              confirm(1, d, "rollback-key-001");
              s.setRollbackOnly();
              return null;
            });
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_event", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger", Integer.class));
    assertEquals(10, menu.find("鱼香肉丝饭").orElseThrow().getStock());
  }
}
