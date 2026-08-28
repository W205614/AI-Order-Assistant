-- 菜品表（菜单），status: 1起售 0停售
CREATE TABLE IF NOT EXISTS dish (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    description VARCHAR(255),
    category VARCHAR(50),
    status TINYINT NOT NULL DEFAULT 1,
    UNIQUE KEY uk_dish_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    nickname VARCHAR(50),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 管理员表
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单表
-- status: 1已下单 2制作中 3配送中 4已送达 5已取消 6已超时
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL DEFAULT 1,
    user_seq BIGINT NOT NULL DEFAULT 0,
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    remark VARCHAR(255),
    create_time DATETIME NOT NULL,
    deliver_at DATETIME,
    deliver_time DATETIME,
    remind_count INT NOT NULL DEFAULT 0,
    remind_time DATETIME,
    idempotency_key VARCHAR(100),
    KEY idx_user (user_id),
    KEY idx_status (status),
    UNIQUE KEY uk_user_seq (user_id, user_seq),
    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每个用户的订单序号分配器。用行锁替代 MAX(user_seq) + 1，避免并发下单产生重复序号。
CREATE TABLE IF NOT EXISTS user_order_sequence (
    user_id BIGINT NOT NULL PRIMARY KEY,
    next_seq BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_draft (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    remark VARCHAR(255),
    status TINYINT NOT NULL DEFAULT 1,
    expires_at DATETIME NOT NULL,
    confirmed_order_id BIGINT,
    create_time DATETIME NOT NULL,
    KEY idx_draft_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_draft_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    draft_id VARCHAR(36) NOT NULL,
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    KEY idx_draft_item (draft_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单明细表
CREATE TABLE IF NOT EXISTS order_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    dish_id BIGINT NOT NULL,
    dish_name VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
