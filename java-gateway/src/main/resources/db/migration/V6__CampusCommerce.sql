CREATE TABLE campus (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO campus(id,name) VALUES(1,'默认校区');
ALTER TABLE merchant ADD COLUMN campus_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE delivery_rule (
 merchant_id BIGINT NOT NULL, campus_id BIGINT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 fee DECIMAL(10,2) NOT NULL DEFAULT 0, minimum_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
 eta_min INT NOT NULL DEFAULT 20, eta_max INT NOT NULL DEFAULT 60, version BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(merchant_id,campus_id)
);
INSERT INTO delivery_rule(merchant_id,campus_id) SELECT id,1 FROM merchant;
CREATE TABLE customer_address (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, campus_id BIGINT NOT NULL,
 recipient_name VARCHAR(50) NOT NULL, phone VARCHAR(30) NOT NULL, detail VARCHAR(255) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, KEY idx_address_user(user_id,id)
);
-- A default SKU shares the existing dish inventory; named SKUs own their stock.
-- This avoids two independently mutable counters for legacy/default inventory.
CREATE TABLE product_sku (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL, dish_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE,
 price DECIMAL(10,2) NOT NULL, packaging_fee DECIMAL(10,2) NOT NULL DEFAULT 0,
 stock INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 1,
 default_dish_id BIGINT GENERATED ALWAYS AS(IF(is_default,dish_id,NULL)) STORED,
 UNIQUE KEY uk_default_sku(default_dish_id),
 UNIQUE KEY uk_sku_name(dish_id,name), KEY idx_sku_merchant(merchant_id,id)
);
INSERT INTO product_sku(id,merchant_id,dish_id,name,is_default,price)
 SELECT id,merchant_id,id,'默认规格',TRUE,price FROM dish;
CREATE TABLE product_bundle (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 price DECIMAL(10,2) NOT NULL, packaging_fee DECIMAL(10,2) NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 1, KEY idx_bundle_merchant(merchant_id,id)
);
CREATE TABLE bundle_component (
 bundle_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, quantity INT NOT NULL, PRIMARY KEY(bundle_id,sku_id)
);
CREATE TABLE cart_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, merchant_id BIGINT NOT NULL,
 product_type VARCHAR(10) NOT NULL, product_id BIGINT NOT NULL, quantity INT NOT NULL,
 remark VARCHAR(255) NOT NULL DEFAULT '', safety_allergens VARCHAR(255) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 1,
 UNIQUE KEY uk_cart_product(user_id,product_type,product_id), KEY idx_cart_user(user_id,merchant_id,id)
);
CREATE TABLE favorite (
 user_id BIGINT NOT NULL, product_type VARCHAR(10) NOT NULL, product_id BIGINT NOT NULL,
 PRIMARY KEY(user_id,product_type,product_id)
);
CREATE TABLE coupon_campaign (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NULL, name VARCHAR(100) NOT NULL,
 threshold_amount DECIMAL(10,2) NOT NULL, discount_amount DECIMAL(10,2) NOT NULL,
 total_count INT NOT NULL, claimed_count INT NOT NULL DEFAULT 0,
 claim_start DATETIME NOT NULL, claim_end DATETIME NOT NULL, valid_start DATETIME NOT NULL, valid_end DATETIME NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, KEY idx_campaign_merchant(merchant_id,id)
);
CREATE TABLE customer_coupon (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, campaign_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', checkout_id VARCHAR(36),
 UNIQUE KEY uk_user_campaign(user_id,campaign_id), KEY idx_coupon_checkout(checkout_id,id)
);
CREATE TABLE coupon_ledger (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, coupon_id BIGINT NOT NULL, checkout_id VARCHAR(36),
 action VARCHAR(20) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE reservation_slot (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, merchant_id BIGINT NOT NULL,
 starts_at DATETIME NOT NULL, ends_at DATETIME NOT NULL, cutoff_at DATETIME NOT NULL,
 prepare_minutes INT NOT NULL, capacity INT NOT NULL, reserved_count INT NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE KEY uk_slot(merchant_id,starts_at,ends_at)
);
CREATE TABLE checkout_quote (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 request_json JSON NOT NULL, snapshot_json JSON NOT NULL, expires_at DATETIME NOT NULL,
 consumed_checkout_id VARCHAR(36), KEY idx_quote_expiry(expires_at)
);
CREATE TABLE checkout (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, quote_id VARCHAR(36) NOT NULL UNIQUE,
 idempotency_key VARCHAR(100) NOT NULL, request_fingerprint VARCHAR(64) NOT NULL,
 status VARCHAR(30) NOT NULL, total_amount DECIMAL(10,2) NOT NULL, refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
 expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_checkout_key(user_id,idempotency_key), KEY idx_checkout_user(user_id,created_at,id)
);
ALTER TABLE orders ADD COLUMN checkout_id VARCHAR(36), ADD COLUMN commerce_version INT NOT NULL DEFAULT 1,
 ADD COLUMN campus_id BIGINT, ADD COLUMN fulfillment_mode VARCHAR(20), ADD COLUMN fulfillment_status VARCHAR(30),
 ADD COLUMN goods_amount DECIMAL(10,2), ADD COLUMN packaging_amount DECIMAL(10,2), ADD COLUMN delivery_fee DECIMAL(10,2),
 ADD COLUMN shop_discount DECIMAL(10,2), ADD COLUMN platform_discount DECIMAL(10,2),
 ADD COLUMN refunded_amount DECIMAL(10,2) NOT NULL DEFAULT 0, ADD COLUMN reservation_slot_id BIGINT,
 ADD COLUMN capacity_released BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN production_started BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN verification_code_hash VARCHAR(64), ADD COLUMN accepted_at DATETIME, ADD COLUMN ready_at DATETIME,
 ADD KEY idx_order_checkout(checkout_id,id);
ALTER TABLE order_item ADD COLUMN product_type VARCHAR(10), ADD COLUMN product_id BIGINT,
 ADD COLUMN remark VARCHAR(255) NOT NULL DEFAULT '',
 ADD COLUMN packaging_amount DECIMAL(10,2), ADD COLUMN shop_discount DECIMAL(10,2), ADD COLUMN platform_discount DECIMAL(10,2);
CREATE TABLE order_unit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, order_item_id BIGINT NOT NULL, unit_no INT NOT NULL,
 goods_paid DECIMAL(10,2) NOT NULL, packaging_paid DECIMAL(10,2) NOT NULL,
 shop_discount DECIMAL(10,2) NOT NULL, platform_discount DECIMAL(10,2) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', aftersale_id VARCHAR(36),
 UNIQUE KEY uk_order_unit(order_item_id,unit_no), KEY idx_units_order(order_id,id)
);
CREATE TABLE customer_order_code (
 order_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, code VARCHAR(6) NOT NULL, failed_attempts INT NOT NULL DEFAULT 0
);
CREATE TABLE order_stock_component (
 order_item_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, quantity_per_unit INT NOT NULL,
 PRIMARY KEY(order_item_id,sku_id)
);
CREATE TABLE sku_inventory_ledger (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, sku_id BIGINT NOT NULL, order_id BIGINT,
 request_key VARCHAR(100) NOT NULL, delta INT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_sku_operation(sku_id,request_key)
);
CREATE TABLE commerce_payment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, checkout_id VARCHAR(36) NOT NULL, order_id BIGINT,
 operation_key VARCHAR(100) NOT NULL UNIQUE, kind VARCHAR(20) NOT NULL, amount DECIMAL(10,2) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE aftersale (
 id VARCHAR(36) PRIMARY KEY, order_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL, amount DECIMAL(10,2) NOT NULL,
 decision_reason VARCHAR(500), disputed BOOLEAN NOT NULL DEFAULT FALSE,
 request_key VARCHAR(100) NOT NULL, request_fingerprint VARCHAR(64) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_aftersale_request(user_id,request_key), KEY idx_aftersale_order(order_id,id)
);
CREATE TABLE aftersale_unit (
 aftersale_id VARCHAR(36) NOT NULL, unit_id BIGINT NOT NULL, PRIMARY KEY(aftersale_id,unit_id)
);
CREATE TABLE rider_campus (
 rider_id BIGINT NOT NULL, campus_id BIGINT NOT NULL, PRIMARY KEY(rider_id,campus_id)
);
CREATE TABLE delivery_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL UNIQUE, merchant_id BIGINT NOT NULL,
 campus_id BIGINT NOT NULL, rider_id BIGINT, status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
 version BIGINT NOT NULL DEFAULT 1, exception_reason VARCHAR(500), picked_at DATETIME,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_delivery_pool(campus_id,status,id), KEY idx_delivery_rider(rider_id,id)
);
CREATE TABLE feedback (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, order_id BIGINT NOT NULL,
 message VARCHAR(1000) NOT NULL, reply VARCHAR(1000), status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_feedback_order(order_id,id)
);
CREATE TABLE commerce_event_cursor(id INT PRIMARY KEY);
INSERT INTO commerce_event_cursor VALUES(1);
CREATE TABLE commerce_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT, merchant_id BIGINT, rider_id BIGINT,
 kind VARCHAR(40) NOT NULL, resource_id VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_commerce_event_user(user_id,id), KEY idx_commerce_event_rider(rider_id,id)
);
