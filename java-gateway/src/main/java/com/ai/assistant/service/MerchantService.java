package com.ai.assistant.service;

import com.ai.assistant.security.*;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {
  public record Merchant(
      long id,
      String name,
      boolean enabled,
      boolean acceptingOrders,
      String opensAt,
      String closesAt,
      List<String> deliveryRegions,
      long version) {}

  public record Settings(
      String name,
      Boolean acceptingOrders,
      String opensAt,
      String closesAt,
      List<String> deliveryRegions,
      Long expectedVersion) {}

  public record Provision(String name, String ownerUsername, String ownerPassword) {}

  public record StaffInput(String username, String password) {}

  private final JdbcTemplate jdbc;
  private final AuditService audit;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public MerchantService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  private static final RowMapper<Merchant> MAPPER =
      (rs, i) ->
          new Merchant(
              rs.getLong("id"),
              rs.getString("name"),
              rs.getBoolean("enabled"),
              rs.getBoolean("accepting_orders"),
              rs.getString("opens_at"),
              rs.getString("closes_at"),
              Arrays.stream(rs.getString("delivery_regions").split(",")).toList(),
              rs.getLong("version"));

  public List<Merchant> list(boolean platform) {
    return jdbc.query(
        "SELECT * FROM merchant "
            + (platform ? "" : "WHERE enabled=TRUE ")
            + "ORDER BY id LIMIT 200",
        MAPPER);
  }

  public Merchant get(long id) {
    return jdbc.query("SELECT * FROM merchant WHERE id=?", MAPPER, id).stream()
        .findFirst()
        .orElseThrow(
            () -> new BusinessException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "商户不存在"));
  }

  public Merchant lock(boolean open) {
    long id = UserContext.merchantId();
    Merchant shop =
        jdbc.query("SELECT * FROM merchant WHERE id=? FOR SHARE", MAPPER, id).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("商户不存在"));
    if (open) {
      if (!shop.enabled() || !shop.acceptingOrders()) throw new IllegalArgumentException("商户暂不接单");
      LocalTime now = BusinessTime.now().toLocalTime(),
          start = LocalTime.parse(shop.opensAt()),
          end = LocalTime.parse(shop.closesAt());
      if (!start.equals(end)) {
        boolean inside =
            start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
        if (!inside) throw new IllegalArgumentException("当前不在商户营业时间");
      }
    }
    return shop;
  }

  @Transactional
  public Merchant provision(Provision input) {
    UserContext.requireRole("PLATFORM_ADMIN");
    text(input.name(), 100, "商户名称");
    account(input.ownerUsername());
    AuthService.validPassword(input.ownerPassword());
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          var ps =
              con.prepareStatement(
                  "INSERT INTO merchant(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS);
          ps.setString(1, input.name().trim());
          return ps;
        },
        keys);
    long id = keys.getKey().longValue();
    jdbc.update(
        "INSERT INTO admin_user(username,password,merchant_id,role,created_at)"
            + " VALUES(?,?,?,'OWNER',NOW())",
        input.ownerUsername(),
        encoder.encode(input.ownerPassword()),
        id);
    audit.record(id, "MERCHANT_CREATED", id);
    return get(id);
  }

  @Transactional
  public Merchant configure(Settings input) {
    UserContext.requireRole("OWNER");
    long id = UserContext.merchantId();
    text(input.name(), 100, "商户名称");
    LocalTime start = LocalTime.parse(input.opensAt()), end = LocalTime.parse(input.closesAt());
    if (input.deliveryRegions() == null
        || input.deliveryRegions().isEmpty()
        || input.deliveryRegions().size() > 10) throw new IllegalArgumentException("配送区域需设置1-10项");
    var regions = input.deliveryRegions().stream().map(String::trim).distinct().toList();
    for (var region : regions) {
      text(region, 50, "配送区域");
      if (region.contains(",")) throw new IllegalArgumentException("区域名称不能包含逗号");
    }
    if (input.expectedVersion() == null || input.acceptingOrders() == null)
      throw new IllegalArgumentException("请提供设置版本及营业状态");
    int updated =
        jdbc.update(
            "UPDATE merchant SET"
                + " name=?,accepting_orders=?,opens_at=?,closes_at=?,delivery_regions=?,version=version+1"
                + " WHERE id=? AND version=?",
            input.name().trim(),
            input.acceptingOrders(),
            start,
            end,
            String.join(",", regions),
            id,
            input.expectedVersion());
    if (updated != 1) throw BusinessException.conflict("商户设置已更新，请刷新", get(id));
    audit.record(id, "MERCHANT_CONFIGURED", id);
    return get(id);
  }

  @Transactional
  public Merchant enable(long id, boolean enabled) {
    UserContext.requireRole("PLATFORM_ADMIN");
    get(id);
    jdbc.update("UPDATE merchant SET enabled=?,version=version+1 WHERE id=?", enabled, id);
    if (!enabled)
      jdbc.update("UPDATE admin_user SET token_version=token_version+1 WHERE merchant_id=?", id);
    audit.record(id, enabled ? "MERCHANT_ENABLED" : "MERCHANT_DISABLED", id);
    return get(id);
  }

  public List<Map<String, Object>> staff() {
    UserContext.requireRole("OWNER");
    return jdbc.queryForList(
        "SELECT id,username,role,enabled FROM admin_user WHERE merchant_id=? ORDER BY id LIMIT 100",
        UserContext.merchantId());
  }

  @Transactional
  public void addStaff(StaffInput input) {
    UserContext.requireRole("OWNER");
    account(input.username());
    AuthService.validPassword(input.password());
    jdbc.update(
        "INSERT INTO admin_user(username,password,merchant_id,role,created_at)"
            + " VALUES(?,?,?,'STAFF',NOW())",
        input.username(),
        encoder.encode(input.password()),
        UserContext.merchantId());
    audit.record(UserContext.merchantId(), "STAFF_CREATED", input.username());
  }

  @Transactional
  public void enableStaff(long id, boolean enabled) {
    UserContext.requireRole("OWNER");
    if (jdbc.update(
            "UPDATE admin_user SET enabled=?,token_version=token_version+1 WHERE id=? AND"
                + " merchant_id=? AND role='STAFF'",
            enabled,
            id,
            UserContext.merchantId())
        != 1) throw new BusinessException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "店员不存在");
    audit.record(UserContext.merchantId(), "STAFF_ENABLED_CHANGED", id);
  }

  @Transactional
  public void resetStaffPassword(long id, String password) {
    UserContext.requireRole("OWNER");
    AuthService.validPassword(password);
    if (jdbc.update(
            "UPDATE admin_user SET password=?,token_version=token_version+1 WHERE id=? AND"
                + " merchant_id=? AND role='STAFF'",
            encoder.encode(password),
            id,
            UserContext.merchantId())
        != 1) throw new BusinessException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "店员不存在");
    audit.record(UserContext.merchantId(), "STAFF_PASSWORD_RESET", id);
  }

  @Transactional
  public void enableCustomer(long id, boolean enabled) {
    UserContext.requireRole("PLATFORM_ADMIN");
    if (jdbc.update(
            "UPDATE user SET enabled=?,token_version=token_version+1 WHERE id=?", enabled, id)
        != 1) throw new IllegalArgumentException("用户不存在");
    audit.record(null, "CUSTOMER_ENABLED_CHANGED", id);
  }

  private void account(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{3,50}"))
      throw new IllegalArgumentException("账号格式无效");
  }

  private void text(String value, int max, String name) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException(name + "不能为空且长度不能超过" + max);
  }
}
