package com.ai.assistant.controller;

import com.ai.assistant.model.Dish;
import com.ai.assistant.model.Order;
import com.ai.assistant.service.OrderService;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口（需管理员 JWT）：菜品增删改/上下架 + 订单列表/详情/状态操作 + 统计
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final OrderService orderService;

    public AdminController(OrderService orderService) {
        this.orderService = orderService;
    }

    // ---------- 菜品管理 ----------

    @GetMapping("/dishes")
    public Result<List<Dish>> listDishes() {
        return Result.success(orderService.listDishes());
    }

    @PostMapping("/dishes")
    public Result<Dish> addDish(@RequestBody Dish dish) {
        return Result.success(orderService.addDish(dish));
    }

    @PutMapping("/dishes/{id}")
    public Result<Dish> updateDish(@PathVariable Long id, @RequestBody Dish dish) {
        return Result.success(orderService.updateDish(id, dish));
    }

    /** 上/下架：status 1起售 0停售 */
    @PutMapping("/dishes/{id}/status")
    public Result<Dish> updateDishStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.success(orderService.updateDishStatus(id, status));
    }

    @DeleteMapping("/dishes/{id}")
    public Result<Void> deleteDish(@PathVariable Long id) {
        orderService.deleteDish(id);
        return Result.success();
    }

    // ---------- 统计 ----------

    /** 平台统计：用户数、订单量、各状态订单数 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(orderService.stats());
    }

    // ---------- 订单管理 ----------

    @GetMapping("/orders")
    public Result<List<Order>> listOrders(@RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate) {
        return Result.success(orderService.listOrders(null, status, startDate, endDate));
    }

    @GetMapping("/orders/{id}")
    public Result<Order> orderDetail(@PathVariable Long id) {
        return Result.success(orderService.getOrder(id).orElseThrow(
                () -> new IllegalArgumentException("找不到订单 #" + id)));
    }

    /**
     * 更新订单状态（严格单向流转）。
     * status：1已下单 2制作中 3配送中 4已送达 5已取消 6已超时
     */
    @PostMapping("/orders/{id}/status")
    public Result<Order> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return Result.success(orderService.updateOrderStatus(id, status));
    }
}
