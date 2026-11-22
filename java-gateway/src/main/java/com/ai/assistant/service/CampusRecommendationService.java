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
  private final java.util.concurrent.Semaphore admission;
  private final int maxCombinations;
  private final org.springframework.transaction.support.TransactionTemplate transaction;

  public CampusRecommendationService(
      CampusCatalogService catalog,
      CommerceQuoteService quotes,
      CommerceCartService cart,
      OrderSafetyService safety,
      @org.springframework.beans.factory.annotation.Value(
              "${commerce.recommendation.max-concurrent:2}")
          int concurrent,
      @org.springframework.beans.factory.annotation.Value(
              "${commerce.recommendation.max-combinations:64}")
          int combinations,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.catalog = catalog;
    this.quotes = quotes;
    this.cart = cart;
    this.safety = safety;
    check(concurrent > 0 && combinations > 0, "推荐容量配置必须大于零");
    this.admission = new java.util.concurrent.Semaphore(concurrent);
    this.maxCombinations = combinations;
    this.transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
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

  public record SelectedProduct(CartInput item, long productVersion) {}

  public record Selection(
      long campusId, Long addressId, String fulfillmentMode,
      java.time.LocalDateTime expiresAt, List<SelectedProduct> products) {}

  @Transactional
  public List<Long> apply(Selection selection) {
    check(selection != null && selection.products() != null
        && !selection.products().isEmpty() && selection.products().size() <= 3, "请选择有效组合");
    check(selection.expiresAt() != null && selection.expiresAt().isAfter(BusinessTime.now()),
        "推荐已过期，请重新查找组合");
    boolean pickup = "PICKUP".equals(selection.fulfillmentMode());
    check(pickup || "DELIVERY".equals(selection.fulfillmentMode()), "履约方式无效");
    safety.lockUser(user());
    if (!pickup)
      check(catalog.addresses().stream().anyMatch(row ->
              id(row, "id") == Objects.requireNonNullElse(selection.addressId(), 0L)
                  && id(row, "campus_id") == selection.campusId()),
          "收货地址已变化，请重新查找组合");
    Set<Long> shops = new HashSet<>();
    for (var row : catalog.shops(selection.campusId()))
      if ((!pickup || id(row, "campus_id") == selection.campusId())
          && Boolean.TRUE.equals(row.get("accepting_orders"))
          && Boolean.TRUE.equals(row.get("open_now")))
        shops.add(id(row, "id"));
    var items = new ArrayList<CartInput>();
    var requiredStock = new TreeMap<Long, Integer>();
    for (var selected : selection.products()) {
      check(selected != null && selected.item() != null, "组合商品不能为空");
      var item = selected.item();
      check(item.quantity() >= 1 && item.quantity() <= 99, "商品数量需为1-99");
      var product = catalog.resolve(item.productType(), item.productId(), item.quantity(), "");
      check(shops.contains(product.merchantId()), "商品店铺暂不服务当前校区");
      check(product.version() == selected.productVersion(), "商品已变化，请重新查找组合");
      for (var c : product.components())
        requiredStock.merge(c.skuId(), Math.multiplyExact(item.quantity(), c.quantity()), Integer::sum);
      items.add(item);
    }
    for (var entry : requiredStock.entrySet())
      check(num(catalog.stock(entry.getKey(), false), "available_stock") >= entry.getValue(),
          "组合商品共用库存不足，请重新查找组合");
    return cart.saveBatch(new CartBatchInput(items));
  }

  @Transactional(readOnly = true)
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
      Long platformCoupon,
      Boolean preferSameShop,
      Boolean preferFastest) {
    public Request(long campusId, Long addressId, String fulfillmentMode, BigDecimal budget,
        List<String> keywords, Map<Long, Long> shopCoupons, Long platformCoupon, boolean preferSameShop) {
      this(campusId, addressId, fulfillmentMode, budget, keywords, shopCoupons, platformCoupon,
          preferSameShop, false);
    }

    public Request(long campusId, Long addressId, String fulfillmentMode, BigDecimal budget,
        List<String> keywords, Map<Long, Long> shopCoupons, Long platformCoupon) {
      this(campusId, addressId, fulfillmentMode, budget, keywords, shopCoupons, platformCoupon, false);
    }
  }

  private static final class CandidateSearch {
    int examined;
    BigDecimal lowestValidTotal;
    final List<Quote> withinBudget = new ArrayList<>();
    final Set<String> unavailable = new LinkedHashSet<>();
  }

  public Map<String, Object> recommend(Request x) {
    if (!admission.tryAcquire())
      throw new BusinessException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "RECOMMEND_LIMIT", "组合推荐繁忙，请稍后重试");
    try {
      return transaction.execute(status -> recommendWithinTransaction(x));
    } finally {
      admission.release();
    }
  }

  private Map<String, Object> recommendWithinTransaction(Request x) {
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
    var search = new CandidateSearch();
    if (Boolean.TRUE.equals(x.preferSameShop()))
      for (long merchant : options.getFirst().stream().map(Product::merchantId).distinct().toList()) {
        if (search.examined >= maxCombinations) break;
        var sameShop = options.stream()
            .map(category -> category.stream().filter(p -> p.merchantId() == merchant).toList())
            .toList();
        combinations(x, sameShop, 0, new ArrayList<>(), search, false);
      }
    combinations(x, options, 0, new ArrayList<>(), search, Boolean.TRUE.equals(x.preferSameShop()));
    var byTotal = Comparator.comparing(Quote::total);
    var byTime = Comparator.comparingInt((Quote q) ->
        q.shops().stream().mapToInt(ShopQuote::etaMax).max().orElseThrow()).thenComparing(byTotal);
    var preference = Boolean.TRUE.equals(x.preferFastest()) ? byTime : byTotal;
    if (Boolean.TRUE.equals(x.preferSameShop()))
      preference = Comparator.comparingInt((Quote q) -> q.shops().size() == 1 ? 0 : 1)
          .thenComparing(preference);
    search.withinBudget.sort(preference);
    var proposed = new LinkedHashSet<Quote>();
    if (!search.withinBudget.isEmpty()) {
      proposed.add(search.withinBudget.getFirst());
      search.withinBudget.stream().min(byTotal).ifPresent(proposed::add);
      search.withinBudget.stream().min(byTime).ifPresent(proposed::add);
      proposed.addAll(search.withinBudget);
    }
    String message = "候选已通过当前报价校验，库存未预留。";
    if (search.withinBudget.isEmpty())
      message = search.lowestValidTotal == null
          ? "在已搜索的可售菜品中没有找到可报价的组合，可替换品类或调整履约条件。"
          : "已核算的可行组合超过当前含费预算，最低为 " + search.lowestValidTotal + " 元。";
    return Map.of(
        "candidates", proposed.stream().limit(3).toList(),
        "searchLimited", true,
        "combinationsExamined", search.examined,
        "issues", search.withinBudget.isEmpty() ? search.unavailable.stream().limit(3).toList() : List.of(),
        "message", message);
  }

  private void combinations(
      Request x,
      List<List<Product>> options,
      int index,
      List<Product> selected,
      CandidateSearch search,
      boolean crossShopOnly) {
    com.ai.assistant.security.UserContext.checkDeadline();
    if (search.examined >= maxCombinations) return;
    if (index < options.size()) {
      for (var p : options.get(index)) {
        if (search.examined >= maxCombinations) break;
        selected.add(p);
        combinations(x, options, index + 1, selected, search, crossShopOnly);
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
    if (crossShopOnly && grouped.size() < 2) return;
    var shopCoupons = new TreeMap<Long, Long>();
    if (x.shopCoupons() != null)
      for (var c : x.shopCoupons().entrySet())
        if (grouped.containsKey(c.getKey())) shopCoupons.put(c.getKey(), c.getValue());
    search.examined++;
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
      if (!q.unavailable().isEmpty()) search.unavailable.addAll(q.unavailable().values());
      else {
        if (search.lowestValidTotal == null || q.total().compareTo(search.lowestValidTotal) < 0)
          search.lowestValidTotal = q.total();
        if (q.total().compareTo(x.budget()) <= 0) search.withinBudget.add(q);
      }
    } catch (IllegalArgumentException rejection) {
      if (rejection.getMessage() != null) search.unavailable.add(rejection.getMessage());
    }
  }
}
