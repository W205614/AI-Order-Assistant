package com.ai.assistant.config;

import com.ai.assistant.security.AuthService;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** One-shot migration process; the HTTP application never receives this database credential. */
public final class DatabaseMigration {
  private DatabaseMigration() {}

  public static void main(String[] args) {
    var env = System.getenv();
    String url = required(env, "DB_URL");
    String password = required(env, "DB_MIGRATION_PASSWORD");
    Flyway.configure().dataSource(url, "ai_order_migrator", password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(Boolean.parseBoolean(env.getOrDefault("FLYWAY_BASELINE_ON_MIGRATE", "false")))
        .baselineVersion("0").load().migrate();
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "ai_order_migrator", password));
    rotateLegacyAccount(jdbc, "user", "demo", "123456", env.get("DEMO_CUSTOMER_PASSWORD"));
    rotateLegacyAccount(jdbc, "admin_user", "admin", "admin123", env.get("DEMO_OWNER_PASSWORD"));
    System.out.println("Database migration and legacy demo credential upgrade completed");
  }

  static void rotateLegacyAccount(JdbcTemplate jdbc, String table, String username,
      String legacyPassword, String replacement) {
    var encoder = new BCryptPasswordEncoder();
    for (var row : jdbc.queryForList("SELECT id,password FROM " + table + " WHERE username=?", username)) {
      if (encoder.matches(legacyPassword, (String) row.get("password"))) {
        AuthService.validPassword(replacement);
        jdbc.update("UPDATE " + table + " SET password=?,token_version=token_version+1 WHERE id=? AND password=?",
            encoder.encode(replacement), row.get("id"), row.get("password"));
      }
    }
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
