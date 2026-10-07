CREATE TABLE merchant (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, accepting_orders BOOLEAN NOT NULL DEFAULT TRUE,
 opens_at TIME NOT NULL DEFAULT '00:00:00', closes_at TIME NOT NULL DEFAULT '00:00:00',
 delivery_regions VARCHAR(500) NOT NULL DEFAULT '校园', version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO merchant(id,name) VALUES(1,'原演示商户');
ALTER TABLE user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE, ADD COLUMN token_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE admin_user ADD COLUMN merchant_id BIGINT NULL DEFAULT 1,
 ADD COLUMN role VARCHAR(30) NOT NULL DEFAULT 'OWNER', ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN token_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE dish DROP INDEX uk_dish_name, ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN version BIGINT NOT NULL DEFAULT 1, ADD COLUMN stock_version BIGINT NOT NULL DEFAULT 1,
 ADD UNIQUE KEY uk_merchant_dish_name(merchant_id,name);
ALTER TABLE orders ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN payment_status VARCHAR(30) NOT NULL DEFAULT 'NOT_APPLICABLE',
 ADD COLUMN payment_expires_at DATETIME, ADD COLUMN inventory_released BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN recipient_name VARCHAR(50), ADD COLUMN recipient_phone VARCHAR(30),
 ADD COLUMN delivery_address VARCHAR(255), ADD COLUMN delivery_region VARCHAR(50),
 ADD COLUMN draft_id VARCHAR(36), ADD COLUMN draft_version BIGINT;
ALTER TABLE order_item ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_draft DROP INDEX uk_one_pending_draft_per_user,
 ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1, ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
 ADD UNIQUE KEY uk_pending_merchant_user(merchant_id,pending_user_id);
ALTER TABLE order_draft_item ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN dish_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE order_safety_context DROP PRIMARY KEY, ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1,
 ADD PRIMARY KEY(user_id,merchant_id);
CREATE INDEX idx_orders_merchant_status_time ON orders(merchant_id,status,create_time,id);
CREATE INDEX idx_orders_merchant_user_id ON orders(merchant_id,user_id,id);
CREATE INDEX idx_payment_expiry ON orders(status,payment_expires_at,id);
CREATE INDEX idx_draft_merchant_user ON order_draft(merchant_id,user_id,status);
CREATE TABLE inventory_ledger (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL, dish_id BIGINT NOT NULL,
 order_id BIGINT, request_key VARCHAR(100) NOT NULL, kind VARCHAR(20) NOT NULL,
 delta INT NOT NULL, actor VARCHAR(60) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_inventory_operation(merchant_id,request_key,dish_id,kind),
 KEY idx_inventory_merchant(merchant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE order_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL, user_seq BIGINT NOT NULL, status TINYINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_event_user(merchant_id,user_id,id), KEY idx_event_merchant(merchant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE audit_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT, actor VARCHAR(60) NOT NULL,
 action VARCHAR(60) NOT NULL, resource_id VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_audit_merchant(merchant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE payment_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL, order_id BIGINT NOT NULL,
 kind VARCHAR(30) NOT NULL, amount DECIMAL(10,2) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_payment_operation(order_id,kind), KEY idx_payment_merchant(merchant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE revoked_token (jti VARCHAR(36) PRIMARY KEY, expires_at DATETIME NOT NULL,
 KEY idx_revocation_expiry(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE auth_rate_window (bucket_key VARCHAR(64) PRIMARY KEY, hits INT NOT NULL DEFAULT 1,
 expires_at DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
