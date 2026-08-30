package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 把 Flyway 引入前的数据库安全收敛到当前结构。
 *
 * <p>该迁移只会被 Flyway 记录并执行一次。所有 DDL 都是增量式的，因此可升级已有
 * 演示数据而不删除订单、草稿、库存或用户数据。</p>
 */
public class V1__LegacyBaseline extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        // MySQL DDL 会隐式提交；让 Flyway 不把这批可重试的增量 DDL 包进伪事务。
        return false;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        createBaseTables(connection);
        upgradeLegacyColumns(connection);
        backfillOrderSequences(connection);
        createSequenceAllocator(connection);
    }

    private void createBaseTables(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE IF NOT EXISTS dish ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, "
                + "price DECIMAL(10,2) NOT NULL, description VARCHAR(255), category VARCHAR(50), "
                + "status TINYINT NOT NULL DEFAULT 1, stock INT NOT NULL DEFAULT 100, allergens VARCHAR(255), "
                + "UNIQUE KEY uk_dish_name (name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS user ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) NOT NULL, "
                + "password VARCHAR(100) NOT NULL, nickname VARCHAR(50), created_at DATETIME NOT NULL, "
                + "UNIQUE KEY uk_username (username)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS admin_user ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) NOT NULL, "
                + "password VARCHAR(100) NOT NULL, created_at DATETIME NOT NULL, "
                + "UNIQUE KEY uk_admin_username (username)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS orders ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL DEFAULT 1, "
                + "user_seq BIGINT NOT NULL DEFAULT 0, total_amount DECIMAL(10,2) NOT NULL DEFAULT 0, "
                + "status TINYINT NOT NULL DEFAULT 1, remark VARCHAR(255), create_time DATETIME NOT NULL, "
                + "deliver_at DATETIME, deliver_time DATETIME, remind_count INT NOT NULL DEFAULT 0, "
                + "remind_time DATETIME, idempotency_key VARCHAR(100), KEY idx_user (user_id), "
                + "KEY idx_status (status), UNIQUE KEY uk_user_seq (user_id, user_seq), "
                + "UNIQUE KEY uk_user_idempotency (user_id, idempotency_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS order_item ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, dish_id BIGINT NOT NULL, "
                + "dish_name VARCHAR(100) NOT NULL, quantity INT NOT NULL, price DECIMAL(10,2) NOT NULL, "
                + "amount DECIMAL(10,2) NOT NULL, KEY idx_order_id (order_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS user_food_preference ("
                + "user_id BIGINT NOT NULL PRIMARY KEY, allergens VARCHAR(255), dislikes VARCHAR(255), "
                + "dietary_goal VARCHAR(255), budget DECIMAL(10,2)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS order_draft ("
                + "id VARCHAR(36) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL, total_amount DECIMAL(10,2) NOT NULL, "
                + "remark VARCHAR(255), status TINYINT NOT NULL DEFAULT 1, expires_at DATETIME NOT NULL, "
                + "confirmed_order_id BIGINT, create_time DATETIME NOT NULL, "
                + "KEY idx_draft_user_status (user_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "CREATE TABLE IF NOT EXISTS order_draft_item ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, draft_id VARCHAR(36) NOT NULL, dish_id BIGINT NOT NULL, "
                + "dish_name VARCHAR(100) NOT NULL, quantity INT NOT NULL, price DECIMAL(10,2) NOT NULL, "
                + "amount DECIMAL(10,2) NOT NULL, KEY idx_draft_item (draft_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private void upgradeLegacyColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "dish", "status", "TINYINT NOT NULL DEFAULT 1");
        addColumnIfMissing(connection, "dish", "stock", "INT NOT NULL DEFAULT 100");
        addColumnIfMissing(connection, "dish", "allergens", "VARCHAR(255)");
        addColumnIfMissing(connection, "orders", "user_id", "BIGINT NOT NULL DEFAULT 1");
        addColumnIfMissing(connection, "orders", "user_seq", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "orders", "idempotency_key", "VARCHAR(100)");
        addIndexIfMissing(connection, "orders", "idx_user", "CREATE INDEX idx_user ON orders (user_id)");
        addIndexIfMissing(connection, "orders", "idx_status", "CREATE INDEX idx_status ON orders (status)");
        addIndexIfMissing(connection, "orders", "uk_user_seq", "CREATE UNIQUE INDEX uk_user_seq ON orders (user_id, user_seq)");
        addIndexIfMissing(connection, "orders", "uk_user_idempotency",
                "CREATE UNIQUE INDEX uk_user_idempotency ON orders (user_id, idempotency_key)");
    }

    private void backfillOrderSequences(Connection connection) throws SQLException {
        if (count(connection, "SELECT COUNT(*) FROM orders WHERE user_seq > 0") == 0) {
            execute(connection, "UPDATE orders o JOIN (SELECT a.id, COUNT(*) AS seq FROM orders a "
                    + "JOIN orders b ON a.user_id = b.user_id AND a.id >= b.id GROUP BY a.id) t "
                    + "ON o.id = t.id SET o.user_seq = t.seq");
        }
    }

    private void createSequenceAllocator(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE IF NOT EXISTS user_order_sequence ("
                + "user_id BIGINT NOT NULL PRIMARY KEY, next_seq BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(connection, "INSERT INTO user_order_sequence (user_id, next_seq) "
                + "SELECT user_id, MAX(user_seq) + 1 FROM orders GROUP BY user_id "
                + "ON DUPLICATE KEY UPDATE next_seq = GREATEST(next_seq, VALUES(next_seq))");
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition) throws SQLException {
        if (!columnExists(connection, table, column)) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void addIndexIfMissing(Connection connection, String table, String index, String sql) throws SQLException {
        if (!indexExists(connection, table, index)) {
            execute(connection, sql);
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", table, column) > 0;
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?", table, index) > 0;
    }

    private int count(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
