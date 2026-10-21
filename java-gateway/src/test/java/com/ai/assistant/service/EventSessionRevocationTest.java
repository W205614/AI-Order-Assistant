package com.ai.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ai.assistant.security.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class EventSessionRevocationTest {
  @AfterEach void clear() { UserContext.clear(); }

  @ParameterizedTest
  @CsvSource({
      "legacy,logout", "legacy,password", "legacy,disabled", "legacy,merchant",
      "legacy,permissions", "legacy,expired", "legacy,database",
      "customer,logout", "customer,password", "customer,disabled", "customer,expired", "customer,database",
      "rider,logout", "rider,password", "rider,disabled", "rider,expired", "rider,database"
  })
  void establishedStreamClosesBeforeAnotherReplayAfterSessionBecomesInvalid(String channel, String reason) {
    boolean management = !channel.equals("customer");
    String role = channel.equals("legacy") ? "OWNER" : management ? "RIDER" : "CUSTOMER";
    var invalid = new AtomicBoolean();
    var identityDb = mock(JdbcTemplate.class);
    var claims = new HashMap<String, Object>();
    claims.put(management ? "adminId" : "userId", 7L);
    claims.put("role", role);
    claims.put("tokenVersion", 1L);
    claims.put("jti", "fixture-jti");
    if (channel.equals("legacy")) claims.put("merchantId", 11L);
    var row = new HashMap<String, Object>();
    row.put("username", "fixture"); row.put("role", role);
    if (channel.equals("legacy")) row.put("merchant_id", 11L);
    var jwt = mock(JwtUtil.class);
    when(jwt.parseJWT(anyString(), eq("fixture-token"))).thenAnswer(call -> {
      if (invalid.get() && reason.equals("expired")) throw new AuthException("expired");
      return new HashMap<>(claims);
    });
    when(identityDb.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
      if (invalid.get() && reason.equals("database"))
        throw new org.springframework.dao.DataAccessResourceFailureException("fixture database unavailable");
      if (invalid.get() && Set.of("password", "disabled").contains(reason)) return List.of();
      if (invalid.get() && reason.equals("permissions")) return List.of(new HashMap<>(Map.of("role", "STAFF", "merchant_id", 11L)));
      return List.of(row);
    });
    when(identityDb.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.contains("revoked_token")) return invalid.get() && reason.equals("logout") ? 1 : 0;
      return invalid.get() && reason.equals("merchant") ? 0 : 1;
    });
    var auth = new AuthService(identityDb, jwt, new AuthProperties());
    var eventDb = mock(JdbcTemplate.class);
    when(eventDb.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    var sender = mock(EventDispatch.class);
    var tasks = new ArrayDeque<Runnable>();
    doAnswer(call -> { tasks.add(call.getArgument(1)); return null; })
        .when(sender).submit(any(), any(), any());
    var legacy = new OrderStatusEventBroker(eventDb, new SimpleMeterRegistry(), sender, auth);
    var campus = new CommerceEventService(eventDb, sender, auth);
    UserContext.set(7L, "fixture-token", role, channel.equals("legacy") ? 11L : null);
    if (channel.equals("legacy")) legacy.subscribe(7L, 0); else campus.subscribe(0, channel.equals("rider"));
    // Async workers must depend on the captured original identity, not this request ThreadLocal.
    UserContext.clear();
    tasks.remove().run();
    verify(eventDb, times(1)).queryForList(anyString(), any(Object[].class));
    invalid.set(true);
    if (channel.equals("legacy")) legacy.heartbeat(); else campus.dispatch();
    tasks.remove().run();
    verify(eventDb, times(1)).queryForList(anyString(), any(Object[].class));
    // Closed subscriptions no longer schedule work even if more business events exist.
    if (channel.equals("legacy")) legacy.heartbeat(); else campus.dispatch();
    assertTrue(tasks.isEmpty());
  }
}
