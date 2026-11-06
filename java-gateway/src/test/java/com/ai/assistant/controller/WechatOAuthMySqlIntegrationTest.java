package com.ai.assistant.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.PaymentSettings;
import com.alibaba.fastjson2.JSON;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real controller and MySQL transactions; only the remote OAuth HTTP boundary is replaced. */
@Testcontainers(disabledWithoutDocker = false)
class WechatOAuthMySqlIntegrationTest {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("oauth_boundary_test")
          .withUsername("test")
          .withPassword("test");
  private static final String APP_SECRET = "fixture-only/+&?= OAuth密钥";
  private static final String ACCESS_TOKEN = "fixture-only-access-token-not-for-storage";
  private static JdbcTemplate db;
  private static DataSourceTransactionManager transactions;
  private WechatOAuthController controller;
  private final AtomicInteger externalCalls = new AtomicInteger();
  private final AtomicReference<HttpRequest> outbound = new AtomicReference<>();
  private final AtomicReference<String> returnedOpenId =
      new AtomicReference<>("openid-current-user");

  @BeforeAll
  static void database() {
    MYSQL.start();
    var source =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    db = new JdbcTemplate(source);
    transactions = new DataSourceTransactionManager(source);
    db.update(
        "INSERT INTO user(id,username,password,nickname,created_at)"
            + " VALUES(1,'oauth-one','unused-test-password','One',NOW()),(2,'oauth-two','unused-test-password','Two',NOW())");
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    db.update("DELETE FROM payment_oauth_state");
    db.update("DELETE FROM payment_wechat_identity");
    UserContext.setUser(1L, null);
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenAnswer(
            invocation ->
                JSON.toJSONString(
                    Map.of(
                        "openid",
                        returnedOpenId.get(),
                        "access_token",
                        ACCESS_TOKEN,
                        "refresh_token",
                        "fixture-only-refresh-token",
                        "scope",
                        "snsapi_base")));
    when(client.send(
            any(HttpRequest.class),
            org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenAnswer(
            invocation -> {
              assertFalse(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "OAuth network exchange must not retain database transaction locks");
              externalCalls.incrementAndGet();
              outbound.set(invocation.getArgument(0));
              return response;
            });
    var environment =
        new MockEnvironment()
            .withProperty("payment.mode", "WECHAT_PARTNER")
            .withProperty("payment.wechat.mch-id", "1000000000")
            .withProperty("payment.wechat.app-id", "wx-fixture-app")
            .withProperty("payment.wechat.app-secret", APP_SECRET)
            .withProperty("payment.wechat.private-key-path", "not-used-by-oauth-private.pem")
            .withProperty("payment.wechat.public-key-path", "not-used-by-oauth-public.pem")
            .withProperty("payment.wechat.certificate-serial", "TEST")
            .withProperty("payment.wechat.public-key-id", "PUB_KEY_ID_TEST")
            .withProperty("payment.wechat.api-v3-key", "k".repeat(32))
            .withProperty("payment.wechat.notify-base-url", "https://payment.example");
    controller =
        new WechatOAuthController(new PaymentSettings(environment), db, transactions, client);
  }

  @AfterEach
  void clearIdentity() {
    UserContext.clear();
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Test
  void validCallbackConsumesStateOnceBindsCurrentUserAndEncodesTheOfficialRequest() {
    String state = begin("/campus/");
    String code = "fixture-code/+?&= 中文#";
    var result = controller.callback(state, code);
    assertEquals(302, result.getStatusCode().value());
    assertEquals(URI.create("/campus/"), result.getHeaders().getLocation());
    assertEquals(1, externalCalls.get());
    HttpRequest request = outbound.get();
    assertEquals("GET", request.method());
    assertEquals("https", request.uri().getScheme());
    assertEquals("api.weixin.qq.com", request.uri().getHost());
    assertEquals("/sns/oauth2/access_token", request.uri().getPath());
    assertEquals(Duration.ofSeconds(10), request.timeout().orElseThrow());
    assertNull(request.uri().getFragment());
    Map<String, String> parameters = query(request.uri());
    assertEquals("wx-fixture-app", parameters.get("appid"));
    assertEquals("authorization_code", parameters.get("grant_type"));
    // Boolean assertions avoid printing credentials or authorization codes in test reports.
    assertTrue(
        APP_SECRET.equals(parameters.get("secret")),
        "OAuth secret must survive URL decoding exactly");
    assertTrue(code.equals(parameters.get("code")), "OAuth code must survive URL decoding exactly");
    assertTrue(
        request
            .uri()
            .getRawQuery()
            .contains("secret=" + URLEncoder.encode(APP_SECRET, StandardCharsets.UTF_8)),
        "OAuth secret must be form-encoded");
    assertTrue(
        request
            .uri()
            .getRawQuery()
            .contains("code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)),
        "OAuth code must be form-encoded");
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_oauth_state WHERE state=?", Integer.class, state));
    var identity = db.queryForMap("SELECT * FROM payment_wechat_identity WHERE user_id=1");
    assertEquals("wx-fixture-app", identity.get("app_id"));
    assertEquals("openid-current-user", identity.get("open_id"));
    assertEquals(3, identity.size());
    String stored = JSON.toJSONString(identity);
    assertFalse(
        stored.contains(APP_SECRET), "App secret must not be persisted in the OAuth identity");
    assertFalse(
        stored.contains(ACCESS_TOKEN), "Access token must not be persisted in the OAuth identity");
    assertFalse(stored.contains("refresh_token"));
    assertThrows(IllegalArgumentException.class, () -> controller.callback(state, code));
    assertEquals(1, externalCalls.get(), "Consumed state must fail before any external exchange");
  }

  @Test
  void expiredAndWrongUserStatesFailBeforeTheExternalExchangeWithoutBurningTheOwnersState() {
    String expired = begin("/campus/");
    db.update(
        "UPDATE payment_oauth_state SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE state=?",
        expired);
    assertThrows(
        IllegalArgumentException.class, () -> controller.callback(expired, "fixture-code"));
    assertEquals(0, externalCalls.get());
    String owned = begin("/chat/");
    UserContext.setUser(2L, null);
    assertThrows(IllegalArgumentException.class, () -> controller.callback(owned, "fixture-code"));
    assertEquals(0, externalCalls.get());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_oauth_state WHERE state=? AND user_id=1",
            Integer.class,
            owned));
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM payment_wechat_identity", Integer.class));
    UserContext.setUser(1L, null);
    assertEquals(
        URI.create("/chat/"),
        controller.callback(owned, "fixture-code").getHeaders().getLocation());
    assertEquals(1, externalCalls.get());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_wechat_identity WHERE user_id=2", Integer.class));
  }

  @Test
  void offsiteOrUnapprovedReturnTargetsAreRejectedBeforeCreatingState() {
    for (String target :
        java.util.List.of(
            "https://untrusted.example/campus/",
            "//untrusted.example/",
            "/campus/?next=https://untrusted.example",
            "/admin/")) {
      assertThrows(IllegalArgumentException.class, () -> controller.start(target));
    }
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM payment_oauth_state", Integer.class));
    assertEquals(0, externalCalls.get());
  }

  @Test
  void anOpenIdOwnedBySomeoneElseReturnsConflictAndNeverReassignsTheIdentity() {
    db.update(
        "INSERT INTO payment_wechat_identity(user_id,app_id,open_id)"
            + " VALUES(2,'wx-fixture-app','already-owned-openid')");
    returnedOpenId.set("already-owned-openid");
    String state = begin("/campus/");
    BusinessException conflict =
        assertThrows(BusinessException.class, () -> controller.callback(state, "fixture-code"));
    assertEquals(HttpStatus.CONFLICT, conflict.status());
    assertEquals(1, externalCalls.get());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_wechat_identity WHERE user_id=1", Integer.class));
    assertEquals(
        "already-owned-openid",
        db.queryForObject(
            "SELECT open_id FROM payment_wechat_identity WHERE user_id=2", String.class));
    assertEquals(
        1, db.queryForObject("SELECT COUNT(*) FROM payment_wechat_identity", Integer.class));
    assertThrows(IllegalArgumentException.class, () -> controller.callback(state, "fixture-code"));
    assertEquals(1, externalCalls.get());
  }

  private String begin(String returnTo) {
    var result = controller.start(returnTo);
    assertEquals(302, result.getStatusCode().value());
    URI location = result.getHeaders().getLocation();
    assertNotNull(location);
    assertEquals("https", location.getScheme());
    assertEquals("open.weixin.qq.com", location.getHost());
    assertEquals("/connect/oauth2/authorize", location.getPath());
    Map<String, String> parameters = query(location);
    assertEquals(
        "https://payment.example/integrations/wechat/oauth/callback",
        parameters.get("redirect_uri"));
    assertEquals("snsapi_base", parameters.get("scope"));
    String state = parameters.get("state");
    assertNotNull(state);
    assertTrue(state.matches("[0-9a-f]{64}"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM payment_oauth_state WHERE state=? AND user_id=? AND return_to=?",
            Integer.class,
            state,
            UserContext.getCurrentId(),
            returnTo));
    return state;
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> result = new HashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }
}
