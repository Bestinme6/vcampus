ALTER TABLE bank_ledger_entries
    DROP CHECK chk_bank_ledger_type,
    ADD CONSTRAINT chk_bank_ledger_type CHECK (entry_type IN
        ('ADMIN_TOPUP', 'TRANSFER_OUT', 'TRANSFER_IN', 'SHOP_PAYMENT', 'SHOP_REFUND',
         'SECONDHAND_HOLD', 'SECONDHAND_REFUND', 'SECONDHAND_PAYOUT',
         'LIBRARY_FEE', 'LIBRARY_REFUND', 'FORUM_BOOST'));

CREATE TABLE IF NOT EXISTS secondhand_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    seller_user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    category VARCHAR(32) NOT NULL,
    condition_text VARCHAR(80) NOT NULL,
    price DECIMAL(15,2) NOT NULL,
    item_status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    moderation_status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_secondhand_item_public (moderation_status, item_status, created_at, id),
    INDEX idx_secondhand_item_seller (seller_user_id, created_at, id),
    CONSTRAINT fk_secondhand_item_seller FOREIGN KEY (seller_user_id) REFERENCES users(id),
    CONSTRAINT chk_secondhand_item_price CHECK (price > 0.00),
    CONSTRAINT chk_secondhand_item_status CHECK
        (item_status IN ('AVAILABLE','RESERVED','SOLD','WITHDRAWN')),
    CONSTRAINT chk_secondhand_item_moderation CHECK
        (moderation_status IN ('VISIBLE','HIDDEN'))
);

CREATE TABLE IF NOT EXISTS secondhand_item_images (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    storage_key VARCHAR(160) NOT NULL UNIQUE,
    thumbnail_storage_key VARCHAR(160) NOT NULL UNIQUE,
    mime_type VARCHAR(32) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    sort_order TINYINT NOT NULL,
    is_cover BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_secondhand_item_image_order (item_id, sort_order),
    INDEX idx_secondhand_item_image_cover (item_id, is_cover),
    CONSTRAINT fk_secondhand_item_image_item FOREIGN KEY (item_id)
        REFERENCES secondhand_items(id) ON DELETE CASCADE,
    CONSTRAINT chk_secondhand_item_image_order CHECK (sort_order BETWEEN 0 AND 4),
    CONSTRAINT chk_secondhand_item_image_size CHECK (byte_size > 0)
);

CREATE TABLE IF NOT EXISTS secondhand_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    item_id BIGINT NOT NULL,
    buyer_user_id BIGINT NOT NULL,
    seller_user_id BIGINT NOT NULL,
    purchase_operation_id VARCHAR(64) NOT NULL UNIQUE,
    amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PAID',
    title_snapshot VARCHAR(120) NOT NULL,
    category_snapshot VARCHAR(32) NOT NULL,
    condition_snapshot VARCHAR(80) NOT NULL,
    price_snapshot DECIMAL(15,2) NOT NULL,
    seller_name_snapshot VARCHAR(100) NOT NULL,
    shipping_recipient VARCHAR(40) NOT NULL,
    shipping_phone VARCHAR(32) NOT NULL,
    shipping_address VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    shipped_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    cancelled_at TIMESTAMP NULL,
    INDEX idx_secondhand_order_buyer (buyer_user_id, created_at, id),
    INDEX idx_secondhand_order_seller (seller_user_id, created_at, id),
    INDEX idx_secondhand_order_item (item_id, created_at, id),
    CONSTRAINT fk_secondhand_order_item FOREIGN KEY (item_id) REFERENCES secondhand_items(id),
    CONSTRAINT fk_secondhand_order_buyer FOREIGN KEY (buyer_user_id) REFERENCES users(id),
    CONSTRAINT fk_secondhand_order_seller FOREIGN KEY (seller_user_id) REFERENCES users(id),
    CONSTRAINT chk_secondhand_order_amount CHECK (amount > 0.00),
    CONSTRAINT chk_secondhand_order_price CHECK (price_snapshot > 0.00),
    CONSTRAINT chk_secondhand_order_parties CHECK (buyer_user_id <> seller_user_id),
    CONSTRAINT chk_secondhand_order_status CHECK
        (status IN ('PAID','SHIPPED','COMPLETED','CANCELLED'))
);

CREATE TABLE IF NOT EXISTS secondhand_escrows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL UNIQUE,
    amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'HELD',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_secondhand_escrow_order FOREIGN KEY (order_id) REFERENCES secondhand_orders(id),
    CONSTRAINT chk_secondhand_escrow_amount CHECK (amount > 0.00),
    CONSTRAINT chk_secondhand_escrow_status CHECK (status IN ('HELD','REFUNDED','SETTLED'))
);

CREATE TABLE IF NOT EXISTS secondhand_listing_moderation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_secondhand_listing_moderation (item_id, created_at, id),
    CONSTRAINT fk_secondhand_listing_moderation_item FOREIGN KEY (item_id)
        REFERENCES secondhand_items(id),
    CONSTRAINT fk_secondhand_listing_moderation_operator FOREIGN KEY (operator_user_id)
        REFERENCES users(id),
    CONSTRAINT chk_secondhand_listing_moderation_action CHECK (action IN ('HIDE','RESTORE')),
    CONSTRAINT chk_secondhand_listing_moderation_reason CHECK
        (CHAR_LENGTH(TRIM(reason)) BETWEEN 2 AND 500)
);

CREATE TABLE IF NOT EXISTS secondhand_order_resolution_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    operation_id VARCHAR(64) NOT NULL UNIQUE,
    action VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_secondhand_order_resolution (order_id, created_at, id),
    CONSTRAINT fk_secondhand_resolution_order FOREIGN KEY (order_id) REFERENCES secondhand_orders(id),
    CONSTRAINT fk_secondhand_resolution_operator FOREIGN KEY (operator_user_id) REFERENCES users(id),
    CONSTRAINT chk_secondhand_resolution_action CHECK (action IN ('REFUND','SETTLE')),
    CONSTRAINT chk_secondhand_resolution_reason CHECK
        (CHAR_LENGTH(TRIM(reason)) BETWEEN 2 AND 500)
);

CREATE TABLE IF NOT EXISTS secondhand_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL UNIQUE,
    item_id BIGINT NOT NULL,
    seller_user_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    INDEX idx_secondhand_review_seller (seller_user_id, status, created_at, id),
    CONSTRAINT fk_secondhand_review_order FOREIGN KEY (order_id) REFERENCES secondhand_orders(id),
    CONSTRAINT fk_secondhand_review_item FOREIGN KEY (item_id) REFERENCES secondhand_items(id),
    CONSTRAINT fk_secondhand_review_seller FOREIGN KEY (seller_user_id) REFERENCES users(id),
    CONSTRAINT fk_secondhand_review_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_secondhand_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_secondhand_review_content CHECK
        (CHAR_LENGTH(TRIM(content)) BETWEEN 5 AND 1000),
    CONSTRAINT chk_secondhand_review_status CHECK (status IN ('NORMAL','HIDDEN','DELETED'))
);

CREATE TABLE IF NOT EXISTS secondhand_review_images (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    position TINYINT NOT NULL,
    storage_key VARCHAR(160) NOT NULL UNIQUE,
    thumbnail_storage_key VARCHAR(160) NOT NULL UNIQUE,
    mime_type VARCHAR(32) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_secondhand_review_image_position (review_id, position),
    CONSTRAINT fk_secondhand_review_image_review FOREIGN KEY (review_id)
        REFERENCES secondhand_reviews(id) ON DELETE CASCADE,
    CONSTRAINT chk_secondhand_review_image_position CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT chk_secondhand_review_image_mime CHECK
        (mime_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT chk_secondhand_review_image_size CHECK (byte_size > 0 AND byte_size <= 5242880),
    CONSTRAINT chk_secondhand_review_image_dimensions CHECK
        (width > 0 AND height > 0 AND width <= 20000000 AND height <= 20000000)
);

CREATE TABLE IF NOT EXISTS secondhand_review_likes (
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (review_id, user_id),
    CONSTRAINT fk_secondhand_review_like_review FOREIGN KEY (review_id)
        REFERENCES secondhand_reviews(id) ON DELETE CASCADE,
    CONSTRAINT fk_secondhand_review_like_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS secondhand_review_moderation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_secondhand_review_moderation (review_id, created_at, id),
    CONSTRAINT fk_secondhand_review_moderation_review FOREIGN KEY (review_id)
        REFERENCES secondhand_reviews(id),
    CONSTRAINT fk_secondhand_review_moderation_operator FOREIGN KEY (operator_user_id)
        REFERENCES users(id),
    CONSTRAINT chk_secondhand_review_moderation_action CHECK (action IN ('HIDE','RESTORE')),
    CONSTRAINT chk_secondhand_review_moderation_reason CHECK
        (CHAR_LENGTH(TRIM(reason)) BETWEEN 2 AND 500)
);
