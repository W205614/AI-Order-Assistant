package com.ai.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 下单请求。items 里 dishId 或 dishName 至少给一个。
 */
@Data
public class PlaceOrderDTO {

    @Valid
    @NotEmpty(message = "订单不能为空")
    @Size(max = 20, message = "单次下单菜品不能超过 20 种")
    private List<ItemDTO> items;

    /** 备注 */
    private String remark;

    @Data
    public static class ItemDTO {
        /** 菜品 id（与 dishName 二选一） */
        private Long dishId;

        /** 菜名（与 dishId 二选一，Agent 常用） */
        private String dishName;

        @Min(value = 1, message = "菜品数量必须大于 0")
        @Max(value = 99, message = "单个菜品数量不能超过 99")
        private Integer quantity;
    }
}
