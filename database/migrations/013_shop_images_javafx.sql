USE vcampus;

SET @vcampus_shop_ddl = IF(
    EXISTS(
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'shop_products'
           AND column_name = 'category'
    ),
    'SELECT 1',
    'ALTER TABLE shop_products ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT ''OTHER'' AFTER description'
);
PREPARE vcampus_shop_upgrade FROM @vcampus_shop_ddl;
EXECUTE vcampus_shop_upgrade;
DEALLOCATE PREPARE vcampus_shop_upgrade;

UPDATE shop_products
   SET category = 'OTHER'
 WHERE category IS NULL;

SET @vcampus_shop_ddl = IF(
    EXISTS(
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'shop_products'
           AND column_name = 'category'
           AND is_nullable = 'YES'
    ),
    'ALTER TABLE shop_products MODIFY COLUMN category VARCHAR(32) NOT NULL DEFAULT ''OTHER''',
    'SELECT 1'
);
PREPARE vcampus_shop_upgrade FROM @vcampus_shop_ddl;
EXECUTE vcampus_shop_upgrade;
DEALLOCATE PREPARE vcampus_shop_upgrade;

CREATE TABLE IF NOT EXISTS shop_product_images (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    storage_key VARCHAR(160) NOT NULL UNIQUE,
    thumbnail_storage_key VARCHAR(160) NOT NULL UNIQUE,
    mime_type VARCHAR(32) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    is_cover BOOLEAN NOT NULL DEFAULT FALSE,
    cover_product_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN is_cover THEN product_id ELSE NULL END) STORED,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_image_product_order (product_id, sort_order),
    UNIQUE KEY uk_shop_image_cover_product (cover_product_id),
    INDEX idx_shop_image_product_cover (product_id, is_cover),
    CONSTRAINT fk_shop_image_product FOREIGN KEY (product_id)
        REFERENCES shop_products(id) ON DELETE CASCADE,
    CONSTRAINT chk_shop_image_size CHECK (byte_size > 0),
    CONSTRAINT chk_shop_image_order CHECK (sort_order >= 0)
);
