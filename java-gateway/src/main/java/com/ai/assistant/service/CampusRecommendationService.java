package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampusRecommendationService {
  private final CampusCatalogService catalog;
  private final CommerceQuoteService quotes;
  private final CommerceCartService cart;
  private final OrderSafetyService safety;

  public CampusRecommendationService(
      CampusCatalogService catalog,
      CommerceQuoteService quotes,
      CommerceCartService cart,
      OrderSafetyService safety) {
    this.catalog = catalog;
    this.quotes = quotes;
    this.cart = cart;
    this.safety = safety;
  }

  public record PrepareRequest(
      long campusId, Long addressId, String fulfillmentMode, CartInput item) {}

  @Transactional
  public long prepareCart(PrepareRequest request) {
    // Lock before any consistent read so a concurrent cart write committed while we wait
    // is visible to cart.save's version check under MySQL REPEATABLE READ.
    safety.lockUser(user());
    check(request != null && request.item() != null, "购物车商品不能为空");
    check(
        Set.of("DELIVERY", "PICKUP").contains(Objects.toString(request.fulfillmentMode(), "")),
        "履约方式无效");
    var item = request.item();
    var product = catalog.resolve(item.productType(), item.productId(), item.quantity(), "");
    var shop =
        catalog.shops(request.campusId()).stream()
            .filter(row -> id(row, "id") == product.merchantId())
            .findFirst();
    check(shop.isPresent(), "商品所属店铺不服务当前校区");
    if ("PICKUP".equals(request.fulfillmentMode())) {
      check(id(shop.orElseThrow(), "campus_id") == request.campusId(), "自提需选择店铺所在校区");
    } else {
      check(
          catalog.addresses().stream()
              .anyMatch(
                  row ->
                      id(row, "id") == Objects.requireNonNullElse(request.addressId(), 0L)
                          && id(row, "campus_id") == request.campusId()),
          "地址不存在或校区不一致");
    }
    return cart.save(item);
  }

  @Transactional
  public List<Product> search(long campus, String keyword) {
    user();
    check(keyword != null && keyword.length() <= 100, "搜索词无效");
    var products = new ArrayList<Product>();
    for (var s : catalog.shops(campus)) {
      if (products.size() >= 100) break;
      for (var row : catalog.catalog(id(s, "id"))) {
        Product p = (Product) row.get("product");
        if (p.name().contains(keyword)) products.add(p);
        if (products.size() >= 100) break;
      }
    }
    return products;
  }

  public record Request(
      long campusId,
      Long addressId,
      String fulfillmentMode,
      BigDecimal budget,
      List<String> keywords,
      Map<Long, Long> shopCoupons,
      Long platformCoupon) {}

  @Transactional
  public Map<String, Object> recommend(Request x) {
    money(x.budget());
    check(
        x.budget().signum() > 0
            && x.keywords() != null
            && !x.keywords().isEmpty()
            && x.keywords().size() <= 3,
        "需提供预算及1-3类餐品");
    var options = new ArrayList<List<Product>>();
    for (String k : x.keywords())
      options.add(
          search(x.campusId(), k).stream()
              .sorted(
                  Comparator.comparing(Product::price)
                      .thenComparingLong(Product::merchantId)
                      .thenComparingLong(Product::id))
              .limit(8)
              .toList());
    var found = new ArrayList<Quote>();
    combinations(x, options, 0, new ArrayList<>(), found);
    found.sort(Comparator.comparing(Quote::total));
    return Map.of(
        "candidates",
        found.stream().limit(3).toList(),
        "searchLimited",
        true,
        "message",
        found.isEmpty() ? "当前候选中没有经报价验证且符合预算的组合" : "候选均经后端报价验证，未锁定库存，不代表全局最优");
  }

  private void combinations(
      Request x,
      List<List<Product>> options,
      int index,
      List<Product> selected,
      List<Quote> found) {
    if (index < options.size()) {
      for (var p : options.get(index)) {
        selected.add(p);
        combinations(x, options, index + 1, selected, found);
        selected.removeLast();
      }
      return;
    }
    var grouped = new TreeMap<Long, List<Line>>();
    var products = new HashSet<String>();
    for (var p : selected) {
      if (!products.add(p.type() + ":" + p.id())) return;
      grouped.computeIfAbsent(p.merchantId(), k -> new ArrayList<>()).add(new Line(0, 0, p, 1, ""));
    }
    var shopCoupons = new TreeMap<Long, Long>();
    if (x.shopCoupons() != null)
      for (var c : x.shopCoupons().entrySet())
        if (grouped.containsKey(c.getKey())) shopCoupons.put(c.getKey(), c.getValue());
    try {
      var q =
          quotes.preview(
              new QuoteInput(
                  new ArrayList<>(grouped.keySet()),
                  x.addressId(),
                  x.campusId(),
                  x.fulfillmentMode(),
                  shopCoupons,
                  x.platformCoupon(),
                  Map.of()),
              grouped);
      if (q.unavailable().isEmpty() && q.total().compareTo(x.budget()) <= 0) found.add(q);
    } catch (IllegalArgumentException ignored) {
    }
  }
}
