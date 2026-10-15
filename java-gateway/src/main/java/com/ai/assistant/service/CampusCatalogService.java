package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampusCatalogService {
  private final JdbcTemplate db;
  private final OrderSafetyService safety;
  private final AuditService audit;

  public CampusCatalogService(JdbcTemplate db, OrderSafetyService safety, AuditService audit) {
    this.db = db;
    this.safety = safety;
    this.audit = audit;
  }

  public List<Map<String, Object>> campuses() {
    return db.queryForList("SELECT * FROM campus WHERE enabled=TRUE ORDER BY id");
  }

  public List<Map<String, Object>> allCampuses() {
    UserContext.requireRole("OWNER", "STAFF", "PLATFORM_ADMIN");
    return db.queryForList("SELECT * FROM campus ORDER BY id");
  }

  @Transactional
  public long campus(String name) {
    UserContext.requireRole("PLATFORM_ADMIN");
    text(name, 100, "校区名称");
    long id = insert(db, "INSERT INTO campus(name) VALUES(?)", name.trim());
    audit.record(null, "CAMPUS_CREATED", id);
    return id;
  }

  public void campusEnabled(long id, boolean enabled) {
    UserContext.requireRole("PLATFORM_ADMIN");
    one(db, "SELECT id FROM campus WHERE id=?", id);
    db.update("UPDATE campus SET enabled=? WHERE id=?", enabled, id);
    audit.record(null, "CAMPUS_ENABLED", id);
  }

  public void validCampus(long campus) {
    one(db, "SELECT id FROM campus WHERE id=? AND enabled=TRUE", campus);
  }

  public List<Map<String, Object>> addresses() {
    return db.queryForList("SELECT * FROM customer_address WHERE user_id=? ORDER BY id", user());
  }

  @Transactional
  public long saveAddress(Long id, AddressInput x) {
    long u = user();
    safety.lockUser(u);
    validCampus(x.campusId());
    text(x.recipientName(), 50, "收货人");
    text(x.detail(), 255, "地址");
    check(x.phone() != null && x.phone().matches("[0-9+ -]{7,30}"), "联系电话无效");
    if (id == null)
      return insert(
          db,
          "INSERT INTO customer_address(user_id,campus_id,recipient_name,phone,detail)"
              + " VALUES(?,?,?,?,?)",
          u,
          x.campusId(),
          x.recipientName(),
          x.phone(),
          x.detail());
    check(
        db.update(
                "UPDATE customer_address SET"
                    + " campus_id=?,recipient_name=?,phone=?,detail=?,version=version+1 WHERE id=?"
                    + " AND user_id=? AND version=?",
                x.campusId(),
                x.recipientName(),
                x.phone(),
                x.detail(),
                id,
                u,
                x.expectedVersion())
            == 1,
        "地址版本已变化或无权访问");
    return id;
  }

  @Transactional
  public void deleteAddress(long id) {
    safety.lockUser(user());
    check(
        db.update("DELETE FROM customer_address WHERE id=? AND user_id=?", id, user()) == 1,
        "地址不存在");
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

  public List<Map<String, Object>> shops(long campus) {
    validCampus(campus);
    return db.queryForList(
        "SELECT"
            + " m.id,m.name,m.campus_id,m.accepting_orders,m.opens_at,m.closes_at,r.fee,r.minimum_amount,r.eta_min,r.eta_max"
            + " FROM merchant m JOIN delivery_rule r ON r.merchant_id=m.id AND r.campus_id=? AND"
            + " r.enabled=TRUE WHERE m.enabled=TRUE ORDER BY m.id LIMIT 200",
        campus);
  }

  @Transactional
  public void ensureDefaultSkus() {
    db.update(
        "INSERT IGNORE INTO product_sku(merchant_id,dish_id,name,is_default,price) SELECT"
            + " merchant_id,id,'默认规格',TRUE,price FROM dish");
  }

  @Transactional
  public List<Map<String, Object>> catalog(long merchant) {
    one(db, "SELECT id FROM merchant WHERE id=? AND enabled=TRUE", merchant);
    var result = new ArrayList<Map<String, Object>>();
    for (var r :
        db.queryForList(
            "SELECT s.id FROM product_sku s WHERE s.merchant_id=? ORDER BY s.id LIMIT 200",
            merchant)) {
      try {
        var p = resolve("SKU", id(r, "id"), 1, "");
        result.add(Map.of("product", p, "stock", available(p)));
      } catch (IllegalArgumentException ignored) {
        /* Unavailable/unsafe products are deliberately omitted. */
      }
    }
    for (var r :
        db.queryForList(
            "SELECT id FROM product_bundle WHERE merchant_id=? ORDER BY id LIMIT 100", merchant)) {
      try {
        var p = resolve("BUNDLE", id(r, "id"), 1, "");
        result.add(Map.of("product", p, "stock", available(p)));
      } catch (IllegalArgumentException ignored) {
      }
    }
    return result;
  }

  public List<Map<String, Object>> manageProducts() {
    UserContext.requireRole("OWNER", "STAFF");
    return db.queryForList(
        "SELECT s.*,IF(s.is_default,d.price,s.price) price,d.name"
            + " dish_name,IF(s.is_default,d.stock,s.stock) available_stock FROM product_sku s JOIN"
            + " dish d ON d.id=s.dish_id WHERE s.merchant_id=? ORDER BY s.id",
        UserContext.merchantId());
  }

  @Transactional
  public long sku(Long skuId, SkuInput x) {
    UserContext.requireRole("OWNER");
    long m = UserContext.merchantId();
    one(db, "SELECT id FROM merchant WHERE id=? FOR UPDATE", m);
    one(db, "SELECT id FROM dish WHERE id=? AND merchant_id=? FOR UPDATE", x.dishId(), m);
    text(x.name(), 100, "规格名称");
    money(x.price());
    money(x.packagingFee());
    check(x.stock() >= 0 && x.stock() <= 1000000, "库存无效");
    if (skuId == null) {
      long id =
          insert(
              db,
              "INSERT INTO product_sku(merchant_id,dish_id,name,price,packaging_fee,stock,enabled)"
                  + " VALUES(?,?,?,?,?,?,?)",
              m,
              x.dishId(),
              x.name(),
              x.price(),
              x.packagingFee(),
              x.stock(),
              x.enabled());
      audit.record("SKU_CREATED", id);
      return id;
    }
    var old = one(db, "SELECT * FROM product_sku WHERE id=? AND merchant_id=?", skuId, m);
    check(id(old, "dish_id") == x.dishId(), "规格不能更换所属菜品");
    if (Boolean.TRUE.equals(old.get("is_default")))
      check(
          x.price()
                  .compareTo(
                      amount(one(db, "SELECT price FROM dish WHERE id=?", x.dishId()), "price"))
              == 0,
          "默认规格价格请通过原菜单修改");
    check(
        db.update(
                "UPDATE product_sku SET name=?,price=?,packaging_fee=?,enabled=?,version=version+1"
                    + " WHERE id=? AND merchant_id=? AND version=?",
                x.name(),
                x.price(),
                x.packagingFee(),
                x.enabled(),
                skuId,
                m,
                x.expectedVersion())
            == 1,
        "规格版本已变化");
    audit.record("SKU_UPDATED", skuId);
    return skuId;
  }

  @Transactional
  public void adjustStock(long sku, long version, int delta, String key) {
    UserContext.requireRole("OWNER");
    DraftService.validKey(key);
    check(delta != 0 && Math.abs((long) delta) <= 1000000, "库存调整无效");
    var s =
        one(
            db,
            "SELECT * FROM product_sku WHERE id=? AND merchant_id=? FOR UPDATE",
            sku,
            UserContext.merchantId());
    var old =
        db.queryForList(
            "SELECT delta FROM sku_inventory_ledger WHERE sku_id=? AND request_key=?",
            sku,
            "adjust:" + key);
    if (!old.isEmpty()) {
      check(num(old.getFirst(), "delta") == delta, "幂等键请求不一致");
      return;
    }
    check(id(s, "version") == version, "规格版本已变化");
    stockChange(s, delta, null, "adjust:" + key);
    db.update("UPDATE product_sku SET version=version+1 WHERE id=?", sku);
    audit.record("SKU_STOCK_ADJUSTED", sku);
  }

  @Transactional
  public long bundle(Long bundleId, BundleInput x) {
    UserContext.requireRole("OWNER");
    long m = UserContext.merchantId();
    one(db, "SELECT id FROM merchant WHERE id=? FOR UPDATE", m);
    text(x.name(), 100, "套餐名称");
    money(x.price());
    money(x.packagingFee());
    check(
        x.components() != null && !x.components().isEmpty() && x.components().size() <= 20,
        "套餐需1-20种组件");
    var ids = new HashSet<Long>();
    for (var c : x.components()) {
      check(c.quantity() > 0 && c.quantity() <= 99 && ids.add(c.skuId()), "套餐组件重复或数量无效");
      one(db, "SELECT id FROM product_sku WHERE id=? AND merchant_id=?", c.skuId(), m);
    }
    long id;
    if (bundleId == null)
      id =
          insert(
              db,
              "INSERT INTO product_bundle(merchant_id,name,price,packaging_fee,enabled)"
                  + " VALUES(?,?,?,?,?)",
              m,
              x.name(),
              x.price(),
              x.packagingFee(),
              x.enabled());
    else {
      id = bundleId;
      check(
          db.update(
                  "UPDATE product_bundle SET"
                      + " name=?,price=?,packaging_fee=?,enabled=?,version=version+1 WHERE id=? AND"
                      + " merchant_id=? AND version=?",
                  x.name(),
                  x.price(),
                  x.packagingFee(),
                  x.enabled(),
                  id,
                  m,
                  x.expectedVersion())
              == 1,
          "套餐版本已变化");
      db.update("DELETE FROM bundle_component WHERE bundle_id=?", id);
    }
    for (var c : x.components())
      db.update("INSERT INTO bundle_component VALUES(?,?,?)", id, c.skuId(), c.quantity());
    audit.record("BUNDLE_SAVED", id);
    return id;
  }

  public List<Map<String, Object>> bundles() {
    UserContext.requireRole("OWNER", "STAFF");
    var rows =
        db.queryForList(
            "SELECT * FROM product_bundle WHERE merchant_id=? ORDER BY id",
            UserContext.merchantId());
    for (var r : rows)
      r.put(
          "components",
          db.queryForList(
              "SELECT * FROM bundle_component WHERE bundle_id=? ORDER BY sku_id", id(r, "id")));
    return rows;
  }

  public Product resolve(String type, long productId, int quantity, String snapshot) {
    check(quantity > 0 && quantity <= 99, "商品数量需为1-99");
    check(type != null && Set.of("SKU", "BUNDLE").contains(type), "商品类型无效");
    var row =
        one(
            db,
            "SELECT * FROM "
                + (type.equals("SKU") ? "product_sku" : "product_bundle")
                + " WHERE id=? AND enabled=TRUE",
            productId);
    long m = id(row, "merchant_id");
    return UserContext.within(
        m,
        () -> {
          var ctx = safety.current(user());
          check(!ctx.needsClarification(), "请先明确本次过敏原");
          var allergens = safety.effectiveAllergens(user(), snapshot);
          check(
              allergens.stream().allMatch(OrderSafetyService.SUPPORTED_ALLERGENS::contains),
              "存在无法核验的过敏原");
          List<Component> components =
              type.equals("SKU")
                  ? List.of(new Component(productId, 1))
                  : db.query(
                      "SELECT sku_id,quantity FROM bundle_component WHERE bundle_id=? ORDER BY"
                          + " sku_id",
                      (r, i) -> new Component(r.getLong(1), r.getInt(2)),
                      productId);
          check(!components.isEmpty(), "套餐没有组件");
          long dishId = 0;
          String name = String.valueOf(row.get("name"));
          BigDecimal price = amount(row, "price");
          long version = id(row, "version");
          for (var c : components) {
            var s = stock(c.skuId(), false);
            check(
                id(s, "merchant_id") == m
                    && Boolean.TRUE.equals(s.get("enabled"))
                    && num(s, "dish_status") == 1,
                "组件停售或归属错误");
            check(num(s, "available_stock") >= (long) quantity * c.quantity(), "商品库存不足");
            check(
                allergens.isEmpty()
                    || Boolean.TRUE.equals(s.get("allergen_reviewed"))
                        && FoodSafety.conflicts((String) s.get("allergens"), allergens).isEmpty(),
                "商品过敏原未核验或冲突");
            if (dishId == 0) dishId = id(s, "dish_id");
            if (type.equals("SKU")) {
              name = s.get("dish_name") + " / " + row.get("name");
              if (Boolean.TRUE.equals(s.get("is_default"))) price = amount(s, "dish_price");
              version = 31 * version + id(s, "dish_version");
            }
          }
          return new Product(
              type,
              productId,
              m,
              dishId,
              name,
              price,
              amount(row, "packaging_fee"),
              version,
              components,
              String.join(",", allergens));
        });
  }

  public Map<String, Object> stock(long sku, boolean lock) {
    return one(
        db,
        "SELECT s.*,d.name dish_name,d.price dish_price,d.version dish_version,d.status"
            + " dish_status,d.allergens,d.allergen_reviewed,IF(s.is_default,d.stock,s.stock)"
            + " available_stock FROM product_sku s JOIN dish d ON d.id=s.dish_id WHERE s.id=?"
            + (lock ? " FOR UPDATE" : ""),
        sku);
  }

  public int available(Product p) {
    int n = Integer.MAX_VALUE;
    for (var c : p.components())
      n = Math.min(n, num(stock(c.skuId(), false), "available_stock") / c.quantity());
    return n;
  }

  public void stockChange(Map<String, Object> sku, int delta, Long order, String key) {
    long id = id(sku, "id");
    int updated;
    if (Boolean.TRUE.equals(sku.get("is_default")))
      updated =
          db.update(
              "UPDATE dish SET stock=stock+?,stock_version=stock_version+1 WHERE id=? AND"
                  + " merchant_id=? AND stock+? BETWEEN 0 AND 1000000",
              delta,
              id(sku, "dish_id"),
              id(sku, "merchant_id"),
              delta);
    else
      updated =
          db.update(
              "UPDATE product_sku SET stock=stock+? WHERE id=? AND stock+? BETWEEN 0 AND 1000000",
              delta,
              id,
              delta);
    check(updated == 1, "商品库存不足或超出范围");
    db.update(
        "INSERT INTO sku_inventory_ledger(sku_id,order_id,request_key,delta) VALUES(?,?,?,?)",
        id,
        order,
        key,
        delta);
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
}
