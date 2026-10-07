package com.ai.assistant.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** 等待用户在页面显式确认的订单草稿。 */
@Data
public class OrderDraft {
  private String id;
  private Long merchantId;
  private Long version;
  private List<OrderItem> items;
  private BigDecimal totalAmount;
  private String remark;
  private LocalDateTime expiresAt;
  private Integer status;
  private Long confirmedOrderId;

  /** 创建草稿时的临时过敏约束快照。 */
  private String safetyAllergens;
}
