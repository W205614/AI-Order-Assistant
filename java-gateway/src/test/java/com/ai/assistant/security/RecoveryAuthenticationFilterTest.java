package com.ai.assistant.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RecoveryAuthenticationFilterTest {
  @Test
  void invalidRegistrationNamesFailBeforeDatabaseAccessOrTokenCreation() {
    var db = mock(JdbcTemplate.class);
    var jwt = mock(JwtUtil.class);
    var auth = new AuthService(db, jwt, new AuthProperties());
    for (String username : new String[] {null, "ab", "a b", "a".repeat(51)})
      assertThrows(
          IllegalArgumentException.class,
          () -> auth.register(username, "valid-test-password", "name"));
    verifyNoInteractions(db, jwt);
  }

  @Test
  void missingCredentialsRemainAnonymousOnlyOnThePublicChallengeRoute() throws Exception {
    AuthService auth = mock(AuthService.class);
    var filter = new AuthenticationFilter(auth, "i".repeat(32), "m".repeat(32));
    for (String path : new String[] {"/auth/phone/challenges", "/cart", "/admin/orders"}) {
      var reached = new AtomicBoolean();
      var response = new MockHttpServletResponse();
      filter.doFilter(
          new MockHttpServletRequest("POST", path),
          response,
          (request, result) -> reached.set(true));
      assertEquals(path.equals("/auth/phone/challenges"), reached.get());
      assertEquals(path.equals("/auth/phone/challenges") ? 200 : 401, response.getStatus());
    }
    verifyNoInteractions(auth);
  }

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
