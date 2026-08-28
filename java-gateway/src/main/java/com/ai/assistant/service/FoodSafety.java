package com.ai.assistant.service;

import java.util.Arrays;
import java.util.List;

/** 无数据库依赖的饮食安全规则，便于在下单入口复用并做确定性测试。 */
final class FoodSafety {
    private FoodSafety() {
    }

    static List<String> splitTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,，]"))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
    }

    static String normalizeTags(String value) {
        return String.join(",", splitTags(value));
    }

    static List<String> conflicts(String dishAllergens, List<String> userAllergens) {
        if (userAllergens == null || userAllergens.isEmpty()) return List.of();
        return splitTags(dishAllergens).stream()
                .filter(dishTag -> userAllergens.stream()
                        .anyMatch(userTag -> userTag != null && userTag.equalsIgnoreCase(dishTag)))
                .toList();
    }
}
