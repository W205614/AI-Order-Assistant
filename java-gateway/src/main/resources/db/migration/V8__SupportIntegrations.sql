CREATE TABLE verified_phone (
 account_type VARCHAR(20) NOT NULL, account_id BIGINT NOT NULL, phone VARCHAR(20) NOT NULL,
 verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(account_type,account_id), UNIQUE KEY uk_verified_phone(account_type,phone)
);
CREATE TABLE phone_challenge (
 id VARCHAR(36) PRIMARY KEY, account_type VARCHAR(20) NOT NULL, account_id BIGINT,
 phone VARCHAR(20) NOT NULL, purpose VARCHAR(10) NOT NULL, code_hash VARCHAR(64) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, expires_at DATETIME NOT NULL, consumed_at DATETIME,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_phone_challenge_expiry(expires_at)
);
CREATE TABLE media_asset (
 id VARCHAR(36) PRIMARY KEY, purpose VARCHAR(20) NOT NULL, resource_id VARCHAR(100) NOT NULL,
 owner_type VARCHAR(20) NOT NULL, owner_id BIGINT NOT NULL, provider VARCHAR(10) NOT NULL,
 object_key VARCHAR(150) NOT NULL UNIQUE, content_type VARCHAR(30) NOT NULL,
 byte_length INT NOT NULL, sha256 VARCHAR(64) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_media_resource(purpose,resource_id,created_at,id)
);
