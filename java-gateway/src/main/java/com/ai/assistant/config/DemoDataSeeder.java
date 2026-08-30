package com.ai.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仅供本地演示的菜单和账号初始化。
 *
 * <p>数据库结构由 Flyway 迁移管理；这里绝不修改表结构。默认关闭，避免空的生产库
 * 自动生成弱密码账号。Docker 演示环境通过 DEMO_SEED_ENABLED=true 显式开启。</p>
 */
@Component
@Slf4j
public class DemoDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DemoDataSeeder(JdbcTemplate jdbc,
                          @Value("${app.demo-seed.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("Demo seed is disabled");
            return;
        }
        seedMenu();
        seedAccounts();
    }

    private void seedMenu() {
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
            for (Object[] dish : dishes) {
                jdbc.update("INSERT INTO dish (id, name, price, description, category, status) VALUES (?,?,?,?,?,1)", dish);
            }
            log.info("Demo seed: menu initialized ({} dishes)", dishes.size());
        }
        jdbc.update("UPDATE dish SET allergens='花生' WHERE name='宫保鸡丁饭' AND (allergens IS NULL OR allergens='')");
        jdbc.update("UPDATE dish SET allergens='鸡蛋' WHERE name='番茄炒蛋饭' AND (allergens IS NULL OR allergens='')");
        jdbc.update("UPDATE dish SET allergens='麸质' WHERE name IN ('牛肉拉面','手工水饺(15个)') AND (allergens IS NULL OR allergens='')");
    }

    private void seedAccounts() {
        Integer adminCount = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
        if (adminCount == null || adminCount == 0) {
            jdbc.update("INSERT INTO admin_user (username, password, created_at) VALUES (?,?,?)",
                    "admin", encoder.encode("admin123"), LocalDateTime.now());
            log.warn("Demo seed: default admin account created; do not enable demo seed in production");
        }
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
        if (userCount == null || userCount == 0) {
            jdbc.update("INSERT INTO user (username, password, nickname, created_at) VALUES (?,?,?,?)",
                    "demo", encoder.encode("123456"), "演示用户", LocalDateTime.now());
            log.warn("Demo seed: default user account created; do not enable demo seed in production");
        }
    }
}
