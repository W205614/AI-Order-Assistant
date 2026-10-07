package com.ai.assistant.controller;

import com.ai.assistant.dto.*;
import com.ai.assistant.security.*;
import com.ai.assistant.service.AuthRateService;
import com.ai.assistant.vo.Result;
import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AuthService auth;
  private final AuthProperties props;
  private final AuthRateService rate;

  public AuthController(AuthService auth, AuthProperties props, AuthRateService rate) {
    this.auth = auth;
    this.props = props;
    this.rate = rate;
  }

  @GetMapping("/csrf")
  public Result<Map<String, String>> csrf(CsrfToken token) {
    return Result.success(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
  }

  @PostMapping("/register")
  public Result<Map<String, Object>> register(
      @RequestBody RegisterDTO dto, HttpServletRequest req, HttpServletResponse res) {
    rate.check("register", req.getRemoteAddr(), Objects.toString(dto.getUsername(), ""));
    return Result.success(
        session(
            auth.register(dto.getUsername(), dto.getPassword(), dto.getNickname()), false, res));
  }

  @PostMapping("/login")
  public Result<Map<String, Object>> login(
      @RequestBody LoginDTO dto, HttpServletRequest req, HttpServletResponse res) {
    rate.check("login", req.getRemoteAddr(), Objects.toString(dto.getUsername(), ""));
    return Result.success(session(auth.login(dto.getUsername(), dto.getPassword()), false, res));
  }

  public Map<String, Object> session(
      Map<String, Object> data, boolean staff, HttpServletResponse res) {
    clearCsrf(res);
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(staff ? "ao_staff" : "ao_user", String.valueOf(data.get("token")))
            .httpOnly(true)
            .secure(props.isCookieSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofMillis(staff ? props.getAdminTtl() : props.getUserTtl()))
            .build()
            .toString());
    var safe = new HashMap<>(data);
    safe.remove("token");
    safe.remove("tokenVersion");
    return safe;
  }

  @GetMapping("/me")
  public Result<Map<String, Object>> me() {
    var data = new HashMap<>(auth.validate(UserContext.isAdmin(), UserContext.getToken()));
    data.remove("jti");
    data.remove("tokenVersion");
    return Result.success(data);
  }

  @PostMapping("/logout")
  public Result<Void> logout(HttpServletResponse res) {
    auth.logout();
    clearCookie(res);
    return Result.success();
  }

  public record PasswordChange(String oldPassword, String newPassword) {}

  @PostMapping("/password")
  public Result<Void> password(@RequestBody PasswordChange change, HttpServletResponse res) {
    auth.changePassword(change.oldPassword(), change.newPassword());
    clearCookie(res);
    return Result.success();
  }

  private void clearCookie(HttpServletResponse res) {
    clearCsrf(res);
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(UserContext.isAdmin() ? "ao_staff" : "ao_user", "")
            .httpOnly(true)
            .secure(props.isCookieSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
  }

  private void clearCsrf(HttpServletResponse res) {
    res.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("XSRF-TOKEN", "")
            .secure(props.isCookieSecure())
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
  }
}
