package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ai.assistant.security.UserContext;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class MenuCacheFailureTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Cache cache = mock(Cache.class);
  private MenuService menu;

  @BeforeEach void setUp() {
    UserContext.set(1L, "token", "CUSTOMER", 1L);
    var manager = mock(CacheManager.class);
    when(manager.getCache("merchantMenu")).thenReturn(cache);
    menu = new MenuService(jdbc, manager, mock(AuditService.class), mock(OrderSafetyService.class));
    when(jdbc.query(anyString(), same(MenuService.MAPPER), eq(1L))).thenReturn(List.of());
  }

  @AfterEach void clear() { UserContext.clear(); }

  @Test void databaseFailurePropagatesWithoutRetry() {
    var failure = new DataAccessResourceFailureException("database unavailable");
    when(jdbc.query(anyString(), same(MenuService.MAPPER), eq(1L))).thenThrow(failure);
    assertSame(failure, assertThrows(DataAccessResourceFailureException.class, menu::all));
    verify(jdbc, times(1)).query(anyString(), same(MenuService.MAPPER), eq(1L));
    verify(cache, never()).put(any(), any());
  }

  @Test void failedCacheWriteDoesNotRepeatDatabaseQuery() {
    doThrow(new IllegalStateException("cache unavailable")).when(cache).put(any(), any());
    assertTrue(menu.all().isEmpty());
    verify(jdbc, times(1)).query(anyString(), same(MenuService.MAPPER), eq(1L));
  }

  @Test void failedCacheReadStillUsesDatabase() {
    when(cache.get(1L, String.class)).thenThrow(new IllegalStateException("cache unavailable"));
    assertTrue(menu.all().isEmpty());
    verify(jdbc, times(1)).query(anyString(), same(MenuService.MAPPER), eq(1L));
  }
}
