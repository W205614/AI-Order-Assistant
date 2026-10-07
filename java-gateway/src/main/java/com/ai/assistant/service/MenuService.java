package com.ai.assistant.service;

import com.ai.assistant.model.*;
import com.ai.assistant.security.UserContext;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Shop metadata is cached; inventory, review state and versions are read live. */
@Service
public class MenuService {
  private final JdbcTemplate jdbc;
  private final CacheManager caches;
  private final AuditService audit;

  public MenuService(JdbcTemplate jdbc, CacheManager caches, AuditService audit) {
    this.jdbc = jdbc;
    this.caches = caches;
    this.audit = audit;
  }

  static final RowMapper<Dish> MAPPER =
      (rs, i) -> {
        Dish d = new Dish();
        d.setId(rs.getLong("id"));
        d.setMerchantId(rs.getLong("merchant_id"));
        d.setName(rs.getString("name"));
        d.setPrice(rs.getBigDecimal("price"));
        d.setCategory(rs.getString("category"));
        d.setDescription(rs.getString("description"));
        d.setStock(rs.getInt("stock"));
        d.setStatus(rs.getInt("status"));
        d.setAllergens(rs.getString("allergens"));
        d.setAllergenReviewed(rs.getBoolean("allergen_reviewed"));
        d.setVersion(rs.getLong("version"));
        d.setStockVersion(rs.getLong("stock_version"));
        return d;
      };

  private List<Dish> fresh() {
    return jdbc.query(
        "SELECT * FROM dish WHERE merchant_id=? ORDER BY id LIMIT 1000",
        MAPPER,
        UserContext.merchantId());
  }

  private List<Dish> metadata() {
    long merchant = UserContext.merchantId();
    try {
      var cache = caches.getCache("merchantMenu");
      String value = cache == null ? null : cache.get(merchant, String.class);
      if (value != null) return JSON.parseArray(value, Dish.class);
      var dishes = fresh();
      if (cache != null) cache.put(merchant, JSON.toJSONString(dishes));
      return dishes;
    } catch (RuntimeException e) {
      return fresh();
    }
  }

  public List<Dish> all() {
    var dishes = metadata();
    var live =
        jdbc.queryForList(
            "SELECT id,version,stock_version,stock,status,allergens,allergen_reviewed FROM dish"
                + " WHERE merchant_id=? ORDER BY id LIMIT 1000",
            UserContext.merchantId());
    var byId = new HashMap<Long, Map<String, Object>>();
    for (var row : live) byId.put(((Number) row.get("id")).longValue(), row);
    if (dishes.size() != live.size()
        || dishes.stream()
            .anyMatch(
                d ->
                    !byId.containsKey(d.getId())
                        || !Objects.equals(
                            d.getVersion(),
                            ((Number) byId.get(d.getId()).get("version")).longValue()))) {
      evictNow();
      dishes = fresh();
    }
    var result = new ArrayList<Dish>();
    for (var d : dishes) {
      var row = byId.get(d.getId());
      if (row == null) continue;
      d.setStock(((Number) row.get("stock")).intValue());
      d.setStatus(((Number) row.get("status")).intValue());
      d.setStockVersion(((Number) row.get("stock_version")).longValue());
      d.setAllergens((String) row.get("allergens"));
      d.setAllergenReviewed(Boolean.TRUE.equals(row.get("allergen_reviewed")));
      result.add(d);
    }
    return result;
  }

  public Map<String, Object> page(
      String category,
      String keyword,
      Boolean available,
      int page,
      int size,
      List<String> allergens,
      boolean blocked) {
    if (page < 1 || page > 1000000 || size < 1 || size > 50)
      throw new IllegalArgumentException("菜单分页参数无效");
    if (category != null && category.length() > 50 || keyword != null && keyword.length() > 100)
      throw new IllegalArgumentException("筛选内容过长");
    List<Dish> matches =
        blocked
            ? List.of()
            : all().stream()
                .filter(
                    d -> category == null || category.isBlank() || category.equals(d.getCategory()))
                .filter(
                    d ->
                        keyword == null
                            || keyword.isBlank()
                            || d.getName().contains(keyword)
                            || Objects.toString(d.getDescription(), "").contains(keyword))
                .filter(
                    d -> !Boolean.TRUE.equals(available) || d.getStatus() == 1 && d.getStock() > 0)
                .filter(
                    d ->
                        allergens.isEmpty()
                            || Boolean.TRUE.equals(d.getAllergenReviewed())
                                && FoodSafety.conflicts(d.getAllergens(), allergens).isEmpty())
                .toList();
    long offset = (long) (page - 1) * size;
    return Map.of(
        "items",
        matches.stream().skip(offset).limit(size).toList(),
        "page",
        page,
        "size",
        size,
        "total",
        matches.size());
  }

  public Optional<Dish> find(long id) {
    return jdbc
        .query(
            "SELECT * FROM dish WHERE merchant_id=? AND id=?", MAPPER, UserContext.merchantId(), id)
        .stream()
        .findFirst();
  }

  public Optional<Dish> find(String name) {
    return jdbc
        .query(
            "SELECT * FROM dish WHERE merchant_id=? AND name=?",
            MAPPER,
            UserContext.merchantId(),
            name)
        .stream()
        .findFirst();
  }

  public Optional<Dish> byInput(String name) {
    var exact = find(name);
    if (exact.isPresent()) return exact;
    var matches =
        jdbc.query(
            "SELECT * FROM dish WHERE merchant_id=? AND name LIKE ? ORDER BY id LIMIT 6",
            MAPPER,
            UserContext.merchantId(),
            "%" + name + "%");
    if (matches.size() > 1)
      throw new IllegalArgumentException(
          "菜名匹配多个菜品，请明确选择：" + String.join("、", matches.stream().map(Dish::getName).toList()));
    return matches.stream().findFirst();
  }

  /** Read and lock in ascending dish ID order, after the user/merchant/draft locks. */
  public List<OrderItem> resolve(List<OrderItem> input, List<String> allergens, boolean blocked) {
    if (blocked) throw new IllegalArgumentException("请先明确本次过敏原");
    if (input == null || input.isEmpty() || input.size() > 20)
      throw new IllegalArgumentException("每单菜品需为1-20种");
    Map<Long, Integer> quantities = new TreeMap<>();
    List<Dish> names =
        input.stream().anyMatch(i -> i != null && i.getDishId() == null) ? fresh() : List.of();
    for (var item : input) {
      if (item == null
          || item.getQuantity() == null
          || item.getQuantity() < 1
          || item.getQuantity() > 99) throw new IllegalArgumentException("菜品数量需为1-99");
      if (item.getDishId() == null && (item.getDishName() == null || item.getDishName().isBlank()))
        throw new IllegalArgumentException("必须选择菜品 ID 或名称");
      Long id = item.getDishId();
      if (id == null) {
        var exact = names.stream().filter(d -> d.getName().equals(item.getDishName())).toList();
        var matches =
            exact.isEmpty()
                ? names.stream().filter(d -> d.getName().contains(item.getDishName())).toList()
                : exact;
        if (matches.size() != 1)
          throw new IllegalArgumentException(
              matches.isEmpty() ? "菜品不存在或不属于当前商户" : "菜名匹配多个菜品，请选择明确的菜品 ID");
        id = matches.getFirst().getId();
      }
      quantities.merge(id, item.getQuantity(), Integer::sum);
    }
    if (quantities.values().stream().anyMatch(q -> q > 99))
      throw new IllegalArgumentException("单个菜品累计数量不能超过99");
    var ids = new ArrayList<>(quantities.keySet());
    var params = new ArrayList<Object>();
    params.add(UserContext.merchantId());
    params.addAll(ids);
    var dishes =
        jdbc.query(
            "SELECT * FROM dish WHERE merchant_id=? AND id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") ORDER BY id FOR UPDATE",
            MAPPER,
            params.toArray());
    if (dishes.size() != ids.size()) throw new IllegalArgumentException("菜品已删除");
    var resolved = new ArrayList<OrderItem>();
    for (var dish : dishes) {
      if (dish.getStatus() != 1 || dish.getStock() < quantities.get(dish.getId()))
        throw new IllegalArgumentException("菜品停售或库存不足：" + dish.getName());
      ensureSafe(dish, allergens);
      OrderItem item = new OrderItem();
      item.setDishId(dish.getId());
      item.setDishName(dish.getName());
      item.setDishVersion(dish.getVersion());
      item.setQuantity(quantities.get(dish.getId()));
      item.setPrice(dish.getPrice());
      item.setAmount(dish.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
      resolved.add(item);
    }
    return resolved;
  }

  static void ensureSafe(Dish dish, List<String> allergens) {
    if (allergens.isEmpty()) return;
    if (!OrderSafetyService.SUPPORTED_ALLERGENS.containsAll(allergens))
      throw new IllegalArgumentException("存在无法核验的过敏原，请联系店员");
    if (!Boolean.TRUE.equals(dish.getAllergenReviewed()))
      throw new IllegalArgumentException("菜品过敏原尚未核验：" + dish.getName());
    if (!FoodSafety.conflicts(dish.getAllergens(), allergens).isEmpty())
      throw new IllegalArgumentException("菜品包含当前约束的过敏原：" + dish.getName());
  }

  @Transactional
  public Dish add(Dish dish) {
    UserContext.requireRole("OWNER");
    long merchant = UserContext.merchantId();
    validate(dish);
    jdbc.queryForObject("SELECT id FROM merchant WHERE id=? FOR UPDATE", Long.class, merchant);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM dish WHERE merchant_id=?", Integer.class, merchant)
        >= 1000) throw new IllegalArgumentException("每商户最多1000种菜品");
    int stock = dish.getStock() == null ? 0 : dish.getStock();
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          var ps =
              con.prepareStatement(
                  "INSERT INTO"
                      + " dish(merchant_id,name,price,description,category,status,stock,allergens,allergen_reviewed)"
                      + " VALUES(?,?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setLong(1, merchant);
          ps.setString(2, dish.getName().trim());
          ps.setBigDecimal(3, dish.getPrice());
          ps.setString(4, dish.getDescription());
          ps.setString(5, dish.getCategory());
          ps.setInt(6, dish.getStatus() == null ? 1 : dish.getStatus());
          ps.setInt(7, stock);
          ps.setString(8, FoodSafety.normalizeTags(dish.getAllergens()));
          ps.setBoolean(9, Boolean.TRUE.equals(dish.getAllergenReviewed()));
          return ps;
        },
        keys);
    long id = keys.getKey().longValue();
    jdbc.update(
        "INSERT INTO inventory_ledger(merchant_id,dish_id,request_key,kind,delta,actor)"
            + " VALUES(?,?,?,'INITIAL',?,?)",
        merchant,
        id,
        "initial:" + id,
        stock,
        UserContext.actor());
    audit.record(merchant, "DISH_CREATED", id);
    evictAfterCommit();
    return find(id).orElseThrow();
  }

  @Transactional
  public Dish update(long id, Dish dish) {
    UserContext.requireRole("OWNER");
    validate(dish);
    Dish current = find(id).orElseThrow(() -> new IllegalArgumentException("菜品不存在"));
    if (dish.getVersion() == null) throw new IllegalArgumentException("请提供菜品版本");
    if (dish.getStock() != null && !Objects.equals(dish.getStock(), current.getStock()))
      throw new IllegalArgumentException("请通过库存调整操作修改库存");
    int changed =
        jdbc.update(
            "UPDATE dish SET"
                + " name=?,price=?,description=?,category=?,status=?,allergens=?,allergen_reviewed=?,version=version+1"
                + " WHERE merchant_id=? AND id=? AND version=?",
            dish.getName().trim(),
            dish.getPrice(),
            dish.getDescription(),
            dish.getCategory(),
            dish.getStatus() == null ? current.getStatus() : dish.getStatus(),
            FoodSafety.normalizeTags(dish.getAllergens()),
            Boolean.TRUE.equals(dish.getAllergenReviewed()),
            UserContext.merchantId(),
            id,
            dish.getVersion());
    if (changed != 1) throw BusinessException.conflict("菜品已更新，请刷新", find(id).orElse(null));
    audit.record(UserContext.merchantId(), "DISH_UPDATED", id);
    evictAfterCommit();
    return find(id).orElseThrow();
  }

  @Transactional
  public Dish status(long id, int status) {
    UserContext.requireRole("OWNER");
    if (status != 0 && status != 1) throw new IllegalArgumentException("状态无效");
    if (jdbc.update(
            "UPDATE dish SET status=?,version=version+1 WHERE merchant_id=? AND id=?",
            status,
            UserContext.merchantId(),
            id)
        != 1) throw new IllegalArgumentException("菜品不存在");
    audit.record(UserContext.merchantId(), "DISH_STATUS_CHANGED", id);
    evictAfterCommit();
    return find(id).orElseThrow();
  }

  @Transactional
  public void delete(long id) {
    status(id, 0);
  }

  private void validate(Dish d) {
    if (d.getName() == null
        || d.getName().isBlank()
        || d.getName().length() > 100
        || d.getCategory() == null
        || d.getCategory().isBlank()
        || d.getCategory().length() > 50) throw new IllegalArgumentException("菜名或分类无效");
    if (d.getDescription() != null && d.getDescription().length() > 255)
      throw new IllegalArgumentException("描述过长");
    if (d.getPrice() == null
        || d.getPrice().signum() <= 0
        || d.getPrice().compareTo(new BigDecimal("99999.99")) > 0
        || d.getPrice().stripTrailingZeros().scale() > 2)
      throw new IllegalArgumentException("价格无效，最多两位小数");
    if (d.getStock() != null && (d.getStock() < 0 || d.getStock() > 1000000)
        || d.getStatus() != null && d.getStatus() != 0 && d.getStatus() != 1)
      throw new IllegalArgumentException("库存或状态无效");
    if (d.getAllergens() != null && d.getAllergens().length() > 255)
      throw new IllegalArgumentException("过敏标注过长");
    if (Boolean.TRUE.equals(d.getAllergenReviewed())
        && !OrderSafetyService.SUPPORTED_ALLERGENS.containsAll(
            FoodSafety.splitTags(d.getAllergens())))
      throw new IllegalArgumentException("核验标签仅支持花生、鸡蛋、麸质");
  }

  private void evictNow() {
    try {
      var c = caches.getCache("merchantMenu");
      if (c != null) c.evict(UserContext.merchantId());
    } catch (RuntimeException ignored) {
    }
  }

  private void evictAfterCommit() {
    long merchant = UserContext.merchantId();
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCommit() {
              UserContext.within(
                  merchant,
                  () -> {
                    evictNow();
                    return null;
                  });
            }
          });
    else evictNow();
  }
}
