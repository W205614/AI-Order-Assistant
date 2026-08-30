package com.ai.assistant.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywayConfigurationDefaultsTest {

    @Test
    void packagedDefaultsAdoptExistingSchemaAndDisableLegacySqlInit() throws IOException {
        Properties properties = new Properties();
        try (var input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            properties.load(input);
        }

        assertEquals("true", properties.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("0", properties.getProperty("spring.flyway.baseline-version"));
        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
    }
}
