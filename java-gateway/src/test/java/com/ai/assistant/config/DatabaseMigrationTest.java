package com.ai.assistant.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.MySQLContainer;

class DatabaseMigrationTest {
  @Test void rotatesOnlyLegacyPasswordsAndInvalidatesTheirSessions() {
    try (var mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("rotation_test")) {
      mysql.start();
      var db = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
      db.execute("CREATE TABLE user(id BIGINT PRIMARY KEY,username VARCHAR(50),password VARCHAR(100),token_version INT NOT NULL DEFAULT 0)");
      var encoder = new BCryptPasswordEncoder();
      db.update("INSERT INTO user(id,username,password) VALUES(1,'demo',?)", encoder.encode("123456"));
      String replacement = "independent-strong-demo-password";
      DatabaseMigration.rotateLegacyAccount(db, "user", "demo", "123456", replacement);
      String hash = db.queryForObject("SELECT password FROM user WHERE id=1", String.class);
      assertTrue(encoder.matches(replacement, hash));
      assertFalse(encoder.matches("123456", hash));
      assertEquals(1, db.queryForObject("SELECT token_version FROM user WHERE id=1", Integer.class));
      DatabaseMigration.rotateLegacyAccount(db, "user", "demo", "123456", "another-strong-password");
      assertEquals(hash, db.queryForObject("SELECT password FROM user WHERE id=1", String.class));
      assertEquals(1, db.queryForObject("SELECT token_version FROM user WHERE id=1", Integer.class));
    }
  }
}
