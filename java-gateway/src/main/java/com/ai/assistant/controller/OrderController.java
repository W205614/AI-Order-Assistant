package com.ai.assistant.controller;

import com.ai.assistant.dto.PlaceOrderDTO;
import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderItem;
import com.ai.assistant.model.OrderDraft;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.OrderService;
import com.ai.assistant.service.OrderStatusEventBroker;
import com.ai.assistant.vo.Result;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户端订单接口：下单 / 列表 / 详情 / 取消 / 催单（需用户 JWT）
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;
    private final OrderStatusEventBroker orderStatusEventBroker;

    public OrderController(OrderService orderService, OrderStatusEventBroker orderStatusEventBroker) {
        this.orderService = orderService;
        this.orderStatusEventBroker = orderStatusEventBroker;
    }

    @PostMapping("/place")
    public Result<Order> place(@Valid @RequestBody PlaceOrderDTO dto,
                               @RequestHeader("Idempotency-Key") String idempotencyKey) {
        List<OrderItem> items = dto.getItems().stream().map(it -> {
            OrderItem item = new OrderItem();
            item.setDishId(it.getDishId());
            item.setDishName(it.getDishName());
            item.setQuantity(it.getQuantity());
            return item;
        }).collect(Collectors.toList());
        Order order = orderService.placeOrder(UserContext.getCurrentId(), items, dto.getRemark(), idempotencyKey);
        return Result.success(order);
    }

    @PostMapping("/drafts")
    public Result<OrderDraft> createDraft(@Valid @RequestBody PlaceOrderDTO dto) {
        List<OrderItem> items = dto.getItems().stream().map(it -> {
            OrderItem item = new OrderItem();
            item.setDishId(it.getDishId()); item.setDishName(it.getDishName()); item.setQuantity(it.getQuantity());
            return item;
        }).collect(Collectors.toList());
        return Result.success(orderService.createOrderDraft(UserContext.getCurrentId(), items, dto.getRemark()));
    }

    @PostMapping("/drafts/{draftId}/confirm")
    public Result<Order> confirmDraft(@PathVariable String draftId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return Result.success(orderService.confirmDraft(UserContext.getCurrentId(), draftId, idempotencyKey));
    }

    @PutMapping("/drafts/{draftId}")
    public Result<OrderDraft> updateDraft(@PathVariable String draftId, @Valid @RequestBody PlaceOrderDTO dto) {
        return Result.success(orderService.updateOrderDraft(UserContext.getCurrentId(), draftId, toOrderItems(dto), dto.getRemark()));
    }

    @DeleteMapping("/drafts/{draftId}")
    public Result<OrderDraft> cancelDraft(@PathVariable String draftId) {
        return Result.success(orderService.cancelOrderDraft(UserContext.getCurrentId(), draftId));
    }

    @GetMapping("/drafts/pending")
    public Result<List<OrderDraft>> pendingDrafts() {
        return Result.success(orderService.listPendingDrafts(UserContext.getCurrentId()));
    }

    /** 仅推送当前在线用户的订单状态变化；认证仍由 Authorization 请求头完成。 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return orderStatusEventBroker.subscribe(UserContext.getCurrentId());
    }

    /**
     * 我的订单（可按状态与日期范围筛选）
     */
    @GetMapping("/list")
    public Result<List<Order>> list(@RequestParam(required = false) Integer status,
                                    @RequestParam(required = false) String startDate,
                                    @RequestParam(required = false) String endDate) {
        return Result.success(orderService.listOrders(UserContext.getCurrentId(), status, startDate, endDate));
    }

    /** 订单详情：{seq} 为用户自己的订单序号（从 1 开始） */
    @GetMapping("/{seq}")
    public Result<Order> detail(@PathVariable Long seq) {
        return Result.success(orderService.getOwnOrder(UserContext.getCurrentId(), seq).orElseThrow(
                () -> new IllegalArgumentException("找不到该订单")));
    }

    @PostMapping("/{seq}/cancel")
    public Result<Order> cancel(@PathVariable Long seq) {
        return Result.success(orderService.cancelOrder(seq, UserContext.getCurrentId()));
    }

    @PostMapping("/{seq}/remind")
    public Result<Order> remind(@PathVariable Long seq) {
        return Result.success(orderService.remindOrder(seq, UserContext.getCurrentId()));
    }

    private List<OrderItem> toOrderItems(PlaceOrderDTO dto) {
        return dto.getItems().stream().map(it -> {
            OrderItem item = new OrderItem();
            item.setDishId(it.getDishId()); item.setDishName(it.getDishName()); item.setQuantity(it.getQuantity());
            return item;
        }).collect(Collectors.toList());
    }
}
