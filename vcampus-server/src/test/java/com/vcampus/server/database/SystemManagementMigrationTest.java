package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemManagementMigrationTest {
    private static final Path MIGRATION = Path.of("..", "database", "migrations", "018_system_management.sql");

    @Test
    void migrationCreatesTwelveEmptyPeriodRowsWithoutChangingTerm() throws Exception {
        assertTrue(Files.exists(MIGRATION));
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:system_management_migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO users(id) VALUES (1)");
                statement.execute("""
                        CREATE TABLE academic_terms(
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          academic_year VARCHAR(9) NOT NULL,
                          term_number TINYINT NOT NULL,
                          term_name VARCHAR(100) NOT NULL,
                          start_date DATE NOT NULL,
                          end_date DATE NOT NULL,
                          selection_start TIMESTAMP NOT NULL,
                          selection_end TIMESTAMP NOT NULL,
                          drop_deadline TIMESTAMP NOT NULL,
                          status VARCHAR(16) NOT NULL,
                          UNIQUE(academic_year, term_number))
                        """);
                statement.execute("""
                        INSERT INTO academic_terms(id,academic_year,term_number,term_name,start_date,end_date,
                          selection_start,selection_end,drop_deadline,status)
                        VALUES (1,'2026-2027',1,'管理员配置','2026-09-01','2027-01-15',
                          '2026-08-20 00:00:00','2026-09-10 23:59:59','2026-09-20 23:59:59','SELECTION')
                        """);
                for (String sql : SqlScriptParser.parse(Files.readString(MIGRATION))) {
                    statement.execute(sql);
                }
                for (String sql : SqlScriptParser.parse(Files.readString(MIGRATION))) {
                    statement.execute(sql);
                }
                try (var rows = statement.executeQuery("SELECT COUNT(*), COUNT(start_time), COUNT(end_time) FROM class_period_times")) {
                    rows.next();
                    assertEquals(12, rows.getInt(1));
                    assertEquals(0, rows.getInt(2));
                    assertEquals(0, rows.getInt(3));
                }
                try (var rows = statement.executeQuery("SELECT term_name FROM academic_terms WHERE id=1")) {
                    rows.next();
                    assertEquals("管理员配置", rows.getString(1));
                }
            }
        }
    }
}
