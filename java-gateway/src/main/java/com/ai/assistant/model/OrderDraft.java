package com.ai.assistant.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 等待用户在页面显式确认的订单草稿。 */
@Data
public class OrderDraft {
    private String id;
    private List<OrderItem> items;
    private BigDecimal totalAmount;
    private String remark;
    private LocalDateTime expiresAt;
    private Integer status;
    private Long confirmedOrderId;
}
