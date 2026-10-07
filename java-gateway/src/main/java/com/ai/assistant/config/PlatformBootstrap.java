package com.ai.assistant.config;

import com.ai.assistant.security.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PlatformBootstrap implements CommandLineRunner {
  private final JdbcTemplate jdbc;
  private final String name, password;

  public PlatformBootstrap(
      JdbcTemplate jdbc,
      @Value("${app.bootstrap.username:}") String name,
      @Value("${app.bootstrap.password:}") String password) {
    this.jdbc = jdbc;
    this.name = name;
    this.password = password;
  }

  public void run(String... args) {
    if (name.isBlank() && password.isBlank()) return;
    if (!name.matches("[A-Za-z0-9_-]{3,50}"))
      throw new IllegalStateException("PLATFORM_ADMIN_USERNAME格式错误");
    AuthService.validPassword(password);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM admin_user WHERE role='PLATFORM_ADMIN'", Integer.class)
        == 0)
      jdbc.update(
          "INSERT INTO admin_user(username,password,merchant_id,role,created_at)"
              + " VALUES(?,?,NULL,'PLATFORM_ADMIN',NOW())",
          name,
          new BCryptPasswordEncoder().encode(password));
  }
}
