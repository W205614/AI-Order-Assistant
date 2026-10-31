package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ai.assistant.security.UserContext;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class CampusFulfillmentMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("fulfillment_test").withUsername("test").withPassword("test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    MYSQL.start();
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.cache.type", () -> "none");
    r.add("app.demo-seed.enabled", () -> "false");
    r.add("auth.monitoring-key", () -> "m".repeat(32));
    r.add("auth.user-secret-key", () -> "u".repeat(32));
    r.add("auth.admin-secret-key", () -> "a".repeat(32));
    r.add("ai.internal-api-key", () -> "i".repeat(32));
  }

  @AfterAll static void stop() { MYSQL.stop(); }

  @Autowired JdbcTemplate db;
  @Autowired CommerceFulfillmentService fulfillment;
  @Autowired CommerceAftersaleService refunds;
  @Autowired CommerceExperienceService experience;
  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM aftersale_unit");
    db.update("DELETE FROM aftersale");
    db.update("DELETE FROM feedback");
    db.update("DELETE FROM delivery_task");
    db.update("DELETE FROM orders");
    db.update("DELETE FROM rider_campus");
    db.update("INSERT IGNORE INTO campus(id,name) VALUES(2,'其他校区')");
    db.update("INSERT IGNORE INTO merchant(id,name,campus_id) VALUES(2,'其他商户',2)");
    db.update("INSERT INTO rider_campus(rider_id,campus_id) VALUES(11,1),(12,1)");
  }

  @AfterEach void clear() { UserContext.clear(); }

  private void owner() { UserContext.set(1L, null, "OWNER", 1L); }
  private void rider() { UserContext.set(11L, null, "RIDER", null); }
  private void platform() { UserContext.set(1L, null, "PLATFORM_ADMIN", null); }

  private void order(long id, long merchant, String state, int status) {
    db.update("INSERT INTO orders(id,user_id,user_seq,merchant_id,create_time,status,"
        + "commerce_version,campus_id,fulfillment_mode,fulfillment_status,recipient_name,"
        + "recipient_phone,delivery_address) VALUES(?,1,?,?,NOW(),?,2,1,'DELIVERY',?,'收货人',"
        + "'13800000000','一号楼')", id, id, merchant, status, state);
  }

  private void task(long id, Long rider, long campus, String state) {
    order(id, 1, "DELIVERED".equals(state) ? "COMPLETED" : "READY",
        "DELIVERED".equals(state) ? 4 : 2);
    db.update("INSERT INTO delivery_task(id,order_id,merchant_id,campus_id,rider_id,status)"
        + " VALUES(?,?,1,?,?,?)", id, id, campus, rider, state);
  }

  private List<Long> ids(List<Map<String, Object>> rows) {
    return rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
  }

  private String refundId(int id) { return String.format("%036d", id); }

  private void refund(int id, long order, long user, String status) {
    db.update("INSERT INTO aftersale(id,order_id,user_id,reason,status,amount,request_key,request_fingerprint,created_at)"
        + " VALUES(?,?,?,'测试售后',?,1,?,'fixture','2026-01-01 00:00:00')",
        refundId(id), order, user, status, "fixture-" + id);
  }

  @Test
  void aftersaleQueuesKeepOldPendingAndDisputedRecordsReachableAndTenantScoped() throws Exception {
    order(1, 1, "PREPARING", 2);
    order(2, 2, "PREPARING", 2);
    order(3, 1, "PREPARING", 2);
    db.update("UPDATE orders SET user_id=2 WHERE id=3");
    refund(1, 1, 1, "PENDING");
    refund(2, 1, 1, "DISPUTED");
    refund(3, 2, 1, "PENDING");
    refund(4, 3, 2, "PENDING");
    for (int i = 5; i <= 225; i++) refund(i, 1, 1, "APPROVED");
    owner();
    assertEquals(List.of(refundId(4), refundId(1)), refunds.list("active", 1, 50).stream().map(r -> r.get("id")).toList());
    assertEquals(100, refunds.list().size());
    var first = refunds.list("history", 1, 100);
    var second = refunds.list("history", 2, 100);
    assertTrue(Collections.disjoint(first.stream().map(r -> r.get("id")).toList(), second.stream().map(r -> r.get("id")).toList()));
    assertEquals(refundId(2), refunds.list("history", 3, 100).getLast().get("id"));
    mvc.perform(get("/admin/aftersales").param("scope", "active").param("pageSize", "1").param("page", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(refundId(1)))
        .andExpect(jsonPath("$.data[0].units").isArray());
    UserContext.set(2L, null, "CUSTOMER", null);
    assertEquals(List.of(refundId(4)), refunds.list("all", 1, 50).stream().map(r -> r.get("id")).toList());
    UserContext.set(1L, null, "CUSTOMER", null);
    assertEquals(3, refunds.list("active", 1, 50).size());
    platform();
    assertEquals(4, refunds.list("active", 1, 50).size());
    assertEquals(200, refunds.list().size());
    mvc.perform(get("/platform/aftersales").param("scope", "active"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4));
  }

  @Test
  void feedbackQueuesAndPagesKeepOldOpenRecordsAndCustomerIsolation() throws Exception {
    order(1, 1, "COMPLETED", 4);
    order(2, 2, "COMPLETED", 4);
    order(3, 1, "COMPLETED", 4);
    db.update("UPDATE orders SET user_id=2 WHERE id=3");
    for (int i = 1; i <= 225; i++)
      db.update("INSERT INTO feedback(id,user_id,order_id,message,status) VALUES(?,?,?,'测试反馈',?)",
          i, i == 3 ? 2 : 1, i <= 3 ? i : 1, i <= 3 ? "OPEN" : "CLOSED");
    owner();
    assertEquals(List.of(3L, 1L), ids(experience.feedbacks("active", 1, 50)));
    assertEquals(100, experience.feedbacks().size());
    assertEquals(225L, ids(experience.feedbacks("history", 1, 100)).getFirst());
    assertEquals(125L, ids(experience.feedbacks("history", 2, 100)).getFirst());
    assertEquals(4L, ids(experience.feedbacks("history", 3, 100)).getLast());
    assertTrue(experience.feedbacks("history", 4, 100).isEmpty());
    mvc.perform(get("/admin/feedback").param("scope", "active").param("pageSize", "1").param("page", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
    UserContext.set(2L, null, "CUSTOMER", null);
    assertEquals(List.of(3L), ids(experience.feedbacks("all", 1, 50)));
    mvc.perform(get("/feedback").param("scope", "active"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    platform();
    assertEquals(3, experience.feedbacks("active", 1, 50).size());
    assertEquals(200, experience.feedbacks().size());
  }

  @Test
  void supportListPagingRejectsInvalidInputsAndUnauthorizedRoles() throws Exception {
    owner();
    for (String endpoint : List.of("/admin/aftersales", "/admin/feedback", "/platform/aftersales", "/platform/feedback", "/aftersales", "/feedback")) {
      mvc.perform(get(endpoint).param("page", "0")).andExpect(status().isBadRequest());
      mvc.perform(get(endpoint).param("pageSize", "201")).andExpect(status().isBadRequest());
      mvc.perform(get(endpoint).param("scope", "bad")).andExpect(status().isBadRequest());
      mvc.perform(get(endpoint).param("page", "1000001")).andExpect(status().isBadRequest());
    }
    rider();
    assertThrows(BusinessException.class, () -> refunds.list("all", 1, 10));
    assertThrows(BusinessException.class, () -> experience.feedbacks("all", 1, 10));
  }

  @Test
  void merchantActiveQueueSurvivesNewHistoryAndKeepsTenantScopedStablePages() throws Exception {
    order(1, 1, "PENDING_ACCEPTANCE", 1);
    for (int i = 2; i <= 101; i++) order(i, 1, "COMPLETED", 4);
    order(102, 2, "PENDING_ACCEPTANCE", 1);
    order(103, 1, "CANCELLED", 5);
    order(104, 1, "CLOSED", 6);
    owner();
    assertEquals(List.of(1L), ids(fulfillment.orders("active", 1, 100)));
    assertEquals(100, fulfillment.orders().size());
    var first = ids(fulfillment.orders("history", 1, 10));
    var second = ids(fulfillment.orders("history", 2, 10));
    assertEquals(List.of(104L, 103L, 101L, 100L, 99L, 98L, 97L, 96L, 95L, 94L), first);
    assertTrue(Collections.disjoint(first, second));
    assertEquals(first, ids(fulfillment.orders("history", 1, 10)));
    assertTrue(fulfillment.orders("history", 12, 10).isEmpty());
    mvc.perform(get("/admin/commerce-orders")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(100));
    mvc.perform(get("/admin/commerce-orders").param("scope", "active"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(1));
  }

  @Test
  void riderAndPlatformActiveQueuesSurviveTwoHundredNewDeliveredTasks() throws Exception {
    task(1, 11L, 1, "ASSIGNED");
    for (int i = 2; i <= 201; i++) task(i, 11L, 1, "DELIVERED");
    rider();
    assertEquals(List.of(1L), ids(fulfillment.tasks("active", 1, 200)));
    assertEquals(200, fulfillment.tasks().size());
    var first = ids(fulfillment.tasks("history", 1, 100));
    var second = ids(fulfillment.tasks("history", 2, 100));
    assertEquals(100, first.size());
    assertEquals(100, second.size());
    assertTrue(Collections.disjoint(first, second));
    assertTrue(fulfillment.tasks("history", 3, 100).isEmpty());
    mvc.perform(get("/delivery-tasks")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(200));
    platform();
    assertEquals(List.of(1L), ids(fulfillment.platformTasks("active", 1, 200)));
    assertEquals(200, fulfillment.platformTasks().size());
    assertEquals(first, ids(fulfillment.platformTasks("history", 1, 100)));
    mvc.perform(get("/platform/delivery-tasks")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(200));
  }

  @Test
  void recipientIsVisibleOnlyDuringCurrentAssignedPickedUpOrExceptionTasks() throws Exception {
    task(1, 11L, 1, "ASSIGNED");
    task(2, 11L, 1, "PICKED_UP");
    task(3, 11L, 1, "EXCEPTION");
    task(4, 11L, 1, "DELIVERED");
    task(5, null, 1, "WAITING");
    task(6, 12L, 1, "ASSIGNED");
    task(7, null, 2, "WAITING");
    rider();
    var rows = fulfillment.tasks("all", 1, 200);
    assertEquals(List.of(5L, 4L, 3L, 2L, 1L), ids(rows));
    for (var row : rows) {
      assertTrue(row.containsKey("recipient"));
      if (((Number) row.get("id")).longValue() >= 4) assertNull(row.get("recipient"));
      else {
        var recipient = assertInstanceOf(CommerceFulfillmentService.Recipient.class, row.get("recipient"));
        assertEquals("收货人", recipient.recipient_name());
        assertEquals("13800000000", recipient.recipient_phone());
        assertEquals("一号楼", recipient.delivery_address());
      }
    }
    mvc.perform(get("/delivery-tasks").param("scope", "history"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].recipient").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"recipient\":null")));
    mvc.perform(get("/delivery-tasks").param("scope", "active"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[1].recipient.recipient_phone").value("13800000000"));
    db.update("UPDATE delivery_task SET rider_id=12 WHERE id=1");
    assertFalse(ids(fulfillment.tasks("all", 1, 200)).contains(1L));
    db.update("UPDATE delivery_task SET rider_id=NULL,status='WAITING' WHERE id=2");
    assertNull(fulfillment.tasks("all", 1, 200).stream()
        .filter(row -> ((Number) row.get("id")).longValue() == 2).findFirst().orElseThrow().get("recipient"));
  }

  @Test
  void invalidPagingAndScopeAreRejectedWithoutWeakeningRoleChecks() throws Exception {
    owner();
    assertThrows(IllegalArgumentException.class, () -> fulfillment.orders("active", 0, 100));
    assertThrows(IllegalArgumentException.class, () -> fulfillment.orders("unknown", 1, 100));
    assertThrows(IllegalArgumentException.class, () -> fulfillment.orders("all", 1, 201));
    assertThrows(BusinessException.class, () -> fulfillment.tasks("all", 1, 10));
    assertThrows(BusinessException.class, () -> fulfillment.platformTasks("all", 1, 10));
    mvc.perform(get("/admin/commerce-orders").param("pageSize", "0"))
        .andExpect(status().isBadRequest());
    rider();
    assertThrows(IllegalArgumentException.class, () -> fulfillment.tasks("all", -1, 200));
    assertThrows(IllegalArgumentException.class, () -> fulfillment.tasks("all", 1, 201));
    assertThrows(IllegalArgumentException.class, () -> fulfillment.tasks("history OR 1=1", 1, 10));
    assertThrows(BusinessException.class, () -> fulfillment.orders("all", 1, 10));
    platform();
    assertThrows(IllegalArgumentException.class, () -> fulfillment.platformTasks("all", 1, 0));
    assertThrows(IllegalArgumentException.class, () -> fulfillment.platformTasks("all", 0, 100));
    assertThrows(IllegalArgumentException.class, () -> fulfillment.platformTasks("bad", 1, 10));
  }
}
