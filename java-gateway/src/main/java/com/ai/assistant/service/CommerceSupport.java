package com.ai.assistant.service;

import com.ai.assistant.security.UserContext;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

final class CommerceSupport {
  static final BigDecimal ZERO = new BigDecimal("0.00");

  private CommerceSupport() {}

  static void check(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  static void text(String s, int max, String label) {
    check(s != null && !s.isBlank() && s.length() <= max, label + "无效");
  }

  static BigDecimal money(BigDecimal x) {
    check(
        x != null
            && x.signum() >= 0
            && x.scale() <= 2
            && x.compareTo(new BigDecimal("9999999")) <= 0,
        "金额无效");
    return x.setScale(2);
  }

  static long id(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  static int num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).intValue();
  }

  static BigDecimal amount(Map<String, Object> row, String key) {
    return (BigDecimal) row.get(key);
  }

  static java.time.LocalDateTime time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof java.time.LocalDateTime t) return t;
    if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
    throw new IllegalStateException("Unexpected database datetime representation");
  }

  static String json(Object value) {
    return com.alibaba.fastjson2.JSON.toJSONString(
        value, com.alibaba.fastjson2.JSONWriter.Feature.WriteNonStringKeyAsString);
  }

  static boolean sameSnapshot(Object left, Object right) {
    return canonical(com.alibaba.fastjson2.JSON.parse(json(left)))
        .equals(canonical(com.alibaba.fastjson2.JSON.parse(json(right))));
  }

  private static Object canonical(Object value) {
    if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
    if (value instanceof Map<?, ?> map) {
      var result = new TreeMap<String, Object>();
      map.forEach((k, v) -> result.put(k.toString(), canonical(v)));
      return result;
    }
    if (value instanceof List<?> list)
      return list.stream().map(CommerceSupport::canonical).toList();
    return value;
  }

  static long user() {
    UserContext.requireRole("CUSTOMER");
    return UserContext.getCurrentId();
  }

  static Map<String, Object> one(JdbcTemplate jdbc, String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    if (rows.isEmpty())
      throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在或无权访问");
    return rows.getFirst();
  }

  static long insert(JdbcTemplate jdbc, String sql, Object... args) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
          return p;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  static String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Exact cent allocation. Input order is the stable tie breaker. */
  static List<BigDecimal> allocate(BigDecimal discount, List<BigDecimal> bases) {
    var result = new ArrayList<BigDecimal>(Collections.nCopies(bases.size(), ZERO));
    BigDecimal sum = bases.stream().reduce(ZERO, BigDecimal::add);
    check(discount.signum() >= 0 && discount.compareTo(sum) <= 0, "优惠金额超过商品金额");
    if (discount.signum() == 0) return result;
    long cents = discount.movePointRight(2).longValueExact(), used = 0;
    var remainders = new ArrayList<BigDecimal>();
    for (int i = 0; i < bases.size(); i++) {
      BigDecimal raw = bases.get(i).multiply(BigDecimal.valueOf(cents));
      long n = raw.divide(sum, 0, RoundingMode.DOWN).longValueExact();
      used += n;
      result.set(i, BigDecimal.valueOf(n, 2));
      remainders.add(raw.subtract(sum.multiply(BigDecimal.valueOf(n))));
    }
    var order = new ArrayList<Integer>();
    for (int i = 0; i < bases.size(); i++) order.add(i);
    order.sort(
        Comparator.<Integer, BigDecimal>comparing(remainders::get)
            .reversed()
            .thenComparingInt(i -> i));
    for (int i = 0; i < cents - used; i++) {
      int k = order.get(i);
      result.set(k, result.get(k).add(new BigDecimal("0.01")));
    }
    return result;
  }
}
