package com.ai.assistant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.assistant.client.AgentHttpClient;
import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.CampusCatalogService;
import com.ai.assistant.service.CampusChatAdmission;
import com.ai.assistant.service.CampusRecommendationService;
import com.ai.assistant.service.OrderSafetyService;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CampusAssistantControllerFailureTest {
  private AgentHttpClient agent;
  private CampusAssistantController controller;
  private CampusAssistantController.Chat request;

  @BeforeEach
  void setUp() {
    agent = mock(AgentHttpClient.class);
    var catalog = mock(CampusCatalogService.class);
    when(catalog.shops(2L)).thenReturn(List.of());
    controller =
        new CampusAssistantController(
            mock(CampusRecommendationService.class),
            catalog,
            agent,
            new AiProperties(),
            mock(OrderSafetyService.class),
            mock(CampusChatAdmission.class));
    request = new CampusAssistantController.Chat(2, 3L, "DELIVERY", "推荐午餐");
    UserContext.set(1L, "test-token", "CUSTOMER", null);
  }

  @AfterEach
  void clearIdentity() {
    UserContext.clear();
  }

  @Test
  void connectionTimeoutIsUnavailableRatherThanModelTimeout() throws Exception {
    failWith(new ConnectTimeoutException("connect timed out"));
    assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
  }

  @Test
  void responseTimeoutStillReturnsGatewayTimeout() throws Exception {
    failWith(new SocketTimeoutException("response timed out"));
    assertFailure(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT");
  }

  @Test
  void connectionIoFailureIsUnavailable() throws Exception {
    failWith(new IOException("agent connection failed"));
    assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
  }

  @Test
  void preservesExplicitAgentTimeoutFailure() throws Exception {
    var failure = new BusinessException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "timeout");
    when(agent.doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString()))
        .thenThrow(failure);
    assertSame(failure, assertThrows(BusinessException.class, () -> controller.chat(request)));
  }

  private void failWith(IOException failure) throws IOException {
    when(agent.doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString()))
        .thenThrow(failure);
  }

  private void assertFailure(HttpStatus status, String code) {
    var failure = assertThrows(BusinessException.class, () -> controller.chat(request));
    assertEquals(status, failure.status());
    assertEquals(code, failure.errorCode());
  }
}
