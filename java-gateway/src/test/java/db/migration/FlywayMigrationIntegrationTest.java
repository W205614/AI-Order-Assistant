package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures a fresh or pre-Flyway database reaches the same recorded schema without data loss. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayMigrationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ai_order_assistant_migration_test")
            .withUsername("test")
            .withPassword("test");

    @BeforeEach
    void resetDatabase() throws Exception {
        if (!MYSQL.isRunning()) MYSQL.start();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS order_draft_item");
            statement.execute("DROP TABLE IF EXISTS order_draft");
            statement.execute("DROP TABLE IF EXISTS order_item");
            statement.execute("DROP TABLE IF EXISTS user_order_sequence");
            statement.execute("DROP TABLE IF EXISTS orders");
            statement.execute("DROP TABLE IF EXISTS user_food_preference");
            statement.execute("DROP TABLE IF EXISTS admin_user");
            statement.execute("DROP TABLE IF EXISTS user");
            statement.execute("DROP TABLE IF EXISTS dish");
            statement.execute("DROP TABLE IF EXISTS flyway_schema_history");
        }
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    @Test
    void freshDatabaseRecordsMigrationsAndSecondRunIsNoop() throws Exception {
        assertEquals(2, flyway().migrate().migrationsExecuted);
        assertEquals(0, flyway().migrate().migrationsExecuted);

        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema = DATABASE() AND table_name IN ('dish','orders','order_draft','user_order_sequence')")) {
            tables.next();
            assertEquals(4, tables.getInt(1));
        }
    }

    @Test
    void legacyOrdersReceiveSequenceAndExistingDataSurvives() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE dish (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, "
                    + "price DECIMAL(10,2) NOT NULL, description VARCHAR(255), category VARCHAR(50), UNIQUE KEY uk_dish_name (name))");
            statement.execute("CREATE TABLE user (id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(50) NOT NULL, "
                    + "password VARCHAR(100) NOT NULL, nickname VARCHAR(50), created_at DATETIME NOT NULL, UNIQUE KEY uk_username (username))");
            statement.execute("CREATE TABLE admin_user (id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(50) NOT NULL, "
                    + "password VARCHAR(100) NOT NULL, created_at DATETIME NOT NULL, UNIQUE KEY uk_admin_username (username))");
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY AUTO_INCREMENT, total_amount DECIMAL(10,2) NOT NULL, "
                    + "status TINYINT NOT NULL, remark VARCHAR(255), create_time DATETIME NOT NULL, deliver_at DATETIME, "
                    + "deliver_time DATETIME, remind_count INT NOT NULL DEFAULT 0, remind_time DATETIME)");
            statement.execute("CREATE TABLE order_item (id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, "
                    + "dish_id BIGINT NOT NULL, dish_name VARCHAR(100) NOT NULL, quantity INT NOT NULL, "
                    + "price DECIMAL(10,2) NOT NULL, amount DECIMAL(10,2) NOT NULL)");
            statement.execute("INSERT INTO dish(id,name,price,description,category) VALUES (9,'保留菜品',19.50,'历史数据','热菜')");
            statement.execute("INSERT INTO orders(id,total_amount,status,create_time) VALUES (7,19.50,1,NOW())");
        }

        assertEquals(2, flyway().migrate().migrationsExecuted);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT name, status, stock FROM dish WHERE id=9")) {
                assertTrue(result.next());
                assertEquals("保留菜品", result.getString("name"));
                assertEquals(1, result.getInt("status"));
                assertEquals(100, result.getInt("stock"));
            }
            try (ResultSet result = statement.executeQuery("SELECT user_id, user_seq FROM orders WHERE id=7")) {
                assertTrue(result.next());
                assertEquals(1L, result.getLong("user_id"));
                assertEquals(1L, result.getLong("user_seq"));
            }
            try (ResultSet result = statement.executeQuery("SELECT next_seq FROM user_order_sequence WHERE user_id=1")) {
                assertTrue(result.next());
                assertEquals(2L, result.getLong("next_seq"));
            }
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
