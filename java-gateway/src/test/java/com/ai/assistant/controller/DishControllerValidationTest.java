package com.ai.assistant.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ai.assistant.config.GlobalExceptionHandler;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DishControllerValidationTest {
  private MockMvc mvc;

  @BeforeEach void setUp() {
    UserContext.set(1L, "token", "CUSTOMER", 1L);
    var safety = mock(OrderSafetyService.class);
    when(safety.current(1L)).thenReturn(new OrderSafetyService.SafetyContext(List.of(), false, null));
    when(safety.effectiveAllergens(1L, null)).thenReturn(List.of());
    var menu = new MenuService(mock(JdbcTemplate.class), mock(CacheManager.class), mock(AuditService.class), safety);
    mvc = MockMvcBuilders.standaloneSetup(new DishController(menu))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @AfterEach void clear() { UserContext.clear(); }

  @Test void missingPaginationUsesDefaults() throws Exception {
    mvc.perform(get("/dish/list")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(30));
  }

  @Test void explicitOutOfRangePaginationIsRejected() throws Exception {
    for (String[] values : List.of(new String[]{"0", "30"}, new String[]{"1", "0"},
        new String[]{"1", "100"}, new String[]{"1000001", "30"})) {
      mvc.perform(get("/dish/list").param("page", values[0]).param("size", values[1]))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
  }
}
