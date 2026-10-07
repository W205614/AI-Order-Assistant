package com.ai.assistant.security;

import com.ai.assistant.service.BusinessException;
import org.springframework.http.HttpStatus;

/** Explicit request identity and tenant. */
public final class UserContext {
  private record Identity(Long id, String token, String role, Long merchantId) {}

  private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

  public static void setTrustedDeadline(long deadline) {
    DEADLINE.set(deadline);
    checkDeadline();
  }

  public static void checkDeadline() {
    Long deadline = DEADLINE.get();
    if (deadline != null && System.currentTimeMillis() >= deadline)
      throw new BusinessException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "本轮时间预算已用完，业务事务已回滚");
  }

  public static void setUser(Long id, String token) {
    set(id, token, "CUSTOMER", null);
  }

  public static void setAdmin(Long id) {
    set(id, null, "OWNER", null);
  }

  public static void set(Long id, String token, String role, Long merchantId) {
    CURRENT.set(new Identity(id, token, role, merchantId));
  }

  public static Long getCurrentId() {
    return CURRENT.get() == null ? null : CURRENT.get().id();
  }

  public static String getToken() {
    return CURRENT.get() == null ? null : CURRENT.get().token();
  }

  public static String role() {
    return CURRENT.get() == null ? "SYSTEM" : CURRENT.get().role();
  }

  public static Long merchantId() {
    if (CURRENT.get() == null || CURRENT.get().merchantId() == null)
      throw new BusinessException(HttpStatus.BAD_REQUEST, "MERCHANT_REQUIRED", "请先选择商户");
    return CURRENT.get().merchantId();
  }

  public static String actor() {
    return role() + ":" + getCurrentId();
  }

  public static boolean isAdmin() {
    return !"CUSTOMER".equals(role()) && !"SYSTEM".equals(role());
  }

  public static void requireRole(String... roles) {
    for (String role : roles) if (role.equals(role())) return;
    throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有执行此操作的权限");
  }

  public static <T> T within(Long merchantId, java.util.function.Supplier<T> task) {
    Identity saved = CURRENT.get();
    CURRENT.set(
        new Identity(
            saved == null ? null : saved.id(),
            saved == null ? null : saved.token(),
            saved == null ? "SYSTEM" : saved.role(),
            merchantId));
    try {
      return task.get();
    } finally {
      if (saved == null) CURRENT.remove();
      else CURRENT.set(saved);
    }
  }

  public static void clear() {
    CURRENT.remove();
    DEADLINE.remove();
  }
}
