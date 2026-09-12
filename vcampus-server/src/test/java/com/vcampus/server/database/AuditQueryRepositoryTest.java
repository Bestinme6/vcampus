package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.UUID;

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
            assertEquals(Timestamp.from(expected), AuditQueryRepository.campusTimestamp(LocalDate.parse("2026-09-12")));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
