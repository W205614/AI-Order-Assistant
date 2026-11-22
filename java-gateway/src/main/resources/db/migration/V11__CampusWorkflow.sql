ALTER TABLE merchant ADD COLUMN preparation_min INT, ADD COLUMN preparation_max INT;
CREATE TABLE commerce_work_note (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 order_id BIGINT NOT NULL,
 operator_id BIGINT NOT NULL,
 note VARCHAR(500) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_work_note_order (order_id,id)
);
