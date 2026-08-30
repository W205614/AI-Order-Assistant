-- 支撑按用户或状态分页读取最新订单。V1 保留旧索引，此处新增复合索引避免全表扫描后排序。
CREATE INDEX idx_orders_user_id ON orders (user_id, id);
CREATE INDEX idx_orders_status_id ON orders (status, id);
