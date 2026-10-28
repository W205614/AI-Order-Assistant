package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantDeliveryService {
  private final JdbcTemplate db;
  private final AuditService audit;

  public MerchantDeliveryService(JdbcTemplate db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  @Transactional
  public void homeCampus(long campus) {
    UserContext.requireRole("OWNER");
    validCampus(campus);
    db.update(
        "UPDATE merchant SET campus_id=?,version=version+1 WHERE id=?",
        campus,
        UserContext.merchantId());
    audit.record("HOME_CAMPUS_CHANGED", campus);
  }

  public List<Map<String, Object>> rules() {
    UserContext.requireRole("OWNER", "STAFF");
    return db.queryForList(
        "SELECT * FROM delivery_rule WHERE merchant_id=? ORDER BY campus_id",
        UserContext.merchantId());
  }

  public Map<String, Object> homeCampus() {
    UserContext.requireRole("OWNER", "STAFF");
    return one(db, "SELECT campus_id FROM merchant WHERE id=?", UserContext.merchantId());
  }

  @Transactional
  public void rule(RuleInput x) {
    UserContext.requireRole("OWNER");
    validCampus(x.campusId());
    money(x.fee());
    money(x.minimumAmount());
    check(x.etaMin() >= 0 && x.etaMax() >= x.etaMin() && x.etaMax() <= 1440, "预计送达时间无效");
    long m = UserContext.merchantId();
    one(db, "SELECT id FROM merchant WHERE id=? FOR UPDATE", m);
    var rows =
        db.queryForList(
            "SELECT * FROM delivery_rule WHERE merchant_id=? AND campus_id=?", m, x.campusId());
    if (rows.isEmpty()) {
      check(x.expectedVersion() == 0, "配送规则版本无效");
      db.update(
          "INSERT INTO"
              + " delivery_rule(merchant_id,campus_id,enabled,fee,minimum_amount,eta_min,eta_max)"
              + " VALUES(?,?,?,?,?,?,?)",
          m,
          x.campusId(),
          x.enabled(),
          x.fee(),
          x.minimumAmount(),
          x.etaMin(),
          x.etaMax());
    } else
      check(
          db.update(
                  "UPDATE delivery_rule SET"
                      + " enabled=?,fee=?,minimum_amount=?,eta_min=?,eta_max=?,version=version+1"
                      + " WHERE merchant_id=? AND campus_id=? AND version=?",
                  x.enabled(),
                  x.fee(),
                  x.minimumAmount(),
                  x.etaMin(),
                  x.etaMax(),
                  m,
                  x.campusId(),
                  x.expectedVersion())
              == 1,
          "配送规则版本已变化");
    audit.record("DELIVERY_RULE_CHANGED", x.campusId());
  }

  private void validCampus(long campus) {
    one(db, "SELECT id FROM campus WHERE id=? AND enabled=TRUE", campus);
  }
}
