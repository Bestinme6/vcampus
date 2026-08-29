package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryMigrationTest {
    @Test
    void migrationDefinesLibraryTablesAndNotificationVocabulary() throws Exception {
        String sql = Files.readString(Path.of("..", "database", "migrations", "003_library.sql"));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS books"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS book_copies"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS library_loans"));
        assertTrue(sql.contains("uk_library_active_copy"));
        assertTrue(sql.contains("LIBRARY_DUE_SOON"));
        assertTrue(sql.contains("LIBRARY_OVERDUE"));
        assertTrue(sql.contains("LIBRARY_LOANS"));
    }

    @Test
    void receiptNotificationMigrationAndFreshSchemaStayAligned() throws Exception {
        Path migration = Path.of("..", "database", "migrations", "004_library_receipt_notifications.sql");
        assertTrue(Files.exists(migration), "004 migration must exist for upgraded databases");

        String migrationSql = Files.readString(migration);
        String schemaSql = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String type : new String[]{
                "LIBRARY_BORROWED", "LIBRARY_RENEWED", "LIBRARY_RETURNED", "LIBRARY_LOST"}) {
            assertTrue(migrationSql.contains(type), "migration missing " + type);
            assertTrue(schemaSql.contains(type), "fresh schema missing " + type);
        }
    }
}
