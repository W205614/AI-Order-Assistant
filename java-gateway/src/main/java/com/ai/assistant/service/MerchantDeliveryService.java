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
    return one(db, "SELECT campus_id,pickup_point,preparation_min,preparation_max FROM merchant WHERE id=?", UserContext.merchantId());
  }

  @Transactional
  public void rule(RuleInput x) {
    UserContext.requireRole("OWNER");
    validCampus(x.campusId());
    money(x.fee());
    money(x.minimumAmount());
    if (x.pickupMinimumAmount() != null) money(x.pickupMinimumAmount());
    check(x.etaMin() >= 0 && x.etaMax() >= x.etaMin() && x.etaMax() <= 1440, "预计送达时间无效");
    long m = UserContext.merchantId();
    one(db, "SELECT id FROM merchant WHERE id=? FOR UPDATE", m);
    var rows =
        db.queryForList(
            "SELECT * FROM delivery_rule WHERE merchant_id=? AND campus_id=?", m, x.campusId());
    var pickupMinimum = x.pickupMinimumAmount() != null
        ? x.pickupMinimumAmount()
        : rows.isEmpty() ? x.minimumAmount() : amount(rows.getFirst(), "pickup_minimum_amount");
    if (rows.isEmpty()) {
      check(x.expectedVersion() == 0, "配送规则版本无效");
      db.update(
          "INSERT INTO"
              + " delivery_rule(merchant_id,campus_id,enabled,fee,minimum_amount,eta_min,eta_max,pickup_minimum_amount)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          m,
          x.campusId(),
          x.enabled(),
          x.fee(),
          x.minimumAmount(),
          x.etaMin(),
          x.etaMax(),
          pickupMinimum);
    } else
      check(
          db.update(
                  "UPDATE delivery_rule SET"
                      + " enabled=?,fee=?,minimum_amount=?,eta_min=?,eta_max=?,pickup_minimum_amount=?,version=version+1"
                      + " WHERE merchant_id=? AND campus_id=? AND version=?",
                  x.enabled(),
                  x.fee(),
                  x.minimumAmount(),
                  x.etaMin(),
                  x.etaMax(),
                  pickupMinimum,
                  m,
                  x.campusId(),
                  x.expectedVersion())
              == 1,
          "配送规则版本已变化");
    audit.record("DELIVERY_RULE_CHANGED", x.campusId());
  }

  @Transactional
  public void pickupPoint(String point) {
    UserContext.requireRole("OWNER");
    check(point != null && point.length() <= 160, "取餐点最多160个字符");
    db.update("UPDATE merchant SET pickup_point=?,version=version+1 WHERE id=?",
        point.strip(), UserContext.merchantId());
    audit.record("PICKUP_POINT_CHANGED", UserContext.merchantId());
  }

  @Transactional
  public void preparation(int min, int max) {
    UserContext.requireRole("OWNER");
    check(min >= 0 && max >= min && max <= 1440, "预计备餐区间无效");
    db.update("UPDATE merchant SET preparation_min=?,preparation_max=?,version=version+1 WHERE id=?",
        min, max, UserContext.merchantId());
    audit.record("PREPARATION_RANGE_CHANGED", UserContext.merchantId());
  }

  private void validCampus(long campus) {
    one(db, "SELECT id FROM campus WHERE id=? AND enabled=TRUE", campus);
  }
}
