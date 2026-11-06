package com.ai.assistant.integration.support;

import static org.junit.jupiter.api.Assertions.*;

import com.ai.assistant.security.AuthException;
import com.ai.assistant.security.AuthService;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.BusinessException;
import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContextManager;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SupportIntegrationsMySqlIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("support_test")
          .withUsername("test")
          .withPassword("test");
  static final AtomicReference<String> SENT_CODE = new AtomicReference<>();
  static final AtomicInteger SMS_CALLS = new AtomicInteger();
  static final HttpServer SMS = server();
  static final Path MEDIA = mediaDirectory();
  static final String PASSWORD = "ci-only-demo-customer-password";
  @Autowired JdbcTemplate db;
  @Autowired PhoneIdentityService phones;
  @Autowired AuthService auth;
  @Autowired MediaService media;

  static Path mediaDirectory() {
    try {
      return Files.createTempDirectory("ai-order-support-test-");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static HttpServer server() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> values = new HashMap<>();
            for (String pair : ((query == null ? "" : query) + "&" + body).split("&")) {
              String[] parts = pair.split("=", 2);
              if (parts.length == 2)
                values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            if (values.containsKey("TemplateParam"))
              SENT_CODE.set(JSON.parseObject(values.get("TemplateParam")).getString("code"));
            SMS_CALLS.incrementAndGet();
            byte[] response =
                "{\"Code\":\"OK\",\"BizId\":\"contract-accepted\",\"RequestId\":\"contract-id\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry properties) {
    MYSQL.start();
    properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
    properties.add("spring.datasource.username", MYSQL::getUsername);
    properties.add("spring.datasource.password", MYSQL::getPassword);
    properties.add("app.demo-seed.enabled", () -> "true");
    properties.add("DEMO_CUSTOMER_PASSWORD", () -> PASSWORD);
    properties.add("DEMO_OWNER_PASSWORD", () -> "ci-only-demo-owner-password");
    properties.add("spring.cache.type", () -> "none");
    properties.add("auth.monitoring-key", () -> "m".repeat(32));
    properties.add("auth.user-secret-key", () -> "u".repeat(32));
    properties.add("auth.admin-secret-key", () -> "a".repeat(32));
    properties.add("ai.internal-api-key", () -> "i".repeat(32));
    properties.add("SMS_PROVIDER", () -> "aliyun");
    properties.add("SMS_ENDPOINT", () -> "127.0.0.1:" + SMS.getAddress().getPort());
    properties.add("SMS_PROTOCOL", () -> "HTTP");
    properties.add("SMS_ACCESS_KEY_ID", () -> "contract-key");
    properties.add("SMS_ACCESS_KEY_SECRET", () -> "contract-secret");
    properties.add("SMS_SIGN_NAME", () -> "contract-sign");
    properties.add("SMS_OTP_TEMPLATE_CODE", () -> "contract-template");
    properties.add("STORAGE_LOCAL_DIRECTORY", MEDIA::toString);
  }

  @BeforeEach
  void reset() {
    UserContext.clear();
    db.update("DELETE FROM verified_phone");
    db.update("DELETE FROM phone_challenge");
    db.update("DELETE FROM auth_rate_window");
    db.update(
        "UPDATE user SET password=?,enabled=TRUE WHERE id=1",
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));
    db.update(
        "UPDATE admin_user SET password=?,enabled=TRUE WHERE id=1",
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .encode("ci-only-demo-owner-password"));
    SMS_CALLS.set(0);
    SENT_CODE.set(null);
  }

  @AfterEach
  void clearIdentity() {
    UserContext.clear();
  }

  @AfterAll
  static void stop() {
    try {
      new TestContextManager(SupportIntegrationsMySqlIntegrationTest.class)
          .getTestContext()
          .markApplicationContextDirty(null);
    } finally {
      try {
        SMS.stop(0);
      } finally {
        MYSQL.stop();
      }
    }
  }

  @Test
  void verifiedBindingAndRecoveryInvalidatePreviousSessions() {
    String token = String.valueOf(auth.login("demo", PASSWORD).get("token"));
    UserContext.set(1L, token, "CUSTOMER", null);
    String id =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "BIND", "13800138001", "CUSTOMER", null),
                    "127.0.0.1")
                .get("challengeId"));
    assertNotNull(SENT_CODE.get(), "The real SDK HTTP request must contain the SMS template code");
    assertNotEquals(
        SENT_CODE.get(),
        db.queryForObject("SELECT code_hash FROM phone_challenge WHERE id=?", String.class, id));
    phones.bind(new PhoneIdentityService.BindInput(PASSWORD, id, SENT_CODE.get()));
    assertThrows(AuthException.class, () -> auth.validate(false, token));
    assertEquals(true, phones.current().get("verified"));
    db.update("UPDATE auth_rate_window SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
    String recovery =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "RECOVER", "13800138001", "CUSTOMER", "demo"),
                    "127.0.0.1")
                .get("challengeId"));
    String newToken = String.valueOf(auth.login("demo", PASSWORD).get("token"));
    phones.recover(
        new PhoneIdentityService.RecoveryInput(recovery, SENT_CODE.get(), "new-customer-password"));
    assertThrows(AuthException.class, () -> auth.validate(false, newToken));
    assertDoesNotThrow(() -> auth.login("demo", "new-customer-password"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            phones.recover(
                new PhoneIdentityService.RecoveryInput(
                    recovery, SENT_CODE.get(), "another-new-password")));
  }

  @Test
  void failedAttemptsCommitAndRateLimitDoesNotRevealUnknownAccounts() {
    db.update(
        "INSERT INTO verified_phone(account_type,account_id,phone)"
            + " VALUES('CUSTOMER',1,'13800138002')");
    String id =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "RECOVER", "13800138002", "CUSTOMER", "demo"),
                    "127.0.0.1")
                .get("challengeId"));
    String actual = SENT_CODE.get();
    String wrong = actual.equals("000000") ? "000001" : "000000";
    for (int i = 0; i < 5; i++)
      assertThrows(
          IllegalArgumentException.class,
          () ->
              phones.recover(
                  new PhoneIdentityService.RecoveryInput(id, wrong, "new-customer-password")));
    assertEquals(
        5, db.queryForObject("SELECT attempts FROM phone_challenge WHERE id=?", Integer.class, id));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            phones.recover(
                new PhoneIdentityService.RecoveryInput(id, actual, "new-customer-password")));
    assertEquals(
        "PHONE_RATE_LIMITED",
        assertThrows(
                BusinessException.class,
                () ->
                    phones.challenge(
                        new PhoneIdentityService.ChallengeInput(
                            "RECOVER", "13800138002", "CUSTOMER", "demo"),
                        "127.0.0.1"))
            .errorCode());
    Map<String, Object> unknown =
        phones.challenge(
            new PhoneIdentityService.ChallengeInput(
                "RECOVER", "13800138003", "CUSTOMER", "unknown-account"),
            "127.0.0.1");
    assertEquals("REQUEST_ACCEPTED", unknown.get("status"));
    assertEquals(2, SMS_CALLS.get());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            phones.recover(
                new PhoneIdentityService.RecoveryInput(
                    String.valueOf(unknown.get("challengeId")),
                    SENT_CODE.get(),
                    "new-customer-password")));
  }

  @Test
  void managementRecoveryUsesTheManagementIdentityAndSeparatePhoneUniqueness() {
    db.update(
        "INSERT INTO verified_phone(account_type,account_id,phone)"
            + " VALUES('CUSTOMER',1,'13800138004')");
    String oldToken =
        String.valueOf(auth.adminLogin("admin", "ci-only-demo-owner-password").get("token"));
    UserContext.set(1L, oldToken, "OWNER", 1L);
    String bind =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "BIND", "13800138004", "MANAGEMENT", null),
                    "127.0.0.1")
                .get("challengeId"));
    phones.bind(
        new PhoneIdentityService.BindInput("ci-only-demo-owner-password", bind, SENT_CODE.get()));
    assertThrows(AuthException.class, () -> auth.validate(true, oldToken));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM verified_phone WHERE phone='13800138004'", Integer.class));
    db.update("UPDATE auth_rate_window SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
    String recover =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "RECOVER", "13800138004", "MANAGEMENT", "admin"),
                    "127.0.0.1")
                .get("challengeId"));
    phones.recover(
        new PhoneIdentityService.RecoveryInput(
            recover, SENT_CODE.get(), "new-management-password"));
    assertDoesNotThrow(() -> auth.adminLogin("admin", "new-management-password"));
    assertDoesNotThrow(() -> auth.login("demo", PASSWORD));
  }

  @Test
  void bindingCannotStealAnotherCustomersVerifiedPhone() {
    db.update(
        "INSERT INTO verified_phone(account_type,account_id,phone)"
            + " VALUES('CUSTOMER',1,'13800138005')");
    var other = auth.register("support-other", PASSWORD, "Other");
    long id = ((Number) other.get("userId")).longValue();
    UserContext.set(id, String.valueOf(other.get("token")), "CUSTOMER", null);
    String bind =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "BIND", "13800138005", "CUSTOMER", null),
                    "127.0.0.1")
                .get("challengeId"));
    assertThrows(
        BusinessException.class,
        () -> phones.bind(new PhoneIdentityService.BindInput(PASSWORD, bind, SENT_CODE.get())));
    assertEquals(
        1L,
        db.queryForObject(
            "SELECT account_id FROM verified_phone WHERE account_type='CUSTOMER' AND"
                + " phone='13800138005'",
            Long.class));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM verified_phone WHERE account_type='CUSTOMER' AND account_id=?",
            Integer.class,
            id));
  }

  @Test
  void rebindingToAnotherCustomersPhoneReturnsConflictWithoutChangingEitherIdentity() {
    String firstToken = String.valueOf(auth.login("demo", PASSWORD).get("token"));
    var other = auth.register("support-rebind", PASSWORD, "Other");
    long otherId = ((Number) other.get("userId")).longValue();
    String otherToken = String.valueOf(other.get("token"));
    db.update(
        "INSERT INTO verified_phone(account_type,account_id,phone)"
            + " VALUES('CUSTOMER',1,'13800138006'),('CUSTOMER',?,'13800138007')",
        otherId);
    var beforePhones =
        db.queryForList(
            "SELECT account_id,phone,verified_at FROM verified_phone"
                + " WHERE account_type='CUSTOMER' ORDER BY account_id");
    var beforeVersions =
        db.queryForList("SELECT id,token_version FROM user WHERE id IN (1,?) ORDER BY id", otherId);
    UserContext.set(1L, firstToken, "CUSTOMER", null);
    String challenge =
        String.valueOf(
            phones
                .challenge(
                    new PhoneIdentityService.ChallengeInput(
                        "BIND", "13800138007", "CUSTOMER", null),
                    "127.0.0.1")
                .get("challengeId"));
    assertNotNull(SENT_CODE.get());
    assertEquals(1, SMS_CALLS.get());
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                phones.bind(
                    new PhoneIdentityService.BindInput(PASSWORD, challenge, SENT_CODE.get())));
    assertEquals(
        beforePhones,
        db.queryForList(
            "SELECT account_id,phone,verified_at FROM verified_phone"
                + " WHERE account_type='CUSTOMER' ORDER BY account_id"));
    assertEquals(
        beforeVersions,
        db.queryForList(
            "SELECT id,token_version FROM user WHERE id IN (1,?) ORDER BY id", otherId));
    assertDoesNotThrow(() -> auth.validate(false, firstToken));
    assertDoesNotThrow(() -> auth.validate(false, otherToken));
    BusinessException conflict = assertInstanceOf(BusinessException.class, failure);
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, conflict.status());
    assertEquals("该手机号码已绑定其他账号", conflict.getMessage());
  }

  @Test
  void mediaChecksMerchantAndPrivateAftersaleOwnershipUsingDatabaseState() throws Exception {
    UserContext.set(1L, null, "OWNER", 1L);
    var picture =
        new MockMultipartFile(
            "image", "dish.png", "image/png", SupportProviderHttpContractTest.png());
    var dish = media.upload("DISH", "1", picture);
    UserContext.set(1L, null, "CUSTOMER", null);
    assertArrayEquals(SupportProviderHttpContractTest.png(), media.download(dish.id()).bytes());
    UserContext.set(99L, null, "OWNER", 99L);
    assertThrows(BusinessException.class, () -> media.upload("DISH", "1", picture));
    db.update(
        "INSERT INTO orders(user_id,user_seq,total_amount,status,create_time,merchant_id)"
            + " VALUES(1,991,10,1,NOW(),1)");
    Long order =
        db.queryForObject("SELECT id FROM orders WHERE user_id=1 AND user_seq=991", Long.class);
    String aftersale = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " aftersale(id,order_id,user_id,reason,status,amount,request_key,request_fingerprint)"
            + " VALUES(?,?,1,'test','PENDING',1,?,?)",
        aftersale,
        order,
        aftersale,
        "a".repeat(64));
    UserContext.set(1L, null, "CUSTOMER", null);
    var proof = media.upload("AFTERSALE", aftersale, picture);
    UserContext.set(2L, null, "CUSTOMER", null);
    assertThrows(BusinessException.class, () -> media.download(proof.id()));
    UserContext.set(1L, null, "STAFF", 1L);
    assertTrue(media.download(proof.id()).privateAsset());
  }
}
