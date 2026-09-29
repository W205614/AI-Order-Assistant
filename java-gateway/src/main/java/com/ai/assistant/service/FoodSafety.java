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
                .map(FoodSafety::canonicalTag)
                .distinct()
                .toList();
    }

    private static String canonicalTag(String tag) {
        String lower = tag.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "花生", "落花生", "peanut", "peanuts" -> "花生";
            case "鸡蛋", "蛋类", "egg", "eggs" -> "鸡蛋";
            case "麸质", "小麦", "面粉", "gluten", "wheat" -> "麸质";
            default -> tag;
        };
    }

    static String normalizeTags(String value) {
        return String.join(",", splitTags(value));
    }

    static List<String> conflicts(String dishAllergens, List<String> userAllergens) {
        if (userAllergens == null || userAllergens.isEmpty()) return List.of();
        List<String> normalizedUsers = userAllergens.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(FoodSafety::canonicalTag).toList();
        return splitTags(dishAllergens).stream()
                .filter(dishTag -> normalizedUsers.stream().anyMatch(userTag -> userTag.equalsIgnoreCase(dishTag)))
                .toList();
    }
}
