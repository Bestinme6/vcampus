CREATE TABLE IF NOT EXISTS shop_shipping_addresses (
    user_id BIGINT NOT NULL,
    slot TINYINT NOT NULL,
    version_token CHAR(36) NOT NULL,
    recipient VARCHAR(40) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    address_text VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, slot),
    CONSTRAINT fk_shop_shipping_address_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_shop_shipping_address_slot CHECK (slot BETWEEN 1 AND 3)
);

ALTER TABLE shop_orders
    ADD COLUMN shipping_recipient VARCHAR(40) NULL;

ALTER TABLE shop_orders
    ADD COLUMN shipping_phone VARCHAR(32) NULL;

ALTER TABLE shop_orders
    ADD COLUMN shipping_address VARCHAR(255) NULL;
