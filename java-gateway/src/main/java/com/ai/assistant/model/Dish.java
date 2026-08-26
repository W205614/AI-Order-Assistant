package com.ai.assistant.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 菜品（内置菜单项）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dish {

    private Long id;

    /** 菜名 */
    private String name;

    /** 单价 */
    private BigDecimal price;

    /** 口味/描述 */
    private String description;

    /** 分类：热菜 / 凉菜 / 主食 / 饮品 */
    private String category;

    /** 状态：1 起售 0 停售 */
    private Integer status;
}
