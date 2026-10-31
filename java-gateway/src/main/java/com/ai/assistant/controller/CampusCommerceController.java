package com.ai.assistant.controller;

import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class CampusCommerceController {
  public record EnabledInput(boolean enabled) {}

  @PutMapping({"/admin/coupons/{id}/enabled", "/platform/coupons/{id}/enabled"})
  public Result<?> campaignEnabled(@PathVariable long id, @RequestBody EnabledInput x) {
    coupons.enabled(id, x.enabled());
    return Result.success(null);
  }

  private final CampusCatalogService catalog;
  private final CommerceCartService cart;
  private final CommerceQuoteService quotes;
  private final CommerceCheckoutService checkouts;
  private final CommerceCouponService coupons;
  private final CommerceAftersaleService refunds;
  private final CommerceFulfillmentService fulfillment;
  private final CommerceExperienceService experience;
  private final CommerceEventService events;

  public CampusCommerceController(
      CampusCatalogService catalog,
      CommerceCartService cart,
      CommerceQuoteService quotes,
      CommerceCheckoutService checkouts,
      CommerceCouponService coupons,
      CommerceAftersaleService refunds,
      CommerceFulfillmentService fulfillment,
      CommerceExperienceService experience,
      CommerceEventService events) {
    this.catalog = catalog;
    this.cart = cart;
    this.quotes = quotes;
    this.checkouts = checkouts;
    this.coupons = coupons;
    this.refunds = refunds;
    this.fulfillment = fulfillment;
    this.experience = experience;
    this.events = events;
  }

  @GetMapping("/campuses")
  public Result<?> campuses() {
    return Result.success(catalog.campuses());
  }

  @GetMapping({"/admin/campus-options", "/platform/campus-options"})
  public Result<?> campusOptions() {
    return Result.success(catalog.allCampuses());
  }

  @GetMapping("/campuses/{campus}/merchants")
  public Result<?> shops(@PathVariable long campus) {
    return Result.success(catalog.shops(campus));
  }

  @GetMapping("/campus-menu/{merchant}")
  public Result<?> menu(@PathVariable long merchant) {
    return Result.success(catalog.catalog(merchant));
  }

  @GetMapping("/cart")
  public Result<?> cart() {
    return Result.success(cart.list());
  }

  @PutMapping("/cart")
  public Result<?> cart(@RequestBody CartInput x) {
    return Result.success(cart.save(x));
  }

  @PutMapping("/cart/batch")
  public Result<?> cartBatch(@RequestBody CartBatchInput x) {
    return Result.success(cart.saveBatch(x));
  }

  @DeleteMapping("/cart/{id}")
  public Result<?> deleteCart(@PathVariable long id, @RequestParam long expectedVersion) {
    cart.delete(id, expectedVersion);
    return Result.success(null);
  }

  @GetMapping("/favorites")
  public Result<?> favorites() {
    return Result.success(cart.favorites());
  }

  @PostMapping("/favorites/{type}/{id}")
  public Result<?> favorite(@PathVariable String type, @PathVariable long id) {
    cart.favorite(type, id, true);
    return Result.success(null);
  }

  @DeleteMapping("/favorites/{type}/{id}")
  public Result<?> unfavorite(@PathVariable String type, @PathVariable long id) {
    cart.favorite(type, id, false);
    return Result.success(null);
  }

  @PostMapping("/cart/reorder/{order}")
  public Result<?> reorder(@PathVariable long order) {
    return Result.success(cart.reorder(order));
  }

  @GetMapping("/checkouts")
  public Result<?> checkouts(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize) {
    return Result.success(checkouts.list(page, pageSize).stream()
        .map(CheckoutResponses::summary).toList());
  }

  @PostMapping("/checkouts/quotes")
  public Result<?> quote(@RequestBody QuoteInput x) {
    return Result.success(quotes.quote(x));
  }

  @PostMapping("/checkouts/confirm")
  public Result<?> confirm(
      @RequestBody ConfirmInput x, @RequestHeader("Idempotency-Key") String key) {
    return Result.success(CheckoutResponses.detail(checkouts.confirm(x, key)));
  }

  @GetMapping("/checkouts/{id}")
  public Result<?> checkout(@PathVariable String id) {
    return Result.success(CheckoutResponses.detail(checkouts.detail(id)));
  }

  @PostMapping("/checkouts/{id}/pay")
  public Result<?> pay(@PathVariable String id) {
    return Result.success(checkouts.pay(id));
  }

  @PostMapping("/checkouts/{id}/close")
  public Result<?> close(@PathVariable String id) {
    return Result.success(checkouts.close(id));
  }

  @PostMapping("/checkouts/orders/{id}/cancel")
  public Result<?> cancel(@PathVariable long id) {
    return Result.success(checkouts.cancel(id));
  }

  @GetMapping("/checkouts/orders/{id}/code")
  public Result<?> code(@PathVariable long id) {
    return Result.success(checkouts.code(id));
  }

  @GetMapping("/coupons")
  public Result<?> campaigns() {
    return Result.success(coupons.campaigns());
  }

  @GetMapping("/coupons/mine")
  public Result<?> myCoupons() {
    return Result.success(coupons.mine());
  }

  @PostMapping("/coupons/{id}/claim")
  public Result<?> claim(@PathVariable long id) {
    return Result.success(coupons.claim(id));
  }

  @GetMapping("/aftersales")
  public Result<?> refunds(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Integer pageSize) {
    return Result.success(refunds.list(scope, page, pageSize));
  }

  @PostMapping("/aftersales")
  public Result<?> apply(@RequestBody RefundInput x) {
    return Result.success(refunds.apply(x));
  }

  @PostMapping("/aftersales/{id}/dispute")
  public Result<?> dispute(@PathVariable String id, @RequestBody Map<String, String> body) {
    refunds.dispute(id, body.get("reason"));
    return Result.success(null);
  }

  @GetMapping("/reservations/{merchant}")
  public Result<?> slots(@PathVariable long merchant) {
    return Result.success(fulfillment.slots(merchant));
  }

  @GetMapping("/feedback")
  public Result<?> feedbacks(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Integer pageSize) {
    return Result.success(experience.feedbacks(scope, page, pageSize));
  }

  @PostMapping("/feedback")
  public Result<?> feedback(@RequestBody FeedbackInput x) {
    return Result.success(experience.feedback(x));
  }

  @GetMapping(value = "/me/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) Long last) {
    return events.subscribe(last == null ? after : last, false);
  }

  @GetMapping("/me/events/replay")
  public Result<?> replay(@RequestParam(defaultValue = "0") long after) {
    UserContext.requireRole("CUSTOMER");
    return Result.success(events.replay(UserContext.getCurrentId(), false, after));
  }

  @PostMapping("/platform/campuses")
  public Result<?> campus(@RequestBody Map<String, String> x) {
    return Result.success(catalog.campus(x.get("name")));
  }

  @PutMapping("/platform/campuses/{id}")
  public Result<?> campusEnabled(@PathVariable long id, @RequestBody Map<String, Boolean> x) {
    catalog.campusEnabled(id, Boolean.TRUE.equals(x.get("enabled")));
    return Result.success(null);
  }

  @GetMapping("/admin/skus")
  public Result<?> skus() {
    return Result.success(catalog.manageProducts());
  }

  @PostMapping("/admin/skus")
  public Result<?> sku(@RequestBody SkuInput x) {
    return Result.success(catalog.sku(null, x));
  }

  @PutMapping("/admin/skus/{id}")
  public Result<?> sku(@PathVariable long id, @RequestBody SkuInput x) {
    return Result.success(catalog.sku(id, x));
  }

  public record StockInput(long expectedVersion, int delta, String requestKey) {}

  @PostMapping("/admin/skus/{id}/stock")
  public Result<?> stock(@PathVariable long id, @RequestBody StockInput x) {
    catalog.adjustStock(id, x.expectedVersion(), x.delta(), x.requestKey());
    return Result.success(null);
  }

  @GetMapping("/admin/bundles")
  public Result<?> bundles() {
    return Result.success(catalog.bundles());
  }

  @PostMapping("/admin/bundles")
  public Result<?> bundle(@RequestBody BundleInput x) {
    return Result.success(catalog.bundle(null, x));
  }

  @PutMapping("/admin/bundles/{id}")
  public Result<?> bundle(@PathVariable long id, @RequestBody BundleInput x) {
    return Result.success(catalog.bundle(id, x));
  }

  @GetMapping({"/admin/coupons", "/platform/coupons"})
  public Result<?> managedCoupons() {
    return Result.success(coupons.managed());
  }

  @PostMapping({"/admin/coupons", "/platform/coupons"})
  public Result<?> campaign(@RequestBody CouponInput x) {
    return Result.success(coupons.create(x));
  }

  @GetMapping("/admin/commerce-orders")
  public Result<?> merchantOrders(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int pageSize) {
    return Result.success(fulfillment.orders(scope, page, pageSize));
  }

  @PostMapping("/admin/commerce-orders/{id}/transition")
  public Result<?> transition(@PathVariable long id, @RequestBody Transition x) {
    fulfillment.transition(id, x);
    return Result.success(null);
  }

  @PostMapping({"/admin/commerce-orders/{id}/cancel", "/platform/commerce-orders/{id}/cancel"})
  public Result<?> merchantCancel(@PathVariable long id) {
    return Result.success(checkouts.cancel(id));
  }

  @PostMapping("/admin/reservations")
  public Result<?> slot(@RequestBody SlotInput x) {
    return Result.success(fulfillment.slot(x));
  }

  @GetMapping("/admin/reservation-slots")
  public Result<?> managedSlots() {
    UserContext.requireRole("OWNER", "STAFF");
    return Result.success(fulfillment.slots(UserContext.merchantId()));
  }

  @DeleteMapping("/admin/reservations/{id}")
  public Result<?> disableSlot(@PathVariable long id) {
    fulfillment.disableSlot(id);
    return Result.success(null);
  }

  @GetMapping({"/admin/aftersales", "/platform/aftersales"})
  public Result<?> managedRefunds(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Integer pageSize) {
    return Result.success(refunds.list(scope, page, pageSize));
  }

  @PostMapping({"/admin/aftersales/{id}/decision", "/platform/aftersales/{id}/decision"})
  public Result<?> decision(@PathVariable String id, @RequestBody DecisionInput x) {
    refunds.decide(id, x);
    return Result.success(null);
  }

  @GetMapping({"/admin/commerce-report", "/platform/commerce-report"})
  public Result<?> report() {
    return Result.success(experience.report());
  }

  @GetMapping({"/admin/product-report", "/platform/product-report"})
  public Result<?> productReport() {
    return Result.success(experience.productReport());
  }

  @GetMapping({"/admin/feedback", "/platform/feedback"})
  public Result<?> managedFeedback(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Integer pageSize) {
    return Result.success(experience.feedbacks(scope, page, pageSize));
  }

  @PostMapping({"/admin/feedback/{id}/reply", "/platform/feedback/{id}/reply"})
  public Result<?> reply(@PathVariable long id, @RequestBody Map<String, String> x) {
    experience.reply(id, x.get("reply"));
    return Result.success(null);
  }

  @GetMapping("/platform/delivery-tasks")
  public Result<?> tasks(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "200") int pageSize) {
    return Result.success(fulfillment.platformTasks(scope, page, pageSize));
  }

  @PostMapping("/platform/delivery-tasks/{id}/assign")
  public Result<?> assign(@PathVariable long id, @RequestBody AssignInput x) {
    fulfillment.assign(id, x);
    return Result.success(null);
  }

  @PostMapping("/platform/verification/{id}/unlock")
  public Result<?> unlock(@PathVariable long id) {
    fulfillment.resetVerification(id);
    return Result.success(null);
  }

  @GetMapping("/platform/commerce-exceptions")
  public Result<?> exceptions() {
    return Result.success(fulfillment.exceptions());
  }

  @GetMapping("/delivery-tasks")
  public Result<?> riderTasks(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "200") int pageSize) {
    return Result.success(fulfillment.tasks(scope, page, pageSize));
  }

  @PostMapping("/delivery-tasks/{id}/claim")
  public Result<?> riderClaim(@PathVariable long id, @RequestBody Map<String, Long> x) {
    fulfillment.claim(id, x.getOrDefault("expectedVersion", 0L));
    return Result.success(null);
  }

  @PostMapping("/delivery-tasks/{id}/transition")
  public Result<?> riderTransition(@PathVariable long id, @RequestBody Transition x) {
    fulfillment.riderTransition(id, x);
    return Result.success(null);
  }

  @GetMapping(value = "/rider/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter riderEvents(@RequestParam(defaultValue = "0") long after) {
    return events.subscribe(after, true);
  }
}
