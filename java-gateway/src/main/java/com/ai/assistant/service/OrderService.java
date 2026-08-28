package com.ai.assistant.service;

import com.ai.assistant.model.Dish;
import com.ai.assistant.model.Order;
import com.ai.assistant.model.OrderItem;
import com.ai.assistant.model.OrderDraft;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.UUID;

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
    private static final int DRAFT_PENDING = 1;
    private static final int DRAFT_CONFIRMED = 2;

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

    /** 创建仅用于展示的确认单，真实订单只能由 confirmDraft 创建。 */
    @Transactional
    public OrderDraft createOrderDraft(Long userId, List<OrderItem> items, String remark) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("订单菜品数量不合法");
        }
        List<OrderItem> resolved = new ArrayList<>();
        for (OrderItem it : items) {
            if (it.getQuantity() == null || it.getQuantity() <= 0 || it.getQuantity() > MAX_QUANTITY) {
                throw new IllegalArgumentException("菜品数量必须在 1-" + MAX_QUANTITY + " 之间");
            }
            Dish dish = it.getDishId() == null ? null : findDish(it.getDishId()).orElse(null);
            if (dish == null && it.getDishName() != null && !it.getDishName().isBlank()) dish = findDish(it.getDishName().trim()).orElse(null);
            if (dish == null || (dish.getStatus() != null && dish.getStatus() == 0)) throw new IllegalArgumentException("菜品不存在或已下架");
            OrderItem item = new OrderItem();
            item.setDishId(dish.getId()); item.setDishName(dish.getName()); item.setQuantity(it.getQuantity());
            item.setPrice(dish.getPrice()); item.setAmount(dish.getPrice().multiply(BigDecimal.valueOf(it.getQuantity())));
            resolved.add(item);
        }
        BigDecimal total = resolved.stream().map(OrderItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String draftId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(), expiresAt = now.plusMinutes(5);
        jdbc.update("INSERT INTO order_draft (id,user_id,total_amount,remark,status,expires_at,create_time) VALUES (?,?,?,?,?,?,?)",
                draftId, userId, total, remark, DRAFT_PENDING, Timestamp.valueOf(expiresAt), Timestamp.valueOf(now));
        for (OrderItem item : resolved) jdbc.update("INSERT INTO order_draft_item (draft_id,dish_id,dish_name,quantity,price,amount) VALUES (?,?,?,?,?,?)",
                draftId, item.getDishId(), item.getDishName(), item.getQuantity(), item.getPrice(), item.getAmount());
        OrderDraft draft = new OrderDraft();
        draft.setId(draftId); draft.setItems(resolved); draft.setTotalAmount(total); draft.setRemark(remark); draft.setExpiresAt(expiresAt);
        return draft;
    }

    /** 显式确认草稿后才创建订单；行锁保证同一草稿不会创建两次。 */
    @Transactional
    public Order confirmDraft(Long userId, String draftId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        List<OrderDraft> drafts = jdbc.query("SELECT * FROM order_draft WHERE id=? AND user_id=? FOR UPDATE", (rs, i) -> {
            OrderDraft d = new OrderDraft(); d.setId(rs.getString("id")); d.setRemark(rs.getString("remark"));
            d.setStatus(rs.getInt("status")); d.setExpiresAt(toLocalDateTime(rs, "expires_at"));
            Object oid = rs.getObject("confirmed_order_id"); if (oid != null) d.setConfirmedOrderId(((Number) oid).longValue()); return d;
        }, draftId, userId);
        if (drafts.isEmpty()) throw new IllegalArgumentException("确认单不存在或无权访问");
        OrderDraft draft = drafts.get(0);
        if (draft.getStatus() == DRAFT_CONFIRMED) return getOrder(draft.getConfirmedOrderId()).orElseThrow();
        if (draft.getStatus() != DRAFT_PENDING || !draft.getExpiresAt().isAfter(LocalDateTime.now())) throw new IllegalArgumentException("确认单已过期，请重新下单");
        List<OrderItem> items = jdbc.query("SELECT dish_id,dish_name,quantity FROM order_draft_item WHERE draft_id=?", (rs, i) -> {
            OrderItem it = new OrderItem(); it.setDishId(rs.getLong("dish_id")); it.setDishName(rs.getString("dish_name")); it.setQuantity(rs.getInt("quantity")); return it;
        }, draftId);
        Order order = placeOrder(userId, items, draft.getRemark(), idempotencyKey);
        jdbc.update("UPDATE order_draft SET status=?, confirmed_order_id=? WHERE id=? AND status=?", DRAFT_CONFIRMED, order.getId(), draftId, DRAFT_PENDING);
        return order;
    }

    /** 供页面恢复未确认订单；草稿本身在数据库中保存，跨浏览器也可继续确认。 */
    public List<OrderDraft> listPendingDrafts(Long userId) {
        List<OrderDraft> drafts = jdbc.query("SELECT id,total_amount,remark,expires_at FROM order_draft WHERE user_id=? AND status=? AND expires_at > ? ORDER BY create_time DESC",
                (rs, i) -> { OrderDraft d = new OrderDraft(); d.setId(rs.getString("id")); d.setTotalAmount(rs.getBigDecimal("total_amount")); d.setRemark(rs.getString("remark")); d.setExpiresAt(toLocalDateTime(rs, "expires_at")); return d; },
                userId, DRAFT_PENDING, Timestamp.valueOf(LocalDateTime.now()));
        for (OrderDraft draft : drafts) {
            draft.setItems(jdbc.query("SELECT dish_id,dish_name,quantity,price,amount FROM order_draft_item WHERE draft_id=?", (rs, i) -> {
                OrderItem item = new OrderItem(); item.setDishId(rs.getLong("dish_id")); item.setDishName(rs.getString("dish_name")); item.setQuantity(rs.getInt("quantity")); item.setPrice(rs.getBigDecimal("price")); item.setAmount(rs.getBigDecimal("amount")); return item;
            }, draft.getId()));
        }
        return drafts;
    }

    // ---------- 下单 ----------

    /**
     * 下单。初始状态：已下单(1)，归属 userId。
     */
    @Transactional
    public Order placeOrder(Long userId, List<OrderItem> items, String remark, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Optional<Order> existing = getOwnOrderByIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
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
        Long userSeq = reserveUserSequence(userId);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO orders (user_id, user_seq, total_amount, status, remark, create_time, deliver_at, remind_count, idempotency_key) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setLong(2, userSeq);
                ps.setBigDecimal(3, total);
                ps.setInt(4, Order.STATUS_ORDERED);
                ps.setString(5, remark);
                ps.setTimestamp(6, Timestamp.valueOf(now));
                ps.setTimestamp(7, Timestamp.valueOf(deliverAt));
                ps.setString(8, idempotencyKey);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            // 同一幂等键的并发重试：唯一约束保证只会写入一张订单，返回先成功的结果。
            // 当前事务仍持有该用户的序号行锁；归还本次未实际创建订单的序号，避免重试造成订单号跳号。
            releaseUserSequence(userId, userSeq);
            return getOwnOrderByIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> e);
        }

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

    /**
     * 以数据库行锁原子分配用户订单号。LAST_INSERT_ID 是连接级别的值，
     * 在 Spring 事务绑定的同一连接内读取，不会被其他请求污染。
     */
    private Long reserveUserSequence(Long userId) {
        jdbc.update("INSERT IGNORE INTO user_order_sequence (user_id, next_seq) VALUES (?, 1)", userId);
        jdbc.update("UPDATE user_order_sequence SET next_seq = LAST_INSERT_ID(next_seq + 1) WHERE user_id = ?", userId);
        Long nextSeq = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (nextSeq == null || nextSeq <= 1) {
            throw new IllegalStateException("订单序号分配失败");
        }
        return nextSeq - 1;
    }

    private void releaseUserSequence(Long userId, Long reservedSeq) {
        int changed = jdbc.update("UPDATE user_order_sequence SET next_seq = next_seq - 1 WHERE user_id = ? AND next_seq = ?",
                userId, reservedSeq + 1);
        if (changed != 1) {
            throw new IllegalStateException("订单序号归还失败");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw new IllegalArgumentException("Idempotency-Key 必须是 8-100 位字母、数字或 . _ : -");
        }
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

    private Optional<Order> getOwnOrderByIdempotencyKey(Long userId, String idempotencyKey) {
        List<Order> list = jdbc.query(
                "SELECT o.*, u.nickname AS user_nickname FROM orders o "
                        + "LEFT JOIN user u ON o.user_id = u.id WHERE o.user_id = ? AND o.idempotency_key = ?",
                this::mapOrder, userId, idempotencyKey);
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
        int changed = jdbc.update("UPDATE orders SET status = ? WHERE id = ? AND status = ?",
                Order.STATUS_CANCELLED, order.getId(), status);
        if (changed == 0) {
            throw new IllegalArgumentException("订单状态已变更，请刷新后重试");
        }
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
        int changed = jdbc.update(
                "UPDATE orders SET status = ?, deliver_time = CASE WHEN ? = ? THEN ? ELSE deliver_time END "
                        + "WHERE id = ? AND status = ?",
                newStatus, newStatus, Order.STATUS_DONE, Timestamp.valueOf(LocalDateTime.now()), id, order.getStatus());
        if (changed == 0) {
            throw new IllegalArgumentException("订单状态已被其他操作更新，请刷新后重试");
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
