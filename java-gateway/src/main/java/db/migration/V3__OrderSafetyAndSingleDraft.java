package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Preserve legacy rows while establishing one pending draft per user. */
public class V3__OrderSafetyAndSingleDraft extends BaseJavaMigration {
    @Override
    public boolean canExecuteInTransaction() { return false; }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection db = context.getConnection();
        if (!hasColumn(db, "dish", "allergen_reviewed")) {
            execute(db, "ALTER TABLE dish ADD COLUMN allergen_reviewed BOOLEAN NOT NULL DEFAULT FALSE");
        }
        if (!hasColumn(db, "order_draft", "safety_allergens")) {
            execute(db, "ALTER TABLE order_draft ADD COLUMN safety_allergens VARCHAR(255)");
        }
        execute(db, "CREATE TABLE IF NOT EXISTS order_safety_context ("
                + "user_id BIGINT NOT NULL PRIMARY KEY, allergens VARCHAR(255), "
                + "needs_clarification BOOLEAN NOT NULL DEFAULT FALSE, expires_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        execute(db, "UPDATE order_draft SET status=4 WHERE status=1 AND expires_at<=NOW()");
        // A legacy database may already contain duplicate pending drafts. Keep the newest.
        Set<Long> seen = new HashSet<>();
        try (Statement query = db.createStatement();
             ResultSet rows = query.executeQuery("SELECT id,user_id FROM order_draft WHERE status=1 "
                     + "ORDER BY user_id,create_time DESC,id DESC")) {
            while (rows.next()) {
                long userId = rows.getLong("user_id");
                if (!seen.add(userId)) {
                    try (var update = db.prepareStatement("UPDATE order_draft SET status=3 WHERE id=? AND status=1")) {
                        update.setString(1, rows.getString("id"));
                        update.executeUpdate();
                    }
                }
            }
        }
        if (!hasColumn(db, "order_draft", "pending_user_id")) {
            execute(db, "ALTER TABLE order_draft ADD COLUMN pending_user_id BIGINT "
                    + "GENERATED ALWAYS AS (CASE WHEN status=1 THEN user_id ELSE NULL END) STORED");
        }
        if (!hasIndex(db, "order_draft", "uk_one_pending_draft_per_user")) {
            execute(db, "CREATE UNIQUE INDEX uk_one_pending_draft_per_user ON order_draft(pending_user_id)");
        }
    }

    private boolean hasColumn(Connection db, String table, String column) throws SQLException {
        try (ResultSet rows = db.getMetaData().getColumns(db.getCatalog(), null, table, column)) {
            return rows.next();
        }
    }

    private boolean hasIndex(Connection db, String table, String index) throws SQLException {
        try (ResultSet rows = db.getMetaData().getIndexInfo(db.getCatalog(), null, table, false, false)) {
            while (rows.next()) if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    private void execute(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) { statement.execute(sql); }
    }
}
