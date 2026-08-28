package com.ai.assistant.controller;

import com.ai.assistant.model.Dish;
import com.ai.assistant.service.OrderService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 菜单接口（需用户 JWT）
 */
@RestController
@RequestMapping("/dish")
public class DishController {

    private final OrderService orderService;

    public DishController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 菜单分页查询；availableOnly=true 时仅返回可售菜品。 */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean availableOnly,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "30") Integer size) {
        int safePage = page == null ? 1 : Math.max(1, page);
        int safeSize = size == null ? 30 : Math.min(50, Math.max(1, size));
        return Result.success(orderService.listDishes(category, keyword, availableOnly, safePage, safeSize));
    }
}
