ALTER TABLE orders ADD COLUMN payment_mode VARCHAR(24) NOT NULL DEFAULT 'SIMULATED',
 ADD COLUMN refund_pending_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE checkout ADD COLUMN payment_mode VARCHAR(24) NOT NULL DEFAULT 'SIMULATED';
CREATE TABLE merchant_payment_binding (
 merchant_id BIGINT PRIMARY KEY, sub_mch_id VARCHAR(32) NOT NULL UNIQUE,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE platform_coupon_participation (
 campaign_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL, participating BOOLEAN NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(campaign_id,merchant_id)
);
CREATE TABLE provider_payment (
 id VARCHAR(32) PRIMARY KEY, source_type VARCHAR(16) NOT NULL, source_id VARCHAR(36) NOT NULL,
 user_id BIGINT NOT NULL, mode VARCHAR(24) NOT NULL, scene VARCHAR(8) NOT NULL,
 status VARCHAR(24) NOT NULL, amount BIGINT NOT NULL, action_json JSON,
 expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, attempts INT NOT NULL DEFAULT 0,
 lease_until DATETIME, close_requested BOOLEAN NOT NULL DEFAULT FALSE, client_ip VARCHAR(64) NOT NULL,
 last_error VARCHAR(80), UNIQUE KEY uk_payment_source(source_type,source_id),
 KEY idx_payment_tasks(status,next_attempt_at,lease_until)
);
CREATE TABLE provider_payment_part (
 payment_id VARCHAR(32) NOT NULL, order_id BIGINT NOT NULL UNIQUE, merchant_id BIGINT NOT NULL,
 out_trade_no VARCHAR(32) NOT NULL UNIQUE, sub_mch_id VARCHAR(32) NOT NULL,
 amount BIGINT NOT NULL, status VARCHAR(24) NOT NULL DEFAULT 'PENDING', transaction_id VARCHAR(64), paid_at DATETIME,
 PRIMARY KEY(payment_id,order_id)
);
CREATE TABLE provider_refund (
 id VARCHAR(32) PRIMARY KEY, operation_key VARCHAR(100) NOT NULL UNIQUE,
 payment_id VARCHAR(32) NOT NULL, order_id BIGINT NOT NULL, amount BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', provider_refund_id VARCHAR(64),
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, accepted_at DATETIME, next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 attempts INT NOT NULL DEFAULT 0, lease_until DATETIME, last_error VARCHAR(80),
 KEY idx_refund_tasks(status,next_attempt_at,lease_until)
);
CREATE TABLE provider_callback (
 id VARCHAR(64) PRIMARY KEY, kind VARCHAR(16) NOT NULL, payload_hash CHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE payment_oauth_state (
 state CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, return_to VARCHAR(32) NOT NULL,
 expires_at DATETIME NOT NULL
);
CREATE TABLE payment_wechat_identity (
 user_id BIGINT PRIMARY KEY, app_id VARCHAR(64) NOT NULL, open_id VARCHAR(128) NOT NULL,
 UNIQUE KEY uk_wechat_identity(app_id,open_id)
);
CREATE TABLE payment_reconciliation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, bill_date DATE NOT NULL, sub_mch_id VARCHAR(32) NOT NULL,
 status VARCHAR(24) NOT NULL, checksum CHAR(64), payment_count INT NOT NULL DEFAULT 0,
 difference_count INT NOT NULL DEFAULT 0, fee_amount BIGINT NOT NULL DEFAULT 0, last_error VARCHAR(80),
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_reconciliation(bill_date,sub_mch_id)
);
CREATE TABLE payment_reconciliation_difference (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, batch_id BIGINT NOT NULL, resource_id VARCHAR(64) NOT NULL,
 kind VARCHAR(40) NOT NULL, detail VARCHAR(200) NOT NULL, resolution VARCHAR(500), resolved_by VARCHAR(80),
 resolved_at DATETIME, UNIQUE KEY uk_reconcile_diff(batch_id,resource_id,kind)
);
CREATE TABLE payment_provider_fee (
 bill_date DATE NOT NULL, sub_mch_id VARCHAR(32) NOT NULL, resource_id VARCHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL, fee_amount BIGINT NOT NULL, batch_id BIGINT NOT NULL,
 PRIMARY KEY(bill_date,sub_mch_id,resource_id,kind)
);
