package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ai.assistant.security.UserContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderSafetyConfirmationTest {
  @Test
  void acknowledgingConfirmedRestrictionsDoesNotRewriteOrExtendThem() {
    var jdbc = mock(JdbcTemplate.class);
    var service = spy(new OrderSafetyService(jdbc));
    var confirmed =
        new OrderSafetyService.SafetyContext(
            List.of("花生"), false, BusinessTime.now().plusMinutes(10));
    doNothing().when(service).lockUser(1L);
    doReturn(confirmed).when(service).current(1L);
    UserContext.set(1L, null, "CUSTOMER", 1L);
    try {
      for (var message :
          List.of(
              "我已在页面确认过敏约束，继续推荐",
              "我已经在页面确认了过敏原，请继续推荐。",
              "已确认本店过敏约束")) {
        assertSame(confirmed, service.recordFromMessage(1L, message));
      }
      verifyNoInteractions(jdbc);
    } finally {
      UserContext.clear();
    }
  }
}
