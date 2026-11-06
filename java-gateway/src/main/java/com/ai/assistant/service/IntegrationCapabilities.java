package com.ai.assistant.service;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class IntegrationCapabilities {
  private final Environment env;

  public IntegrationCapabilities(Environment env) {
    this.env = env;
  }

  public Map<String, Object> snapshot() {
    return Map.of(
        "paymentMode",
        env.getProperty("payment.mode", "SIMULATED"),
        "sms",
        "aliyun"
            .equals(
                env.getProperty(
                    "integration.sms.provider", env.getProperty("SMS_PROVIDER", "disabled"))),
        "maps",
        !env.getProperty("integration.maps.api-key", env.getProperty("AMAP_API_KEY", "")).isBlank()
            || !env.getProperty("AMAP_API_KEY_FILE", "").isBlank(),
        "media",
        true,
        "storage",
        env.getProperty(
            "integration.storage.provider", env.getProperty("STORAGE_PROVIDER", "local")));
  }
}
