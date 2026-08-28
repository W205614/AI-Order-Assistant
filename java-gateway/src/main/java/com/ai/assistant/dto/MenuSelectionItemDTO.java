package com.ai.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户在菜单面板中明确勾选的一项菜品。 */
@Data
public class MenuSelectionItemDTO {

    @NotBlank(message = "菜品名称不能为空")
    @Size(max = 100, message = "菜品名称过长")
    private String dishName;

    @NotNull(message = "菜品数量不能为空")
    @Min(value = 1, message = "菜品数量至少为 1")
    @Max(value = 99, message = "菜品数量不能超过 99")
    private Integer quantity;
}
