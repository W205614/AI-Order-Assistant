package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceQuoteService {
  private final JdbcTemplate db;
  private final CampusCatalogService catalog;
  private final CommerceCouponService coupons;
  private final MerchantService merchants;

  public CommerceQuoteService(
      JdbcTemplate db,
      CampusCatalogService catalog,
      CommerceCouponService coupons,
      MerchantService merchants) {
    this.db = db;
    this.catalog = catalog;
    this.coupons = coupons;
    this.merchants = merchants;
  }

  @Transactional
  public Quote quote(QuoteInput input) {
    Quote q = calculate(input);
    db.update(
        "INSERT INTO checkout_quote(id,user_id,request_json,snapshot_json,expires_at)"
            + " VALUES(?,?,?,?,?)",
        q.id(),
        user(),
        json(q.request()),
        json(q),
        q.expiresAt());
    return q;
  }

  public Quote calculate(QuoteInput input) {
    return calculate(input, null);
  }

  public Quote preview(QuoteInput input, Map<Long, List<Line>> lines) {
    return calculate(input, lines);
  }

  private Quote calculate(QuoteInput input, Map<Long, List<Line>> override) {
    long u = user();
    check(
        input != null
            && input.merchantIds() != null
            && !input.merchantIds().isEmpty()
            && input.merchantIds().size() <= 10,
        "请选择1-10家店铺");
    check(
        Set.of("DELIVERY", "PICKUP").contains(Objects.toString(input.fulfillmentMode(), "")),
        "履约方式无效");
    catalog.validCampus(input.campusId());
    check(input.merchantIds().stream().allMatch(x -> x != null && x > 0), "店铺标识无效");
    var ids = new ArrayList<>(new TreeSet<>(input.merchantIds()));
    var shopCoupons =
        input.shopCoupons() == null ? Map.<Long, Long>of() : new TreeMap<>(input.shopCoupons());
    var slots =
        input.reservationSlots() == null
            ? Map.<Long, Long>of()
            : new TreeMap<>(input.reservationSlots());
    check(ids.containsAll(shopCoupons.keySet()) && ids.containsAll(slots.keySet()), "优惠券或预约包含未选店铺");
    var request =
        new QuoteInput(
            ids,
            input.addressId(),
            input.campusId(),
            input.fulfillmentMode(),
            shopCoupons,
            input.platformCoupon(),
            slots);
    long addressVersion = 0;
    if ("DELIVERY".equals(request.fulfillmentMode())) {
      var a =
          one(
              db,
              "SELECT * FROM customer_address WHERE id=? AND user_id=?",
              request.addressId(),
              u);
      check(id(a, "campus_id") == request.campusId(), "地址校区不一致");
      addressVersion = id(a, "version");
    }
    var available = new ArrayList<ShopQuote>();
    var errors = new TreeMap<Long, String>();
    for (long m : ids) {
      try {
        available.add(
            UserContext.within(
                m, () -> shop(request, m, override == null ? null : override.get(m))));
      } catch (IllegalArgumentException e) {
        errors.put(
            m,
            e instanceof BusinessException && ((BusinessException) e).status().value() == 404
                ? "店铺或商品不可用"
                : e.getMessage());
      }
    }
    BigDecimal basis =
        available.stream()
            .map(s -> s.goods().subtract(s.shopDiscount()))
            .reduce(ZERO, BigDecimal::add);
    BigDecimal platform = ZERO;
    try {
      platform = coupons.discount(request.platformCoupon(), null, basis);
    } catch (IllegalArgumentException e) {
      errors.put(0L, "平台优惠券不可用，请重新选择：" + e.getMessage());
    }
    var allocations =
        allocate(
            platform, available.stream().map(s -> s.goods().subtract(s.shopDiscount())).toList());
    var shops = new ArrayList<ShopQuote>();
    for (int i = 0; i < available.size(); i++) {
      var s = available.get(i);
      var d = allocations.get(i);
      shops.add(
          new ShopQuote(
              s.merchantId(),
              s.merchantName(),
              s.merchantVersion(),
              s.ruleVersion(),
              s.campusId(),
              s.mode(),
              s.slotId(),
              s.couponId(),
              s.lines(),
              s.goods(),
              s.packaging(),
              s.delivery(),
              s.shopDiscount(),
              d,
              s.goods().add(s.packaging()).add(s.delivery()).subtract(s.shopDiscount()).subtract(d),
              s.etaMin(),
              s.etaMax()));
    }
    BigDecimal total = shops.stream().map(ShopQuote::payable).reduce(ZERO, BigDecimal::add);
    check(total.compareTo(new BigDecimal("99999999.99")) <= 0, "结算金额超过上限");
    return new Quote(
        UUID.randomUUID().toString(),
        1,
        BusinessTime.now().plusMinutes(5),
        request,
        addressVersion,
        shops,
        errors,
        total);
  }

  private ShopQuote shop(QuoteInput r, long m, List<Line> override) {
    // Reservations may be placed outside current opening hours, but the slot must be inside them.
    var merchant = merchants.get(m);
    check(merchant.enabled() && merchant.acceptingOrders(), "店铺暂不接单");
    Long slot = r.reservationSlots().get(m);
    if (slot == null) merchants.lock(true);
    List<Map<String, Object>> rows;
    if (override == null)
      rows =
          db.queryForList(
              "SELECT * FROM cart_item WHERE user_id=? AND merchant_id=? ORDER BY"
                  + " product_type,product_id",
              user(),
              m);
    else {
      rows = new ArrayList<>();
      for (var l : override)
        rows.add(
            Map.of(
                "id",
                0L,
                "version",
                0L,
                "product_type",
                l.product().type(),
                "product_id",
                l.product().id(),
                "quantity",
                l.quantity(),
                "remark",
                "",
                "safety_allergens",
                l.product().safetySnapshot()));
    }
    check(!rows.isEmpty() && rows.size() <= 20, "每家店铺需1-20项商品");
    var lines = new ArrayList<Line>();
    var needs = new TreeMap<Long, Integer>();
    BigDecimal goods = ZERO, pack = ZERO;
    for (var row : rows) {
      var p =
          catalog.resolve(
              (String) row.get("product_type"),
              id(row, "product_id"),
              num(row, "quantity"),
              (String) row.get("safety_allergens"));
      check(p.merchantId() == m, "商品店铺不符");
      int q = num(row, "quantity");
      lines.add(new Line(id(row, "id"), id(row, "version"), p, q, (String) row.get("remark")));
      goods = goods.add(p.price().multiply(BigDecimal.valueOf(q)));
      pack = pack.add(p.packagingFee().multiply(BigDecimal.valueOf(q)));
      for (var c : p.components())
        needs.merge(c.skuId(), Math.multiplyExact(c.quantity(), q), Integer::sum);
    }
    for (var e : needs.entrySet())
      check(
          num(catalog.stock(e.getKey(), false), "available_stock") >= e.getValue(),
          "套餐与单品共用组件库存不足");
    var rule =
        one(
            db,
            "SELECT * FROM delivery_rule WHERE merchant_id=? AND campus_id=? AND enabled=TRUE",
            m,
            r.campusId());
    check(goods.compareTo(amount(rule, "minimum_amount")) >= 0, "未达到该店起送金额");
    BigDecimal fee = "PICKUP".equals(r.fulfillmentMode()) ? ZERO : amount(rule, "fee");
    if ("PICKUP".equals(r.fulfillmentMode()))
      check(
          id(one(db, "SELECT campus_id FROM merchant WHERE id=?", m), "campus_id") == r.campusId(),
          "自提校区与店铺所在地不符");
    if (slot != null) validateSlot(slot, m, merchant.opensAt(), merchant.closesAt());
    BigDecimal discount = coupons.discount(r.shopCoupons().get(m), m, goods);
    return new ShopQuote(
        m,
        merchant.name(),
        merchant.version(),
        id(rule, "version"),
        r.campusId(),
        r.fulfillmentMode(),
        slot,
        r.shopCoupons().get(m),
        lines,
        goods,
        pack,
        fee,
        discount,
        ZERO,
        ZERO,
        num(rule, "eta_min"),
        num(rule, "eta_max"));
  }

  public void validateSlot(long id, long m, String opens, String closes) {
    var s =
        one(
            db,
            "SELECT * FROM reservation_slot WHERE id=? AND merchant_id=? AND enabled=TRUE",
            id,
            m);
    var now = BusinessTime.now();
    var start = time(s, "starts_at");
    var end = time(s, "ends_at");
    var cutoff = time(s, "cutoff_at");
    check(
        now.isBefore(cutoff)
            && start.isAfter(now)
            && !start.isAfter(now.plusDays(7))
            && num(s, "reserved_count") < num(s, "capacity"),
        "预约时段已截止或容量不足");
    check(
        inside(start.toLocalTime(), LocalTime.parse(opens), LocalTime.parse(closes))
            && inside(
                end.minusNanos(1).toLocalTime(), LocalTime.parse(opens), LocalTime.parse(closes)),
        "预约时段不在营业时间内");
  }

  static boolean inside(LocalTime now, LocalTime start, LocalTime end) {
    return start.equals(end)
        || (start.isBefore(end)
            ? !now.isBefore(start) && now.isBefore(end)
            : !now.isBefore(start) || now.isBefore(end));
  }
}
