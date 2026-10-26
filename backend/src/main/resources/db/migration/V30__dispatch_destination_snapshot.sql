ALTER TABLE work_order_assignment
    ADD COLUMN destination_shop_id BIGINT REFERENCES repair_shop(id),
    ADD COLUMN destination_name VARCHAR(100),
    ADD COLUMN destination_address VARCHAR(255),
    ADD COLUMN location_precision VARCHAR(16) CHECK (location_precision IN ('ROAD','PRECISE'));
