USE vcampus;

CREATE TABLE IF NOT EXISTS forum_sections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_forum_section_enabled_sort (enabled, sort_order, id),
    CONSTRAINT fk_forum_section_creator
        FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS forum_posts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    view_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_commented_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL,
    INDEX idx_forum_post_feed
        (section_id, status, pinned, last_commented_at, created_at),
    INDEX idx_forum_post_author (author_user_id, created_at),
    CONSTRAINT fk_forum_post_section FOREIGN KEY (section_id) REFERENCES forum_sections(id),
    CONSTRAINT fk_forum_post_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_post_status CHECK (status IN ('NORMAL', 'DELETED', 'HIDDEN')),
    CONSTRAINT chk_forum_post_counts CHECK (view_count >= 0 AND comment_count >= 0)
);

CREATE TABLE IF NOT EXISTS forum_comments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    INDEX idx_forum_comment_timeline (post_id, status, created_at, id),
    INDEX idx_forum_comment_author (author_user_id, created_at),
    CONSTRAINT fk_forum_comment_post FOREIGN KEY (post_id) REFERENCES forum_posts(id),
    CONSTRAINT fk_forum_comment_author FOREIGN KEY (author_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_comment_status CHECK (status IN ('NORMAL', 'DELETED', 'HIDDEN'))
);

CREATE TABLE IF NOT EXISTS forum_moderation_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operator_user_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_forum_moderation_target (target_type, target_id, created_at),
    INDEX idx_forum_moderation_operator (operator_user_id, created_at),
    CONSTRAINT fk_forum_moderation_operator
        FOREIGN KEY (operator_user_id) REFERENCES users(id),
    CONSTRAINT chk_forum_moderation_target
        CHECK (target_type IN ('SECTION', 'POST', 'COMMENT'))
);
