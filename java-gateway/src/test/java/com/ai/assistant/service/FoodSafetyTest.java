package com.ai.assistant.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodSafetyTest {

    @Test
    void normalizesChineseAndEnglishSeparators() {
        assertEquals("花生,鸡蛋,麸质", FoodSafety.normalizeTags(" 花生，鸡蛋, 麸质，花生 "));
    }

    @Test
    void returnsOnlyActualAllergenConflicts() {
        assertEquals(List.of("花生"), FoodSafety.conflicts("花生,鸡蛋", List.of("花生", "牛奶")));
    }

    @Test
    void comparesLatinTagsWithoutCaseSensitivity() {
        assertEquals(List.of("Peanut"), FoodSafety.conflicts("Peanut,Gluten", List.of("peanut")));
    }

    @Test
    void handlesMissingTagsSafely() {
        assertEquals(List.of(), FoodSafety.conflicts(null, List.of("花生")));
        assertEquals(List.of(), FoodSafety.conflicts("花生", List.of()));
    }
}
