package com.vcampus.server.database;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemManagementRepositoryTest {
    private ConnectionFactory connections;
    private SystemManagementRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        connections = new ConnectionFactory(new DatabaseConfig(url, "sa", ""));
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO users VALUES(9)");
            statement.execute("""
                    CREATE TABLE academic_terms(id BIGINT PRIMARY KEY, academic_year VARCHAR(9), term_number INT,
                      term_name VARCHAR(100), start_date DATE, end_date DATE, selection_start TIMESTAMP,
                      selection_end TIMESTAMP, drop_deadline TIMESTAMP, status VARCHAR(16))
                    """);
            statement.execute("""
                    INSERT INTO academic_terms VALUES(1,'2026-2027',1,'第一学期','2026-09-01','2027-01-15',
                      '2026-08-20 00:00:00','2026-09-10 23:59:59','2026-09-20 23:59:59','SELECTION')
                    """);
            statement.execute("""
                    CREATE TABLE class_period_times(period_number INT PRIMARY KEY, start_time TIME, end_time TIME,
                      updated_by_user_id BIGINT, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      FOREIGN KEY(updated_by_user_id) REFERENCES users(id),
                      CHECK(period_number <> 7 OR start_time IS NULL))
                    """);
            for (int period = 1; period <= 12; period++) {
                statement.execute("INSERT INTO class_period_times(period_number) VALUES(" + period + ")");
            }
        }
        repository = new SystemManagementRepository(connections);
    }

    @Test
    void readsAllTermsAndTwelvePeriods() throws Exception {
        var snapshot = repository.configuration();
        assertEquals(1, snapshot.terms().size());
        assertEquals("第一学期", snapshot.terms().getFirst().termName());
        assertEquals(12, snapshot.periods().size());
        assertEquals(12, snapshot.periods().getLast().periodNumber());
    }

    @Test
    void updatesKnownTermButNotUnknownTerm() throws Exception {
        var changed = term(1, "秋季学期");
        assertTrue(repository.updateTerm(changed, 9));
        assertEquals("秋季学期", repository.configuration().terms().getFirst().termName());
        assertFalse(repository.updateTerm(term(99, "不存在"), 9));
    }

    @Test
    void periodFailureRollsBackRowsAlreadyUpdated() throws Exception {
        List<ClassPeriodTime> configured = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(number -> new ClassPeriodTime(number,
                        LocalTime.of(7, 0).plusMinutes(number * 50L),
                        LocalTime.of(7, 45).plusMinutes(number * 50L)))
                .toList();

        assertThrows(SQLException.class, () -> repository.updatePeriods(configured, 9));
        assertNull(repository.configuration().periods().getFirst().startTime());
    }

    @Test
    void postCommitConnectionCleanupCannotTurnSuccessIntoFailure() {
        AtomicBoolean committed = new AtomicBoolean();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeUpdate" -> 1;
                    case "close", "setTime", "setNull", "setLong", "setInt" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> {
                        if (Boolean.TRUE.equals(args[0])) throw new SQLException("forced cleanup failure");
                        yield null;
                    }
                    case "prepareStatement" -> statement;
                    case "commit" -> { committed.set(true); yield null; }
                    case "close", "rollback" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        SystemManagementRepository isolated = new SystemManagementRepository(() -> connection);
        List<ClassPeriodTime> periods = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(number -> new ClassPeriodTime(number, null, null)).toList();

        assertDoesNotThrow(() -> isolated.updatePeriods(periods, 9));
        assertTrue(committed.get());
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

    private SystemTermConfig term(long id, String name) {
        return new SystemTermConfig(id, "2026-2027", 1, name,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2027-01-15"),
                LocalDateTime.parse("2026-08-20T00:00:00"),
                LocalDateTime.parse("2026-09-10T23:59:59"),
                LocalDateTime.parse("2026-09-20T23:59:59"), AcademicTermStatus.SELECTION);
    }
}
