package com.ai.assistant.service;

import com.ai.assistant.model.UserFoodPreference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class UserPreferenceService {
    private final JdbcTemplate jdbc;

    public UserPreferenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserFoodPreference get(Long userId) {
        List<UserFoodPreference> rows = jdbc.query(
                "SELECT user_id,allergens,dislikes,dietary_goal,budget FROM user_food_preference WHERE user_id=?",
                (rs, i) -> {
                    UserFoodPreference p = new UserFoodPreference();
                    p.setUserId(rs.getLong("user_id")); p.setAllergens(rs.getString("allergens"));
                    p.setDislikes(rs.getString("dislikes")); p.setDietaryGoal(rs.getString("dietary_goal"));
                    p.setBudget(rs.getBigDecimal("budget")); return p;
                }, userId);
        if (!rows.isEmpty()) return rows.get(0);
        UserFoodPreference empty = new UserFoodPreference(); empty.setUserId(userId); return empty;
    }

    public UserFoodPreference save(Long userId, UserFoodPreference preference) {
        validateText(preference.getAllergens(), "过敏原");
        validateText(preference.getDislikes(), "不喜欢食材");
        validateText(preference.getDietaryGoal(), "饮食目标");
        BigDecimal budget = preference.getBudget();
        if (budget != null && (budget.signum() <= 0 || budget.compareTo(new BigDecimal("9999")) > 0)) {
            throw new IllegalArgumentException("预算必须在 0-9999 元之间");
        }
        jdbc.update("INSERT INTO user_food_preference(user_id,allergens,dislikes,dietary_goal,budget) VALUES(?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE allergens=VALUES(allergens),dislikes=VALUES(dislikes),dietary_goal=VALUES(dietary_goal),budget=VALUES(budget)",
                userId, clean(preference.getAllergens()), clean(preference.getDislikes()),
                clean(preference.getDietaryGoal()), budget);
        return get(userId);
    }

    private void validateText(String value, String field) {
        if (value != null && value.length() > 255) throw new IllegalArgumentException(field + "不能超过255个字符");
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
