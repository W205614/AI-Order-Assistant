package com.ai.assistant.service;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class PaymentSettings {
  public final String mode,
      mchId,
      appId,
      appSecret,
      privateKeyPath,
      serial,
      publicKeyId,
      publicKeyPath,
      apiV3Key,
      notifyBase;

  public PaymentSettings(Environment env) {
    mode = env.getProperty("payment.mode", "SIMULATED");
    if (!java.util.Set.of("SIMULATED", "WECHAT_PARTNER").contains(mode))
      throw new IllegalArgumentException("PAYMENT_MODE must be SIMULATED or WECHAT_PARTNER");
    mchId = env.getProperty("payment.wechat.mch-id", "");
    appId = env.getProperty("payment.wechat.app-id", "");
    appSecret = secret(env, "payment.wechat.app-secret", "WECHAT_APP_SECRET");
    privateKeyPath = env.getProperty("payment.wechat.private-key-path", "");
    serial = env.getProperty("payment.wechat.certificate-serial", "");
    publicKeyId = env.getProperty("payment.wechat.public-key-id", "");
    publicKeyPath = env.getProperty("payment.wechat.public-key-path", "");
    apiV3Key = secret(env, "payment.wechat.api-v3-key", "WECHAT_API_V3_KEY");
    notifyBase = env.getProperty("payment.wechat.notify-base-url", "").replaceAll("/+$", "");
    if (real()) {
      for (String value :
          java.util.List.of(
              mchId,
              appId,
              appSecret,
              privateKeyPath,
              serial,
              publicKeyId,
              publicKeyPath,
              apiV3Key,
              notifyBase))
        if (value.isBlank())
          throw new IllegalArgumentException("WECHAT_PARTNER configuration is incomplete");
      URI base = URI.create(notifyBase);
      if (!"https".equals(base.getScheme())
          || base.getHost() == null
          || base.getRawQuery() != null
          || base.getUserInfo() != null
          || !base.getPath().isEmpty())
        throw new IllegalArgumentException("WECHAT_NOTIFY_BASE_URL must be an HTTPS origin");
      if (apiV3Key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32)
        throw new IllegalArgumentException("WECHAT_API_V3_KEY must be 32 bytes");
    }
  }

  public boolean real() {
    return "WECHAT_PARTNER".equals(mode);
  }

  private static String secret(Environment env, String property, String variable) {
    String path = env.getProperty(variable + "_FILE", "");
    if (path.isBlank()) return env.getProperty(property, "");
    try {
      return java.nio.file.Files.readString(java.nio.file.Path.of(path)).strip();
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Cannot read " + variable + "_FILE", e);
    }
  }
}
