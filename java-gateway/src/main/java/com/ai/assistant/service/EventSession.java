package com.ai.assistant.service;

import com.ai.assistant.security.AuthException;
import com.ai.assistant.security.AuthService;
import com.ai.assistant.security.UserContext;
import java.util.Objects;

/** Original HTTP identity, retained only in memory for asynchronous event authorization. */
final class EventSession {
  private final boolean management;
  private final String token;
  private final long id;
  private final String role;

  private EventSession(boolean management, String token, long id, String role) {
    this.management = management;
    this.token = token;
    this.id = id;
    this.role = role;
  }

  static EventSession capture() {
    String token = UserContext.getToken();
    Long id = UserContext.getCurrentId();
    if (token == null || token.isBlank() || id == null) throw new AuthException("请先登录");
    return new EventSession(UserContext.isAdmin(), token, id, UserContext.role());
  }

  void validate(AuthService auth) {
    // AuthService checks signature/expiry, revocation, enabled accounts, token version,
    // enabled merchants, and changed staff permissions. No request ThreadLocal is reused.
    var claims = auth.validate(management, token);
    if (!(claims.get(management ? "adminId" : "userId") instanceof Number principal)
        || principal.longValue() != id || !Objects.equals(role, claims.get("role")))
      throw new AuthException("登录已失效，请重新登录");
  }
}
