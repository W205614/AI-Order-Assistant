SET @account_sql=CONCAT('CREATE USER IF NOT EXISTS ''ai_order_app''@''%'' IDENTIFIED BY ',QUOTE(@application_password));
PREPARE account_statement FROM @account_sql;
EXECUTE account_statement;
DEALLOCATE PREPARE account_statement;
SET @account_sql=CONCAT('ALTER USER ''ai_order_app''@''%'' IDENTIFIED BY ',QUOTE(@application_password));
PREPARE account_statement FROM @account_sql;
EXECUTE account_statement;
DEALLOCATE PREPARE account_statement;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ai_order_app'@'%';
-- Database-level grants interpret unescaped underscores as wildcards.
GRANT SELECT,INSERT,UPDATE,DELETE ON `ai\_order\_assistant`.* TO 'ai_order_app'@'%';

SET @account_sql=CONCAT('CREATE USER IF NOT EXISTS ''ai_order_migrator''@''%'' IDENTIFIED BY ',QUOTE(@migration_password));
PREPARE account_statement FROM @account_sql;
EXECUTE account_statement;
DEALLOCATE PREPARE account_statement;
SET @account_sql=CONCAT('ALTER USER ''ai_order_migrator''@''%'' IDENTIFIED BY ',QUOTE(@migration_password));
PREPARE account_statement FROM @account_sql;
EXECUTE account_statement;
DEALLOCATE PREPARE account_statement;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ai_order_migrator'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON `ai\_order\_assistant`.* TO 'ai_order_migrator'@'%';
