package com.vcampus.server.database;

import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.service.AcademicService;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcademicReferenceDatesTest {
    @Test
    void referenceTermsExposeStartAndEndDatesWithoutChangingTheirIdentityFields() throws Exception {
        ConnectionFactory connections = newDatabase();
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE academic_terms (id BIGINT PRIMARY KEY, term_name VARCHAR(80), status VARCHAR(30), start_date DATE, end_date DATE)");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(30), course_name VARCHAR(80), credits DECIMAL(4,1), enabled BOOLEAN)");
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(30), display_name VARCHAR(80), enabled BOOLEAN)");
            statement.execute("CREATE TABLE user_roles (user_id BIGINT, role_id BIGINT)");
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, role_code VARCHAR(30))");
            statement.execute("CREATE TABLE majors (id BIGINT PRIMARY KEY, department_id BIGINT, major_code VARCHAR(10), major_name VARCHAR(80), enabled BOOLEAN)");
            statement.execute("INSERT INTO academic_terms VALUES (7, '2026 秋', 'IN_PROGRESS', DATE '2026-08-31', DATE '2027-01-17')");
            statement.execute("INSERT INTO majors VALUES (8, 3, '080901', '计算机科学与技术', TRUE)");
        }

        AcademicRepository repository = new AcademicRepository(connections, new NoopNotifications());
        AcademicRepository.AcademicReferences references = repository.references();
        AcademicRepository.TermReference term = references.terms().getFirst();

        assertEquals(7L, term.id());
        assertEquals("2026 秋", term.name());
        assertEquals("IN_PROGRESS", term.status().name());
        assertEquals("2026-08-31", term.startDate().toString());
        assertEquals("2027-01-17", term.endDate().toString());
        assertEquals("计算机科学与技术", references.majors().getFirst().name());
    }

    @Test
    void referenceResponseKeepsLegacyTermRowAndAddsDatesAsSeparateKeys() throws Exception {
        ConnectionFactory connections = newDatabase();
        createReferenceSchema(connections);
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO academic_terms VALUES (7, '2026 秋', 'IN_PROGRESS', DATE '2026-08-31', DATE '2027-01-17')");
            statement.execute("INSERT INTO majors VALUES (8, 3, '080901', '计算机科学与技术', TRUE)");
        }
        SessionManager sessions = new SessionManager();
        String token = sessions.create(new UserAccount(11, "student", "hash", "salt", "学生", true,
                false, Set.of(UserRole.STUDENT))).token();
        AcademicService service = new AcademicService(
                new AcademicRepository(connections, new NoopNotifications()), sessions);

        var response = service.referenceData(RequestMessage.create("request", Map.of("sessionToken", token)));

        assertEquals(3, RowCodec.decode(response.data().get("term.0")).size());
        assertEquals("2026-08-31", response.data().get("term.0.startDate"));
        assertEquals("2027-01-17", response.data().get("term.0.endDate"));
        assertEquals("1", response.data().get("major.count"));
        assertEquals(java.util.List.of("8", "3", "080901", "计算机科学与技术"),
                RowCodec.decode(response.data().get("major.0")));
    }

    private static ConnectionFactory newDatabase() {
        return new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:academic-reference-dates-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
    }

    private static void createReferenceSchema(ConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE academic_terms (id BIGINT PRIMARY KEY, term_name VARCHAR(80), status VARCHAR(30), start_date DATE, end_date DATE)");
            statement.execute("CREATE TABLE courses (id BIGINT PRIMARY KEY, course_code VARCHAR(30), course_name VARCHAR(80), credits DECIMAL(4,1), enabled BOOLEAN)");
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(30), display_name VARCHAR(80), enabled BOOLEAN)");
            statement.execute("CREATE TABLE user_roles (user_id BIGINT, role_id BIGINT)");
            statement.execute("CREATE TABLE roles (id BIGINT PRIMARY KEY, role_code VARCHAR(30))");
            statement.execute("CREATE TABLE majors (id BIGINT PRIMARY KEY, department_id BIGINT, major_code VARCHAR(10), major_name VARCHAR(80), enabled BOOLEAN)");
        }
    }

    private static final class NoopNotifications implements NotificationWriter {
        @Override public void insert(Connection connection, NotificationDraft draft) { }
        @Override public void insertBatch(Connection connection, java.util.List<NotificationDraft> drafts) { }
    }
}
