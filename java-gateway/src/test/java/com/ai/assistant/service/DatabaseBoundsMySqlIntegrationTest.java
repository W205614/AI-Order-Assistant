package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ai.assistant.security.UserContext;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** Isolated MySQL proves cancellation, row rollback and bounded lock waits, not daily data. */
@SpringBootTest(properties = {"app.database.timeout-seconds=3",
    "spring.datasource.hikari.connection-init-sql=SET time_zone = '+08:00', innodb_lock_wait_timeout=1"})
class DatabaseBoundsMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("bounds_test").withUsername("test").withPassword("test");
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    MYSQL.start();
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.cache.type", () -> "none");
    r.add("auth.monitoring-key", () -> "m".repeat(32));
    r.add("auth.user-secret-key", () -> "u".repeat(32));
    r.add("auth.admin-secret-key", () -> "a".repeat(32));
    r.add("ai.internal-api-key", () -> "i".repeat(32));
  }
  @AfterAll static void stop() { MYSQL.stop(); }
  @AfterEach void clear() { UserContext.clear(); }
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager manager;

  @BeforeEach void table() {
    db.execute("CREATE TABLE IF NOT EXISTS runtime_bound_probe(id INT PRIMARY KEY, value INT NOT NULL)");
    db.update("INSERT INTO runtime_bound_probe VALUES(1,0) ON DUPLICATE KEY UPDATE value=0");
  }

  @Test void queryTimeoutRollsBackWritesAndConnectionRemainsUsable() {
    var transaction = new TransactionTemplate(manager);
    assertTimeout(java.time.Duration.ofSeconds(7), () -> assertThrows(RuntimeException.class,
        () -> transaction.executeWithoutResult(status -> {
          db.update("UPDATE runtime_bound_probe SET value=1 WHERE id=1");
          db.queryForObject("SELECT SLEEP(6)", Integer.class);
        })));
    assertEquals(0, db.queryForObject("SELECT value FROM runtime_bound_probe WHERE id=1", Integer.class));
    assertEquals(1, db.queryForObject("SELECT 1", Integer.class));
  }

  @Test void actualMysqlLockWaitIsBoundedAndLeavesNoWrite() throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor();
    try {
      var holder = worker.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
        db.queryForObject("SELECT value FROM runtime_bound_probe WHERE id=1 FOR UPDATE", Integer.class);
        locked.countDown();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }));
      assertTrue(locked.await(2, TimeUnit.SECONDS));
      assertTimeout(java.time.Duration.ofSeconds(4), () -> assertThrows(org.springframework.dao.CannotAcquireLockException.class,
          () -> new TransactionTemplate(manager).executeWithoutResult(status ->
              db.update("UPDATE runtime_bound_probe SET value=2 WHERE id=1"))));
      release.countDown();
      holder.get(3, TimeUnit.SECONDS);
      assertEquals(0, db.queryForObject("SELECT value FROM runtime_bound_probe WHERE id=1", Integer.class));
    } finally { release.countDown(); worker.shutdownNow(); }
  }

  @Test void aiDeadlineShortensQueryAndTransactionBudget() {
    UserContext.setTrustedDeadline(System.currentTimeMillis() + 2200);
    assertTimeout(java.time.Duration.ofSeconds(4), () -> assertThrows(RuntimeException.class,
        () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
          db.update("UPDATE runtime_bound_probe SET value=3 WHERE id=1");
          db.queryForObject("SELECT SLEEP(5)", Integer.class);
        })));
    UserContext.clear();
    assertEquals(0, db.queryForObject("SELECT value FROM runtime_bound_probe WHERE id=1", Integer.class));
  }
}
