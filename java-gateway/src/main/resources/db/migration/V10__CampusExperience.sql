ALTER TABLE delivery_rule ADD COLUMN pickup_minimum_amount DECIMAL(10,2);
UPDATE delivery_rule SET pickup_minimum_amount=minimum_amount;
ALTER TABLE delivery_rule MODIFY pickup_minimum_amount DECIMAL(10,2) NOT NULL DEFAULT 0;
ALTER TABLE merchant ADD COLUMN pickup_point VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN pickup_point VARCHAR(160);
ALTER TABLE product_sku ADD COLUMN temporarily_sold_out BOOLEAN NOT NULL DEFAULT FALSE;
