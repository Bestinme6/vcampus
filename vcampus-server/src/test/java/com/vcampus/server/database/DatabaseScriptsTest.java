package com.vcampus.server.database;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseScriptsTest {
    @Test
    void splitsMysqlLiteralsAndCommentsWithoutLosingEmbeddedSql() {
        assertEquals(List.of("SELECT 'a;b', \"c;d\", `e;f`", "SELECT 'it''s;ok'", "SELECT 2"),
                SqlScriptParser.parse("-- ignored ;\nSELECT 'a;b', \"c;d\", `e;f`;"
                        + "/* ignored ; */ SELECT 'it''s;ok'; # ignored ;\n SELECT 2; -- end"));
        assertEquals(List.of("SET @sql='SELECT \\'a;b\\''", "PREPARE p FROM @sql", "EXECUTE p"),
                SqlScriptParser.parse("SET @sql='SELECT \\'a;b\\''; PREPARE p FROM @sql; EXECUTE p;"));
        assertEquals("SELECT 1 + 2", SqlScriptParser.parse("SELECT 1/*comment*/ + 2").getFirst().replaceAll("\\s+", " "));
        assertEquals(List.of(), SqlScriptParser.parse("; -- x\n /* y */ ;"));
    }

    @Test
    void rejectsIncompleteOrUnsupportedSyntaxBeforeExecution() {
        for (String sql : List.of("SELECT 'a", "SELECT `a", "/* open", "DELIMITER $$", "/*! SELECT 1 */")) {
            assertThrows(IllegalArgumentException.class, () -> SqlScriptParser.parse(sql));
        }
    }

    @Test
    void bundledScriptsAreCompleteAndCannotSwitchCatalog() throws Exception {
        var scripts = DatabaseScripts.bundled();
        assertTrue(scripts.tables().size() > 35);
        assertTrue(scripts.tables().containsAll(List.of("users", "roles", "vcampus_schema_state", "class_schedules")));
        assertTrue(scripts.seed().size() > 20);
        assertTrue(scripts.schema().stream().anyMatch(sql -> sql.startsWith("PREPARE")));
        assertFalse(scripts.schema().stream().anyMatch(sql -> sql.startsWith("CREATE DATABASE") || sql.startsWith("USE ")));
        assertFalse(scripts.seed().stream().anyMatch(sql -> sql.startsWith("USE ")));
    }

    @Test
    void fingerprintsArePortableButChangeWhenEitherScriptChanges() throws Exception {
        String schema = resource("schema.sql").replace("\r\n", "\n");
        String seed = resource("seed.sql").replace("\r\n", "\n");
        String fingerprint = DatabaseScripts.from(schema, seed).fingerprint();
        assertEquals(fingerprint, DatabaseScripts.from(schema.replace("\n", "\r\n"), seed).fingerprint());
        assertNotEquals(fingerprint, DatabaseScripts.from(schema + "\n-- new version", seed).fingerprint());
        assertNotEquals(fingerprint, DatabaseScripts.from(schema, seed + "\n-- new version").fingerprint());
        assertThrows(IllegalArgumentException.class, () -> DatabaseScripts.from(schema + "\nUSE mysql;", seed));
        assertThrows(IllegalArgumentException.class, () -> DatabaseScripts.from("SELECT 1;", seed));
    }

    @Test
    void refusesAmbiguousAndSystemCatalogs() {
        for (String url : List.of("jdbc:mysql://localhost/mysql", "jdbc:mysql://localhost/SYS",
                "jdbc:mysql://localhost/information_schema", "jdbc:mysql://localhost/performance_schema",
                "jdbc:mysql://localhost/", "jdbc:mysql://localhost/vcampus/extra", "jdbc:h2:mem:test",
                "jdbc:mysql://localhost/vcampus%2Fother")) {
            assertThrows(IllegalArgumentException.class, () -> DatabaseBootstrapper.targetCatalog(url));
        }
        assertEquals("vcampus", DatabaseBootstrapper.targetCatalog("jdbc:mysql://localhost:3306/vcampus?useUnicode=true"));
        assertEquals("vcampus_test_123", DatabaseBootstrapper.targetCatalog("jdbc:mysql://localhost/vcampus_test_123"));
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/database/" + name)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
