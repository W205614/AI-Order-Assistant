package com.ai.assistant.vo;

import com.ai.assistant.model.Order;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Bounded order-list response shared by user and administration endpoints. */
@Data
public class OrderPage {
    private List<Order> items;
    private long total;
    private int page;
    private int size;

    /** Counts use the same owner/date scope but intentionally ignore the selected status filter. */
    private Map<Integer, Integer> statusCounts;
}
