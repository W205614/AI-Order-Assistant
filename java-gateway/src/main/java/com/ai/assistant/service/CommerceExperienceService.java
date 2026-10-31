package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceExperienceService {
  private final JdbcTemplate db;
  private final CommerceCheckoutService checkouts;
  private final AuditService audit;

  public CommerceExperienceService(
      JdbcTemplate db, CommerceCheckoutService checkouts, AuditService audit) {
    this.db = db;
    this.checkouts = checkouts;
    this.audit = audit;
  }

  @Transactional
  public long feedback(FeedbackInput x) {
    checkouts.authorizedOrder(x.orderId());
    long u = user();
    text(x.message(), 1000, "反馈内容");
    return insert(
        db,
        "INSERT INTO feedback(user_id,order_id,message) VALUES(?,?,?)",
        u,
        x.orderId(),
        x.message());
  }

  public List<Map<String, Object>> feedbacks() {
    return feedbacks("all", 1, null);
  }

  public List<Map<String, Object>> feedbacks(String scope, int page, Integer pageSize) {
    String role = UserContext.role();
    boolean platform = "PLATFORM_ADMIN".equals(role);
    boolean customer = "CUSTOMER".equals(role);
    if (!platform && !customer) UserContext.requireRole("OWNER", "STAFF");
    int size = pageSize == null ? (platform ? 200 : 100) : pageSize;
    long offset = pageOffset(scope, page, size);
    String filter = "active".equals(scope) ? " AND f.status='OPEN'"
        : "history".equals(scope) ? " AND f.status='CLOSED'" : "";
    String from = " FROM feedback f";
    String where = " WHERE 1=1";
    var params = new ArrayList<Object>();
    if (customer) {
      where += " AND f.user_id=?";
      params.add(user());
    } else if (!platform) {
      from += " JOIN orders o ON o.id=f.order_id";
      where += " AND o.merchant_id=?";
      params.add(UserContext.merchantId());
    }
    params.add(size);
    params.add(offset);
    return db.queryForList("SELECT f.*" + from + where + filter
        + " ORDER BY f.id DESC LIMIT ? OFFSET ?", params.toArray());
  }

  @Transactional
  public void reply(long id, String reply) {
    UserContext.requireRole("OWNER", "STAFF", "PLATFORM_ADMIN");
    text(reply, 1000, "回复");
    var f = one(db, "SELECT * FROM feedback WHERE id=?", id);
    var o = checkouts.lockOrder(id(f, "order_id"));
    db.update("UPDATE feedback SET reply=?,status='CLOSED' WHERE id=?", reply, id);
    audit.record(id(o, "merchant_id"), "FEEDBACK_REPLIED", id);
  }

  public List<Map<String, Object>> report() {
    UserContext.requireRole("OWNER", "STAFF", "PLATFORM_ADMIN");
    String where = "PLATFORM_ADMIN".equals(UserContext.role()) ? "" : " AND o.merchant_id=?";
    Object[] params = where.isEmpty() ? new Object[] {} : new Object[] {UserContext.merchantId()};
    return db.queryForList(
        "SELECT o.campus_id,o.merchant_id,COUNT(*) order_count,SUM(o.total_amount)"
            + " original_payable,SUM(CASE WHEN o.payment_status NOT IN ('UNPAID','CLOSED') THEN"
            + " o.total_amount ELSE 0 END) paid_amount,SUM(o.refunded_amount)"
            + " refunded_amount,SUM(CASE WHEN o.payment_status NOT IN ('UNPAID','CLOSED') THEN"
            + " o.total_amount-o.refunded_amount ELSE 0 END) net_collected,SUM(CASE WHEN o.status=4"
            + " THEN o.total_amount-o.refunded_amount ELSE 0 END) completed_net,SUM(CASE WHEN"
            + " o.payment_status NOT IN ('UNPAID','CLOSED') THEN"
            + " o.shop_discount-COALESCE(r.shop_refund,0) ELSE 0 END)"
            + " merchant_promotion_cost,SUM(CASE WHEN o.payment_status NOT IN ('UNPAID','CLOSED')"
            + " THEN o.platform_discount-COALESCE(r.platform_refund,0) ELSE 0 END)"
            + " platform_promotion_cost FROM orders o LEFT JOIN (SELECT order_id,SUM(shop_discount)"
            + " shop_refund,SUM(platform_discount) platform_refund FROM order_unit WHERE"
            + " status='REFUNDED' GROUP BY order_id) r ON r.order_id=o.id WHERE"
            + " o.commerce_version=2"
            + where
            + " GROUP BY o.campus_id,o.merchant_id ORDER BY o.campus_id,o.merchant_id",
        params);
  }

  public List<Map<String, Object>> productReport() {
    UserContext.requireRole("OWNER", "STAFF", "PLATFORM_ADMIN");
    String filter = "PLATFORM_ADMIN".equals(UserContext.role()) ? "" : " AND o.merchant_id=?";
    return db.queryForList(
        "SELECT o.campus_id,o.merchant_id,i.product_type,i.product_id,i.dish_name,COUNT(*)"
            + " sold_units,SUM(u.status='REFUNDED') refunded_units,SUM(CASE WHEN"
            + " u.status<>'REFUNDED' THEN u.goods_paid+u.packaging_paid ELSE 0 END) retained_amount"
            + " FROM orders o JOIN order_item i ON i.order_id=o.id JOIN order_unit u ON"
            + " u.order_item_id=i.id WHERE o.commerce_version=2 AND o.payment_status NOT IN"
            + " ('UNPAID','CLOSED')"
            + filter
            + " GROUP BY o.campus_id,o.merchant_id,i.product_type,i.product_id,i.dish_name ORDER BY"
            + " o.merchant_id,i.product_type,i.product_id LIMIT 500",
        filter.isEmpty() ? new Object[] {} : new Object[] {UserContext.merchantId()});
  }
}
