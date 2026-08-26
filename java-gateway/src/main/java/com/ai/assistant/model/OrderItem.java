package com.ai.assistant.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单条目
 */
@Data
public class OrderItem {

    private Long dishId;

    /** 下单时的菜名快照 */
    private String dishName;

    private Integer quantity;

    /** 单价快照 */
    private BigDecimal price;

    /** 小计 = 单价 * 数量 */
    private BigDecimal amount;
}
