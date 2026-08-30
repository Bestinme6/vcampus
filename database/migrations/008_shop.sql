USE vcampus;

CREATE TABLE IF NOT EXISTS shop_products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    price DECIMAL(15,2) NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_product_sku (sku),
    INDEX idx_shop_product_enabled_name (enabled, name),
    CONSTRAINT chk_shop_product_price CHECK (price > 0.00),
    CONSTRAINT chk_shop_product_stock CHECK (stock >= 0)
);

CREATE TABLE IF NOT EXISTS shop_cart_items (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT fk_shop_cart_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_cart_product FOREIGN KEY (product_id) REFERENCES shop_products(id),
    CONSTRAINT chk_shop_cart_quantity CHECK (quantity > 0)
);

CREATE TABLE IF NOT EXISTS shop_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    buyer_user_id BIGINT NOT NULL,
    checkout_operation_id VARCHAR(64) NOT NULL UNIQUE,
    total_amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    shipped_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    cancelled_at TIMESTAMP NULL,
    INDEX idx_shop_order_buyer_time (buyer_user_id, created_at),
    INDEX idx_shop_order_status_time (status, created_at),
    CONSTRAINT fk_shop_order_buyer FOREIGN KEY (buyer_user_id) REFERENCES users(id),
    CONSTRAINT chk_shop_order_total CHECK (total_amount > 0.00),
    CONSTRAINT chk_shop_order_status CHECK (status IN ('PAID', 'SHIPPED', 'COMPLETED', 'CANCELLED'))
);

CREATE TABLE IF NOT EXISTS shop_order_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_snapshot VARCHAR(64) NOT NULL,
    name_snapshot VARCHAR(120) NOT NULL,
    unit_price DECIMAL(15,2) NOT NULL,
    quantity INT NOT NULL,
    subtotal DECIMAL(15,2) NOT NULL,
    UNIQUE KEY uk_shop_order_item_product (order_id, product_id),
    CONSTRAINT fk_shop_order_item_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
    CONSTRAINT fk_shop_order_item_product FOREIGN KEY (product_id) REFERENCES shop_products(id),
    CONSTRAINT chk_shop_order_item_price CHECK (unit_price > 0.00),
    CONSTRAINT chk_shop_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_shop_order_item_subtotal CHECK (subtotal > 0.00)
);

CREATE TABLE IF NOT EXISTS shop_inventory_movements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity_delta INT NOT NULL,
    stock_after INT NOT NULL,
    order_id BIGINT NULL,
    operator_user_id BIGINT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_inventory_product_time (product_id, created_at),
    INDEX idx_shop_inventory_order (order_id),
    CONSTRAINT fk_shop_inventory_product FOREIGN KEY (product_id) REFERENCES shop_products(id),
    CONSTRAINT fk_shop_inventory_order FOREIGN KEY (order_id) REFERENCES shop_orders(id),
    CONSTRAINT fk_shop_inventory_operator FOREIGN KEY (operator_user_id)
        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_shop_inventory_type CHECK (movement_type IN
        ('INITIAL', 'ADMIN_ADJUST', 'SALE', 'ORDER_CANCEL')),
    CONSTRAINT chk_shop_inventory_delta CHECK (quantity_delta <> 0),
    CONSTRAINT chk_shop_inventory_stock CHECK (stock_after >= 0)
);

ALTER TABLE notifications
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (notification_type IN
        ('SCHEDULE_ASSIGNED', 'GRADE_PUBLISHED', 'STUDENT_STATUS_CHANGED',
         'ROLES_CHANGED', 'ACCOUNT_ENABLED', 'ACCOUNT_DISABLED', 'PASSWORD_RESET',
         'LIBRARY_BORROWED', 'LIBRARY_RENEWED', 'LIBRARY_RETURNED', 'LIBRARY_LOST',
         'LIBRARY_DUE_SOON', 'LIBRARY_OVERDUE', 'FORUM_POST_COMMENTED',
         'FORUM_POST_MODERATED', 'FORUM_COMMENT_MODERATED',
         'BANK_TRANSFER_RECEIVED', 'BANK_ACCOUNT_TOPPED_UP',
         'BANK_ACCOUNT_STATUS_CHANGED', 'SHOP_ORDER_PAID',
         'SHOP_ORDER_REFUNDED', 'SHOP_ORDER_SHIPPED')),
    DROP CHECK chk_notification_source,
    ADD CONSTRAINT chk_notification_source CHECK (source_module IN
        ('ACADEMIC', 'STUDENT_STATUS', 'ACCOUNT_SECURITY', 'LIBRARY', 'FORUM', 'BANK', 'SHOP')),
    DROP CHECK chk_notification_target,
    ADD CONSTRAINT chk_notification_target CHECK (target IN
        ('TEACHER_SCHEDULE', 'STUDENT_GRADES', 'STUDENT_PROFILE', 'LIBRARY_LOANS',
         'FORUM_POST', 'BANK_LEDGER', 'SHOP_ORDERS', 'NONE'));
