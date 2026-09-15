package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFeeMigrationTest {
    @Test void migrationAndFreshSchemaDefineFees() throws Exception {
        String migration = Files.readString(Path.of("..", "database", "migrations", "022_library_fees.sql"));
        String schema = Files.readString(Path.of("..", "database", "schema.sql"));
        for (String sql : List.of(migration, schema)) {
            assertTrue(sql.contains("replacement_price DECIMAL(10,2)"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS library_fee_policy"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS library_fee_bills"));
            assertTrue(sql.contains("'PENDING', 'PAID', 'WAIVED', 'REFUNDED'"));
            assertTrue(sql.contains("'LIBRARY_FEE', 'LIBRARY_REFUND'"));
            assertTrue(sql.contains("'LIBRARY_FEE_CREATED'"));
            assertTrue(sql.contains("'LIBRARY_FEES'"));
        }
        assertTrue(migration.contains("VALUES (1, 1, 0.50, 20.00, 0.2000, 0.5000, 1.0000)"));
    }

    @Test void seedSuppliesDistinctReferencePrices() throws Exception {
        String seed = Files.readString(Path.of("..", "database", "seed.sql"));
        assertTrue(seed.contains("replacement_price"));
        assertTrue(seed.contains("49.00"));
        assertTrue(seed.contains("68.00"));
        assertTrue(seed.contains("89.00"));
    }
}
