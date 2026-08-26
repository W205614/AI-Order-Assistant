package com.ai.assistant.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册 / 登录 / 管理员登录
 */
@Service
@Slf4j
public class AuthService {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final AuthProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(JdbcTemplate jdbc, JwtUtil jwtUtil, AuthProperties props) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.props = props;
    }

    /** 用户注册，成功后直接登录返回 token */
    public Map<String, Object> register(String username, String password, String nickname) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (username.length() < 3 || username.length() > 20) {
            throw new IllegalArgumentException("用户名长度需在 3-20 位");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
        String nick = (nickname == null || nickname.isBlank()) ? username : nickname;
        jdbc.update("INSERT INTO user (username, password, nickname, created_at) VALUES (?,?,?,?)",
                username, encoder.encode(password), nick, LocalDateTime.now());
        log.info("New user registered: {}", username);
        return login(username, password);
    }

    /** 用户登录 */
    public Map<String, Object> login(String username, String password) {
        List<Map<String, Object>> users = queryUser(username);
        if (users.isEmpty() || !encoder.matches(password == null ? "" : password, (String) users.get(0).get("password"))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        Map<String, Object> user = users.get(0);
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.get("id"));
        String token = jwtUtil.createJWT(props.getUserSecretKey(), props.getUserTtl(), claims);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.get("id"));
        data.put("username", user.get("username"));
        data.put("nickname", user.get("nickname"));
        data.put("token", token);
        return data;
    }

    /** 管理员登录 */
    public Map<String, Object> adminLogin(String username, String password) {
        List<Map<String, Object>> admins = jdbc.query(
                "SELECT id, username, password FROM admin_user WHERE username = ?",
                (rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("username", rs.getString("username"));
                    m.put("password", rs.getString("password"));
                    return m;
                }, username);
        if (admins.isEmpty() || !encoder.matches(password == null ? "" : password, (String) admins.get(0).get("password"))) {
            throw new IllegalArgumentException("管理员账号或密码错误");
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("adminId", admins.get(0).get("id"));
        claims.put("role", "admin");
        String token = jwtUtil.createJWT(props.getAdminSecretKey(), props.getAdminTtl(), claims);

        Map<String, Object> data = new HashMap<>();
        data.put("adminId", admins.get(0).get("id"));
        data.put("username", admins.get(0).get("username"));
        data.put("token", token);
        return data;
    }

    private List<Map<String, Object>> queryUser(String username) {
        return jdbc.query(
                "SELECT id, username, password, nickname FROM user WHERE username = ?",
                (rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("username", rs.getString("username"));
                    m.put("password", rs.getString("password"));
                    m.put("nickname", rs.getString("nickname"));
                    return m;
                }, username);
    }
}
