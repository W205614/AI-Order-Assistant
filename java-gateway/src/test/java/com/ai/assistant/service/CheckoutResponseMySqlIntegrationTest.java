package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class CheckoutResponseMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("checkout_response_test").withUsername("test").withPassword("test");

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
  @AfterEach void clear() { UserContext.clear(); }

  @Autowired JdbcTemplate db;
  @Autowired CampusCatalogService catalog;
  @Autowired CommerceCartService cart;
  @Autowired CommerceQuoteService quotes;
  @Autowired CommerceCheckoutService checkouts;
  @Autowired MockMvc mvc;

  private Set<String> publicColumns(String table) {
    var fields = new HashSet<>(db.queryForList("SELECT column_name FROM information_schema.columns"
        + " WHERE table_schema=DATABASE() AND table_name=?", String.class, table));
    fields.remove("future_internal_note");
    fields.remove("verification_code_hash");
    return fields;
  }

  @Test
  void checkoutAndNestedContractsPreserveExistingFieldsAndNeverExposeFutureStorageColumns() throws Exception {
    var tables = List.of("checkout", "orders", "order_item", "aftersale");
    for (String table : tables)
      db.execute("ALTER TABLE " + table
          + " ADD COLUMN future_internal_note VARCHAR(50) DEFAULT 'private-sentinel'");
    try {
      db.update("INSERT INTO user(id,username,password,created_at) VALUES(1,'contract','unused',NOW())");
      db.update("INSERT INTO dish(id,merchant_id,name,price,stock,status,allergen_reviewed)"
          + " VALUES(101,1,'测试餐品',10,10,1,TRUE)");
      UserContext.set(1L, null, "CUSTOMER", null);
      catalog.ensureDefaultSkus();
      long sku = id(one(db, "SELECT id FROM product_sku WHERE dish_id=101"), "id");
      cart.save(new CartInput("SKU", sku, 1, "少盐", null));
      var quote = quotes.quote(new QuoteInput(List.of(1L), null, 1, "PICKUP", Map.of(), null, Map.of()));
      String request = JSON.toJSONString(new ConfirmInput(quote.id(), quote.version()));
      String key = "contract-" + UUID.randomUUID();
      var first = JSON.parseObject(mvc.perform(post("/checkouts/confirm")
          .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getJSONObject("data");
      String checkout = first.getString("id");
      assertEquals("UNPAID", first.getString("status"));
      assertDetailKeys(first);
      var replay = JSON.parseObject(mvc.perform(post("/checkouts/confirm")
          .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getJSONObject("data");
      assertEquals(checkout, replay.getString("id"));
      assertDetailKeys(replay);

      long order = first.getJSONArray("orders").getJSONObject(0).getLongValue("id");
      db.update("INSERT INTO aftersale(id,order_id,user_id,reason,status,amount,request_key,request_fingerprint)"
          + " VALUES(?,?,1,'核对字段','PENDING',0,?,'fingerprint')", UUID.randomUUID().toString(), order,
          "refund-contract");
      var detail = JSON.parseObject(mvc.perform(get("/checkouts/" + checkout))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getJSONObject("data");
      assertDetailKeys(detail);
      var item = detail.getJSONArray("orders").getJSONObject(0).getJSONArray("items").getJSONObject(0);
      assertEquals("少盐", item.getString("remark"));
      assertEquals(1, item.getLongValue("refundable_quantity"));
      var refund = detail.getJSONArray("orders").getJSONObject(0).getJSONArray("aftersales").getJSONObject(0);
      assertEquals(publicColumns("aftersale"), refund.keySet());
      var summary = JSON.parseObject(mvc.perform(get("/checkouts"))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
          .getJSONArray("data").getJSONObject(0);
      assertEquals(publicColumns("checkout"), summary.keySet());
      assertFalse(checkouts.list().getFirst().containsKey("future_internal_note"));
      var internal = checkouts.detail(checkout);
      assertFalse(internal.containsKey("future_internal_note"));
      assertFalse(JSON.toJSONString(internal).contains("private-sentinel"));
      assertFalse(JSON.toJSONString(detail).contains("private-sentinel"));
      UserContext.set(2L, null, "CUSTOMER", null);
      assertThrows(BusinessException.class, () -> checkouts.detail(checkout));
    } finally {
      for (String table : tables) db.execute("ALTER TABLE " + table + " DROP COLUMN future_internal_note");
    }
  }

  private void assertDetailKeys(JSONObject detail) {
    var expectedCheckout = publicColumns("checkout");
    expectedCheckout.add("orders");
    assertEquals(expectedCheckout, detail.keySet());
    var order = detail.getJSONArray("orders").getJSONObject(0);
    var expectedOrder = publicColumns("orders");
    expectedOrder.addAll(List.of("merchant_name", "items", "aftersales"));
    assertEquals(expectedOrder, order.keySet());
    assertFalse(order.containsKey("verification_code_hash"));
    var item = order.getJSONArray("items").getJSONObject(0);
    var expectedItem = publicColumns("order_item");
    expectedItem.add("refundable_quantity");
    assertEquals(expectedItem, item.keySet());
  }
}
