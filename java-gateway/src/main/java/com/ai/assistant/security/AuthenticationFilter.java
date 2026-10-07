package com.ai.assistant.security;

import com.ai.assistant.service.BusinessException;
import com.ai.assistant.vo.Result;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {
  private final AuthService auth;
  private final String internalKey;

  public AuthenticationFilter(AuthService auth, @Value("${ai.internal-api-key}") String key) {
    this.auth = auth;
    internalKey = key;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    try {
      String path = req.getRequestURI();
      boolean internal =
          !internalKey.isBlank()
              && java.security.MessageDigest.isEqual(
                  internalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  Objects.toString(req.getHeader("X-Agent-Internal-Key"), "")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (internal
          && (req.getHeader("Authorization") != null
              || req.getHeader("X-Agent-Deadline") != null)) {
        String deadline = req.getHeader("X-Agent-Deadline");
        if (deadline == null || !deadline.matches("[0-9]{13}"))
          throw new IllegalArgumentException("内部截止时间缺失或无效");
        UserContext.setTrustedDeadline(Long.parseLong(deadline));
      }
      if (path.equals("/actuator/prometheus") && internal) {
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "monitor", null, List.of(new SimpleGrantedAuthority("ROLE_MONITOR"))));
      } else if (!Set.of(
                  "/auth/login",
                  "/auth/register",
                  "/admin/login",
                  "/auth/csrf",
                  "/",
                  "/chat/",
                  "/chat/index.html",
                  "/admin",
                  "/admin/",
                  "/admin/index.html",
                  "/platform",
                  "/platform/",
                  "/platform/index.html")
              .contains(path)
          && !path.startsWith("/assets/")
          && !path.startsWith("/actuator/health")) {
        boolean staff =
            path.startsWith("/admin")
                || path.startsWith("/platform")
                || "management".equals(req.getHeader("X-Session-Type"));
        String token = token(req, staff ? "ao_staff" : "ao_user");
        if (token != null) {
          var claims = auth.validate(staff, token);
          String role = String.valueOf(claims.get("role"));
          Long id = ((Number) claims.get(staff ? "adminId" : "userId")).longValue();
          Long merchant =
              staff && claims.get("merchantId") instanceof Number n ? n.longValue() : null;
          String selected = req.getHeader("X-Merchant-Id");
          if (selected != null) {
            if (!selected.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException("商户标识无效");
            long requested = Long.parseLong(selected);
            if (staff && !Objects.equals(merchant, requested))
              throw new BusinessException(HttpStatus.FORBIDDEN, "MERCHANT_FORBIDDEN", "无权访问该商户");
            if (!staff) merchant = requested;
          }
          UserContext.set(id, token, role, merchant);
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new UsernamePasswordAuthenticationToken(
                      id, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        }
      }
      if (req.getContentLengthLong() > 131072)
        throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "BODY_TOO_LARGE", "请求内容过大");
      chain.doFilter(req, res);
    } catch (AuthException e) {
      write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
    } catch (BusinessException e) {
      write(res, e.status(), e.errorCode(), e.getMessage());
    } catch (IllegalArgumentException e) {
      write(res, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    } finally {
      UserContext.clear();
      SecurityContextHolder.clearContext();
    }
  }

  public static String token(HttpServletRequest req, String name) {
    String header = req.getHeader("Authorization");
    if (header != null && !header.isBlank())
      return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    if (req.getCookies() != null)
      for (Cookie c : req.getCookies()) if (c.getName().equals(name)) return c.getValue();
    return null;
  }

  public static void write(HttpServletResponse res, HttpStatus status, String code, String message)
      throws IOException {
    res.setStatus(status.value());
    res.setContentType("application/json;charset=UTF-8");
    Result<Object> body = Result.error(message);
    body.setErrorCode(code);
    res.getWriter().write(JSON.toJSONString(body));
  }
}
