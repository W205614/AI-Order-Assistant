package com.ai.assistant.service;

import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderDraft;
import com.ai.assistant.model.OrderItem;
import com.ai.assistant.vo.OrderPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Real MySQL regression for the transaction boundaries that mocks cannot prove. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceMySqlIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ai_order_assistant_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // Spring resolves dynamic properties while preparing its context, before the
        // JUnit Testcontainers extension starts @Container fields. Start here so
        // getJdbcUrl() never reads an unstarted container in CI.
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.demo-seed.enabled", () -> "true");
        registry.add("spring.cache.type", () -> "none");
        registry.add("auth.user-secret-key", () -> "u".repeat(32));
        registry.add("auth.admin-secret-key", () -> "a".repeat(32));
        registry.add("ai.internal-api-key", () -> "i".repeat(32));
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderSafetyService safetyService;
    @Autowired
    private UserPreferenceService preferenceService;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private com.ai.assistant.security.AuthService authService;
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM order_draft_item");
        jdbc.update("DELETE FROM order_draft");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM user_order_sequence");
        jdbc.update("DELETE FROM user_food_preference");
        jdbc.update("DELETE FROM order_safety_context");
        jdbc.update("UPDATE dish SET status=1, stock=10");
        jdbc.update("UPDATE dish SET allergen_reviewed=FALSE");
        jdbc.update("INSERT IGNORE INTO user(id,username,password,nickname,created_at) VALUES (2,'integration-user','x','集成测试用户',NOW())");
    }

    @Test
    void confirmsDraftOnceAndHidesItFromAnotherUser() {
        OrderDraft draft = orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), "少辣");

        Order first = orderService.confirmDraft(1L, draft.getId(), "integration-idem-001");
        Order retried = orderService.confirmDraft(1L, draft.getId(), "integration-idem-002");

        assertEquals(first.getId(), retried.getId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertFalse(orderService.getOwnOrder(2L, first.getUserSeq()).isPresent());
        assertEquals(2, orderService.updateOrderStatus(first.getId(), 2).getStatus());
        assertThrows(IllegalArgumentException.class, () -> orderService.updateOrderStatus(first.getId(), 4));
    }

    @Test
    void blocksAllergenAtDraftCreationInRealDatabase() {
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='宫保鸡丁饭'");
        jdbc.update("INSERT INTO user_food_preference(user_id,allergens) VALUES (?,?)", 1L, "花生");
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createOrderDraft(1L, List.of(item("宫保鸡丁饭", 1)), null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_draft", Integer.class));
    }

    @Test
    void unknownSavedAllergenBlocksEvenReviewedDish() {
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='鱼香肉丝饭'");
        jdbc.update("INSERT INTO user_food_preference(user_id,allergens) VALUES (?,?)", 1L, "芝麻");
        assertTrue(((List<?>) orderService.listDishesForUser(1L, null, null, true, 1, 50).get("items")).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null));
    }

    @Test
    void temporaryAllergyNeedsExplicitSelectionAndRechecksAtConfirmation() {
        assertTrue(safetyService.recordFromMessage(1L, "这次我对花生过敏").needsClarification());
        int remainingSeconds = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND,NOW(),expires_at) FROM order_safety_context WHERE user_id=1", Integer.class);
        assertTrue(remainingSeconds > 1740 && remainingSeconds <= 1801,
                "Temporary constraints must expire in 30 minutes; actual seconds=" + remainingSeconds);
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null));
        safetyService.set(1L, List.of("花生"));
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null));
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name IN ('鱼香肉丝饭','宫保鸡丁饭')");
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createOrderDraft(1L, List.of(item("宫保鸡丁饭", 1)), null));
        OrderDraft safe = orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null);
        jdbc.update("UPDATE dish SET allergen_reviewed=FALSE WHERE name='鱼香肉丝饭'");
        assertThrows(IllegalArgumentException.class,
                () -> orderService.confirmDraft(1L, safe.getId(), "allergy-confirm-001"));
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='鱼香肉丝饭'");
        orderService.confirmDraft(1L, safe.getId(), "allergy-confirm-002");
        assertTrue(safetyService.current(1L).allergens().isEmpty());
    }

    @Test
    void savedAllergyUpdateWaitsForUserLockAndIsRecheckedBeforeConfirmation() throws Exception {
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='宫保鸡丁饭'");
        OrderDraft draft = orderService.createOrderDraft(1L, List.of(item("宫保鸡丁饭", 1)), null);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        safetyService.lockUser(1L);
                        locked.countDown();
                        try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    }));
            assertTrue(locked.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var saving = new java.util.concurrent.CountDownLatch(1);
            Future<?> saved = pool.submit(() -> {
                var preference = new com.ai.assistant.model.UserFoodPreference();
                preference.setAllergens("花生");
                saving.countDown();
                preferenceService.save(1L, preference);
            });
            assertTrue(saving.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> saved.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            saved.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,
                    () -> orderService.confirmDraft(1L, draft.getId(), "saved-allergy-confirm-001"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void temporaryConstraintsEndWithAbandonmentOrExpiryAndSnapshotStillProtectsConfirmation() {
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name='鱼香肉丝饭'");
        safetyService.set(1L, List.of("花生"));
        OrderDraft abandoned = orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null);
        orderService.cancelOrderDraft(1L, abandoned.getId());
        assertFalse(safetyService.current(1L).active());

        safetyService.set(1L, List.of("花生"));
        OrderDraft expired = orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null);
        jdbc.update("UPDATE order_draft SET expires_at=DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE id=?", expired.getId());
        jdbc.update("UPDATE order_safety_context SET expires_at=DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE user_id=1");
        assertTrue(orderService.listPendingDrafts(1L).isEmpty());
        assertFalse(safetyService.current(1L).active());
        assertThrows(IllegalArgumentException.class, () -> orderService.confirmDraft(1L, expired.getId(), "expired-confirm-001"));

        safetyService.set(1L, List.of("花生"));
        OrderDraft snapshot = orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null);
        safetyService.set(1L, List.of("鸡蛋"));
        jdbc.update("UPDATE dish SET allergens='花生' WHERE name='鱼香肉丝饭'");
        try {
            assertThrows(IllegalArgumentException.class, () -> orderService.confirmDraft(1L, snapshot.getId(), "snapshot-confirm-001"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        } finally {
            jdbc.update("UPDATE dish SET allergens='' WHERE name='鱼香肉丝饭'");
        }
    }

    @Test
    void concurrentDraftCreationLeavesExactlyOnePendingDraft() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<OrderDraft>> attempts = java.util.stream.IntStream.range(0, 32)
                    .<Callable<OrderDraft>>mapToObj(i -> () -> orderService.createOrderDraft(
                            1L, List.of(item(i % 2 == 0 ? "鱼香肉丝饭" : "番茄炒蛋饭", 1)), null)).toList();
            List<Future<OrderDraft>> results = pool.invokeAll(attempts);
            for (Future<OrderDraft> result : results) assertFalse(result.get().getId().isBlank());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_draft WHERE user_id=1 AND status=1", Integer.class));
    }

    @Test
    void businessDateFilterUsesRestaurantDayBoundaries() {
        String[] times = {"2026-09-28 23:59:59", "2026-09-29 00:00:00",
                "2026-09-29 23:59:59", "2026-09-30 00:00:00"};
        for (int i = 0; i < times.length; i++) {
            jdbc.update("INSERT INTO orders(user_id,user_seq,total_amount,status,create_time,remind_count) VALUES (1,?,18.00,1,?,0)",
                    i + 1, times[i]);
        }
        OrderPage page = orderService.listOrders(1L, null, "2026-09-29", "2026-09-29", 1, 20);
        assertEquals(2, page.getTotal());
        assertEquals(List.of(3L, 2L), page.getItems().stream().map(Order::getUserSeq).toList());
        assertEquals("+08:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
    }

    @Test
    void directOrderPlaceEndpointIsUnavailable() throws Exception {
        String token = String.valueOf(authService.login("demo", "123456").get("token"));
        mockMvc.perform(post("/order/place")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"items\":[{\"dishName\":\"鱼香肉丝饭\",\"quantity\":1}]}"))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test
    void allergyRecommendationOnlyUsesReviewedSafeMenu() throws Exception {
        jdbc.update("UPDATE dish SET allergen_reviewed=TRUE WHERE name IN ('鱼香肉丝饭','宫保鸡丁饭')");
        safetyService.set(1L, List.of("花生"));
        String token = String.valueOf(authService.login("demo", "123456").get("token"));
        mockMvc.perform(post("/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"message\":\"请推荐我能吃的菜\",\"history\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("鱼香肉丝饭")))
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("宫保鸡丁饭"))));
    }

    @Test
    void concurrentConfirmationsCannotOversellOneRemainingDish() throws Exception {
        jdbc.update("UPDATE dish SET stock=1 WHERE name='鱼香肉丝饭'");
        OrderDraft firstDraft = orderService.createOrderDraft(1L, List.of(item("鱼香肉丝饭", 1)), null);
        OrderDraft secondDraft = orderService.createOrderDraft(2L, List.of(item("鱼香肉丝饭", 1)), null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                    () -> confirm(firstDraft, 1L, "integration-race-001"),
                    () -> confirm(secondDraft, 2L, "integration-race-002")
            );
            long successful = pool.invokeAll(attempts).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception ignored) {
                    return false;
                }
            }).count();
            assertEquals(1, successful);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM dish WHERE name='鱼香肉丝饭'", Integer.class));
    }

    @Test
    void paginatesOrdersWithOneBatchOfItemsAndScopedStatusCounts() {
        for (int i = 1; i <= 25; i++) {
            int status = i <= 12 ? Order.STATUS_ORDERED : Order.STATUS_PREPARING;
            long id = 1000L + i;
            jdbc.update("INSERT INTO orders(id,user_id,user_seq,total_amount,status,create_time,remind_count) VALUES (?,?,?,?,?,NOW(),0)",
                    id, 1L, (long) i, "18.00", status);
            jdbc.update("INSERT INTO order_item(order_id,dish_id,dish_name,quantity,price,amount) VALUES (?,?,?,?,?,?)",
                    id, 1L, "鱼香肉丝饭", 1, "18.00", "18.00");
        }
        jdbc.update("INSERT INTO orders(id,user_id,user_seq,total_amount,status,create_time,remind_count) VALUES (1100,2,1,18.00,1,NOW(),0)");

        OrderPage first = orderService.listOrders(1L, null, null, null, 1, 20);
        OrderPage second = orderService.listOrders(1L, null, null, null, 2, 20);
        OrderPage onlyPreparing = orderService.listOrders(1L, Order.STATUS_PREPARING, null, null, 1, 20);

        assertEquals(25, first.getTotal());
        assertEquals(20, first.getItems().size());
        assertEquals(1025L, first.getItems().get(0).getId());
        assertEquals(1, first.getItems().get(0).getItems().size());
        assertEquals(5, second.getItems().size());
        assertEquals(1005L, second.getItems().get(0).getId());
        assertEquals(12, first.getStatusCounts().get(Order.STATUS_ORDERED));
        assertEquals(13, first.getStatusCounts().get(Order.STATUS_PREPARING));
        assertEquals(13, onlyPreparing.getTotal());
        assertEquals(12, onlyPreparing.getStatusCounts().get(Order.STATUS_ORDERED));
        List<Map<String, Object>> plan = jdbc.queryForList(
                "EXPLAIN SELECT id FROM orders WHERE user_id=? ORDER BY id DESC LIMIT 20", 1L);
        assertTrue(String.valueOf(plan.get(0).get("possible_keys")).contains("idx_orders_user_id"));
        assertTrue(plan.get(0).get("key") != null);
        assertThrows(IllegalArgumentException.class, () -> orderService.listOrders(1L, null, null, null, 1, 101));
    }

    private boolean confirm(OrderDraft draft, Long userId, String key) {
        try {
            orderService.confirmDraft(userId, draft.getId(), key);
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    private OrderItem item(String dishName, int quantity) {
        OrderItem item = new OrderItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        return item;
    }
}
