package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void usabilityMigrationAndFreshSchemaDefineAutomaticLibraryCodes() throws Exception {
        Path migration = Path.of("..", "database", "migrations", "010_library_usability.sql");
        assertTrue(Files.exists(migration), "010 migration must exist for upgraded databases");

        String migrationSql = Files.readString(migration);
        String schemaSql = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String contract : new String[]{
                "library_code_sequences", "catalog_code", "BOOK_CATALOG", "COPY_BARCODE"}) {
            assertTrue(migrationSql.contains(contract), "migration missing " + contract);
            assertTrue(schemaSql.contains(contract), "fresh schema missing " + contract);
        }
        assertTrue(schemaSql.contains("isbn VARCHAR(20) NULL UNIQUE"));
    }

    @Test
    void usabilityMigrationBackfillsDenseCatalogCodesInsteadOfReusingGappedIds()
            throws Exception {
        String migrationSql = Files.readString(Path.of(
                "..", "database", "migrations", "010_library_usability.sql"));

        assertTrue(migrationSql.contains("ROW_NUMBER() OVER (ORDER BY id)"));
        assertFalse(migrationSql.contains("LPAD(id, 9, '0')"));
    }

    @Test
    void damagedReturnMigrationAndFreshSchemaAllowDamagedCondition() throws Exception {
        Path migration = Path.of(
                "..", "database", "migrations", "011_library_damaged_returns.sql");
        assertTrue(Files.exists(migration), "011 migration must exist for upgraded databases");

        String migrationSql = Files.readString(migration);
        String schemaSql = Files.readString(Path.of("..", "database", "schema.sql"));
        assertTrue(migrationSql.contains("'NORMAL', 'LOST', 'DAMAGED'"));
        assertTrue(schemaSql.contains("'NORMAL', 'LOST', 'DAMAGED'"));
    }
}
