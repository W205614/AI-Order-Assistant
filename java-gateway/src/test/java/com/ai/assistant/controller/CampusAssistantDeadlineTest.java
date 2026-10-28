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

class CampusAssistantDeadlineTest {
  @AfterEach void clear() { UserContext.clear(); }

  @Test void propagatesAnExistingShorterDeadlineAndRestoresItAfterTheCall() throws Exception {
    var catalog = mock(CampusCatalogService.class);
    var safety = mock(OrderSafetyService.class);
    var agent = mock(AgentHttpClient.class);
    var admission = new CampusChatAdmission(1, 1, 30, false, mock(ObjectProvider.class));
    var controller = new CampusAssistantController(mock(CampusRecommendationService.class),
        catalog, agent, new AiProperties(), safety, admission);
    UserContext.set(1L, "token", "CUSTOMER", null);
    long deadline = System.currentTimeMillis() + 5000;
    UserContext.setTrustedDeadline(deadline);
    when(catalog.shops(1)).thenReturn(List.of());
    when(agent.doPostJson(anyString(), any(), anyLong(), anyString(), anyLong(), anyString()))
        .thenAnswer(call -> {
          var payload = (Map<?, ?>) call.getArgument(1);
          assertEquals(deadline, payload.get("deadlineEpochMs"));
          long timeout = call.getArgument(2);
          assertTrue(timeout > 0 && timeout <= 5000);
          return "{\"reply\":\"ok\"}";
        });
    controller.chat(new CampusAssistantController.Chat(1, null, "PICKUP", "fixture"));
    assertEquals(deadline, UserContext.effectiveDeadline(Long.MAX_VALUE));
    try (var released = admission.acquire(1)) { assertNotNull(released); }
  }
}
