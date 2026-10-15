package com.ai.assistant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Shared wire contracts for campus commerce. IDs are never authorization. */
public final class CommerceTypes {
  private CommerceTypes() {}

  public record AddressInput(
      long campusId, String recipientName, String phone, String detail, Long expectedVersion) {}

  public record RuleInput(
      long campusId,
      boolean enabled,
      BigDecimal fee,
      BigDecimal minimumAmount,
      int etaMin,
      int etaMax,
      long expectedVersion) {}

  public record SkuInput(
      long dishId,
      String name,
      BigDecimal price,
      BigDecimal packagingFee,
      int stock,
      boolean enabled,
      Long expectedVersion) {}

  public record Component(long skuId, int quantity) {}

  public record BundleInput(
      String name,
      BigDecimal price,
      BigDecimal packagingFee,
      boolean enabled,
      List<Component> components,
      Long expectedVersion) {}

  public record CartInput(
      String productType, long productId, int quantity, String remark, Long expectedVersion) {}

  public record CouponInput(
      String name,
      BigDecimal thresholdAmount,
      BigDecimal discountAmount,
      int totalCount,
      LocalDateTime claimStart,
      LocalDateTime claimEnd,
      LocalDateTime validStart,
      LocalDateTime validEnd) {}

  public record QuoteInput(
      List<Long> merchantIds,
      Long addressId,
      long campusId,
      String fulfillmentMode,
      Map<Long, Long> shopCoupons,
      Long platformCoupon,
      Map<Long, Long> reservationSlots) {}

  public record ConfirmInput(String quoteId, long expectedVersion) {}

  public record SlotInput(
      LocalDateTime startsAt,
      LocalDateTime endsAt,
      LocalDateTime cutoffAt,
      int prepareMinutes,
      int capacity) {}

  public record RefundItem(long orderItemId, int quantity) {}

  public record RefundInput(
      long orderId, List<RefundItem> items, String reason, String requestKey) {}

  public record DecisionInput(boolean approve, String reason) {}

  public record Transition(String action, String code) {}

  public record AssignInput(Long riderId, long expectedVersion, String reason) {}

  public record RiderInput(String username, String password, List<Long> campusIds) {}

  public record FeedbackInput(long orderId, String message) {}

  public record Product(
      String type,
      long id,
      long merchantId,
      long dishId,
      String name,
      BigDecimal price,
      BigDecimal packagingFee,
      long version,
      List<Component> components,
      String safetySnapshot) {}

  public record Line(long cartId, long cartVersion, Product product, int quantity, String remark) {}

  public record ShopQuote(
      long merchantId,
      String merchantName,
      long merchantVersion,
      long ruleVersion,
      long campusId,
      String mode,
      Long slotId,
      Long couponId,
      List<Line> lines,
      BigDecimal goods,
      BigDecimal packaging,
      BigDecimal delivery,
      BigDecimal shopDiscount,
      BigDecimal platformDiscount,
      BigDecimal payable,
      int etaMin,
      int etaMax) {}

  public record Quote(
      String id,
      long version,
      LocalDateTime expiresAt,
      QuoteInput request,
      long addressVersion,
      List<ShopQuote> shops,
      Map<Long, String> unavailable,
      BigDecimal total) {}
}
