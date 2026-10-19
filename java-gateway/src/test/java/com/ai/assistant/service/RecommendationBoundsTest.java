package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RecommendationBoundsTest {
  @Test void previewCountIsBoundedAndAdmissionRejectsBeforeTransaction() throws Exception {
    var catalog = mock(CampusCatalogService.class);
    var quotes = mock(CommerceQuoteService.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var service = spy(new CampusRecommendationService(catalog, quotes, mock(CommerceCartService.class),
        mock(OrderSafetyService.class), 2, 64, manager));
    var options = new ArrayList<Product>();
    for (int i = 1; i <= 8; i++) options.add(new Product("SKU", i, 1, i, "food",
        BigDecimal.ONE, BigDecimal.ZERO, 0, List.of(), ""));
    doReturn(options).when(service).search(anyLong(), anyString());
    when(quotes.preview(any(), anyMap())).thenThrow(new IllegalArgumentException("unavailable"));
    var request = new CampusRecommendationService.Request(1, null, "PICKUP", BigDecimal.TEN,
        List.of("a", "b", "c"), Map.of(), null);
    UserContext.setUser(1L, null);
    try {
      var result = service.recommend(request);
      assertEquals(64, result.get("combinationsExamined"));
      verify(quotes, times(64)).preview(any(), anyMap());
      assertEquals(true, result.get("searchLimited"));
    } finally { UserContext.clear(); }

    reset(quotes, manager);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    when(quotes.preview(any(), anyMap())).thenAnswer(call -> {
      entered.countDown();
      release.await(3, TimeUnit.SECONDS);
      throw new IllegalArgumentException("unavailable");
    });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> {
        UserContext.setUser(1L, null);
        try { service.recommend(request); } finally { UserContext.clear(); }
      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      BusinessException error = assertThrows(BusinessException.class, () -> service.recommend(request));
      assertEquals("RECOMMEND_LIMIT", error.errorCode());
      verify(manager, times(2)).getTransaction(any());
      release.countDown();
      for (var task : tasks) task.get(4, TimeUnit.SECONDS);
    } finally { release.countDown(); pool.shutdownNow(); }
  }
}
