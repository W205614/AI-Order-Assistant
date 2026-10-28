package com.ai.assistant.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ai.assistant.client.AgentHttpClient;
import com.ai.assistant.properties.AiProperties;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CampusChatAdmissionControllerTest {
  @AfterEach void clear() { UserContext.clear(); }

  @Test void rejectedRequestDoesNotReadCatalogOrWriteRestrictions() {
    var catalog = mock(CampusCatalogService.class);
    var safety = mock(OrderSafetyService.class);
    var agent = mock(AgentHttpClient.class);
    var admission = new CampusChatAdmission(1, 1, 30, false, mock(ObjectProvider.class));
    var controller = new CampusAssistantController(mock(CampusRecommendationService.class), catalog,
        agent, new AiProperties(), safety, admission);
    UserContext.set(1L, "token", "CUSTOMER", null);
    try (var busy = admission.acquire(1)) {
      assertThrows(BusinessException.class, () -> controller.chat(
          new CampusAssistantController.Chat(1, null, "PICKUP", "我对花生过敏")));
      verifyNoInteractions(catalog, safety, agent);
    }
  }

  @Test void recordsBoundedSafetyBatchBeforeAgentAndReleasesAfterFailure() throws Exception {
    var catalog = mock(CampusCatalogService.class);
    var safety = mock(OrderSafetyService.class);
    var agent = mock(AgentHttpClient.class);
    var admission = new CampusChatAdmission(1, 1, 30, false, mock(ObjectProvider.class));
    when(catalog.shops(1)).thenReturn(List.of(Map.of("id", 2L), Map.of("id", 3L)));
    when(agent.doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString()))
        .thenThrow(new java.io.IOException("offline"));
    var controller = new CampusAssistantController(mock(CampusRecommendationService.class), catalog,
        agent, new AiProperties(), safety, admission);
    UserContext.set(1L, "token", "CUSTOMER", null);
    assertThrows(BusinessException.class, () -> controller.chat(
        new CampusAssistantController.Chat(1, null, "PICKUP", "我对花生过敏")));
    var order = inOrder(safety, agent);
    order.verify(safety).recordAcrossMerchants(1, "我对花生过敏", List.of(2L, 3L));
    order.verify(agent).doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString());
    try (var released = admission.acquire(1)) { assertNotNull(released); }
    // The request deadline must not leak into later operations on the servlet thread.
    assertEquals(100, UserContext.databaseTimeoutSeconds(100));
  }
}
