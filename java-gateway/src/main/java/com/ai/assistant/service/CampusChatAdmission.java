package com.ai.assistant.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Admit campus chat before catalog reads or safety writes. Concurrency is per gateway process. */
@Service
public class CampusChatAdmission {
  private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>("""
      local t=redis.call('TIME')
      local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
      redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-60000)
      if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end
      redis.call('ZADD',KEYS[1],now,ARGV[2])
      redis.call('PEXPIRE',KEYS[1],60000)
      return 1
      """, Long.class);
  private final int totalLimit;
  private final int userLimit;
  private final int rateLimit;
  private final boolean redisEnabled;
  private final ObjectProvider<StringRedisTemplate> templates;
  private final Map<Long, Integer> active = new HashMap<>();
  private final Map<Long, ArrayDeque<Long>> windows = new HashMap<>();
  private int total;

  public CampusChatAdmission(
      @Value("${ai.admission.max-concurrent:8}") int totalLimit,
      @Value("${ai.admission.user-concurrent:2}") int userLimit,
      @Value("${ai.admission.per-minute:30}") int rateLimit,
      @Value("${ai.admission.redis-enabled:false}") boolean redisEnabled,
      ObjectProvider<StringRedisTemplate> templates) {
    if (totalLimit < 1 || totalLimit > 64 || userLimit < 1 || userLimit > 16
        || rateLimit < 1 || rateLimit > 10000)
      throw new IllegalArgumentException("AI admission limits are invalid");
    this.totalLimit = totalLimit;
    this.userLimit = userLimit;
    this.rateLimit = rateLimit;
    this.redisEnabled = redisEnabled;
    this.templates = templates;
  }

  public Lease acquire(long user) {
    synchronized (this) {
      if (total >= totalLimit || active.getOrDefault(user, 0) >= userLimit) throw capacity();
      total++;
      active.merge(user, 1, Integer::sum);
    }
    var lease = new Lease(user);
    try {
      if (redisEnabled) {
        var template = templates.getIfAvailable();
        if (template == null) throw unavailable();
        Long allowed = template.execute(RATE, List.of("ai-order-gateway:campus-chat:user:" + user),
            Integer.toString(rateLimit), java.util.UUID.randomUUID().toString());
        if (allowed == null) throw unavailable();
        if (!Long.valueOf(1).equals(allowed)) throw capacity();
      } else {
        memoryRate(user);
      }
      return lease;
    } catch (DataAccessException e) {
      lease.close();
      throw unavailable();
    } catch (RuntimeException e) {
      lease.close();
      throw e;
    }
  }

  private synchronized void memoryRate(long user) {
    long now = System.nanoTime();
    long cutoff = now - java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
    windows.values().removeIf(q -> q.isEmpty() || q.peekLast() <= cutoff);
    var q = windows.computeIfAbsent(user, ignored -> new ArrayDeque<>());
    while (!q.isEmpty() && q.peekFirst() <= cutoff) q.removeFirst();
    if (q.size() >= rateLimit) throw capacity();
    q.addLast(now);
  }

  private static BusinessException capacity() {
    return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "AI_CAPACITY", "AI 请求过多，请稍后再试或使用菜单点餐");
  }

  private static BusinessException unavailable() {
    return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI 准入服务暂不可用，请使用菜单点餐");
  }

  public final class Lease implements AutoCloseable {
    private final long user;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Lease(long user) { this.user = user; }
    @Override public void close() {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (CampusChatAdmission.this) {
        total--;
        active.computeIfPresent(user, (id, n) -> n == 1 ? null : n - 1);
      }
    }
  }
}
