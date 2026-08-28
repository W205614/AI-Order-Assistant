package com.ai.assistant.controller;

import com.ai.assistant.model.UserFoodPreference;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.UserPreferenceService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/preferences")
public class UserPreferenceController {
    private final UserPreferenceService service;

    public UserPreferenceController(UserPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public Result<UserFoodPreference> get() {
        return Result.success(service.get(UserContext.getCurrentId()));
    }

    @PutMapping
    public Result<UserFoodPreference> save(@RequestBody UserFoodPreference preference) {
        return Result.success(service.save(UserContext.getCurrentId(), preference));
    }
}
