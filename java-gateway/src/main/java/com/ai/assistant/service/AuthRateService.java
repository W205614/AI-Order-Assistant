package com.ai.assistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthRateService {
  private final JdbcTemplate jdbc;

  public AuthRateService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void check(String action, String address, String account) {
    window(action + ":ip:" + address, action.equals("register") ? 10 : 30, 60);
    window(action + ":account:" + account, action.equals("register") ? 3 : 10, 300);
  }

  private void window(String raw, int max, int seconds) {
    String key;
    try {
      key =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    jdbc.update(
        "INSERT INTO auth_rate_window(bucket_key,hits,expires_at)"
            + " VALUES(?,1,DATE_ADD(NOW(),INTERVAL ? SECOND)) ON DUPLICATE KEY UPDATE"
            + " hits=IF(expires_at<=NOW(),1,LEAST(hits+1,100000)),expires_at=IF(expires_at<=NOW(),VALUES(expires_at),expires_at)",
        key,
        seconds);
    int hits =
        jdbc.queryForObject(
            "SELECT hits FROM auth_rate_window WHERE bucket_key=?", Integer.class, key);
    if (hits > max)
      throw new BusinessException(
          HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMITED", "操作过于频繁，请稍后重试");
  }
}
