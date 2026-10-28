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
public class CampusCatalogService {
  private final JdbcTemplate db;
  private final OrderSafetyService safety;
  private final AuditService audit;
  private final CustomerAddressService addresses;
  private final MerchantDeliveryService delivery;
  private final RiderAccountService riders;

  public CampusCatalogService(JdbcTemplate db, OrderSafetyService safety, AuditService audit,
      CustomerAddressService addresses, MerchantDeliveryService delivery, RiderAccountService riders) {
    this.db = db;
    this.safety = safety;
    this.audit = audit;
    this.addresses = addresses;
    this.delivery = delivery;
    this.riders = riders;
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

  public List<Map<String, Object>> addresses() { return addresses.addresses(); }
  public long saveAddress(Long id, AddressInput x) { return addresses.saveAddress(id, x); }
  public void deleteAddress(long id) { addresses.deleteAddress(id); }
  public void homeCampus(long campus) { delivery.homeCampus(campus); }
  public List<Map<String, Object>> rules() { return delivery.rules(); }
  public Map<String, Object> homeCampus() { return delivery.homeCampus(); }
  public void rule(RuleInput x) { delivery.rule(x); }

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
            "SELECT * FROM product_sku WHERE id=? AND merchant_id=?",
            sku,
            UserContext.merchantId());
    s = stock(sku, true);
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
    stockChange(s, delta, null, "adjust:" + key, 1000000);
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
    return resolve(type, productId, quantity, snapshot, null);
  }

  /** Only confirmation calls this after locking the snapshot's complete stock set. */
  Product resolveCurrent(Product expected, int quantity, String snapshot) {
    return resolve(expected.type(), expected.id(), quantity, snapshot, expected);
  }

  private Product resolve(
      String type, long productId, int quantity, String snapshot, Product expected) {
    boolean current = expected != null;
    check(quantity > 0 && quantity <= 99, "商品数量需为1-99");
    check(type != null && Set.of("SKU", "BUNDLE").contains(type), "商品类型无效");
    var row =
        one(
            db,
            "SELECT * FROM "
                + (type.equals("SKU") ? "product_sku" : "product_bundle")
                + " WHERE id=? AND enabled=TRUE"
                + (current ? " FOR SHARE" : ""),
            productId);
    // A changed bundle may introduce lower-ID dishes that were not in the locked snapshot.
    // Reject it before reading/locking new components, preserving the global stock lock order.
    if (current && "BUNDLE".equals(type))
      check(id(row, "version") == expected.version(), "套餐已变化，请重新报价");
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
                          + " sku_id"
                          + (current ? " FOR SHARE" : ""),
                      (r, i) -> new Component(r.getLong(1), r.getInt(2)),
                      productId);
          check(!components.isEmpty(), "套餐没有组件");
          long dishId = 0;
          String name = String.valueOf(row.get("name"));
          BigDecimal price = amount(row, "price");
          long version = id(row, "version");
          for (var c : components) {
            var s = current ? stockCurrent(c.skuId()) : stock(c.skuId(), false);
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
    if (lock) lockStocks(List.of(sku));
    return readStock(sku, lock ? " FOR UPDATE" : "");
  }

  Map<String, Object> stockCurrent(long sku) {
    return readStock(sku, " FOR SHARE");
  }

  private Map<String, Object> readStock(long sku, String suffix) {
    return one(
        db,
        "SELECT s.*,d.name dish_name,d.price dish_price,d.version dish_version,d.status"
            + " dish_status,d.allergens,d.allergen_reviewed,IF(s.is_default,d.stock,s.stock)"
            + " available_stock FROM product_sku s JOIN dish d ON d.id=s.dish_id WHERE s.id=?"
            + suffix,
        sku);
  }

  /** All inventory writers acquire every parent dish before any SKU, both in ID order. */
  public void lockStocks(Collection<Long> skuIds) {
    var ids = new TreeSet<>(skuIds);
    if (ids.isEmpty()) return;
    var rows =
        db.queryForList(
            "SELECT id,dish_id FROM product_sku WHERE id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ")",
            ids.toArray());
    check(rows.size() == ids.size(), "商品规格不存在");
    var dishes = new TreeSet<Long>();
    for (var row : rows) dishes.add(id(row, "dish_id"));
    for (long dish : dishes) one(db, "SELECT id FROM dish WHERE id=? FOR UPDATE", dish);
    for (long sku : ids) one(db, "SELECT id FROM product_sku WHERE id=? FOR UPDATE", sku);
  }

  public int available(Product p) {
    int n = Integer.MAX_VALUE;
    for (var c : p.components())
      n = Math.min(n, num(stock(c.skuId(), false), "available_stock") / c.quantity());
    return n;
  }

  public void stockChange(Map<String, Object> sku, int delta, Long order, String key) {
    // Returning an earlier reservation must remain possible after a merchant replenishes
    // available stock to the manual cap. Compensation only observes the storage bound.
    stockChange(sku, delta, order, key, Integer.MAX_VALUE);
  }

  private void stockChange(
      Map<String, Object> sku, int delta, Long order, String key, int maximum) {
    long id = id(sku, "id");
    int updated;
    if (Boolean.TRUE.equals(sku.get("is_default")))
      updated =
          db.update(
              "UPDATE dish SET stock=stock+?,stock_version=stock_version+1 WHERE id=? AND"
                  + " merchant_id=? AND stock+? BETWEEN 0 AND ?",
              delta,
              id(sku, "dish_id"),
              id(sku, "merchant_id"),
              delta,
              maximum);
    else
      updated =
          db.update(
              "UPDATE product_sku SET stock=stock+? WHERE id=? AND stock+? BETWEEN 0 AND ?",
              delta,
              id,
              delta,
              maximum);
    check(updated == 1, "商品库存不足或超出范围");
    db.update(
        "INSERT INTO sku_inventory_ledger(sku_id,order_id,request_key,delta) VALUES(?,?,?,?)",
        id,
        order,
        key,
        delta);
  }

  public long rider(RiderInput x) { return riders.rider(x); }
}
