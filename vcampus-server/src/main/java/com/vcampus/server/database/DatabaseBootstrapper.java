package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Course-demo reset policy: a changed schema/seed clears only the application's tables. */
public final class DatabaseBootstrapper {
    private static final String STATE = "vcampus_schema_state";
    private static final Pattern URL = Pattern.compile(
            "^jdbc:mysql://[^/?#]+/([A-Za-z0-9_]+)(?:\\?[^#]*)?$");
    private final DatabaseConfig config;

    public DatabaseBootstrapper(DatabaseConfig config) {
        this.config = config;
    }

    public boolean initialize() throws IOException, SQLException {
        return initialize(DatabaseScripts.bundled());
    }

    boolean initialize(DatabaseScripts scripts) throws SQLException {
        String catalog = targetCatalog(config.url());
        try (Connection connection = new ConnectionFactory(config).openConnection()) {
            if (!catalog.equals(connection.getCatalog())) {
                throw new SQLException("JDBC 目标库与当前数据库不一致，拒绝初始化");
            }
            String lock = "vcampus_bootstrap:" + catalog.toLowerCase(Locale.ROOT);
            if (!"1".equals(scalar(connection, "SELECT GET_LOCK(?, 30)", lock))) {
                throw new SQLException("等待数据库初始化锁超时，服务端未启动；请等待其他初始化进程结束");
            }
            try {
                return initializeLocked(connection, scripts);
            } finally {
                // Closing this dedicated connection also releases the lock after a connection failure.
                scalar(connection, "SELECT RELEASE_LOCK(?)", lock);
            }
        } catch (SQLException exception) {
            // Do not log JDBC URLs, passwords, or failing SQL values.
            throw new SQLException("数据库初始化失败，服务端未启动。请检查 MySQL 连接和目标库的建表/删表/读写权限。"
                    + " SQLState=" + exception.getSQLState() + ", code=" + exception.getErrorCode()
                    + "; " + safeMessage(exception), exception.getSQLState(), exception.getErrorCode(), exception);
        }
    }

    static String targetCatalog(String url) {
        var matcher = URL.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("自动初始化需要 jdbc:mysql://主机:端口/数据库名 格式的单库地址");
        }
        String catalog = matcher.group(1);
        if (catalog.length() > 46 || Set.of("mysql", "sys", "information_schema", "performance_schema")
                .contains(catalog.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("拒绝对系统数据库或无效数据库名执行初始化");
        }
        return catalog;
    }

    private boolean initializeLocked(Connection connection, DatabaseScripts scripts) throws SQLException {
        Set<String> existing = existingTables(connection);
        String fingerprint = null;
        if (existing.contains(STATE)) {
            try {
                fingerprint = scalar(connection, "SELECT script_fingerprint FROM " + STATE + " WHERE singleton_id=1");
            } catch (SQLException exception) {
                if (exception.getErrorCode() != 1054) { // Only an old/malformed marker column is recoverable.
                    throw exception;
                }
            }
        }
        if (scripts.fingerprint().equals(fingerprint) && existing.containsAll(scripts.tables())) {
            System.out.println("VCampus 数据库版本一致，保留现有数据。");
            return false;
        }
        rejectExternalReferences(connection, scripts.tables());
        String mode = scalar(connection, "SELECT @@SESSION.sql_mode");
        if (mode.contains("NO_BACKSLASH_ESCAPES") || mode.contains("ANSI_QUOTES")) {
            throw new SQLException("请移除 MySQL 会话的 NO_BACKSLASH_ESCAPES/ANSI_QUOTES 模式后初始化");
        }
        System.out.println("VCampus 数据库版本变化或未初始化：正在清空并重建 VCampus 表。");
        String foreignKeys = scalar(connection, "SELECT @@SESSION.FOREIGN_KEY_CHECKS");
        try {
            execute(connection, "SET FOREIGN_KEY_CHECKS=0");
            // Invalidate success before dropping any business table, even when an early DROP fails.
            execute(connection, "DROP TABLE IF EXISTS `" + STATE + "`");
            for (String table : scripts.tables().stream().sorted().toList()) {
                if (!table.equals(STATE)) {
                    execute(connection, "DROP TABLE IF EXISTS `" + table + "`");
                }
            }
        } finally {
            execute(connection, "SET FOREIGN_KEY_CHECKS=" + ("0".equals(foreignKeys) ? "0" : "1"));
        }
        executeScript(connection, scripts.schema(), "schema.sql");
        executeScript(connection, scripts.seed(), "seed.sql");
        if (!existingTables(connection).containsAll(scripts.tables())
                || Integer.parseInt(scalar(connection, "SELECT COUNT(*) FROM roles")) < 1) {
            throw new SQLException("重建后的表或基础角色数据不完整");
        }
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + STATE + " (singleton_id,script_fingerprint) VALUES (1,?)")) {
            statement.setString(1, scripts.fingerprint());
            statement.executeUpdate();
        }
        if (!scripts.fingerprint().equals(scalar(connection,
                "SELECT script_fingerprint FROM " + STATE + " WHERE singleton_id=1"))) {
            throw new SQLException("数据库初始化版本记录校验失败");
        }
        System.out.println("VCampus 数据库重建完成。旧账号已清空，请运行 AdminBootstrapMain 创建管理员。");
        return true;
    }

    private static void rejectExternalReferences(Connection connection, Set<String> tables) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT TABLE_SCHEMA,TABLE_NAME,REFERENCED_TABLE_NAME"
                + " FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA=DATABASE()")) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (tables.contains(rows.getString(3)) && (!connection.getCatalog().equals(rows.getString(1))
                            || !tables.contains(rows.getString(2)))) {
                        throw new SQLException("其他表引用了 VCampus 表，拒绝自动重建；请先解除外部引用");
                    }
                }
            }
        }
    }

    private static Set<String> existingTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (var statement = connection.prepareStatement(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private static void executeScript(Connection connection, List<String> statements, String name) throws SQLException {
        for (int i = 0; i < statements.size(); i++) {
            try {
                execute(connection, statements.get(i));
            } catch (SQLException exception) {
                throw new SQLException(name + " 第 " + (i + 1) + " 条语句执行失败",
                        exception.getSQLState(), exception.getErrorCode(), exception);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql, String... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static String safeMessage(SQLException exception) {
        // Our own diagnostics have no server-provided values; vendor errors are identified by code only.
        return exception.getMessage() != null && (exception.getMessage().startsWith("schema.sql")
                || exception.getMessage().startsWith("seed.sql") || exception.getSQLState() == null)
                ? exception.getMessage() : "MySQL 操作未完成";
    }
}
