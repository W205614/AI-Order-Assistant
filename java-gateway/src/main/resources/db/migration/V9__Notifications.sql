CREATE TABLE notification (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 account_type VARCHAR(20) NOT NULL, account_id BIGINT NOT NULL,
 event_key VARCHAR(100) NOT NULL, kind VARCHAR(60) NOT NULL,
 resource_id VARCHAR(100) NOT NULL, message VARCHAR(255) NOT NULL,
 read_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notification_event(account_type,account_id,event_key),
 KEY idx_notification_account(account_type,account_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notification_cursor (
 source VARCHAR(20) PRIMARY KEY, last_id BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO notification_cursor SELECT 'commerce',COALESCE(MAX(id),0) FROM commerce_event;
INSERT INTO notification_cursor SELECT 'order',COALESCE(MAX(id),0) FROM order_event;
CREATE TABLE notification_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, notification_id BIGINT NOT NULL UNIQUE,
 template_code VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token VARCHAR(36), lease_until DATETIME, provider_reference VARCHAR(100),
 last_error VARCHAR(80), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_notification_due(status,available_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
