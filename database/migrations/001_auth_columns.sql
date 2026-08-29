USE vcampus;

-- 仅当你曾经执行过不含以下两个字段的旧版 schema.sql 时运行本迁移。
-- 全新数据库已经由最新 schema.sql 创建这些字段，不要重复执行本文件。
ALTER TABLE users
    ADD COLUMN force_password_change BOOLEAN NOT NULL DEFAULT TRUE AFTER enabled;

ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMP NULL AFTER force_password_change;
