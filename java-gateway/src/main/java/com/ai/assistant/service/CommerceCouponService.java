package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceCouponService {
  private final JdbcTemplate db;
  private final OrderSafetyService safety;
  private final AuditService audit;

  public CommerceCouponService(JdbcTemplate db, OrderSafetyService safety, AuditService audit) {
    this.db = db;
    this.safety = safety;
    this.audit = audit;
  }

  public List<Map<String, Object>> campaigns() {
    return db.queryForList(
        "SELECT * FROM coupon_campaign WHERE enabled=TRUE AND claim_start<=? AND claim_end>? ORDER"
            + " BY id LIMIT 200",
        BusinessTime.now(),
        BusinessTime.now());
  }

  public List<Map<String, Object>> mine() {
    long u = user();
    return db.queryForList(
        "SELECT cc.id,cc.campaign_id,CASE WHEN cc.status='AVAILABLE' AND c.valid_end<=? THEN"
            + " 'EXPIRED' ELSE cc.status END"
            + " status,cc.checkout_id,c.merchant_id,c.name,c.threshold_amount,c.discount_amount,c.valid_start,c.valid_end"
            + " FROM customer_coupon cc JOIN coupon_campaign c ON c.id=cc.campaign_id WHERE"
            + " cc.user_id=? ORDER BY cc.id DESC LIMIT 500",
        BusinessTime.now(),
        u);
  }

  public List<Map<String, Object>> managed() {
    UserContext.requireRole("OWNER", "PLATFORM_ADMIN");
    return "PLATFORM_ADMIN".equals(UserContext.role())
        ? db.queryForList(
            "SELECT * FROM coupon_campaign WHERE merchant_id IS NULL ORDER BY id DESC LIMIT 200")
        : db.queryForList(
            "SELECT * FROM coupon_campaign WHERE merchant_id=? ORDER BY id DESC LIMIT 200",
            UserContext.merchantId());
  }

  @Transactional
  public long create(CouponInput x) {
    UserContext.requireRole("OWNER", "PLATFORM_ADMIN");
    text(x.name(), 100, "活动名称");
    money(x.thresholdAmount());
    money(x.discountAmount());
    check(
        x.discountAmount().signum() > 0 && x.totalCount() > 0 && x.totalCount() <= 1000000,
        "发行数量或优惠金额无效");
    check(
        x.claimStart() != null
            && x.claimEnd() != null
            && x.validStart() != null
            && x.validEnd() != null
            && x.claimStart().isBefore(x.claimEnd())
            && x.validStart().isBefore(x.validEnd())
            && !x.claimEnd().isAfter(x.validEnd()),
        "优惠券时间范围无效");
    Long m = "PLATFORM_ADMIN".equals(UserContext.role()) ? null : UserContext.merchantId();
    long id =
        insert(
            db,
            "INSERT INTO"
                + " coupon_campaign(merchant_id,name,threshold_amount,discount_amount,total_count,claim_start,claim_end,valid_start,valid_end)"
                + " VALUES(?,?,?,?,?,?,?,?,?)",
            m,
            x.name(),
            x.thresholdAmount(),
            x.discountAmount(),
            x.totalCount(),
            x.claimStart(),
            x.claimEnd(),
            x.validStart(),
            x.validEnd());
    audit.record(m, "COUPON_CAMPAIGN_CREATED", id);
    return id;
  }

  @Transactional
  public long claim(long campaign) {
    long u = user();
    safety.lockUser(u);
    var c = one(db, "SELECT * FROM coupon_campaign WHERE id=? FOR UPDATE", campaign);
    var existing =
        db.queryForList(
            "SELECT id FROM customer_coupon WHERE user_id=? AND campaign_id=?", u, campaign);
    if (!existing.isEmpty()) return id(existing.getFirst(), "id");
    check(
        db.update(
                "UPDATE coupon_campaign SET claimed_count=claimed_count+1 WHERE id=? AND"
                    + " enabled=TRUE AND claimed_count<total_count AND claim_start<=? AND"
                    + " claim_end>?",
                campaign,
                BusinessTime.now(),
                BusinessTime.now())
            == 1,
        "活动未开放、已结束或优惠券领完");
    long id =
        insert(db, "INSERT INTO customer_coupon(user_id,campaign_id) VALUES(?,?)", u, campaign);
    ledger(id, null, "CLAIM");
    return id;
  }

  public BigDecimal discount(Long coupon, Long merchant, BigDecimal basis) {
    if (coupon == null) return ZERO;
    var c =
        one(
            db,
            "SELECT"
                + " cc.*,c.merchant_id,c.threshold_amount,c.discount_amount,c.valid_start,c.valid_end,c.enabled"
                + " FROM customer_coupon cc JOIN coupon_campaign c ON c.id=cc.campaign_id WHERE"
                + " cc.id=? AND cc.user_id=?",
            coupon,
            user());
    check(
        Objects.equals(c.get("merchant_id") == null ? null : id(c, "merchant_id"), merchant),
        "优惠券适用店铺不符");
    var now = BusinessTime.now();
    check(
        "AVAILABLE".equals(c.get("status"))
            && Boolean.TRUE.equals(c.get("enabled"))
            && !now.isBefore(time(c, "valid_start"))
            && now.isBefore(time(c, "valid_end")),
        "优惠券不可用或已过期");
    check(basis.compareTo(amount(c, "threshold_amount")) >= 0, "未达到优惠门槛");
    return amount(c, "discount_amount").min(basis);
  }

  public void lock(List<Long> coupons, String checkout) {
    for (long c : new TreeSet<>(coupons)) {
      check(
          db.update(
                  "UPDATE customer_coupon cc JOIN coupon_campaign c ON c.id=cc.campaign_id SET"
                      + " cc.status='LOCKED',cc.checkout_id=? WHERE cc.id=? AND cc.user_id=? AND"
                      + " cc.status='AVAILABLE' AND c.enabled=TRUE AND c.valid_start<=? AND"
                      + " c.valid_end>?",
                  checkout,
                  c,
                  user(),
                  BusinessTime.now(),
                  BusinessTime.now())
              == 1,
          "优惠券已变化，请重新报价");
      ledger(c, checkout, "LOCK");
    }
  }

  public void paid(String checkout) {
    for (var c :
        db.queryForList(
            "SELECT id FROM customer_coupon WHERE checkout_id=? AND status='LOCKED' ORDER BY id FOR"
                + " UPDATE",
            checkout)) {
      db.update("UPDATE customer_coupon SET status='USED' WHERE id=?", id(c, "id"));
      ledger(id(c, "id"), checkout, "USE");
    }
  }

  public void release(String checkout, Long merchant, boolean unpaid) {
    for (var c :
        db.queryForList(
            "SELECT cc.id,c.valid_end FROM customer_coupon cc JOIN coupon_campaign c ON"
                + " c.id=cc.campaign_id WHERE cc.checkout_id=? AND cc.status IN ('LOCKED','USED')"
                + " AND "
                + (unpaid ? "TRUE" : merchant == null ? "c.merchant_id IS NULL" : "c.merchant_id=?")
                + " ORDER BY cc.id FOR UPDATE",
            unpaid || merchant == null
                ? new Object[] {checkout}
                : new Object[] {checkout, merchant})) {
      String status = time(c, "valid_end").isAfter(BusinessTime.now()) ? "AVAILABLE" : "EXPIRED";
      db.update(
          "UPDATE customer_coupon SET status=?,checkout_id=NULL WHERE id=?", status, id(c, "id"));
      ledger(id(c, "id"), checkout, "RELEASE");
    }
  }

  private void ledger(long coupon, String checkout, String action) {
    db.update(
        "INSERT INTO coupon_ledger(coupon_id,checkout_id,action) VALUES(?,?,?)",
        coupon,
        checkout,
        action);
  }

  @Transactional
  public void enabled(long campaign, boolean enabled) {
    UserContext.requireRole("OWNER", "PLATFORM_ADMIN");
    var c = one(db, "SELECT * FROM coupon_campaign WHERE id=? FOR UPDATE", campaign);
    if ("PLATFORM_ADMIN".equals(UserContext.role()))
      check(c.get("merchant_id") == null, "平台入口仅管理平台券");
    else
      check(
          c.get("merchant_id") != null && id(c, "merchant_id") == UserContext.merchantId(),
          "无权管理该优惠券");
    db.update("UPDATE coupon_campaign SET enabled=? WHERE id=?", enabled, campaign);
    audit.record(
        c.get("merchant_id") == null ? null : id(c, "merchant_id"),
        "COUPON_CAMPAIGN_ENABLED",
        campaign);
  }
}
