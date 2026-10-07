-- Serialize the last event insert until commit, so replay IDs never skip an
-- earlier uncommitted event. This is intentionally a single-instance scale design.
CREATE TABLE order_event_cursor (
 id TINYINT PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO order_event_cursor(id) VALUES(1);
