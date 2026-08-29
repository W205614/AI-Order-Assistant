package com.ai.assistant.service;

import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderDraft;
import com.ai.assistant.model.OrderItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real MySQL regression for the transaction boundaries that mocks cannot prove. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
        registry.add("spring.sql.init.mode", () -> "always");
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
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM order_draft_item");
        jdbc.update("DELETE FROM order_draft");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM user_order_sequence");
        jdbc.update("DELETE FROM user_food_preference");
        jdbc.update("UPDATE dish SET status=1, stock=10");
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
        jdbc.update("INSERT INTO user_food_preference(user_id,allergens) VALUES (?,?)", 1L, "花生");
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createOrderDraft(1L, List.of(item("宫保鸡丁饭", 1)), null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_draft", Integer.class));
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
