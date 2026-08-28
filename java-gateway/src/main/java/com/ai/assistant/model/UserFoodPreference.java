package com.ai.assistant.model;

import lombok.Data;

import java.math.BigDecimal;

/** 用户主动维护的饮食偏好；不会由模型静默写入。 */
@Data
public class UserFoodPreference {
    private Long userId;
    private String allergens;
    private String dislikes;
    private String dietaryGoal;
    private BigDecimal budget;
}
