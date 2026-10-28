package com.ai.assistant.security;

import static org.junit.jupiter.api.Assertions.*;

import com.ai.assistant.service.BusinessException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UserContextDeadlineTest {
  @AfterEach void clear() { UserContext.clear(); }

  @Test void nestedDeadlineCannotExtendCallerAndRestoresEachScope() {
    long original = System.currentTimeMillis() + 10000;
    long nested = original - 2000;
    UserContext.setTrustedDeadline(original);
    UserContext.withinDeadline(nested, () -> {
      assertEquals(nested, UserContext.effectiveDeadline(Long.MAX_VALUE));
      UserContext.withinDeadline(original + 1000, () -> {
        assertEquals(nested, UserContext.effectiveDeadline(Long.MAX_VALUE));
        return null;
      });
      assertEquals(nested, UserContext.effectiveDeadline(Long.MAX_VALUE));
      return null;
    });
    assertEquals(original, UserContext.effectiveDeadline(Long.MAX_VALUE));
  }

  @Test void expiredScopeSkipsWorkAndRestoresPreviouslyAbsentDeadline() {
    var called = new AtomicBoolean();
    var error = assertThrows(BusinessException.class, () -> UserContext.withinDeadline(
        System.currentTimeMillis() - 1, () -> {called.set(true); return null;}));
    assertEquals("AI_TIMEOUT", error.errorCode());
    assertFalse(called.get());
    assertEquals(Long.MAX_VALUE, UserContext.effectiveDeadline(Long.MAX_VALUE));
  }

  @Test void failedWorkRestoresTheCallersDeadline() {
    long original = System.currentTimeMillis() + 10000;
    UserContext.setTrustedDeadline(original);
    assertThrows(IllegalStateException.class, () -> UserContext.withinDeadline(
        original - 2000, () -> {throw new IllegalStateException("fixture");}));
    assertEquals(original, UserContext.effectiveDeadline(Long.MAX_VALUE));
  }
}
