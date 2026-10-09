package com.ai.assistant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.assistant.client.AgentHttpClient;
import com.ai.assistant.dto.ChatRequestDTO;
import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.BusinessException;
import com.ai.assistant.service.OrderSafetyService;
import com.ai.assistant.service.OrderService;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatControllerFailureTest {
  private AgentHttpClient agent;
  private ChatController controller;
  private ChatRequestDTO request;

  @BeforeEach
  void setUp() {
    agent = mock(AgentHttpClient.class);
    var safety = mock(OrderSafetyService.class);
    when(safety.recordFromMessage(anyLong(), anyString()))
        .thenReturn(new OrderSafetyService.SafetyContext(List.of(), false, null));
    controller = new ChatController(agent, new AiProperties(), safety, mock(OrderService.class));
    request = new ChatRequestDTO();
    request.setMessage("推荐午餐");
    UserContext.set(1L, "test-token", "CUSTOMER", 1L);
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
  void refusedConnectionIsUnavailable() throws Exception {
    failWith(new ConnectException("connection refused"));
    assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
  }

  @Test
  void unresolvableAgentIsUnavailable() throws Exception {
    failWith(new UnknownHostException("agent"));
    assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE");
  }

  @Test
  void responseTimeoutStillReturnsGatewayTimeout() throws Exception {
    failWith(new SocketTimeoutException("response timed out"));
    assertFailure(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT");
  }

  @Test
  void preservesExplicitAgentCapacityAndTimeoutFailures() throws Exception {
    for (var failure : List.of(
        new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "AI_CAPACITY", "capacity"),
        new BusinessException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "timeout"),
        new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "unavailable"))) {
      doThrow(failure).when(agent)
          .doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString());
      assertSame(failure, assertThrows(BusinessException.class,
          () -> controller.chat(request, "trace-failure-test")));
    }
  }

  private void failWith(IOException failure) throws IOException {
    when(agent.doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString()))
        .thenThrow(failure);
  }

  private void assertFailure(HttpStatus status, String code) {
    var failure = assertThrows(BusinessException.class,
        () -> controller.chat(request, "trace-failure-test"));
    assertEquals(status, failure.status());
    assertEquals(code, failure.errorCode());
  }
}
