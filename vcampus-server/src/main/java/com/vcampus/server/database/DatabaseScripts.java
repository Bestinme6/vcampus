package com.vcampus.server.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates all bundled resources before any destructive statement can run. */
record DatabaseScripts(List<String> schema, List<String> seed, Set<String> tables, String fingerprint) {
    private static final Pattern TABLE = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z][a-z0-9_]*)\\s*\\(.*");

    static DatabaseScripts bundled() throws IOException {
        return from(read("schema.sql"), read("seed.sql"));
    }

    static DatabaseScripts from(String schema, String seed) {
        schema = normalize(schema);
        seed = normalize(seed);
        List<String> ddl = SqlScriptParser.parse(schema);
        List<String> dml = SqlScriptParser.parse(seed);
        Set<String> tables = new LinkedHashSet<>();
        for (String statement : ddl) {
            var matcher = TABLE.matcher(statement);
            if (matcher.matches()) {
                tables.add(matcher.group(1));
            } else if (statement.matches("(?is)^CREATE\\s+TABLE\\b.*")) {
                throw new IllegalArgumentException("无法识别初始化脚本中的受控表名");
            }
        }
        if (!tables.containsAll(Set.of("vcampus_schema_state", "users", "roles"))) {
            throw new IllegalArgumentException("初始化脚本缺少 VCampus 核心表");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(schema.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(seed.getBytes(StandardCharsets.UTF_8));
            return new DatabaseScripts(executable(ddl), executable(dml), Set.copyOf(tables),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> executable(List<String> statements) {
        // The scripts remain usable in Workbench. JDBC must never switch away from its validated catalog.
        return statements.stream().filter(sql -> !sql.matches("(?is)^USE\\s+vcampus$")
                && !sql.matches("(?is)^CREATE\\s+DATABASE\\s+IF\\s+NOT\\s+EXISTS\\s+vcampus\\s+.*"))
                .peek(sql -> {
                    if (sql.matches("(?is)^(USE|CREATE\\s+DATABASE|DROP\\s+DATABASE)\\b.*")) {
                        throw new IllegalArgumentException("初始化脚本包含不支持的数据库切换语句");
                    }
                }).toList();
    }

    private static String normalize(String sql) {
        return sql.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
    }

    private static String read(String name) throws IOException {
        try (var stream = DatabaseScripts.class.getResourceAsStream("/database/" + name)) {
            if (stream == null) {
                throw new IOException("服务端缺少 database/" + name + "；请重新进行 Maven 构建或更新 Eclipse Maven 工程");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
