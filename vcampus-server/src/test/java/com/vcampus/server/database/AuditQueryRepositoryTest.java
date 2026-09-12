package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditQueryRepositoryTest {
    private ConnectionFactory connections;
    private AuditQueryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        connections = new ConnectionFactory(new DatabaseConfig(url, "sa", ""));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),display_name VARCHAR(100))");
            statement.execute("CREATE TABLE audit_logs(id BIGINT PRIMARY KEY,user_id BIGINT,action_code VARCHAR(128),result_code VARCHAR(32),client_address VARCHAR(128),created_at TIMESTAMP,FOREIGN KEY(user_id) REFERENCES users(id))");
            statement.execute("INSERT INTO users VALUES(1,'admin','系统管理员'),(2,'teacher','张老师')");
            statement.execute("INSERT INTO audit_logs VALUES(1,1,'system.config.updateTerm','SUCCESS','127.0.0.1','2026-09-12 00:00:00')");
            statement.execute("INSERT INTO audit_logs VALUES(2,1,'system.config.updatePeriods','SUCCESS','127.0.0.1','2026-09-12 23:59:59')");
            statement.execute("INSERT INTO audit_logs VALUES(3,2,'auth.login','FAILED','10.0.0.2','2026-09-13 00:00:00')");
            statement.execute("INSERT INTO audit_logs VALUES(4,NULL,'auth.login','FAILED',NULL,'2026-09-11 12:00:00')");
        }
        repository = new AuditQueryRepository(connections);
    }

    @Test
    void combinesFiltersWithInclusiveDateAndStableOrdering() throws Exception {
        var query = new AuditQueryStore.AuditQuery("ADMIN", "system.config.", "SUCCESS",
                LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-12"), 1, 20);
        var page = repository.search(query);
        assertEquals(2, page.total());
        assertEquals(2, page.rows().getFirst().id());
        assertEquals(1, page.rows().getLast().id());
    }

    @Test
    void paginatesAndPreservesAnonymousActor() throws Exception {
        var query = new AuditQueryStore.AuditQuery("", "auth.", "FAILED", null, null, 2, 1);
        var page = repository.search(query);
        assertEquals(2, page.total());
        assertEquals(2, page.page());
        assertNull(page.rows().getFirst().userId());
        assertEquals("", page.rows().getFirst().clientAddress());
    }

    @Test
    void treatsLikeMetacharactersAsLiteralFilterText() throws Exception {
        try (var connection = connections.openConnection();
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO users VALUES(3,'ops_100%','精确用户'),(4,'opsX100Y','通配用户')");
            statement.execute("INSERT INTO audit_logs VALUES(5,3,'system.config.update_Percent%','SUCCESS','',CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO audit_logs VALUES(6,4,'system.config.updateXPercentZ','SUCCESS','',CURRENT_TIMESTAMP)");
        }

        var page = repository.search(new AuditQueryStore.AuditQuery(
                "ops_100%", "system.config.update_", "SUCCESS", null, null, 1, 20));

        assertEquals(1, page.total());
        assertEquals(5, page.rows().getFirst().id());
    }

    @Test
    void campusBoundaryIsIndependentOfJvmDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Instant expected = LocalDate.parse("2026-09-12")
                    .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            assertEquals(expected, AuditQueryRepository.campusBoundary(LocalDate.parse("2026-09-12")));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void executesShanghaiDayFilterWhenJvmDefaultZoneDiffers() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Calendar shanghai = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            try (var connection = connections.openConnection();
                 var statement = connection.prepareStatement(
                         "INSERT INTO audit_logs VALUES(?,1,'zone.test','SUCCESS','',?)")) {
                List<Instant> instants = List.of(
                        Instant.parse("2026-09-11T15:59:59Z"),
                        Instant.parse("2026-09-11T16:00:00Z"),
                        Instant.parse("2026-09-12T15:59:59Z"),
                        Instant.parse("2026-09-12T16:00:00Z"));
                for (int index = 0; index < instants.size(); index++) {
                    statement.setLong(1, 7 + index);
                    statement.setTimestamp(2, Timestamp.from(instants.get(index)), shanghai);
                    statement.executeUpdate();
                }
            }

            var page = repository.search(new AuditQueryStore.AuditQuery("", "zone.", "SUCCESS",
                    LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-12"), 1, 20));

            assertEquals(2, page.total());
            assertEquals(List.of(9L, 8L), page.rows().stream().map(row -> row.id()).toList());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void bindsAuditInstantWithExplicitShanghaiCalendar() throws Exception {
        AtomicReference<Calendar> calendar = new AtomicReference<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setTimestamp") && args.length == 3) {
                        calendar.set((Calendar) args[2]);
                    }
                    return defaultValue(method.getReturnType());
                });

        AuditQueryRepository.bindInstant(statement, 1, Instant.parse("2026-09-11T16:00:00Z"));

        assertEquals("Asia/Shanghai", calendar.get().getTimeZone().getID());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
