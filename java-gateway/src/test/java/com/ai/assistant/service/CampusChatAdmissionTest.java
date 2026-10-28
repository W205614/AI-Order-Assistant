package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class CampusChatAdmissionTest {
  @SuppressWarnings("unchecked")
  private ObjectProvider<StringRedisTemplate> provider() { return mock(ObjectProvider.class); }

  @Test void boundsUsersAndTotalAndReleasesExactlyOnce() {
    var admission = new CampusChatAdmission(2, 1, 30, false, provider());
    var first = admission.acquire(1);
    assertEquals("AI_CAPACITY", assertThrows(BusinessException.class,
        () -> admission.acquire(1)).errorCode());
    var second = admission.acquire(2);
    assertThrows(BusinessException.class, () -> admission.acquire(3));
    first.close();
    first.close();
    try (var third = admission.acquire(3)) {
      assertThrows(BusinessException.class, () -> admission.acquire(4));
    }
    second.close();
    try (var again = admission.acquire(1)) { assertNotNull(again); }
  }

  @Test void rateFailureAlsoReleasesCapacity() {
    var admission = new CampusChatAdmission(1, 1, 1, false, provider());
    admission.acquire(1).close();
    assertThrows(BusinessException.class, () -> admission.acquire(1));
    try (var other = admission.acquire(2)) { assertNotNull(other); }
  }

  @Test void redisFailureClosesAdmissionAndFailsOnlyAi() {
    var templates = provider();
    var redis = mock(StringRedisTemplate.class);
    when(templates.getIfAvailable()).thenReturn(redis);
    when(redis.execute(any(), anyList(), any(Object[].class)))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
    var admission = new CampusChatAdmission(1, 1, 30, true, templates);
    assertEquals("AI_UNAVAILABLE", assertThrows(BusinessException.class,
        () -> admission.acquire(1)).errorCode());
    when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);
    try (var allowed = admission.acquire(2)) { assertNotNull(allowed); }
  }
}
