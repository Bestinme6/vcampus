CREATE TABLE IF NOT EXISTS shop_product_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id VARCHAR(64) NOT NULL UNIQUE,
    order_item_id BIGINT NOT NULL UNIQUE,
    product_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    repeat_purchase BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    INDEX idx_shop_review_product_status_time (product_id, status, created_at, id),
    INDEX idx_shop_review_author_time (author_user_id, created_at, id),
    CONSTRAINT fk_shop_review_order_item FOREIGN KEY (order_item_id) REFERENCES shop_order_items(id),
    CONSTRAINT fk_shop_review_product FOREIGN KEY (product_id) REFERENCES shop_products(id),
    CONSTRAINT fk_shop_review_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_shop_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_shop_review_content CHECK (CHAR_LENGTH(TRIM(content)) BETWEEN 5 AND 1000),
    CONSTRAINT chk_shop_review_status CHECK (status IN ('NORMAL','HIDDEN','DELETED'))
);

CREATE TABLE IF NOT EXISTS shop_review_images (
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
    UNIQUE KEY uk_shop_review_image_position (review_id, position),
    INDEX idx_shop_review_image_review (review_id),
    CONSTRAINT fk_shop_review_image_review FOREIGN KEY (review_id)
        REFERENCES shop_product_reviews(id) ON DELETE CASCADE,
    CONSTRAINT chk_shop_review_image_position CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT chk_shop_review_image_mime CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT chk_shop_review_image_size CHECK (byte_size > 0 AND byte_size <= 5242880),
    CONSTRAINT chk_shop_review_image_width CHECK (width > 0 AND width <= 20000000),
    CONSTRAINT chk_shop_review_image_height CHECK (height > 0 AND height <= 20000000),
    CONSTRAINT chk_shop_review_image_pixels CHECK (CAST(width AS SIGNED) * height <= 20000000)
);

CREATE TABLE IF NOT EXISTS shop_review_likes (
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (review_id, user_id),
    INDEX idx_shop_review_like_user_time (user_id, created_at),
    CONSTRAINT fk_shop_review_like_review FOREIGN KEY (review_id)
        REFERENCES shop_product_reviews(id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_review_like_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS shop_review_moderation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    operator_user_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_review_moderation_review_time (review_id, created_at, id),
    INDEX idx_shop_review_moderation_operator_time (operator_user_id, created_at, id),
    CONSTRAINT fk_shop_review_moderation_review FOREIGN KEY (review_id)
        REFERENCES shop_product_reviews(id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_review_moderation_operator FOREIGN KEY (operator_user_id)
        REFERENCES users(id),
    CONSTRAINT chk_shop_review_moderation_action CHECK (action IN ('HIDE','RESTORE')),
    CONSTRAINT chk_shop_review_moderation_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 500)
);

