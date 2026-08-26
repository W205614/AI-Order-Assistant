package com.ai.assistant.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单
 */
@Data
public class Order {

    /** 状态常量 */
    public static final int STATUS_ORDERED = 1;     // 已下单
    public static final int STATUS_PREPARING = 2;   // 制作中
    public static final int STATUS_DELIVERING = 3;  // 配送中
    public static final int STATUS_DONE = 4;        // 已送达
    public static final int STATUS_CANCELLED = 5;   // 已取消
    public static final int STATUS_TIMEOUT = 6;     // 已超时

    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 用户看到的订单序号（每个用户从 1 开始） */
    private Long userSeq;

    /** 用户昵称（管理端展示用，用户端为空） */
    private String userNickname;

    private List<OrderItem> items;

    private BigDecimal totalAmount;

    /** 1已下单 2制作中 3配送中 4已送达 5已取消 6已超时 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    /** 预计送达时间（模拟，下单后约 100~160 秒） */
    private LocalDateTime deliverAt;

    /** 实际送达时间 */
    private LocalDateTime deliverTime;

    /** 催单次数 */
    private Integer remindCount;

    /** 最近一次催单时间 */
    private LocalDateTime remindTime;
}
