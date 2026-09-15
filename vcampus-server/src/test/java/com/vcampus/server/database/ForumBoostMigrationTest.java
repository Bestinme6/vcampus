package com.vcampus.server.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumBoostMigrationTest {
    @Test
    void migrationAndFreshSchemaCreateTheSameConstrainedBoostTable() throws Exception {
        verifyBoostDdl(script("migrations/023_forum_bank_boost.sql"));
        verifyBoostDdl(script("schema.sql"));
    }

    @Test
    void migrationExtendsBankAndNotificationVocabulary() throws Exception {
        for (String sql : List.of(
                script("migrations/023_forum_bank_boost.sql"), script("schema.sql"))) {
            assertTrue(sql.contains("'FORUM_BOOST'"));
            assertTrue(sql.contains("'BANK_FORUM_BOOST_PAID'"));
        }
    }

    private void verifyBoostDdl(String script) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE forum_posts(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE bank_ledger_entries(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO users VALUES(2)");
            statement.execute("INSERT INTO forum_posts VALUES(11)");
            statement.execute("INSERT INTO bank_ledger_entries VALUES(31),(32),(33)");

            List<String> boostStatements = SqlScriptParser.parse(script).stream()
                    .filter(sql -> sql.startsWith("CREATE TABLE IF NOT EXISTS forum_post_boosts"))
                    .toList();
            assertEquals(1, boostStatements.size());
            for (String sql : boostStatements) {
                statement.execute(sql);
            }
            Set<String> indexNames = new HashSet<>();
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                    null, null, "FORUM_POST_BOOSTS", false, false)) {
                while (indexes.next()) {
                    indexNames.add(indexes.getString("INDEX_NAME"));
                }
            }
            assertTrue(indexNames.contains("IDX_FORUM_POST_BOOST_ACTIVE"));
            assertTrue(indexNames.contains("IDX_FORUM_POST_BOOST_SPONSOR_DAY"));

            statement.execute("INSERT INTO forum_post_boosts(" +
                    "post_id,sponsor_user_id,tier,amount,initial_heat,operation_id," +
                    "bank_ledger_id,created_at,expires_at) VALUES(" +
                    "11,2,'FIVE',5.00,50,'550e8400-e29b-41d4-a716-446655440000',31," +
                    "TIMESTAMP '2026-09-15 02:00:00',TIMESTAMP '2026-09-17 02:00:00')");

            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO forum_post_boosts(" +
                            "post_id,sponsor_user_id,tier,amount,initial_heat,operation_id," +
                            "bank_ledger_id,created_at,expires_at) VALUES(" +
                            "11,2,'FIVE',10.00,50,'550e8400-e29b-41d4-a716-446655440001',32," +
                            "CURRENT_TIMESTAMP,DATEADD(HOUR,48,CURRENT_TIMESTAMP))"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO forum_post_boosts(" +
                            "post_id,sponsor_user_id,tier,amount,initial_heat,operation_id," +
                            "bank_ledger_id,created_at,expires_at) VALUES(" +
                            "11,2,'FIVE',5.00,50,'550e8400-e29b-41d4-a716-446655440000',32," +
                            "CURRENT_TIMESTAMP,DATEADD(HOUR,48,CURRENT_TIMESTAMP))"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO forum_post_boosts(" +
                            "post_id,sponsor_user_id,tier,amount,initial_heat,operation_id," +
                            "bank_ledger_id,created_at,expires_at) VALUES(" +
                            "11,2,'TEN',10.00,100,'550e8400-e29b-41d4-a716-446655440002',31," +
                            "CURRENT_TIMESTAMP,DATEADD(HOUR,48,CURRENT_TIMESTAMP))"));
        }
    }

    private String script(String relativePath) throws Exception {
        return Files.readString(Path.of("..", "database").resolve(relativePath));
    }
}
