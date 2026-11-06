package com.ai.assistant.controller;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import com.alibaba.fastjson2.JSON;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/integrations/wechat/oauth")
public class WechatOAuthController {
  private final PaymentSettings settings;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final HttpClient client;

  @org.springframework.beans.factory.annotation.Autowired
  public WechatOAuthController(
      PaymentSettings settings,
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this(
        settings,
        db,
        manager,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
  }

  WechatOAuthController(
      PaymentSettings settings,
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager,
      HttpClient client) {
    this.settings = settings;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.client = client;
  }

  @GetMapping("/start")
  public ResponseEntity<Void> start(@RequestParam(defaultValue = "/campus/") String returnTo) {
    UserContext.requireRole("CUSTOMER");
    if (!settings.real())
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_DISABLED", "真实支付未启用");
    if (!Set.of("/campus/", "/chat/").contains(returnTo))
      throw new IllegalArgumentException("返回路径无效");
    String state =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    db.update(
        "INSERT INTO payment_oauth_state(state,user_id,return_to,expires_at)"
            + " VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 5 MINUTE))",
        state,
        UserContext.getCurrentId(),
        returnTo);
    return ResponseEntity.status(302)
        .location(
            URI.create(
                "https://open.weixin.qq.com/connect/oauth2/authorize?appid="
                    + settings.appId
                    + "&redirect_uri="
                    + enc(settings.notifyBase + "/integrations/wechat/oauth/callback")
                    + "&response_type=code&scope=snsapi_base&state="
                    + state
                    + "#wechat_redirect"))
        .build();
  }

  @GetMapping("/callback")
  public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam String code) {
    UserContext.requireRole("CUSTOMER");
    if (!state.matches("[0-9a-f]{64}") || code.isBlank() || code.length() > 256)
      throw new IllegalArgumentException("授权参数无效");
    String target =
        tx.execute(
            s -> {
              var rows =
                  db.queryForList(
                      "SELECT return_to FROM payment_oauth_state WHERE state=? AND user_id=? AND"
                          + " expires_at>NOW() FOR UPDATE",
                      state,
                      UserContext.getCurrentId());
              if (rows.isEmpty()) throw new IllegalArgumentException("授权会话失效，请重新发起");
              db.update("DELETE FROM payment_oauth_state WHERE state=?", state);
              return (String) rows.getFirst().get("return_to");
            });
    try {
      var request =
          java.net.http.HttpRequest.newBuilder(
                  URI.create(
                      "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                          + settings.appId
                          + "&secret="
                          + enc(settings.appSecret)
                          + "&code="
                          + enc(code)
                          + "&grant_type=authorization_code"))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      var response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      var data = JSON.parseObject(response.body());
      String openid = data.getString("openid");
      if (response.statusCode() != 200
          || data.containsKey("errcode")
          || openid == null
          || openid.isBlank()
          || openid.length() > 128)
        throw new BusinessException(HttpStatus.BAD_GATEWAY, "WECHAT_OAUTH_FAILED", "微信授权失败，请重新发起");
      tx.executeWithoutResult(
          s -> {
            var existing =
                db.queryForList(
                    "SELECT user_id FROM payment_wechat_identity WHERE app_id=? AND open_id=? FOR"
                        + " UPDATE",
                    settings.appId,
                    openid);
            if (!existing.isEmpty()
                && ((Number) existing.getFirst().get("user_id")).longValue()
                    != UserContext.getCurrentId())
              throw BusinessException.conflict("该微信身份已关联其他账号", null);
            var own =
                db.queryForList(
                    "SELECT user_id FROM payment_wechat_identity WHERE user_id=? FOR UPDATE",
                    UserContext.getCurrentId());
            if (own.isEmpty())
              db.update(
                  "INSERT INTO payment_wechat_identity(user_id,app_id,open_id) VALUES(?,?,?)",
                  UserContext.getCurrentId(),
                  settings.appId,
                  openid);
            else
              db.update(
                  "UPDATE payment_wechat_identity SET app_id=?,open_id=? WHERE user_id=?",
                  settings.appId,
                  openid,
                  UserContext.getCurrentId());
          });
      return ResponseEntity.status(302).location(URI.create(target)).build();
    } catch (java.io.IOException e) {
      throw new BusinessException(
          HttpStatus.BAD_GATEWAY, "WECHAT_OAUTH_UNAVAILABLE", "微信授权服务不可用，请稍后重试");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_OAUTH_UNAVAILABLE", "授权请求中断");
    }
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
