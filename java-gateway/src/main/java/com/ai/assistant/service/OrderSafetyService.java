package com.ai.assistant.service;

import com.ai.assistant.security.UserContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Java-owned, short-lived order safety state. Browser history and model output are never
 * authoritative.
 */
@Service
public class OrderSafetyService {
  public record SafetyContext(
      List<String> allergens, boolean needsClarification, LocalDateTime expiresAt) {
    public boolean active() {
      return needsClarification || !allergens.isEmpty();
    }
  }

  public static final List<String> SUPPORTED_ALLERGENS = List.of("花生", "鸡蛋", "麸质");
  private static final Pattern ALLERGY_INTENT =
      Pattern.compile("(?i)过敏|不能吃|忌口|避免|allergic|allergy");
  private static final Pattern CONFIRMED_CONTEXT_REFERENCE =
      Pattern.compile(
          "(?:我)?(?:已|已经)(?:在页面)?确认(?:过|了)?(?:本次|本店)?过敏(?:原|约束)"
              + "(?:\\s*[，,；;。]\\s*(?:请)?继续推荐)?[。！!\\s]*");
  private static final Map<String, Pattern> ALIASES =
      Map.of(
          "花生", Pattern.compile("(?i)花生|落花生|peanut"),
          "鸡蛋", Pattern.compile("(?i)鸡蛋|蛋类|egg"),
          "麸质", Pattern.compile("(?i)麸质|小麦|面粉|gluten|wheat"));
  private final JdbcTemplate jdbc;

  public OrderSafetyService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockUser(long userId) {
    List<Long> users =
        jdbc.query("SELECT id FROM user WHERE id=? FOR UPDATE", (rs, i) -> rs.getLong(1), userId);
    if (users.isEmpty()) throw new IllegalArgumentException("用户不存在");
  }

  public SafetyContext current(long userId) {
    List<SafetyContext> rows =
        jdbc.query(
            "SELECT allergens,needs_clarification,expires_at FROM order_safety_context WHERE"
                + " user_id=? AND merchant_id=? AND expires_at>NOW()",
            (rs, i) ->
                new SafetyContext(
                    FoodSafety.splitTags(rs.getString(1)),
                    rs.getBoolean(2),
                    rs.getObject(3, LocalDateTime.class)),
            userId,
            UserContext.merchantId());
    return rows.isEmpty() ? new SafetyContext(List.of(), false, null) : rows.get(0);
  }

  /** 合并长期偏好、本店临时约束和草稿快照；快照在临时约束过期后仍有效。 */
  public List<String> effectiveAllergens(long userId, String draftSnapshot) {
    Set<String> allergens = new LinkedHashSet<>();
    jdbc.query(
        "SELECT allergens FROM user_food_preference WHERE user_id=?",
        rs -> {
          allergens.addAll(FoodSafety.splitTags(rs.getString(1)));
        },
        userId);
    allergens.addAll(current(userId).allergens());
    allergens.addAll(FoodSafety.splitTags(draftSnapshot));
    return List.copyOf(allergens);
  }

  @Transactional
  public SafetyContext recordFromMessage(long userId, String message) {
    if (message == null || !ALLERGY_INTENT.matcher(message).find()) return current(userId);
    lockUser(userId);
    return recordLocked(userId, message);
  }

  /** One transaction and one user lock; never dispatch tools with only a partial safety update. */
  @Transactional
  public void recordAcrossMerchants(long userId, String message, List<Long> merchants) {
    if (merchants == null || merchants.size() > 200 || merchants.stream().anyMatch(m -> m == null || m <= 0))
      throw new IllegalArgumentException("候选商户范围无效");
    if (message == null || !ALLERGY_INTENT.matcher(message).find() || merchants.isEmpty()) return;
    lockUser(userId);
    for (long merchant : new java.util.TreeSet<>(merchants)) {
      UserContext.checkDeadline();
      UserContext.within(merchant, () -> recordLocked(userId, message));
    }
  }

  private SafetyContext recordLocked(long userId, String message) {
    SafetyContext previous = current(userId);
    // A narrow reference may reuse this shop's server-confirmed state. It cannot establish,
    // extend or change restrictions; any added constraint must still be confirmed in the UI.
    if (!previous.needsClarification()
        && !previous.allergens().isEmpty()
        && previous.allergens().stream().allMatch(SUPPORTED_ALLERGENS::contains)
        && previous.expiresAt() != null
        && previous.expiresAt().isAfter(BusinessTime.now())
        && CONFIRMED_CONTEXT_REFERENCE.matcher(message.strip()).matches()) return previous;
    Set<String> tags = new LinkedHashSet<>(previous.allergens());
    for (String tag : SUPPORTED_ALLERGENS) {
      if (ALIASES.get(tag).matcher(message).find()) tags.add(tag);
    }
    // The parser only suggests labels. A user must confirm the exact restriction in the UI.
    save(userId, new ArrayList<>(tags), true, contextExpiry(userId));
    return current(userId);
  }

  @Transactional
  public SafetyContext set(long userId, List<String> allergens) {
    if (allergens == null
        || allergens.isEmpty()
        || allergens.size() > SUPPORTED_ALLERGENS.size()
        || allergens.stream().anyMatch(tag -> !SUPPORTED_ALLERGENS.contains(tag))) {
      throw new IllegalArgumentException("请选择受支持的临时过敏原：花生、鸡蛋或麸质");
    }
    lockUser(userId);
    List<String> distinct = allergens.stream().distinct().toList();
    save(userId, distinct, false, contextExpiry(userId));
    return current(userId);
  }

  @Transactional
  public void clearByUser(long userId) {
    lockUser(userId);
    Integer pending =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_draft WHERE user_id=? AND merchant_id=? AND status=1 AND"
                + " expires_at>NOW()",
            Integer.class,
            userId,
            UserContext.merchantId());
    if (pending != null && pending > 0) throw new IllegalArgumentException("请先放弃待确认购物车，再清除本次过敏约束");
    clearLocked(userId);
  }

  public void clearLocked(long userId) {
    jdbc.update(
        "DELETE FROM order_safety_context WHERE user_id=? AND merchant_id=?",
        userId,
        UserContext.merchantId());
  }

  public void alignWithDraftLocked(long userId, LocalDateTime expiresAt) {
    jdbc.update(
        "UPDATE order_safety_context SET expires_at=? WHERE user_id=? AND merchant_id=? AND"
            + " expires_at>NOW()",
        expiresAt,
        userId,
        UserContext.merchantId());
  }

  private LocalDateTime contextExpiry(long userId) {
    List<LocalDateTime> draftExpiry =
        jdbc.query(
            "SELECT expires_at FROM order_draft WHERE user_id=? AND merchant_id=? AND status=1 AND"
                + " expires_at>NOW() ORDER BY expires_at LIMIT 1",
            (rs, i) -> rs.getObject(1, LocalDateTime.class),
            userId,
            UserContext.merchantId());
    return draftExpiry.isEmpty() ? BusinessTime.now().plusMinutes(30) : draftExpiry.get(0);
  }

  private void save(
      long userId, List<String> allergens, boolean needsClarification, LocalDateTime expiresAt) {
    jdbc.update(
        "INSERT INTO"
            + " order_safety_context(user_id,allergens,needs_clarification,expires_at,merchant_id)"
            + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE allergens=VALUES(allergens),"
            + "needs_clarification=VALUES(needs_clarification),expires_at=VALUES(expires_at)",
        userId,
        String.join(",", allergens),
        needsClarification,
        expiresAt,
        UserContext.merchantId());
  }
}
