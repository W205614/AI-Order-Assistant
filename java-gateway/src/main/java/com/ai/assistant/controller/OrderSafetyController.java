package com.ai.assistant.controller;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.OrderSafetyService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Explicit user control for temporary, per-order allergy restrictions. */
@RestController
@RequestMapping("/order/safety-context")
public class OrderSafetyController {
    public record SafetySelection(List<String> allergens) { }
    private final OrderSafetyService safety;

    public OrderSafetyController(OrderSafetyService safety) { this.safety = safety; }

    @GetMapping
    public Result<OrderSafetyService.SafetyContext> get() {
        return Result.success(safety.current(UserContext.getCurrentId()));
    }

    @PutMapping
    public Result<OrderSafetyService.SafetyContext> set(@RequestBody SafetySelection selection) {
        return Result.success(safety.set(UserContext.getCurrentId(), selection.allergens()));
    }

    @DeleteMapping
    public Result<Void> clear() {
        safety.clearByUser(UserContext.getCurrentId());
        return Result.success();
    }
}
