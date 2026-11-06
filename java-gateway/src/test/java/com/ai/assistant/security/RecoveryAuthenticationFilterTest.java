package com.ai.assistant.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RecoveryAuthenticationFilterTest {
  @Test
  void expiredCookieDoesNotPreventPublicRecoveryButProtectedRoutesStillRejectIt() throws Exception {
    AuthService auth = mock(AuthService.class);
    when(auth.validate(false, "expired")).thenThrow(new AuthException("expired"));
    var filter = new AuthenticationFilter(auth, "i".repeat(32), "m".repeat(32));
    for (String path :
        new String[] {"/auth/password/recovery", "/auth/phone/challenges", "/media/file"}) {
      var request = new MockHttpServletRequest("POST", path);
      request.setCookies(new Cookie("ao_user", "expired"));
      var response = new MockHttpServletResponse();
      var reached = new AtomicBoolean();
      filter.doFilter(
          request,
          response,
          (req, res) -> {
            reached.set(true);
            assertEquals("SYSTEM", UserContext.role());
          });
      assertEquals(!path.startsWith("/media"), reached.get());
      assertEquals(path.startsWith("/media") ? 401 : 200, response.getStatus());
    }
    assertEquals("SYSTEM", UserContext.role());
  }

  @Test
  void challengeRetainsValidIdentitySoBindingCannotBecomeAnonymous() throws Exception {
    AuthService auth = mock(AuthService.class);
    when(auth.validate(false, "valid")).thenReturn(Map.of("userId", 1L, "role", "CUSTOMER"));
    var filter = new AuthenticationFilter(auth, "i".repeat(32), "m".repeat(32));
    var request = new MockHttpServletRequest("POST", "/auth/phone/challenges");
    request.setCookies(new Cookie("ao_user", "valid"));
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> assertEquals(1L, UserContext.getCurrentId()));
    assertEquals("SYSTEM", UserContext.role());
  }
}
