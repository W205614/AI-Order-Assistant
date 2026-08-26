package com.ai.assistant.controller;

import com.ai.assistant.model.Dish;
import com.ai.assistant.service.OrderService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** 全部菜品（含 status：1起售 0停售，前端可标记售罄） */
    @GetMapping("/list")
    public Result<List<Dish>> list() {
        return Result.success(orderService.listDishes());
    }
}
