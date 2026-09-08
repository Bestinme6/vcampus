package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumMigrationTest {
    @Test void latestSchemaIncludesGuardedForumUpgradeAndReplyNotification() throws Exception {
        String sql = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String name : new String[]{"forum_post_likes", "forum_post_bookmarks"}) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + name));
        }
        for (String column : new String[]{"reply_to_comment_id", "is_announcement", "announced_at"}) {
            assertTrue(sql.contains("column_name='" + column + "'"));
            assertTrue(sql.contains("ADD COLUMN " + column));
        }
        assertTrue(sql.contains("information_schema.table_constraints"));
        assertTrue(sql.contains("information_schema.statistics"));
        int refresh = sql.indexOf("ALTER TABLE notifications");
        assertTrue(sql.substring(0, refresh).contains("'FORUM_COMMENT_REPLIED'"));
        assertTrue(sql.substring(refresh).contains("'FORUM_COMMENT_REPLIED'"));
    }
    @Test
    void migrationAndFreshSchemaDefineAllForumTables() throws Exception {
        Path migration = Path.of("..", "database", "migrations", "005_forum.sql");
        assertTrue(Files.exists(migration));

        String migrationSql = Files.readString(migration);
        String schemaSql = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String table : new String[]{
                "forum_sections", "forum_posts", "forum_comments",
                "forum_moderation_logs"}) {
            assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS " + table));
            assertTrue(schemaSql.contains("CREATE TABLE IF NOT EXISTS " + table));
        }
        assertTrue(migrationSql.contains("idx_forum_post_feed"));
        assertTrue(migrationSql.contains("idx_forum_comment_timeline"));
        assertTrue(migrationSql.contains("idx_forum_moderation_target"));
    }

    @Test
    void forumNotificationMigrationAndFreshSchemaAllowForumVocabulary() throws Exception {
        Path migration = Path.of("..", "database", "migrations", "006_forum_notifications.sql");
        assertTrue(Files.exists(migration));

        String migrationSql = Files.readString(migration);
        String schemaSql = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String literal : new String[]{
                "FORUM_POST_COMMENTED", "FORUM_POST_MODERATED", "FORUM_COMMENT_MODERATED",
                "FORUM", "FORUM_POST"}) {
            assertTrue(migrationSql.contains("'" + literal + "'"));
            assertTrue(schemaSql.contains("'" + literal + "'"));
        }
        assertTrue(migrationSql.contains("chk_notification_type"));
        assertTrue(migrationSql.contains("chk_notification_source"));
        assertTrue(migrationSql.contains("chk_notification_target"));
    }

    @Test
    void rerunningFreshSchemaRefreshesChecksOnAnExistingNotificationsTable()
            throws Exception {
        String schemaSql = Files.readString(Path.of("..", "database", "schema.sql"));
        int notificationTable = schemaSql.indexOf("CREATE TABLE IF NOT EXISTS notifications");
        int refresh = schemaSql.indexOf("ALTER TABLE notifications", notificationTable);

        assertTrue(notificationTable >= 0);
        assertTrue(refresh > notificationTable);
        String refreshSql = schemaSql.substring(refresh);
        assertTrue(refreshSql.contains("DROP CHECK chk_notification_type"));
        assertTrue(refreshSql.contains("DROP CHECK chk_notification_source"));
        assertTrue(refreshSql.contains("DROP CHECK chk_notification_target"));
        assertTrue(refreshSql.contains("ADD CONSTRAINT chk_notification_source CHECK"
                + " (source_module IN"));
        assertTrue(refreshSql.contains("'FORUM'"));
    }
    @Test void schemaDefinesForumReportsWithUniquenessAndQueueIndexes() throws Exception {
        String sql = Files.readString(Path.of("..", "database", "schema.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS forum_reports"));
        assertTrue(sql.contains("UNIQUE KEY uk_forum_reporter_target"
                + " (reporter_user_id, target_type, target_id)"));
        assertTrue(sql.contains("idx_forum_report_queue"));
        assertTrue(sql.contains("idx_forum_report_target"));
        for (String value : new String[]{"PENDING", "DISMISSED", "ACTIONED",
                "SPAM", "HARASSMENT", "MISLEADING", "PRIVACY", "INAPPROPRIATE", "OTHER"}) {
            assertTrue(sql.contains("'" + value + "'"), value);
        }
        assertTrue(Files.exists(Path.of("..", "database", "migrations", "016_forum_reports.sql")));
    }
    @Test void schemaDefinesOrderedForumPostImages()throws Exception{
        String sql=Files.readString(Path.of("..","database","schema.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS forum_post_images"));
        assertTrue(sql.contains("uk_forum_post_image_position (post_id, position)"));
        assertTrue(sql.contains("position BETWEEN 0 AND 2"));
        assertTrue(Files.exists(Path.of("..","database","migrations","017_forum_post_images.sql")));
    }
}
