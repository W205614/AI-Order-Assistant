package com.ai.assistant.controller;

import com.ai.assistant.model.Dish;
import com.ai.assistant.model.Order;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.InventoryService;
import com.ai.assistant.service.OrderService;
import com.ai.assistant.service.OrderStatusEventBroker;
import com.ai.assistant.vo.OrderPage;
import com.ai.assistant.vo.Result;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 管理端接口（需管理员 JWT）：菜品增删改/上下架 + 订单列表/详情/状态操作 + 统计 */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private final OrderService orderService;
  private final InventoryService inventory;
  private final OrderStatusEventBroker events;

  public AdminController(
      OrderService orderService, InventoryService inventory, OrderStatusEventBroker events) {
    this.orderService = orderService;
    this.inventory = inventory;
    this.events = events;
  }

  // ---------- 菜品管理 ----------

  @GetMapping("/dishes")
  public Result<List<Dish>> listDishes() {
    UserContext.requireRole("OWNER");
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
    UserContext.requireRole("OWNER");
    return Result.success(orderService.stats());
  }

  // ---------- 订单管理 ----------

  @GetMapping("/orders")
  public Result<OrderPage> listOrders(
      @RequestParam(required = false) Integer status,
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(defaultValue = "1") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return Result.success(orderService.listOrders(null, status, startDate, endDate, page, size));
  }

  @GetMapping("/orders/{id}")
  public Result<Order> orderDetail(@PathVariable Long id) {
    return Result.success(
        orderService.getOrder(id).orElseThrow(() -> new IllegalArgumentException("找不到订单 #" + id)));
  }

  /** 更新订单状态（严格单向流转）。 status：1已下单 2制作中 3配送中 4已送达 5已取消 6已超时 */
  @PostMapping("/orders/{id}/status")
  public Result<Order> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
    return Result.success(orderService.updateOrderStatus(id, status));
  }

  @PostMapping("/dishes/{id}/inventory")
  public Result<Dish> inventory(
      @PathVariable Long id, @RequestBody InventoryService.Adjustment input) {
    return Result.success(inventory.adjust(id, input));
  }

  @GetMapping("/inventory")
  public Result<?> inventoryLedger(
      @RequestParam(defaultValue = "9223372036854775807") long before) {
    return Result.success(inventory.ledger(before));
  }

  @GetMapping(value = "/events", produces = "text/event-stream")
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) Long last) {
    UserContext.requireRole("OWNER", "STAFF");
    return events.subscribe(null, last == null ? after : last);
  }

  @GetMapping("/events/replay")
  public Result<?> replay(@RequestParam(defaultValue = "0") long after) {
    UserContext.requireRole("OWNER", "STAFF");
    return Result.success(events.replay(null, after));
  }
}
