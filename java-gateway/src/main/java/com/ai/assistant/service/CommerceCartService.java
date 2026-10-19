package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceCartService {
  private final JdbcTemplate db;
  private final CampusCatalogService catalog;
  private final OrderSafetyService safety;

  public CommerceCartService(
      JdbcTemplate db, CampusCatalogService catalog, OrderSafetyService safety) {
    this.db = db;
    this.catalog = catalog;
    this.safety = safety;
  }

  public List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT c.*,m.name merchant_name,CASE WHEN c.product_type='SKU' THEN CONCAT(d.name,' /"
            + " ',s.name) ELSE b.name END product_name FROM cart_item c JOIN merchant m ON"
            + " m.id=c.merchant_id LEFT JOIN product_sku s ON c.product_type='SKU' AND"
            + " s.id=c.product_id LEFT JOIN dish d ON d.id=s.dish_id LEFT JOIN product_bundle b ON"
            + " c.product_type='BUNDLE' AND b.id=c.product_id WHERE user_id=? ORDER BY"
            + " c.merchant_id,c.id",
        user());
  }

  @Transactional
  public long save(CartInput x) {
    long u = user();
    safety.lockUser(u);
    check(x.quantity() >= 1 && x.quantity() <= 99, "商品数量需为1-99");
    check(x.remark() == null || x.remark().length() <= 255, "备注过长");
    // User-row serialization already protects this cart. Locking a missing cart row would
    // take a next-key gap lock and deadlock unrelated users' first inserts at REPEATABLE READ.
    var old =
        db.queryForList(
            "SELECT * FROM cart_item WHERE user_id=? AND product_type=? AND product_id=?",
            u,
            x.productType(),
            x.productId());
    var p =
        catalog.resolve(
            x.productType(),
            x.productId(),
            x.quantity(),
            old.isEmpty() ? "" : (String) old.getFirst().get("safety_allergens"));
    if (old.isEmpty()) {
      check(x.expectedVersion() == null || x.expectedVersion() == 0, "购物车版本已变化");
      check(list().size() < 100, "购物车最多100项");
      return insert(
          db,
          "INSERT INTO"
              + " cart_item(user_id,merchant_id,product_type,product_id,quantity,remark,safety_allergens)"
              + " VALUES(?,?,?,?,?,?,?)",
          u,
          p.merchantId(),
          x.productType(),
          x.productId(),
          x.quantity(),
          Objects.toString(x.remark(), ""),
          p.safetySnapshot());
    }
    var r = old.getFirst();
    check(x.expectedVersion() != null && x.expectedVersion() == id(r, "version"), "购物车版本已变化，请刷新");
    check(
        db.update(
                "UPDATE cart_item SET quantity=?,remark=?,safety_allergens=?,version=version+1"
                    + " WHERE id=? AND user_id=? AND version=?",
                x.quantity(),
                Objects.toString(x.remark(), ""),
                p.safetySnapshot(),
                id(r, "id"),
                u,
                x.expectedVersion())
            == 1,
        "购物车版本已变化，请刷新");
    return id(r, "id");
  }

  @Transactional
  public List<Long> saveBatch(CartBatchInput batch) {
    check(batch != null && batch.items() != null && !batch.items().isEmpty()
        && batch.items().size() <= 100, "整组加购需提供1-100项商品");
    var keys = new HashSet<String>();
    for (var item : batch.items()) {
      check(item != null && Set.of("SKU", "BUNDLE").contains(Objects.toString(item.productType(), ""))
          && item.productId() > 0, "商品标识无效");
      check(keys.add(item.productType() + ":" + item.productId()), "整组商品不能重复");
    }
    // Acquire before the first consistent read, including capacity and version checks.
    safety.lockUser(user());
    var saved = new ArrayList<Long>();
    for (var item : batch.items()) saved.add(save(item));
    return saved;
  }

  @Transactional
  public void delete(long id, long version) {
    safety.lockUser(user());
    check(
        db.update(
                "DELETE FROM cart_item WHERE id=? AND user_id=? AND version=?", id, user(), version)
            == 1,
        "购物车版本已变化或不存在");
  }

  public List<Map<String, Object>> favorites() {
    return db.queryForList(
        "SELECT * FROM favorite WHERE user_id=? ORDER BY product_type,product_id", user());
  }

  public void favorite(String type, long id, boolean add) {
    if (add) {
      catalog.resolve(type, id, 1, "");
      db.update("INSERT IGNORE INTO favorite VALUES(?,?,?)", user(), type, id);
    } else
      db.update(
          "DELETE FROM favorite WHERE user_id=? AND product_type=? AND product_id=?",
          user(),
          type,
          id);
  }

  @Transactional
  public List<Long> reorder(long order) {
    // Acquire serialization before any consistent read can establish an old RR snapshot.
    safety.lockUser(user());
    one(db, "SELECT id FROM orders WHERE id=? AND user_id=?", order, user());
    var added = new ArrayList<Long>();
    for (var item :
        db.queryForList("SELECT * FROM order_item WHERE order_id=? ORDER BY id", order)) {
      String type = Objects.toString(item.get("product_type"), "SKU");
      long product =
          item.get("product_id") == null
              ? id(
                  one(
                      db,
                      "SELECT id FROM product_sku WHERE dish_id=? AND is_default=TRUE",
                      id(item, "dish_id")),
                  "id")
              : id(item, "product_id");
      var existing =
          db.queryForList(
              "SELECT version FROM cart_item WHERE user_id=? AND product_type=? AND product_id=?",
              user(),
              type,
              product);
      added.add(
          save(
              new CartInput(
                  type,
                  product,
                  num(item, "quantity"),
                  "",
                  existing.isEmpty() ? null : id(existing.getFirst(), "version"))));
    }
    return added;
  }
}
