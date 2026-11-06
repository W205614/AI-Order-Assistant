package com.ai.assistant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.*;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class WechatPaymentProvider implements PaymentProvider {
  private final RSAPublicKeyConfig config;
  private final HttpClient client;
  private final NotificationParser parser;

  @org.springframework.beans.factory.annotation.Autowired
  public WechatPaymentProvider(PaymentSettings settings) {
    if (!settings.real()) {
      config = null;
      client = null;
      parser = null;
      return;
    }
    config =
        new RSAPublicKeyConfig.Builder()
            .merchantId(settings.mchId)
            .privateKeyFromPath(settings.privateKeyPath)
            .merchantSerialNumber(settings.serial)
            .publicKeyFromPath(settings.publicKeyPath)
            .publicKeyId(settings.publicKeyId)
            .apiV3Key(settings.apiV3Key)
            .build();
    client =
        new DefaultHttpClientBuilder()
            .config(config)
            .okHttpClient(
                new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .readTimeout(java.time.Duration.ofSeconds(10))
                    .writeTimeout(java.time.Duration.ofSeconds(10))
                    .callTimeout(java.time.Duration.ofSeconds(30))
                    .retryOnConnectionFailure(false)
                    .addInterceptor(responseHeaders())
                    .build())
            .connectTimeoutMs(3000)
            .readTimeoutMs(10000)
            .writeTimeoutMs(10000)
            .disableRetryOnConnectionFailure()
            .build();
    parser = new NotificationParser(config);
  }

  WechatPaymentProvider(RSAPublicKeyConfig config, HttpClient client) {
    this.config = config;
    this.client = client;
    this.parser = new NotificationParser(config);
  }

  static okhttp3.Interceptor responseHeaders() {
    // HTTP header names are case-insensitive. SDK 0.2.17 indexes these names case-sensitively.
    return chain -> {
      var response = chain.proceed(chain.request());
      var normalized = response.newBuilder();
      for (String name :
          java.util.List.of(
              "Wechatpay-Timestamp",
              "Wechatpay-Nonce",
              "Wechatpay-Serial",
              "Wechatpay-Signature")) {
        String value = response.header(name);
        if (value != null) normalized.header(name, value);
      }
      return normalized.build();
    };
  }

  @Override
  public JSONObject request(String method, String path, JSONObject body) {
    available();
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException(
          "Payment network calls must run outside a database transaction");
    var request =
        new HttpRequest.Builder()
            .httpMethod(HttpMethod.valueOf(method))
            .url("https://api.mch.weixin.qq.com" + path)
            .addHeader("Accept", "application/json");
    if (body != null) request.body(new JsonRequestBody.Builder().body(body.toJSONString()).build());
    var response = client.execute(request.build(), Map.class).getServiceResponse();
    return response == null ? new JSONObject() : JSON.parseObject(JSON.toJSONString(response));
  }

  @Override
  public JSONObject notification(Map<String, String> headers, String body) {
    available();
    String timestamp = headers.get("wechatpay-timestamp");
    if (timestamp == null
        || !timestamp.matches("[0-9]{10}")
        || Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300)
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CALLBACK", "回调时间无效");
    try {
      var request =
          new RequestParam.Builder()
              .serialNumber(headers.get("wechatpay-serial"))
              .nonce(headers.get("wechatpay-nonce"))
              .timestamp(timestamp)
              .signature(headers.get("wechatpay-signature"))
              .body(body)
              .build();
      return JSON.parseObject(JSON.toJSONString(parser.parse(request, Map.class)));
    } catch (com.wechat.pay.java.core.exception.WechatPayException | IllegalArgumentException e) {
      // SDK exceptions contain signed payloads; they must never enter logs or public responses.
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CALLBACK", "回调签名或密文无效");
    }
  }

  @Override
  public String sign(String message) {
    available();
    return config.createSigner().sign(message).getSign();
  }

  @Override
  public byte[] download(String url) {
    available();
    URI uri = URI.create(url);
    if (!"https".equals(uri.getScheme())
        || !"api.mch.weixin.qq.com".equals(uri.getHost())
        || uri.getUserInfo() != null
        || (uri.getPort() != -1 && uri.getPort() != 443))
      throw new IllegalArgumentException("账单下载地址无效");
    try (var stream = client.download(url)) {
      byte[] bytes = stream.readNBytes(20 * 1024 * 1024 + 1);
      if (bytes.length > 20 * 1024 * 1024) throw new IllegalArgumentException("账单超过20MiB限制");
      return bytes;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("账单下载失败", e);
    }
  }

  private void available() {
    if (client == null)
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE", "真实支付未启用");
  }
}
