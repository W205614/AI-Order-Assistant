package com.ai.assistant.integration.support;

import com.ai.assistant.security.AuthService;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.AuditService;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.BusinessTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PhoneIdentityService {
  public record ChallengeInput(String purpose, String phone, String accountType, String username) {}

  public record BindInput(String currentPassword, String challengeId, String code) {}

  public record RecoveryInput(String challengeId, String code, String newPassword) {}

  private final JdbcTemplate db;
  private final SmsSender sms;
  private final TransactionTemplate tx;
  private final AuditService audit;
  private final byte[] secret;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  private final SecureRandom random = new SecureRandom();

  public PhoneIdentityService(
      JdbcTemplate db,
      SmsSender sms,
      PlatformTransactionManager manager,
      AuditService audit,
      SupportSettings settings) {
    this.db = db;
    this.sms = sms;
    this.tx = new TransactionTemplate(manager);
    this.audit = audit;
    String key = settings.secret("integration.sms.otp-hash-secret", "SMS_OTP_HASH_SECRET");
    if (key.isBlank()) key = settings.secret("auth.user-secret-key", "JWT_USER_SECRET");
    if (key.length() < 32)
      throw new IllegalStateException("SMS OTP hash secret must contain at least 32 characters");
    this.secret = key.getBytes(StandardCharsets.UTF_8);
  }

  public static void validPhone(String phone) {
    if (phone == null || !phone.matches("1[3-9][0-9]{9}"))
      throw new IllegalArgumentException("请输入有效的中国大陆手机号码");
  }

  public Map<String, Object> challenge(ChallengeInput input, String remoteAddress) {
    if (input == null) throw new IllegalArgumentException("请提供验证码申请信息");
    validPhone(input.phone());
    if (!Set.of("BIND", "RECOVER").contains(Objects.toString(input.purpose(), "")))
      throw new IllegalArgumentException("验证码用途无效");
    String type = validType(input.accountType());
    if ("BIND".equals(input.purpose())) requireIdentity(type);
    else if (input.username() == null || !input.username().matches("[A-Za-z0-9_-]{3,50}"))
      throw new IllegalArgumentException("账号格式无效");
    sms.requireEnabled();
    rate("phone:minute:" + type + ":" + input.phone(), 1, 60);
    rate("phone:hour:" + type + ":" + input.phone(), 5, 3600);
    rate("phone:day:" + type + ":" + input.phone(), 20, 86400);
    rate("ip:" + remoteAddress, 30, 3600);
    rate("ip:day:" + remoteAddress, 100, 86400);
    rate(
        "account:"
            + type
            + ":"
            + ("BIND".equals(input.purpose()) ? UserContext.getCurrentId() : input.username()),
        5,
        3600);
    rate(
        "account:day:"
            + type
            + ":"
            + ("BIND".equals(input.purpose()) ? UserContext.getCurrentId() : input.username()),
        20,
        86400);
    Long account;
    if ("BIND".equals(input.purpose())) account = UserContext.getCurrentId();
    else {
      var matches =
          db.queryForList(
              "SELECT p.account_id FROM verified_phone p JOIN "
                  + table(type)
                  + " a ON a.id=p.account_id WHERE p.account_type=? AND p.phone=?"
                  + " AND a.username=? AND a.enabled=TRUE",
              type,
              input.phone(),
              input.username());
      account =
          matches.isEmpty() ? null : ((Number) matches.getFirst().get("account_id")).longValue();
    }
    String id = UUID.randomUUID().toString();
    String code = String.format("%06d", random.nextInt(1_000_000));
    db.update(
        "INSERT INTO phone_challenge(id,account_type,account_id,phone,purpose,code_hash,expires_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        type,
        account,
        input.phone(),
        input.purpose(),
        digest(id, code),
        BusinessTime.now().plusMinutes(5));
    // Always send the same code template to the requested number. An unknown account
    // has no account_id and can never be recovered, while send failures and timing
    // cannot reveal whether a username/verified-phone pair exists.
    sms.sendCode(input.phone(), code);
    return Map.of("challengeId", id, "expiresIn", 300, "status", "REQUEST_ACCEPTED");
  }

  public Map<String, Object> current() {
    String type = currentType();
    requireIdentity(type);
    var rows =
        db.queryForList(
            "SELECT phone FROM verified_phone WHERE account_type=? AND account_id=?",
            type,
            UserContext.getCurrentId());
    if (rows.isEmpty()) return Map.of("verified", false);
    String phone = String.valueOf(rows.getFirst().get("phone"));
    return Map.of("verified", true, "phone", phone.substring(0, 3) + "****" + phone.substring(7));
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 60000)
  public void cleanupExpiredChallenges() {
    db.update(
        "DELETE FROM phone_challenge WHERE expires_at<DATE_SUB(NOW(),INTERVAL 1 DAY) LIMIT 1000");
  }

  public void bind(BindInput input) {
    if (input == null) throw new IllegalArgumentException("请提供绑定信息");
    String type = currentType();
    requireIdentity(type);
    rate("bind-verify:" + type + ":" + UserContext.getCurrentId(), 10, 300);
    String stored =
        db.queryForObject(
            "SELECT password FROM " + table(type) + " WHERE id=? AND enabled=TRUE",
            String.class,
            UserContext.getCurrentId());
    if (input.currentPassword() == null
        || input.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(input.currentPassword(), stored))
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "PASSWORD_INVALID", "当前密码错误");
    String error =
        tx.execute(
            status -> {
              Map<String, Object> challenge = check(input.challengeId(), input.code(), "BIND");
              if (challenge == null) return "验证码错误、已使用或已过期";
              if (!type.equals(challenge.get("account_type"))
                  || ((Number) challenge.get("account_id")).longValue()
                      != UserContext.getCurrentId()) return "验证码不属于当前账号";
              try {
                db.update(
                    "INSERT INTO verified_phone(account_type,account_id,phone) VALUES(?,?,?)"
                        + " ON DUPLICATE KEY UPDATE phone=VALUES(phone),verified_at=NOW()",
                    type,
                    UserContext.getCurrentId(),
                    challenge.get("phone"));
              } catch (DuplicateKeyException e) {
                throw BusinessException.conflict("该手机号码已绑定其他账号", null);
              }
              // A phone already bound to another account must not be reassigned by MySQL's
              // duplicate-key branch; ownership is checked after locking the unique row.
              Long bound =
                  db.queryForObject(
                      "SELECT account_id FROM verified_phone WHERE account_type=? AND phone=? FOR"
                          + " UPDATE",
                      Long.class,
                      type,
                      challenge.get("phone"));
              if (!Objects.equals(bound, UserContext.getCurrentId()))
                throw BusinessException.conflict("该手机号码已绑定其他账号", null);
              var current =
                  db.queryForMap(
                      "SELECT password,enabled FROM " + table(type) + " WHERE id=? FOR UPDATE",
                      UserContext.getCurrentId());
              if (!Boolean.TRUE.equals(current.get("enabled"))
                  || !stored.equals(current.get("password")))
                throw new BusinessException(
                    HttpStatus.UNAUTHORIZED, "IDENTITY_CHANGED", "账号状态或密码已变化，请重新登录");
              consume(input.challengeId());
              db.update(
                  "UPDATE " + table(type) + " SET token_version=token_version+1 WHERE id=?",
                  UserContext.getCurrentId());
              audit.record(null, "PHONE_BOUND", UserContext.getCurrentId());
              return null;
            });
    if (error != null) throw new IllegalArgumentException(error);
  }

  public void recover(RecoveryInput input) {
    if (input == null) throw new IllegalArgumentException("请提供找回密码信息");
    AuthService.validPassword(input.newPassword());
    String error =
        tx.execute(
            status -> {
              var challenge = check(input.challengeId(), input.code(), "RECOVER");
              if (challenge == null || challenge.get("account_id") == null) return "验证码错误、已使用或已过期";
              String type = String.valueOf(challenge.get("account_type"));
              long account = ((Number) challenge.get("account_id")).longValue();
              var bound =
                  db.queryForList(
                      "SELECT phone FROM verified_phone WHERE account_type=? AND account_id=? FOR"
                          + " UPDATE",
                      type,
                      account);
              if (bound.isEmpty() || !challenge.get("phone").equals(bound.getFirst().get("phone")))
                return "绑定状态已变化，请重新申请验证码";
              int changed =
                  db.update(
                      "UPDATE "
                          + table(type)
                          + " SET password=?,token_version=token_version+1"
                          + " WHERE id=? AND enabled=TRUE",
                      passwords.encode(input.newPassword()),
                      account);
              if (changed != 1) return "账号不可用，请联系平台";
              consume(input.challengeId());
              db.update(
                  "INSERT INTO audit_log(actor,action,resource_id) VALUES(?, 'PASSWORD_RECOVERED',"
                      + " ?)",
                  type + ":" + account,
                  account);
              return null;
            });
    if (error != null) throw new IllegalArgumentException(error);
  }

  private Map<String, Object> check(String id, String code, String purpose) {
    if (id == null || !id.matches("[a-f0-9-]{36}") || code == null || !code.matches("[0-9]{6}"))
      throw new IllegalArgumentException("验证码格式无效");
    var rows =
        db.queryForList(
            "SELECT * FROM phone_challenge WHERE id=? AND expires_at>? FOR UPDATE",
            id,
            BusinessTime.now());
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (!purpose.equals(row.get("purpose"))
        || row.get("consumed_at") != null
        || ((Number) row.get("attempts")).intValue() >= 5) return null;
    db.update("UPDATE phone_challenge SET attempts=attempts+1 WHERE id=?", id);
    return MessageDigest.isEqual(
            String.valueOf(row.get("code_hash")).getBytes(StandardCharsets.US_ASCII),
            digest(id, code).getBytes(StandardCharsets.US_ASCII))
        ? row
        : null;
  }

  private void consume(String id) {
    db.update("UPDATE phone_challenge SET consumed_at=? WHERE id=?", BusinessTime.now(), id);
  }

  private String digest(String id, String code) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Cannot hash verification code", e);
    }
  }

  private void rate(String bucket, int limit, int seconds) {
    String key = digest("phone-rate", bucket);
    db.update(
        "INSERT INTO auth_rate_window(bucket_key,hits,expires_at)"
            + " VALUES(?,1,DATE_ADD(NOW(),INTERVAL ? SECOND)) ON DUPLICATE KEY UPDATE"
            + " hits=IF(expires_at<=NOW(),1,LEAST(hits+1,100000)),"
            + "expires_at=IF(expires_at<=NOW(),VALUES(expires_at),expires_at)",
        key,
        seconds);
    Integer hits =
        db.queryForObject(
            "SELECT hits FROM auth_rate_window WHERE bucket_key=?", Integer.class, key);
    if (hits > limit)
      throw new BusinessException(
          HttpStatus.TOO_MANY_REQUESTS, "PHONE_RATE_LIMITED", "验证码操作过于频繁，请稍后重试");
  }

  private static String validType(String type) {
    if (!Set.of("CUSTOMER", "MANAGEMENT").contains(Objects.toString(type, "")))
      throw new IllegalArgumentException("账号类型无效");
    return type;
  }

  private static String currentType() {
    return UserContext.isAdmin() ? "MANAGEMENT" : "CUSTOMER";
  }

  private static void requireIdentity(String type) {
    if (UserContext.getCurrentId() == null || !type.equals(currentType()))
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录对应账号");
  }

  private static String table(String type) {
    return "CUSTOMER".equals(type) ? "user" : "admin_user";
  }
}
