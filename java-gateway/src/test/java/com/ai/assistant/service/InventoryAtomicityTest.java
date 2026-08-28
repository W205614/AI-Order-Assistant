package com.ai.assistant.service;

import com.ai.assistant.model.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryAtomicityTest {
    private static final String SQL = "UPDATE dish SET stock = stock - ? WHERE id = ? AND status = 1 AND stock >= ?";

    @Test
    void aggregatesSameDishAndUsesConditionalAtomicUpdate() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        OrderService service = new OrderService(jdbc, mock(CacheManager.class));

        invokeDecrease(service, List.of(item(7L, 1), item(7L, 1)));

        verify(jdbc, times(1)).update(SQL, 2, 7L, 2);
    }

    @Test
    void refusesOrderWhenConditionalUpdateDoesNotAcquireStock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        OrderService service = new OrderService(jdbc, mock(CacheManager.class));

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> invokeDecrease(service, List.of(item(7L, 1))));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    private void invokeDecrease(OrderService service, List<OrderItem> items) throws Exception {
        Method method = OrderService.class.getDeclaredMethod("decreaseStockAtomically", List.class);
        method.setAccessible(true);
        method.invoke(service, items);
    }

    private OrderItem item(Long dishId, int quantity) {
        OrderItem item = new OrderItem();
        item.setDishId(dishId); item.setQuantity(quantity);
        return item;
    }
}
