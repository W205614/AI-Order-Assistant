package com.ai.assistant.integration.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SupportSettings {
  private final Environment environment;

  public SupportSettings(Environment environment) {
    this.environment = environment;
  }

  public String value(String property, String variable, String fallback) {
    return environment.getProperty(property, environment.getProperty(variable, fallback)).trim();
  }

  public String required(String property, String variable) {
    String value = secret(property, variable);
    if (value.isBlank()) throw new IllegalStateException(variable + " is required");
    return value;
  }

  public String secret(String property, String variable) {
    String path = value(property + "-file", variable + "_FILE", "");
    if (path.isBlank()) return value(property, variable, "");
    try {
      return Files.readString(Path.of(path)).strip();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read secret file for " + variable, e);
    }
  }
}
