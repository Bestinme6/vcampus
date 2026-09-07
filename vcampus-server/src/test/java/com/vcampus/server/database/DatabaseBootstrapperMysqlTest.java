package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in; creates/drops only a new, random test catalog, never the configured application catalog. */
@EnabledIfEnvironmentVariable(named = "VCAMPUS_BOOTSTRAP_MYSQL_TEST", matches = "true")
class DatabaseBootstrapperMysqlTest {
    private DatabaseConfig admin;
    private DatabaseConfig target;
    private String catalog;
    private boolean created;
    private Connection connection;

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        admin = DatabaseConfig.fromEnvironment();
        DatabaseBootstrapper.targetCatalog(admin.url());
        catalog = "vcampus_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var owner = new ConnectionFactory(admin).openConnection(); var sql = owner.createStatement()) {
            sql.execute("CREATE DATABASE `" + catalog + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        String url = admin.url().replaceFirst("^(jdbc:mysql://[^/]+/)[^?]+", "$1" + catalog);
        // Do not proceed if URL substitution failed or selected the original database.
        assertEquals(catalog, DatabaseBootstrapper.targetCatalog(url));
        target = new DatabaseConfig(url, admin.username(), admin.password());
        connection = new ConnectionFactory(target).openConnection();
    }

    @AfterEach
    void removeOnlyOwnedTestDatabase() throws Exception {
        if (connection != null) connection.close();
        if (created && catalog != null && catalog.matches("vcampus_test_[a-f0-9]{32}")) {
            try (var owner = new ConnectionFactory(admin).openConnection(); var sql = owner.createStatement()) {
                sql.execute("DROP DATABASE `" + catalog + "`");
            }
        }
    }

    @Test
    void oldDatabaseRebuildsOnceAndRepeatStartupKeepsData() throws Exception {
        execute("CREATE TABLE users (legacy_id INT)");
        execute("INSERT INTO users VALUES (91)");
        execute("CREATE TABLE unrelated_notes (id INT)");
        execute("INSERT INTO unrelated_notes VALUES (7)");
        var bootstrap = new DatabaseBootstrapper(target);
        assertTrue(bootstrap.initialize());
        assertEquals("0", scalar("SELECT COUNT(*) FROM users"));
        assertEquals("9", scalar("SELECT COUNT(*) FROM roles"));
        assertEquals("7", scalar("SELECT id FROM unrelated_notes"));
        execute("INSERT INTO users (username,password_hash,password_salt,display_name) VALUES ('test-user','hash','salt','test')");
        assertFalse(bootstrap.initialize());
        assertEquals("1", scalar("SELECT COUNT(*) FROM users"));
        assertEquals(DatabaseScripts.bundled().fingerprint(), scalar("SELECT script_fingerprint FROM vcampus_schema_state"));
    }

    @Test
    void changedVersionAndMissingTableTriggerRebuild() throws Exception {
        var bootstrap = new DatabaseBootstrapper(target);
        bootstrap.initialize();
        execute("UPDATE vcampus_schema_state SET script_fingerprint=REPEAT('0',64)");
        assertTrue(bootstrap.initialize());
        execute("DROP TABLE forum_post_bookmarks");
        assertTrue(bootstrap.initialize());
        assertFalse(bootstrap.initialize());
    }

    @Test
    void failedSeedLeavesNoSuccessMarkerAndCanRetry() throws Exception {
        var bootstrap = new DatabaseBootstrapper(target);
        bootstrap.initialize();
        var original = DatabaseScripts.bundled();
        var brokenSeed = new ArrayList<>(original.seed());
        brokenSeed.add("INSERT INTO missing_table_for_failure_test VALUES (1)");
        var broken = new DatabaseScripts(original.schema(), brokenSeed, original.tables(), "0".repeat(64));
        var failure = assertThrows(SQLException.class, () -> bootstrap.initialize(broken));
        assertTrue(failure.getMessage().contains("seed.sql 第"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM vcampus_schema_state"));
        assertEquals("1", scalar("SELECT IS_FREE_LOCK('vcampus_bootstrap:" + catalog + "')"));
        assertTrue(bootstrap.initialize());
        assertFalse(bootstrap.initialize());
    }

    @Test
    void externalReferencePreventsAnyDestruction() throws Exception {
        var bootstrap = new DatabaseBootstrapper(target);
        bootstrap.initialize();
        execute("CREATE TABLE unrelated_audit (user_id BIGINT, FOREIGN KEY(user_id) REFERENCES users(id))");
        execute("UPDATE vcampus_schema_state SET script_fingerprint=REPEAT('0',64)");
        var failure = assertThrows(SQLException.class, bootstrap::initialize);
        assertTrue(failure.getMessage().contains("其他表引用"));
        assertEquals("9", scalar("SELECT COUNT(*) FROM roles"));
        assertEquals("0".repeat(64), scalar("SELECT script_fingerprint FROM vcampus_schema_state"));
    }

    @Test
    void concurrentStartupsOnlyRebuildOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); return new DatabaseBootstrapper(target).initialize(); });
            var second = executor.submit(() -> { gate.await(); return new DatabaseBootstrapper(target).initialize(); });
            gate.countDown();
            assertNotEquals(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
        }
        assertFalse(new DatabaseBootstrapper(target).initialize());
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    @Test
    void readOnlyAccountCanUseCurrentVersionButCannotResetOldVersion() throws Exception {
        new DatabaseBootstrapper(target).initialize();
        String username = "vc_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String password = UUID.randomUUID().toString();
        boolean userCreated = false;
        try {
            try (var sql = connection.prepareStatement("CREATE USER ?@'localhost' IDENTIFIED BY ?")) {
                sql.setString(1, username);
                sql.setString(2, password);
                sql.execute();
                userCreated = true;
            }
            execute("GRANT SELECT ON `" + catalog + "`.* TO '" + username + "'@'localhost'");
            var readOnly = new DatabaseBootstrapper(new DatabaseConfig(target.url(), username, password));
            assertFalse(readOnly.initialize());
            execute("UPDATE vcampus_schema_state SET script_fingerprint=REPEAT('0',64)");
            var failure = assertThrows(SQLException.class, readOnly::initialize);
            assertTrue(failure.getMessage().contains("建表/删表/读写权限"));
            assertEquals("9", scalar("SELECT COUNT(*) FROM roles"));
            assertEquals("1", scalar("SELECT IS_FREE_LOCK('vcampus_bootstrap:" + catalog + "')"));
        } finally {
            if (userCreated) execute("DROP USER '" + username + "'@'localhost'");
        }
    }

    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
