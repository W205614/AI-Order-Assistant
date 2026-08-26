package com.ai.assistant.dto;

import jakarta.validation.Valid;
import lombok.Data;

import java.util.List;

/**
 * 下单请求。items 里 dishId 或 dishName 至少给一个。
 */
@Data
public class PlaceOrderDTO {

    @Valid
    private List<ItemDTO> items;

    /** 备注 */
    private String remark;

    @Data
    public static class ItemDTO {
        /** 菜品 id（与 dishName 二选一） */
        private Long dishId;

        /** 菜名（与 dishId 二选一，Agent 常用） */
        private String dishName;

        private Integer quantity;
    }
}
