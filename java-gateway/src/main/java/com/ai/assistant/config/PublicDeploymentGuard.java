package com.ai.assistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PublicDeploymentGuard implements CommandLineRunner {
  private final JdbcTemplate jdbc;
  private final boolean secure;
  private final boolean seed;

  public PublicDeploymentGuard(
      JdbcTemplate jdbc,
      @Value("${auth.cookie-secure:false}") boolean secure,
      @Value("${app.demo-seed.enabled:false}") boolean seed) {
    this.jdbc = jdbc;
    this.secure = secure;
    this.seed = seed;
  }

  public void run(String... args) {
    if (!secure) return;
    if (seed) throw new IllegalStateException("HTTPS 部署禁止启用弱密码演示种子");
    var encoder = new BCryptPasswordEncoder();
    for (var row :
        jdbc.queryForList(
            "SELECT username,password FROM user WHERE username='demo' AND enabled=TRUE"))
      if (encoder.matches("123456", (String) row.get("password")))
        throw new IllegalStateException("公网部署前请停用或修改旧演示用户密码");
    for (var row :
        jdbc.queryForList(
            "SELECT username,password FROM admin_user WHERE username='admin' AND enabled=TRUE"))
      if (encoder.matches("admin123", (String) row.get("password")))
        throw new IllegalStateException("公网部署前请停用或修改旧演示老板密码");
  }
}
