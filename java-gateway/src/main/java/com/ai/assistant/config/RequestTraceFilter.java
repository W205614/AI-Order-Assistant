package com.ai.assistant.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader("X-Request-Id");
    String id =
        incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", id);
    request.setAttribute("requestId", id);
    String saved = MDC.get("requestId");
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      if (saved == null) MDC.remove("requestId");
      else MDC.put("requestId", saved);
    }
  }
}
