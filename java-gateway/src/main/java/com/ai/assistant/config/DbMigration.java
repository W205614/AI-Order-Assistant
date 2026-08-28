package com.ai.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时执行：老库补列迁移 + 初始化种子数据（菜单 / 默认管理员 / 演示用户）。
 * 放在 SQL init 之后运行，兼容已有数据库（不丢数据）。
 */
@Component
@Slf4j
public class DbMigration implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DbMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        migrate();
        seed();
    }

    private void migrate() {
        if (!hasColumn("dish", "status")) {
            jdbc.execute("ALTER TABLE dish ADD COLUMN status TINYINT NOT NULL DEFAULT 1");
            log.info("Migration: dish.status added");
        }
        if (!hasColumn("orders", "user_id")) {
            jdbc.execute("ALTER TABLE orders ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1");
            jdbc.execute("ALTER TABLE orders ADD INDEX idx_user (user_id)");
            log.info("Migration: orders.user_id added");
        }
        // 每用户订单序号：先加列，再给旧数据按 id 顺序回填
        if (!hasColumn("orders", "user_seq")) {
            jdbc.execute("ALTER TABLE orders ADD COLUMN user_seq BIGINT NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE orders ADD INDEX idx_user_seq (user_id, user_seq)");
            log.info("Migration: orders.user_seq added");
        }
        if (!hasColumn("orders", "idempotency_key")) {
            jdbc.execute("ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(100)");
            log.info("Migration: orders.idempotency_key added");
        }
        Integer filled = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_seq > 0", Integer.class);
        if ((filled == null || filled == 0) && hasColumn("orders", "user_seq")) {
            // 派生表方式回填，避免 MySQL 1093（目标表不能出现在子查询 FROM 中）
            jdbc.execute("UPDATE orders o "
                    + "JOIN (SELECT a.id, COUNT(*) AS seq FROM orders a "
                    + "      JOIN orders b ON a.user_id = b.user_id AND a.id >= b.id GROUP BY a.id) t "
                    + "ON o.id = t.id SET o.user_seq = t.seq");
            log.info("Migration: orders.user_seq backfilled");
        }
        addUniqueIndexIfAbsent("orders", "uk_user_seq", "ALTER TABLE orders ADD UNIQUE INDEX uk_user_seq (user_id, user_seq)");
        addUniqueIndexIfAbsent("orders", "uk_user_idempotency", "ALTER TABLE orders ADD UNIQUE INDEX uk_user_idempotency (user_id, idempotency_key)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_order_sequence (user_id BIGINT NOT NULL PRIMARY KEY, next_seq BIGINT NOT NULL)");
        jdbc.execute("INSERT INTO user_order_sequence (user_id, next_seq) "
                + "SELECT user_id, MAX(user_seq) + 1 FROM orders GROUP BY user_id "
                + "ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS order_draft (id VARCHAR(36) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL, total_amount DECIMAL(10,2) NOT NULL, remark VARCHAR(255), status TINYINT NOT NULL DEFAULT 1, expires_at DATETIME NOT NULL, confirmed_order_id BIGINT, create_time DATETIME NOT NULL, KEY idx_draft_user_status (user_id, status))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS order_draft_item (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, draft_id VARCHAR(36) NOT NULL, dish_id BIGINT NOT NULL, dish_name VARCHAR(100) NOT NULL, quantity INT NOT NULL, price DECIMAL(10,2) NOT NULL, amount DECIMAL(10,2) NOT NULL, KEY idx_draft_item (draft_id))");
    }

    private void seed() {
        // 菜单：空表才初始化
        Integer dishCount = jdbc.queryForObject("SELECT COUNT(*) FROM dish", Integer.class);
        if (dishCount == null || dishCount == 0) {
            List<Object[]> dishes = List.of(
                    new Object[]{1L, "鱼香肉丝饭", "18.00", "微辣，经典下饭", "热菜"},
                    new Object[]{2L, "宫保鸡丁饭", "18.00", "微辣，花生脆口", "热菜"},
                    new Object[]{3L, "麻婆豆腐饭", "16.00", "麻辣，下饭神器", "热菜"},
                    new Object[]{4L, "番茄炒蛋饭", "15.00", "不辣，酸甜开胃", "热菜"},
                    new Object[]{5L, "清炒时蔬", "12.00", "清淡，荤素搭配", "热菜"},
                    new Object[]{6L, "可乐鸡翅", "22.00", "微甜，外酥里嫩", "热菜"},
                    new Object[]{7L, "拍黄瓜", "8.00", "清淡爽口", "凉菜"},
                    new Object[]{8L, "凉拌木耳", "10.00", "清淡解腻", "凉菜"},
                    new Object[]{9L, "扬州炒饭", "16.00", "不辣，配料丰富", "主食"},
                    new Object[]{10L, "牛肉拉面", "20.00", "微辣，汤头浓郁", "主食"},
                    new Object[]{11L, "手工水饺(15个)", "18.00", "不辣，皮薄馅大", "主食"},
                    new Object[]{12L, "冰镇酸梅汤", "6.00", "酸甜解辣", "饮品"},
                    new Object[]{13L, "柠檬水", "5.00", "清爽不甜", "饮品"});
            for (Object[] d : dishes) {
                jdbc.update("INSERT INTO dish (id, name, price, description, category, status) VALUES (?,?,?,?,?,1)",
                        d[0], d[1], d[2], d[3], d[4]);
            }
            log.info("Seed: menu initialized (13 dishes)");
        }

        // 默认管理员 admin / admin123
        Integer adminCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
        if (adminCount == null || adminCount == 0) {
            jdbc.update("INSERT INTO admin_user (username, password, created_at) VALUES (?,?,?)",
                    "admin", encoder.encode("admin123"), LocalDateTime.now());
            log.info("Seed: admin account created (admin / admin123)");
        }

        // 演示用户 demo / 123456
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
        if (userCount == null || userCount == 0) {
            jdbc.update("INSERT INTO user (username, password, nickname, created_at) VALUES (?,?,?,?)",
                    "demo", encoder.encode("123456"), "演示用户", LocalDateTime.now());
            log.info("Seed: demo user created (demo / 123456)");
        }
    }

    private boolean hasColumn(String table, String column) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return c != null && c > 0;
    }

    private void addUniqueIndexIfAbsent(String table, String index, String sql) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        if (count == null || count == 0) {
            try {
                jdbc.execute(sql);
                log.info("Migration: {} added", index);
            } catch (DataAccessException e) {
                throw new IllegalStateException("无法创建唯一索引 " + index + "，请先修复历史重复数据", e);
            }
        }
    }
}
