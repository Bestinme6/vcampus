CREATE TABLE IF NOT EXISTS forum_post_images (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    position TINYINT NOT NULL,
    storage_key VARCHAR(160) NOT NULL UNIQUE,
    thumbnail_storage_key VARCHAR(160) NOT NULL UNIQUE,
    mime_type VARCHAR(32) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_forum_post_image_position (post_id, position),
    INDEX idx_forum_post_image_post (post_id, position),
    CONSTRAINT fk_forum_post_image_post FOREIGN KEY (post_id) REFERENCES forum_posts(id),
    CONSTRAINT chk_forum_post_image_position CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT chk_forum_post_image_size CHECK (byte_size > 0 AND byte_size <= 5242880),
    CONSTRAINT chk_forum_post_image_dimensions CHECK (width > 0 AND height > 0)
);
