package com.ai.assistant.service;

import com.ai.assistant.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将订单状态变化推给当前在线的订单所属用户。
 *
 * 订单状态本身始终以数据库为准；本组件只负责在线提醒，不将通知当作持久化消息队列。
 */
@Component
@Slf4j
public class OrderStatusEventBroker {

    private static final long CONNECTION_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private final Map<Long, Set<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
        Set<SseEmitter> emitters = emittersByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));
        try {
            // 立即发送一个轻量事件以建立并刷新 SSE 响应；前端会忽略该事件。
            emitter.send(SseEmitter.event().name("connected").data(Map.of("ok", true)));
        } catch (IOException e) {
            remove(userId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public void publish(Order order) {
        if (order == null || order.getUserId() == null || order.getStatus() == null) {
            return;
        }
        Set<SseEmitter> emitters = emittersByUser.get(order.getUserId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "orderId", order.getId(),
                "userSeq", order.getUserSeq(),
                "status", order.getStatus());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("order-status").data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("Remove unavailable order event stream for user {}", order.getUserId());
                remove(order.getUserId(), emitter);
                emitter.completeWithError(e);
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByUser.remove(userId, emitters);
        }
    }
}
