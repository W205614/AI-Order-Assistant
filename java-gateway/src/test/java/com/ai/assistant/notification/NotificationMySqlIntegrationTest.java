package com.ai.assistant.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ai.assistant.integration.support.SmsSender;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.EventDispatch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/**
 * Actual MySQL events, cursors, isolation and task transitions; only the external SMS seam is
 * isolated.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationMySqlIntegrationTest {
  private final MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("notification_test")
          .withUsername("test")
          .withPassword("test");
  private final SmsSender sms = mock(SmsSender.class);
  private JdbcTemplate db;
  private TransactionTemplate tx;
  private NotificationService notifications;
  private EventDispatch dispatch;

  @BeforeAll
  void start() {
    mysql.start();
    var source =
        new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    tx = new TransactionTemplate(manager);
    dispatch = new EventDispatch(1, 4);
    notifications =
        new NotificationService(
            db,
            manager,
            sms,
            null,
            dispatch,
            new SimpleMeterRegistry(),
            "order-template",
            "ready-template",
            "",
            "");
    db.update(
        "INSERT INTO admin_user(id,username,password,created_at,merchant_id,role)"
            + " VALUES(1,'owner','hash',NOW(),1,'OWNER'),(2,'staff','hash',NOW(),1,'STAFF'),"
            + "(3,'platform','hash',NOW(),NULL,'PLATFORM_ADMIN')");
  }

  @AfterAll
  void stop() {
    if (notifications != null) notifications.shutdown();
    if (dispatch != null) dispatch.stop();
    mysql.stop();
  }

  @BeforeEach
  void resetState() {
    reset(sms);
    for (String table :
        new String[] {
          "notification_task", "notification", "commerce_event", "order_event", "verified_phone"
        }) db.update("DELETE FROM " + table);
    db.update("UPDATE notification_cursor SET last_id=0");
    UserContext.set(1L, null, "CUSTOMER", null);
  }

  @AfterEach
  void clearIdentity() {
    UserContext.clear();
  }

  private void event(String kind, boolean merchant) {
    db.update(
        "INSERT INTO commerce_event(user_id,merchant_id,kind,resource_id) VALUES(1,?,?,?)",
        merchant ? 1L : null,
        kind,
        "88");
  }

  private void task() {
    db.update(
        "INSERT INTO verified_phone(account_type,account_id,phone)"
            + " VALUES('CUSTOMER',1,'13800000000')");
    event("FULFILLMENT_READY", false);
    notifications.collect();
  }

  @Test
  void committedEventsAreIdempotentAndAccountTablesAreIsolated() {
    event("FULFILLMENT_READY", true);
    notifications.collect();
    notifications.collect();
    assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
    var customer = notifications.list(Long.MAX_VALUE);
    assertEquals(1, customer.size());
    long own = ((Number) customer.getFirst().get("id")).longValue();
    UserContext.set(1L, null, "OWNER", 1L);
    var owner = notifications.list(Long.MAX_VALUE);
    assertEquals(1, owner.size());
    assertNotEquals(own, ((Number) owner.getFirst().get("id")).longValue());
    assertThrows(BusinessException.class, () -> notifications.read(own));
    UserContext.set(1L, null, "CUSTOMER", null);
    notifications.read(own);
    assertNotNull(notifications.list(Long.MAX_VALUE).getFirst().get("read_at"));
    UserContext.set(9L, null, "CUSTOMER", null);
    assertTrue(notifications.list(Long.MAX_VALUE).isEmpty());
  }

  @Test
  void rollbackCannotCreateNotifications() {
    tx.executeWithoutResult(
        status -> {
          event("FULFILLMENT_READY", true);
          status.setRollbackOnly();
        });
    notifications.collect();
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM notification", Integer.class));
  }

  @Test
  void acceptanceNeverClaimsHandsetDeliveryAndDoesNotRepeat() {
    task();
    when(sms.send(anyString(), anyString(), anyMap())).thenReturn("provider-accepted");
    notifications.deliver();
    notifications.deliver();
    assertEquals(
        "ACCEPTED", db.queryForObject("SELECT status FROM notification_task", String.class));
    assertEquals(
        "provider-accepted",
        db.queryForObject("SELECT provider_reference FROM notification_task", String.class));
    verify(sms, times(1))
        .send(eq("13800000000"), eq("ready-template"), eq(java.util.Map.of("resource", "88")));
  }

  @Test
  void uncertainProviderResultRequiresExplicitOperatorDecision() {
    task();
    when(sms.send(anyString(), anyString(), anyMap()))
        .thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "SMS_UNAVAILABLE", "unknown"));
    notifications.deliver();
    notifications.deliver();
    assertEquals(
        "UNKNOWN", db.queryForObject("SELECT status FROM notification_task", String.class));
    verify(sms, times(1)).send(anyString(), anyString(), anyMap());
    long id = db.queryForObject("SELECT id FROM notification_task", Long.class);
    assertThrows(BusinessException.class, () -> notifications.retry(id, true));
    UserContext.set(3L, null, "PLATFORM_ADMIN", null);
    assertThrows(IllegalArgumentException.class, () -> notifications.retry(id, false));
    notifications.retry(id, true);
    doReturn("accepted-after-review").when(sms).send(anyString(), anyString(), anyMap());
    notifications.deliver();
    assertEquals(
        "ACCEPTED", db.queryForObject("SELECT status FROM notification_task", String.class));
    assertEquals(2, db.queryForObject("SELECT attempts FROM notification_task", Integer.class));
  }

  @Test
  void confirmedRejectionHasAFiveAttemptCeiling() {
    task();
    when(sms.send(anyString(), anyString(), anyMap()))
        .thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "SMS_REJECTED", "rejected"));
    for (int attempt = 0; attempt < 6; attempt++) {
      db.update("UPDATE notification_task SET available_at=NOW()");
      notifications.deliver();
    }
    assertEquals("FAILED", db.queryForObject("SELECT status FROM notification_task", String.class));
    assertEquals(5, db.queryForObject("SELECT attempts FROM notification_task", Integer.class));
    verify(sms, times(5)).send(anyString(), anyString(), anyMap());
  }

  @Test
  void crashedDeliveryLeaseIsUnknownAndNotResent() {
    task();
    db.update(
        "UPDATE notification_task SET"
            + " status='SENDING',attempts=1,lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
    notifications.deliver();
    assertEquals(
        "UNKNOWN", db.queryForObject("SELECT status FROM notification_task", String.class));
    verifyNoInteractions(sms);
  }
}
