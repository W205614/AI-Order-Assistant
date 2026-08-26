package com.ai.assistant.service;

import com.ai.assistant.model.Dish;
import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 点餐后端核心逻辑（MySQL 持久化）。
 * 订单按用户隔离；状态严格单向流转：1已下单 2制作中 3配送中 4已送达 5已取消 6已超时。
 */
@Service
@Slf4j
public class OrderService {

    /** 每单最多条目数、单个菜品最大数量 */
    private static final int MAX_ITEMS = 20;
    private static final int MAX_QUANTITY = 99;

    private static final Map<Integer, String> STATUS_NAMES = Map.of(
            Order.STATUS_ORDERED, "已下单", Order.STATUS_PREPARING, "制作中",
            Order.STATUS_DELIVERING, "配送中", Order.STATUS_DONE, "已送达",
            Order.STATUS_CANCELLED, "已取消", Order.STATUS_TIMEOUT, "已超时");

    /** 管理端允许的状态流转（严格单向，禁止跳步/回退） */
    private static final Map<Integer, List<Integer>> ALLOWED_TRANSITIONS = Map.of(
            Order.STATUS_ORDERED, List.of(Order.STATUS_PREPARING, Order.STATUS_CANCELLED, Order.STATUS_TIMEOUT),
            Order.STATUS_PREPARING, List.of(Order.STATUS_DELIVERING, Order.STATUS_CANCELLED),
            Order.STATUS_DELIVERING, List.of(Order.STATUS_DONE, Order.STATUS_TIMEOUT));

    private final JdbcTemplate jdbc;

    public OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 菜品 ----------

    public List<Dish> listDishes() {
        return jdbc.query(
                "SELECT id, name, price, description, category, status FROM dish ORDER BY id",
                (rs, i) -> mapDish(rs));
    }

    public Optional<Dish> findDish(Long id) {
        return jdbc.query(
                "SELECT id, name, price, description, category, status FROM dish WHERE id = ?",
                (rs, i) -> mapDish(rs), id).stream().findFirst();
    }

    public Optional<Dish> findDish(String name) {
        return jdbc.query(
                "SELECT id, name, price, description, category, status FROM dish WHERE name LIKE ? LIMIT 1",
                (rs, i) -> mapDish(rs), "%" + name + "%").stream().findFirst();
    }

    public Dish addDish(Dish dish) {
        validateDish(dish);
        if (findDish(dish.getName()).isPresent()) {
            throw new IllegalArgumentException("菜单里已有同名菜品「" + dish.getName() + "」");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dish (name, price, description, category, status) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dish.getName());
            ps.setBigDecimal(2, dish.getPrice());
            ps.setString(3, dish.getDescription());
            ps.setString(4, dish.getCategory());
            ps.setInt(5, dish.getStatus() == null ? 1 : dish.getStatus());
            return ps;
        }, keyHolder);
        dish.setId(keyHolder.getKey().longValue());
        if (dish.getStatus() == null) dish.setStatus(1);
        return dish;
    }

    public Dish updateDish(Long id, Dish dish) {
        requireDish(id);
        validateDish(dish);
        jdbc.update("UPDATE dish SET name = ?, price = ?, description = ?, category = ?, status = ? WHERE id = ?",
                dish.getName(), dish.getPrice(), dish.getDescription(), dish.getCategory(),
                dish.getStatus() == null ? 1 : dish.getStatus(), id);
        dish.setId(id);
        return dish;
    }

    /** 上架/下架：status 1起售 0停售 */
    public Dish updateDishStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("状态只能为 0（停售）或 1（起售）");
        }
        requireDish(id);
        jdbc.update("UPDATE dish SET status = ? WHERE id = ?", status, id);
        return findDish(id).orElseThrow();
    }

    public void deleteDish(Long id) {
        requireDish(id);
        jdbc.update("DELETE FROM dish WHERE id = ?", id);
    }

    private void validateDish(Dish dish) {
        if (dish.getName() == null || dish.getName().isBlank()) {
            throw new IllegalArgumentException("菜品名称不能为空");
        }
        if (dish.getPrice() == null || dish.getPrice().signum() <= 0) {
            throw new IllegalArgumentException("菜品价格必须大于 0");
        }
        if (dish.getCategory() == null || dish.getCategory().isBlank()) {
            throw new IllegalArgumentException("菜品分类不能为空");
        }
    }

    private void requireDish(Long id) {
        if (findDish(id).isEmpty()) {
            throw new IllegalArgumentException("找不到菜品 #" + id);
        }
    }

    private Dish mapDish(ResultSet rs) throws SQLException {
        Dish d = new Dish();
        d.setId(rs.getLong("id"));
        d.setName(rs.getString("name"));
        d.setPrice(rs.getBigDecimal("price"));
        d.setDescription(rs.getString("description"));
        d.setCategory(rs.getString("category"));
        d.setStatus(rs.getInt("status"));
        return d;
    }

    // ---------- 下单 ----------

    /**
     * 下单。初始状态：已下单(1)，归属 userId。
     */
    @Transactional
    public Order placeOrder(Long userId, List<OrderItem> items, String remark) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("订单不能为空，请至少点一个菜品");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("单次下单菜品不能超过 " + MAX_ITEMS + " 种");
        }
        List<OrderItem> resolved = new ArrayList<>();
        for (OrderItem it : items) {
            if (it.getQuantity() == null || it.getQuantity() <= 0) {
                throw new IllegalArgumentException("菜品数量必须大于 0");
            }
            if (it.getQuantity() > MAX_QUANTITY) {
                throw new IllegalArgumentException("单个菜品数量不能超过 " + MAX_QUANTITY);
            }
            Dish dish = null;
            if (it.getDishId() != null) {
                dish = findDish(it.getDishId()).orElse(null);
            }
            if (dish == null && it.getDishName() != null && !it.getDishName().isBlank()) {
                dish = findDish(it.getDishName().trim()).orElse(null);
            }
            if (dish == null) {
                throw new IllegalArgumentException("菜单里没有「" + it.getDishName() + "」，请先展示菜单让用户选择");
            }
            if (dish.getStatus() != null && dish.getStatus() == 0) {
                throw new IllegalArgumentException("「" + dish.getName() + "」已售罄/下架，请选择其他菜品");
            }
            OrderItem item = new OrderItem();
            item.setDishId(dish.getId());
            item.setDishName(dish.getName());
            item.setQuantity(it.getQuantity());
            item.setPrice(dish.getPrice());
            item.setAmount(dish.getPrice().multiply(BigDecimal.valueOf(it.getQuantity())));
            resolved.add(item);
        }

        BigDecimal total = resolved.stream().map(OrderItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deliverAt = now.plusMinutes(30);
        // 每用户订单序号从 1 开始
        Long userSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(user_seq), 0) + 1 FROM orders WHERE user_id = ?", Long.class, userId);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO orders (user_id, user_seq, total_amount, status, remark, create_time, deliver_at, remind_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, userSeq);
            ps.setBigDecimal(3, total);
            ps.setInt(4, Order.STATUS_ORDERED);
            ps.setString(5, remark);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, Timestamp.valueOf(deliverAt));
            return ps;
        }, keyHolder);

        Long orderId = keyHolder.getKey().longValue();
        for (OrderItem item : resolved) {
            jdbc.update(
                    "INSERT INTO order_item (order_id, dish_id, dish_name, quantity, price, amount) VALUES (?, ?, ?, ?, ?, ?)",
                    orderId, item.getDishId(), item.getDishName(), item.getQuantity(),
                    item.getPrice(), item.getAmount());
        }

        log.info("Order #{} (seq {}) placed by user {}, {} items, total={}, ETA {}",
                orderId, userSeq, userId, resolved.size(), total, deliverAt);
        return getOwnOrder(userId, userSeq).orElseThrow();
    }

    // ---------- 订单查询（按用户隔离） ----------

    /** 查询某用户的订单；userId 传 null 表示管理员查全部（含用户昵称） */
    public List<Order> listOrders(Long userId, Integer status, String startDate, String endDate) {
        String sql = "SELECT o.*, u.nickname AS user_nickname FROM orders o "
                + "LEFT JOIN user u ON o.user_id = u.id WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (userId != null) {
            sql += " AND o.user_id = ?";
            args.add(userId);
        }
        if (status != null) {
            sql += " AND o.status = ?";
            args.add(status);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql += " AND o.create_time >= ?";
            args.add(Timestamp.valueOf(parseDate(startDate).atStartOfDay()));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql += " AND o.create_time < ?";
            args.add(Timestamp.valueOf(parseDate(endDate).plusDays(1).atStartOfDay()));
        }
        sql += " ORDER BY o.id DESC";
        List<Order> orders = jdbc.query(sql, this::mapOrder, args.toArray());
        orders.forEach(this::loadItems);
        return orders;
    }

    /** 管理端按全局 id 取订单（含用户昵称） */
    public Optional<Order> getOrder(Long id) {
        List<Order> list = jdbc.query(
                "SELECT o.*, u.nickname AS user_nickname FROM orders o "
                        + "LEFT JOIN user u ON o.user_id = u.id WHERE o.id = ?",
                this::mapOrder, id);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Order order = list.get(0);
        loadItems(order);
        return Optional.of(order);
    }

    /** 用户端按「用户自己的订单序号」取订单（越权访问别人的序号同样查不到） */
    public Optional<Order> getOwnOrder(Long userId, Long seq) {
        List<Order> list = jdbc.query(
                "SELECT o.*, u.nickname AS user_nickname FROM orders o "
                        + "LEFT JOIN user u ON o.user_id = u.id WHERE o.user_id = ? AND o.user_seq = ?",
                this::mapOrder, userId, seq);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Order order = list.get(0);
        loadItems(order);
        return Optional.of(order);
    }

    private Order requireOwnOrder(Long userId, Long seq) {
        return getOwnOrder(userId, seq).orElseThrow(() -> new IllegalArgumentException("找不到该订单"));
    }

    // ---------- 用户操作：取消 / 催单 ----------

    public Order cancelOrder(Long seq, Long userId) {
        Order order = requireOwnOrder(userId, seq);
        int status = order.getStatus();
        if (isTerminal(status)) {
            throw new IllegalArgumentException("订单已结束（送达/取消/超时），无法取消");
        }
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", Order.STATUS_CANCELLED, order.getId());
        log.info("Order seq #{} cancelled by user {}", seq, userId);
        return getOwnOrder(userId, seq).orElseThrow();
    }

    public Order remindOrder(Long seq, Long userId) {
        Order order = requireOwnOrder(userId, seq);
        int status = order.getStatus();
        if (isTerminal(status)) {
            throw new IllegalArgumentException("订单已结束（送达/取消/超时），无需催单");
        }
        jdbc.update("UPDATE orders SET remind_count = remind_count + 1, remind_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), order.getId());
        log.info("Order seq #{} reminded by user {} ({} times)", seq, userId, order.getRemindCount() + 1);
        return getOwnOrder(userId, seq).orElseThrow();
    }

    // ---------- 管理端操作 ----------

    /** 严格状态流转 */
    public Order updateOrderStatus(Long id, Integer newStatus) {
        if (newStatus == null || !STATUS_NAMES.containsKey(newStatus)) {
            throw new IllegalArgumentException("非法状态值：" + newStatus);
        }
        Order order = requireOrderForAdmin(id);
        List<Integer> allowed = ALLOWED_TRANSITIONS.get(order.getStatus());
        if (allowed == null || !allowed.contains(newStatus)) {
            throw new IllegalArgumentException("非法状态流转：" + STATUS_NAMES.get(order.getStatus())
                    + " → " + STATUS_NAMES.get(newStatus));
        }
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", newStatus, id);
        if (newStatus == Order.STATUS_DONE) {
            jdbc.update("UPDATE orders SET deliver_time = ? WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now()), id);
        }
        log.info("Order #{} status -> {} (admin)", id, newStatus);
        return getOrder(id).orElseThrow();
    }

    private Order requireOrderForAdmin(Long id) {
        return getOrder(id).orElseThrow(() -> new IllegalArgumentException("找不到订单 #" + id));
    }

    private boolean isTerminal(int status) {
        return status == Order.STATUS_DONE || status == Order.STATUS_CANCELLED || status == Order.STATUS_TIMEOUT;
    }

    private java.time.LocalDate parseDate(String s) {
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式错误，应为 yyyy-MM-dd：" + s);
        }
    }

    // ---------- 统计（管理端 /stats） ----------

    public Map<String, Object> stats() {
        Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        Map<Integer, Integer> byStatus = new LinkedHashMap<>();
        for (int s = 1; s <= 6; s++) {
            byStatus.put(s, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = ?", Integer.class, s));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("users", users == null ? 0 : users);
        data.put("orders", orders == null ? 0 : orders);
        data.put("ordersByStatus", byStatus);
        return data;
    }

    // ---------- RowMapper ----------

    private Order mapOrder(ResultSet rs, int rowNum) throws SQLException {
        Order order = new Order();
        order.setId(rs.getLong("id"));
        order.setUserId(rs.getLong("user_id"));
        order.setUserSeq(rs.getLong("user_seq"));
        order.setUserNickname(rs.getString("user_nickname"));
        order.setTotalAmount(rs.getBigDecimal("total_amount"));
        order.setStatus(rs.getInt("status"));
        order.setRemark(rs.getString("remark"));
        order.setCreateTime(toLocalDateTime(rs, "create_time"));
        order.setDeliverAt(toLocalDateTime(rs, "deliver_at"));
        order.setDeliverTime(toLocalDateTime(rs, "deliver_time"));
        order.setRemindCount(rs.getInt("remind_count"));
        order.setRemindTime(toLocalDateTime(rs, "remind_time"));
        return order;
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private void loadItems(Order order) {
        List<OrderItem> items = jdbc.query(
                "SELECT dish_id, dish_name, quantity, price, amount FROM order_item WHERE order_id = ?",
                (rs, i) -> {
                    OrderItem item = new OrderItem();
                    item.setDishId(rs.getLong("dish_id"));
                    item.setDishName(rs.getString("dish_name"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setPrice(rs.getBigDecimal("price"));
                    item.setAmount(rs.getBigDecimal("amount"));
                    return item;
                }, order.getId());
        order.setItems(items);
    }
}
