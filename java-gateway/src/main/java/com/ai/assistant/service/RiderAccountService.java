package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Service
public class RiderAccountService {
  private final JdbcTemplate db;
  private final AuditService audit;

  public RiderAccountService(JdbcTemplate db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  @Transactional
  public long rider(RiderInput x) {
    UserContext.requireRole("PLATFORM_ADMIN");
    check(x.username() != null && x.username().matches("[A-Za-z0-9_-]{3,50}"), "骑手账号无效");
    com.ai.assistant.security.AuthService.validPassword(x.password());
    check(
        x.campusIds() != null && !x.campusIds().isEmpty() && x.campusIds().size() <= 100,
        "请选择服务校区");
    for (long c : x.campusIds()) validCampus(c);
    long id =
        insert(
            db,
            "INSERT INTO admin_user(username,password,merchant_id,role,created_at)"
                + " VALUES(?,?,NULL,'RIDER',NOW())",
            x.username(),
            new BCryptPasswordEncoder().encode(x.password()));
    for (long c : new TreeSet<>(x.campusIds()))
      db.update("INSERT INTO rider_campus VALUES(?,?)", id, c);
    audit.record(null, "RIDER_CREATED", id);
    return id;
  }
  public List<Map<String, Object>> list() {
    UserContext.requireRole("PLATFORM_ADMIN");
    return db.queryForList("SELECT a.id,a.username,a.enabled,GROUP_CONCAT(rc.campus_id ORDER BY rc.campus_id)"
        + " campus_ids FROM admin_user a LEFT JOIN rider_campus rc ON rc.rider_id=a.id WHERE"
        + " a.role='RIDER' GROUP BY a.id ORDER BY a.id");
  }

  private void validCampus(long campus) {
    one(db, "SELECT id FROM campus WHERE id=? AND enabled=TRUE", campus);
  }
}
