package com.ai.assistant.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FlywayConfigurationDefaultsTest {

  @Test
  void packagedDefaultsAdoptExistingSchemaAndDisableLegacySqlInit() throws IOException {
    Properties properties = new Properties();
    try (var input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
      properties.load(input);
    }

    assertEquals(
        "${FLYWAY_BASELINE_ON_MIGRATE:false}",
        properties.getProperty("spring.flyway.baseline-on-migrate"));
    assertEquals("0", properties.getProperty("spring.flyway.baseline-version"));
    assertEquals("never", properties.getProperty("spring.sql.init.mode"));
  }
}
