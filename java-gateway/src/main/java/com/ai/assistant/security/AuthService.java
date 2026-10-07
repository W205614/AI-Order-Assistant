package com.ai.assistant.security;

import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.BusinessTime;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final JdbcTemplate jdbc;
  private final JwtUtil jwt;
  private final AuthProperties props;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final String dummy = encoder.encode(UUID.randomUUID().toString());

  public AuthService(JdbcTemplate jdbc, JwtUtil jwt, AuthProperties props) {
    this.jdbc = jdbc;
    this.jwt = jwt;
    this.props = props;
  }

  public static void validPassword(String value) {
    if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException("新密码至少12个字符，UTF-8长度不超过72字节");
  }

  public Map<String, Object> register(String username, String password, String nickname) {
    if (username == null || !username.matches("[A-Za-z0-9_-]{3,50}"))
      throw new IllegalArgumentException("账号须为3-50位字母、数字、下划线或横线");
    validPassword(password);
    if (nickname != null && nickname.length() > 50) throw new IllegalArgumentException("昵称过长");
    try {
      jdbc.update(
          "INSERT INTO user(username,password,nickname,created_at) VALUES(?,?,?,?)",
          username,
          encoder.encode(password),
          nickname == null || nickname.isBlank() ? username : nickname,
          BusinessTime.now());
    } catch (DuplicateKeyException e) {
      throw BusinessException.conflict("用户名已存在", null);
    }
    return login(username, password);
  }

  public Map<String, Object> login(String username, String password) {
    return authenticate(false, username, password);
  }

  public Map<String, Object> adminLogin(String username, String password) {
    return authenticate(true, username, password);
  }

  private Map<String, Object> authenticate(boolean staff, String username, String password) {
    if (username == null
        || username.length() > 50
        || password == null
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new AuthException("账号或密码错误");
    var rows =
        jdbc.queryForList(
            "SELECT * FROM " + (staff ? "admin_user" : "user") + " WHERE username=?", username);
    var row = rows.isEmpty() ? null : rows.get(0);
    boolean matches =
        encoder.matches(password, row == null ? dummy : String.valueOf(row.get("password")));
    if (!matches || row == null || !Boolean.TRUE.equals(row.get("enabled")))
      throw new AuthException("账号或密码错误");
    Map<String, Object> claims = new HashMap<>();
    claims.put(staff ? "adminId" : "userId", ((Number) row.get("id")).longValue());
    claims.put("tokenVersion", ((Number) row.get("token_version")).longValue());
    claims.put("role", staff ? String.valueOf(row.get("role")) : "CUSTOMER");
    if (staff && row.get("merchant_id") != null)
      claims.put("merchantId", ((Number) row.get("merchant_id")).longValue());
    String token =
        jwt.createJWT(
            staff ? props.getAdminSecretKey() : props.getUserSecretKey(),
            staff ? props.getAdminTtl() : props.getUserTtl(),
            claims);
    Map<String, Object> result = new HashMap<>(claims);
    result.put("username", username);
    result.put("token", token);
    if (!staff) result.put("nickname", row.get("nickname"));
    return result;
  }

  public Map<String, Object> validate(boolean staff, String token) {
    var claims = jwt.parseJWT(staff ? props.getAdminSecretKey() : props.getUserSecretKey(), token);
    String idKey = staff ? "adminId" : "userId";
    if (!(claims.get(idKey) instanceof Number id)
        || !(claims.get("tokenVersion") instanceof Number ver)) throw new AuthException("凭证无效");
    var rows =
        jdbc.queryForList(
            "SELECT * FROM "
                + (staff ? "admin_user" : "user")
                + " WHERE id=? AND enabled=TRUE AND token_version=?",
            id.longValue(),
            ver.longValue());
    if (rows.isEmpty()
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM revoked_token WHERE jti=? AND expires_at>NOW()",
                Integer.class,
                claims.get("jti"))
            > 0) throw new AuthException("登录已失效，请重新登录");
    var row = rows.get(0);
    if (staff
        && row.get("merchant_id") instanceof Number merchant
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM merchant WHERE id=? AND enabled=TRUE",
                Integer.class,
                merchant.longValue())
            != 1) throw new AuthException("商户已停用");
    if (staff
        && (!Objects.equals(claims.get("role"), row.get("role"))
            || !Objects.equals(number(claims.get("merchantId")), number(row.get("merchant_id")))))
      throw new AuthException("权限已变化，请重新登录");
    claims.put("username", row.get("username"));
    if (!staff) claims.put("nickname", row.get("nickname"));
    return claims;
  }

  private Long number(Object n) {
    return n == null ? null : ((Number) n).longValue();
  }

  public void logout() {
    var claims =
        jwt.parseJWT(
            UserContext.isAdmin() ? props.getAdminSecretKey() : props.getUserSecretKey(),
            UserContext.getToken());
    Instant exp = (Instant) claims.get("exp");
    jdbc.update(
        "INSERT IGNORE INTO revoked_token(jti,expires_at) VALUES(?,?)",
        claims.get("jti"),
        LocalDateTime.ofInstant(exp, ZoneId.of("Asia/Shanghai")));
  }

  @Transactional
  public void changePassword(String oldPassword, String newPassword) {
    validPassword(newPassword);
    String table = UserContext.isAdmin() ? "admin_user" : "user";
    String hash =
        jdbc.queryForObject(
            "SELECT password FROM " + table + " WHERE id=? FOR UPDATE",
            String.class,
            UserContext.getCurrentId());
    if (oldPassword == null
        || oldPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(oldPassword, hash)) throw new AuthException("原密码错误");
    jdbc.update(
        "UPDATE " + table + " SET password=?,token_version=token_version+1 WHERE id=?",
        encoder.encode(newPassword),
        UserContext.getCurrentId());
  }
}
