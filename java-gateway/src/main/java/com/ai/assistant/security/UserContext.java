package com.ai.assistant.security;

/**
 * 当前请求用户上下文（ThreadLocal）。
 * 由 AuthInterceptor 在进入 Controller 前设置，请求结束清除。
 */
public class UserContext {

    private static final ThreadLocal<Long> CURRENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IS_ADMIN = new ThreadLocal<>();

    public static void setUser(Long userId, String token) {
        CURRENT_ID.set(userId);
        CURRENT_TOKEN.set(token);
        IS_ADMIN.set(false);
    }

    public static void setAdmin(Long adminId) {
        CURRENT_ID.set(adminId);
        CURRENT_TOKEN.set(null);
        IS_ADMIN.set(true);
    }

    /** 当前登录用户/管理员 id（未登录返回 null） */
    public static Long getCurrentId() {
        return CURRENT_ID.get();
    }

    /** 当前用户 JWT（仅用户端有值） */
    public static String getToken() {
        return CURRENT_TOKEN.get();
    }

    public static boolean isAdmin() {
        return Boolean.TRUE.equals(IS_ADMIN.get());
    }

    public static void clear() {
        CURRENT_ID.remove();
        CURRENT_TOKEN.remove();
        IS_ADMIN.remove();
    }
}
