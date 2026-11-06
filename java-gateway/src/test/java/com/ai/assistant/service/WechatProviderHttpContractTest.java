package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.*;
import com.sun.net.httpserver.HttpServer;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.Test;

class WechatProviderHttpContractTest {
  @Test
  void actualSdkSignsHttpAndVerifiesResponseAndEncryptedNotification() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair merchant = generator.generateKeyPair(), platform = generator.generateKeyPair();
    String apiKey = "12345678901234567890123456789012",
        serial = "PUB_KEY_ID_TEST",
        merchantSerial = "merchant-test";
    var config =
        new RSAPublicKeyConfig.Builder()
            .merchantId("1234567890")
            .merchantSerialNumber(merchantSerial)
            .privateKey(merchant.getPrivate())
            .publicKey(platform.getPublic())
            .publicKeyId(serial)
            .apiV3Key(apiKey)
            .build();
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    server.createContext(
        "/v3/combine-transactions/native",
        exchange -> {
          try {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            var fields = new HashMap<String, String>();
            var matcher = java.util.regex.Pattern.compile("(\\w+)=\"([^\"]+)\"").matcher(auth);
            while (matcher.find()) fields.put(matcher.group(1), matcher.group(2));
            assertEquals("1234567890", fields.get("mchid"));
            String message =
                "POST\n/v3/combine-transactions/native\n"
                    + fields.get("timestamp")
                    + "\n"
                    + fields.get("nonce_str")
                    + "\n"
                    + body
                    + "\n";
            Signature verify = Signature.getInstance("SHA256withRSA");
            verify.initVerify(merchant.getPublic());
            verify.update(message.getBytes(StandardCharsets.UTF_8));
            assertTrue(verify.verify(Base64.getDecoder().decode(fields.get("signature"))));
            String result = "{\"code_url\":\"weixin://wxpay/test\"}",
                stamp = Long.toString(Instant.now().getEpochSecond()),
                nonce = "provider-response";
            Signature sign = Signature.getInstance("SHA256withRSA");
            sign.initSign(platform.getPrivate());
            sign.update(
                (stamp + "\n" + nonce + "\n" + result + "\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Wechatpay-Timestamp", stamp);
            exchange.getResponseHeaders().set("Wechatpay-Nonce", nonce);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Wechatpay-Serial", serial);
            exchange
                .getResponseHeaders()
                .set("Wechatpay-Signature", Base64.getEncoder().encodeToString(sign.sign()));
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Throwable e) {
            failure.set(e);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var network =
          new okhttp3.OkHttpClient.Builder()
              .addInterceptor(WechatPaymentProvider.responseHeaders())
              .addInterceptor(
                  chain -> {
                    var original = chain.request();
                    var url =
                        original
                            .url()
                            .newBuilder()
                            .scheme("http")
                            .host("127.0.0.1")
                            .port(server.getAddress().getPort())
                            .build();
                    return chain.proceed(original.newBuilder().url(url).build());
                  })
              .build();
      var client =
          new DefaultHttpClientBuilder()
              .config(config)
              .okHttpClient(network)
              .disableRetryOnConnectionFailure()
              .build();
      var provider = new WechatPaymentProvider(config, client);
      assertEquals(
          "weixin://wxpay/test",
          provider
              .request(
                  "POST",
                  "/v3/combine-transactions/native",
                  JSONObject.of("combine_out_trade_no", "test"))
              .getString("code_url"));
      assertNull(failure.get());
      String nonce = "012345678901",
          aad = "transaction",
          clear = "{\"combine_out_trade_no\":\"test\"}";
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "AES"),
          new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      String body =
          JSONObject.of(
                  "id",
                  "callback-1",
                  "resource",
                  JSONObject.of(
                      "algorithm",
                      "AEAD_AES_256_GCM",
                      "nonce",
                      nonce,
                      "associated_data",
                      aad,
                      "ciphertext",
                      Base64.getEncoder()
                          .encodeToString(cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8)))))
              .toJSONString();
      String stamp = Long.toString(Instant.now().getEpochSecond()), headerNonce = "signed-callback";
      Signature sign = Signature.getInstance("SHA256withRSA");
      sign.initSign(platform.getPrivate());
      sign.update(
          (stamp + "\n" + headerNonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
      var headers =
          Map.of(
              "wechatpay-timestamp",
              stamp,
              "wechatpay-nonce",
              headerNonce,
              "wechatpay-serial",
              serial,
              "wechatpay-signature",
              Base64.getEncoder().encodeToString(sign.sign()));
      assertEquals("test", provider.notification(headers, body).getString("combine_out_trade_no"));
      assertThrows(
          BusinessException.class,
          () -> provider.notification(headers, body.replace("callback-1", "callback-2")));
      var expired = new HashMap<>(headers);
      expired.put("wechatpay-timestamp", "1000000000");
      assertThrows(BusinessException.class, () -> provider.notification(expired, body));
    } finally {
      server.stop(0);
    }
  }
}
