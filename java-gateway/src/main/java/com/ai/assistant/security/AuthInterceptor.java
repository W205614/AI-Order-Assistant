package com.ai.assistant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 鉴权拦截器：
 *   - /admin/** 下的接口要求管理员 JWT
 *   - /dish、/order、/chat 要求用户 JWT
 *   - 页面跳转（GET /chat、/admin）跳过
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final AuthProperties props;

    public AuthInterceptor(JwtUtil jwtUtil, AuthProperties props) {
        this.jwtUtil = jwtUtil;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // 页面跳转不鉴权
        if ("GET".equals(method) && (uri.equals("/chat") || uri.equals("/chat/")
                || uri.equals("/admin") || uri.equals("/admin/"))) {
            return true;
        }

        String token = extractToken(request);
        try {
            if (uri.startsWith("/admin/")) {
                Map<String, Object> claims = jwtUtil.parseJWT(props.getAdminSecretKey(), token);
                Object adminId = claims.get("adminId");
                if (adminId == null) {
                    throw new AuthException("管理员凭证无效");
                }
                UserContext.setAdmin(Long.valueOf(adminId.toString()));
            } else {
                Map<String, Object> claims = jwtUtil.parseJWT(props.getUserSecretKey(), token);
                Object userId = claims.get("userId");
                if (userId == null) {
                    throw new AuthException("登录已过期，请重新登录");
                }
                UserContext.setUser(Long.valueOf(userId.toString()), token);
            }
            return true;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("登录已过期，请重新登录");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            throw new AuthException("未登录");
        }
        if (header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return header.trim();
    }
}
