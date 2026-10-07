package com.ai.assistant.service;

import com.ai.assistant.dto.ConfirmDraftDTO;
import com.ai.assistant.model.*;
import com.ai.assistant.vo.OrderPage;
import java.util.*;
import org.springframework.stereotype.Service;

/** 对旧控制器保持小型门面，事务边界由各业务模块管理。 */
@Service
public class OrderService {
  private final MenuService menu;
  private final DraftService drafts;
  private final OrderTransactionService transactions;
  private final OrderQueryService queries;
  private final OrderSafetyService safety;

  public OrderService(
      MenuService menu,
      DraftService drafts,
      OrderTransactionService transactions,
      OrderQueryService queries,
      OrderSafetyService safety) {
    this.menu = menu;
    this.drafts = drafts;
    this.transactions = transactions;
    this.queries = queries;
    this.safety = safety;
  }

  public List<Dish> listDishes() {
    return menu.all();
  }

  public Map<String, Object> listDishes(
      String category, String keyword, Boolean available, int page, int size) {
    return menu.page(category, keyword, available, page, size, List.of(), false);
  }

  public Map<String, Object> listDishesForUser(
      Long user, String category, String keyword, Boolean available, int page, int size) {
    var context = safety.current(user);
    var allergens = drafts.allergens(user, null);
    return menu.page(
        category,
        keyword,
        available,
        page,
        size,
        allergens,
        context.needsClarification()
            || !allergens.stream().allMatch(OrderSafetyService.SUPPORTED_ALLERGENS::contains));
  }

  public boolean hasActiveAllergens(Long user, OrderSafetyService.SafetyContext context) {
    return !drafts.allergens(user, null).isEmpty();
  }

  public Optional<Dish> findDish(Long id) {
    return menu.find(id);
  }

  public Optional<Dish> findDish(String name) {
    return menu.find(name);
  }

  public Dish addDish(Dish dish) {
    return menu.add(dish);
  }

  public Dish updateDish(Long id, Dish dish) {
    return menu.update(id, dish);
  }

  public Dish updateDishStatus(Long id, Integer status) {
    return menu.status(id, status);
  }

  public void deleteDish(Long id) {
    menu.delete(id);
  }

  public OrderDraft createOrderDraft(Long user, List<OrderItem> items, String note) {
    return drafts.create(user, items, note);
  }

  public OrderDraft updateOrderDraft(
      Long user, String id, List<OrderItem> items, String note, Long expected) {
    return drafts.update(user, id, items, note, expected);
  }

  public OrderDraft cancelOrderDraft(Long user, String id) {
    return drafts.cancel(user, id);
  }

  public List<OrderDraft> listPendingDrafts(Long user) {
    return drafts.pending(user);
  }

  public Order confirmDraft(Long user, String id, String key, ConfirmDraftDTO request) {
    return transactions.confirm(user, id, key, request);
  }

  public Optional<Order> getOrder(Long id) {
    return queries.get(id);
  }

  public Optional<Order> getOwnOrder(Long user, Long seq) {
    return queries.own(user, seq);
  }

  public OrderPage listOrders(
      Long user, Integer status, String start, String end, Integer page, Integer size) {
    return queries.list(user, status, start, end, page, size);
  }

  public Order cancelOrder(Long seq, Long user) {
    return transactions.cancel(user, seq);
  }

  public Order remindOrder(Long seq, Long user) {
    return transactions.remind(user, seq);
  }

  public Order simulatePayment(Long seq, Long user) {
    return transactions.pay(user, seq);
  }

  public Order updateOrderStatus(Long id, Integer status) {
    return transactions.status(id, status);
  }

  public Map<String, Object> stats() {
    return queries.stats();
  }
}
