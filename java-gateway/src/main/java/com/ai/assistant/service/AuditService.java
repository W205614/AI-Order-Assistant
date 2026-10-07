package com.ai.assistant.service;

import com.ai.assistant.security.UserContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate jdbc;

  public AuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(String action, Object resource) {
    record(UserContext.merchantId(), action, resource);
  }

  public void record(Long merchant, String action, Object resource) {
    jdbc.update(
        "INSERT INTO audit_log(merchant_id,actor,action,resource_id) VALUES(?,?,?,?)",
        merchant,
        UserContext.actor(),
        action,
        String.valueOf(resource));
  }

  public List<Map<String, Object>> list(Long merchant, long before) {
    if (merchant == null)
      return jdbc.queryForList(
          "SELECT * FROM audit_log WHERE id<? ORDER BY id DESC LIMIT 100", before);
    return jdbc.queryForList(
        "SELECT * FROM audit_log WHERE merchant_id=? AND id<? ORDER BY id DESC LIMIT 100",
        merchant,
        before);
  }
}
