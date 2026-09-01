package com.ai.assistant.service;

import com.ai.assistant.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStatusNotificationTest {

    @Test
    void adminStatusChangePublishesEventForOrderOwner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OrderStatusEventBroker broker = mock(OrderStatusEventBroker.class);
        Order before = order(42L, 7L, 1L, Order.STATUS_ORDERED);
        Order after = order(42L, 7L, 1L, Order.STATUS_PREPARING);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L)))
                .thenReturn(List.of(before), List.of(after));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        OrderService service = new OrderService(jdbc, mock(CacheManager.class), broker);

        Order result = service.updateOrderStatus(42L, Order.STATUS_PREPARING);

        verify(broker, times(1)).publish(after);
        org.junit.jupiter.api.Assertions.assertSame(after, result);
    }

    @Test
    void userCancellationPublishesEventForOtherOpenPages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OrderStatusEventBroker broker = mock(OrderStatusEventBroker.class);
        Order before = order(42L, 7L, 1L, Order.STATUS_ORDERED);
        Order cancelled = order(42L, 7L, 1L, Order.STATUS_CANCELLED);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(1L)))
                .thenReturn(List.of(before), List.of(cancelled));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        OrderService service = new OrderService(jdbc, mock(CacheManager.class), broker);

        Order result = service.cancelOrder(1L, 7L);

        verify(broker, times(1)).publish(cancelled);
        org.junit.jupiter.api.Assertions.assertSame(cancelled, result);
    }

    private Order order(Long id, Long userId, Long userSeq, int status) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setUserSeq(userSeq);
        order.setStatus(status);
        return order;
    }
}
