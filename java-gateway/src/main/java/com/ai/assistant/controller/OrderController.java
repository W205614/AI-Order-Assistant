package com.ai.assistant.controller;

import com.ai.assistant.dto.ConfirmDraftDTO;
import com.ai.assistant.dto.PlaceOrderDTO;
import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderDraft;
import com.ai.assistant.model.OrderItem;
import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.DraftService;
import com.ai.assistant.service.OrderTransactionService;
import com.ai.assistant.service.OrderQueryService;
import com.ai.assistant.service.OrderStatusEventBroker;
import com.ai.assistant.vo.OrderPage;
import com.ai.assistant.vo.Result;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 用户端订单接口：下单 / 列表 / 详情 / 取消 / 催单（需用户 JWT） */
@RestController
@RequestMapping("/order")
public class OrderController {

  private final DraftService drafts;
  private final OrderTransactionService transactions;
  private final OrderQueryService queries;
  private final OrderStatusEventBroker orderStatusEventBroker;

  public OrderController(DraftService drafts, OrderTransactionService transactions,
      OrderQueryService queries, OrderStatusEventBroker orderStatusEventBroker) {
    this.drafts = drafts;
    this.transactions = transactions;
    this.queries = queries;
    this.orderStatusEventBroker = orderStatusEventBroker;
  }

  @PostMapping("/drafts")
  public Result<OrderDraft> createDraft(@Valid @RequestBody PlaceOrderDTO dto) {
    return Result.success(
        drafts.create(UserContext.getCurrentId(), toOrderItems(dto), dto.getRemark()));
  }

  @PostMapping("/drafts/{draftId}/confirm")
  public Result<Order> confirmDraft(
      @PathVariable String draftId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ConfirmDraftDTO input) {
    return Result.success(
        transactions.confirm(UserContext.getCurrentId(), draftId, idempotencyKey, input));
  }

  @PutMapping("/drafts/{draftId}")
  public Result<OrderDraft> updateDraft(
      @PathVariable String draftId, @Valid @RequestBody PlaceOrderDTO dto) {
    return Result.success(
        drafts.update(
            UserContext.getCurrentId(),
            draftId,
            toOrderItems(dto),
            dto.getRemark(),
            dto.getExpectedVersion()));
  }

  @DeleteMapping("/drafts/{draftId}")
  public Result<OrderDraft> cancelDraft(@PathVariable String draftId) {
    return Result.success(drafts.cancel(UserContext.getCurrentId(), draftId));
  }

  @GetMapping("/drafts/pending")
  public Result<List<OrderDraft>> pendingDrafts() {
    return Result.success(drafts.pending(UserContext.getCurrentId()));
  }

  /** 仅推送当前在线用户的订单状态变化；认证仍由 Authorization 请求头完成。 */
  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) Long last) {
    return orderStatusEventBroker.subscribe(
        UserContext.getCurrentId(), last == null ? after : last);
  }

  @GetMapping("/events/replay")
  public Result<?> replay(@RequestParam(defaultValue = "0") long after) {
    return Result.success(orderStatusEventBroker.replay(UserContext.getCurrentId(), after));
  }

  @PostMapping("/{seq}/pay")
  public Result<Order> pay(@PathVariable Long seq) {
    return Result.success(transactions.pay(UserContext.getCurrentId(), seq));
  }

  /** 我的订单（可按状态与日期范围筛选） */
  @GetMapping("/list")
  public Result<OrderPage> list(
      @RequestParam(required = false) Integer status,
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(defaultValue = "1") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return Result.success(
        queries.list(
            UserContext.getCurrentId(), status, startDate, endDate, page, size));
  }

  /** 订单详情：{seq} 为用户自己的订单序号（从 1 开始） */
  @GetMapping("/{seq}")
  public Result<Order> detail(@PathVariable Long seq) {
    return Result.success(
        queries
            .own(UserContext.getCurrentId(), seq)
            .orElseThrow(() -> new IllegalArgumentException("找不到该订单")));
  }

  @PostMapping("/{seq}/cancel")
  public Result<Order> cancel(@PathVariable Long seq) {
    return Result.success(transactions.cancel(UserContext.getCurrentId(), seq));
  }

  @PostMapping("/{seq}/remind")
  public Result<Order> remind(@PathVariable Long seq) {
    return Result.success(transactions.remind(UserContext.getCurrentId(), seq));
  }

  private List<OrderItem> toOrderItems(PlaceOrderDTO dto) {
    return dto.getItems().stream()
        .map(
            it -> {
              OrderItem item = new OrderItem();
              item.setDishId(it.getDishId());
              item.setDishName(it.getDishName());
              item.setQuantity(it.getQuantity());
              return item;
            })
        .collect(Collectors.toList());
  }
}
