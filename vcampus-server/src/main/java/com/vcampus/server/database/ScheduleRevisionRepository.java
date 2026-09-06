package com.vcampus.server.database;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.model.ScheduleRevisionStatus;
import com.vcampus.common.model.ScheduleSlot;
import com.vcampus.server.database.AcademicRepository.AcademicRuleException;
import com.vcampus.server.database.NotificationWriter.NotificationDraft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ScheduleRevisionRepository {
    private final ConnectionFactory connectionFactory;
    private final NotificationWriter notifications;

    public ScheduleRevisionRepository(ConnectionFactory connectionFactory,
                                      NotificationWriter notifications) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.notifications = Objects.requireNonNull(notifications);
    }

    public ScheduleDraft getOrCreateDraft(long sectionId, long operatorId)
            throws SQLException {
        return inTransaction(connection -> {
            lockSection(connection, sectionId);
            Long existing = findDraftId(connection, sectionId);
            if (existing != null) return loadDraft(connection, existing);
            int revisionNo = nextRevisionNo(connection, sectionId);
            long revisionId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO course_section_schedule_revisions
                        (section_id, revision_no, status, created_by_user_id)
                    VALUES (?, ?, 'DRAFT', ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, sectionId);
                statement.setInt(2, revisionNo);
                statement.setLong(3, operatorId);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Database did not return schedule revision id");
                    revisionId = keys.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO class_schedules
                        (section_id, revision_id, day_of_week, start_period, end_period,
                         start_week, end_week, classroom)
                    SELECT source.section_id, ?, source.day_of_week, source.start_period,
                           source.end_period, source.start_week, source.end_week, source.classroom
                      FROM class_schedules source
                      JOIN course_section_schedule_revisions published
                        ON published.id = source.revision_id
                     WHERE source.section_id = ? AND published.status = 'PUBLISHED'
                    """)) {
                statement.setLong(1, revisionId);
                statement.setLong(2, sectionId);
                statement.executeUpdate();
            }
            return loadDraft(connection, revisionId);
        });
    }

    public ScheduleDraft saveDraft(SaveScheduleDraft command, long operatorId)
            throws SQLException {
        Objects.requireNonNull(command);
        validateSlots(command.slots(), false);
        return inTransaction(connection -> {
            LockedRevision draft = lockDraft(connection, command.revisionId());
            requireCommandMatches(command.sectionId(), command.expectedRevisionNo(), draft);
            replaceSlots(connection, draft.sectionId(), draft.id(), command.slots());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE course_section_schedule_revisions
                       SET revision_no = revision_no + 1
                     WHERE id = ? AND status = 'DRAFT' AND revision_no = ?
                    """)) {
                statement.setLong(1, draft.id());
                statement.setInt(2, draft.revisionNo());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("课表草稿已被其他管理员修改，请刷新后重试");
                }
            }
            return loadDraft(connection, draft.id());
        });
    }

    public ScheduleRevision publish(PublishSchedule command, long operatorId,
                                    String operatorName) throws SQLException {
        Objects.requireNonNull(command);
        return inTransaction(connection -> {
            LockedRevision draft = lockDraft(connection, command.revisionId());
            requireCommandMatches(command.sectionId(), command.expectedRevisionNo(), draft);
            List<ScheduleSlot> slots = loadSlots(connection, draft.id());
            validateSlots(slots, true);
            List<ScheduleConflict> conflicts = findPublicationConflicts(
                    connection, draft, slots);
            if (!conflicts.isEmpty()) throw new ScheduleConflictException(conflicts);
            lockPublished(connection, draft.sectionId());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE course_section_schedule_revisions
                       SET status = 'SUPERSEDED'
                     WHERE section_id = ? AND status = 'PUBLISHED'
                    """)) {
                statement.setLong(1, draft.sectionId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE course_section_schedule_revisions
                       SET status = 'PUBLISHED', published_by_user_id = ?,
                           published_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND status = 'DRAFT' AND revision_no = ?
                    """)) {
                statement.setLong(1, operatorId);
                statement.setLong(2, draft.id());
                statement.setInt(3, draft.revisionNo());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("课表草稿已被其他管理员修改，请刷新后重试");
                }
            }
            notifications.insertBatch(connection, scheduleNotifications(
                    connection, draft.sectionId(), operatorId, operatorName));
            return loadRevision(connection, draft.id());
        });
    }

    private Long findDraftId(Connection connection, long sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM course_section_schedule_revisions
                 WHERE section_id = ? AND status = 'DRAFT'
                 ORDER BY id DESC LIMIT 1 FOR UPDATE
                """)) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private int nextRevisionNo(Connection connection, long sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(revision_no), 0) + 1
                  FROM course_section_schedule_revisions
                 WHERE section_id = ?
                """)) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private LockedRevision lockDraft(Connection connection, long revisionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT revision.id, revision.section_id, revision.revision_no,
                       revision.status, section.term_id, section.teacher_user_id
                  FROM course_section_schedule_revisions revision
                  JOIN course_sections section ON section.id = revision.section_id
                 WHERE revision.id = ?
                 FOR UPDATE
                """)) {
            statement.setLong(1, revisionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AcademicRuleException("课表修订不存在");
                ScheduleRevisionStatus status = ScheduleRevisionStatus.valueOf(
                        result.getString("status"));
                if (status != ScheduleRevisionStatus.DRAFT) {
                    throw new IllegalStateException("只有课表草稿可以修改或发布");
                }
                return new LockedRevision(
                        result.getLong("id"), result.getLong("section_id"),
                        result.getInt("revision_no"), result.getLong("term_id"),
                        result.getLong("teacher_user_id"));
            }
        }
    }

    private void requireCommandMatches(long sectionId, int expectedRevisionNo,
                                       LockedRevision draft) {
        if (sectionId != draft.sectionId()) {
            throw new IllegalArgumentException("课表修订不属于指定教学班");
        }
        if (expectedRevisionNo != draft.revisionNo()) {
            throw new IllegalStateException("课表草稿已被其他管理员修改，请刷新后重试");
        }
    }

    private void lockSection(Connection connection, long sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM course_sections WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AcademicRuleException("教学班不存在");
            }
        }
    }

    private void lockPublished(Connection connection, long sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM course_section_schedule_revisions
                 WHERE section_id = ? AND status = 'PUBLISHED'
                 ORDER BY id FOR UPDATE
                """)) {
            statement.setLong(1, sectionId);
            try (ResultSet ignored = statement.executeQuery()) {
                while (ignored.next()) { }
            }
        }
    }

    private void replaceSlots(Connection connection, long sectionId, long revisionId,
                              List<ScheduleSlot> slots) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM class_schedules WHERE revision_id = ? AND section_id = ?")) {
            statement.setLong(1, revisionId);
            statement.setLong(2, sectionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO class_schedules
                    (section_id, revision_id, day_of_week, start_period, end_period,
                     start_week, end_week, classroom)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (ScheduleSlot slot : slots) {
                statement.setLong(1, sectionId);
                statement.setLong(2, revisionId);
                statement.setInt(3, slot.dayOfWeek());
                statement.setInt(4, slot.startPeriod());
                statement.setInt(5, slot.endPeriod());
                statement.setInt(6, slot.startWeek());
                statement.setInt(7, slot.endWeek());
                statement.setString(8, slot.classroom());
                statement.addBatch();
            }
            if (!slots.isEmpty()) statement.executeBatch();
        }
    }

    private List<ScheduleSlot> loadSlots(Connection connection, long revisionId)
            throws SQLException {
        List<ScheduleSlot> slots = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT day_of_week, start_period, end_period,
                       start_week, end_week, classroom
                  FROM class_schedules
                 WHERE revision_id = ?
                 ORDER BY day_of_week, start_period, start_week, id
                """)) {
            statement.setLong(1, revisionId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    slots.add(new ScheduleSlot(
                            result.getInt("day_of_week"), result.getInt("start_period"),
                            result.getInt("end_period"), result.getInt("start_week"),
                            result.getInt("end_week"), result.getString("classroom")));
                }
            }
        }
        return List.copyOf(slots);
    }

    private ScheduleDraft loadDraft(Connection connection, long revisionId)
            throws SQLException {
        ScheduleRevision revision = loadRevision(connection, revisionId);
        if (revision.status() != ScheduleRevisionStatus.DRAFT) {
            throw new IllegalStateException("课表草稿不存在");
        }
        return new ScheduleDraft(revision.id(), revision.sectionId(),
                revision.revisionNo(), revision.slots());
    }

    private ScheduleRevision loadRevision(Connection connection, long revisionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, section_id, revision_no, status, created_by_user_id,
                       created_at, published_by_user_id, published_at
                  FROM course_section_schedule_revisions
                 WHERE id = ?
                """)) {
            statement.setLong(1, revisionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AcademicRuleException("课表修订不存在");
                Long publisher = nullableLong(result, "published_by_user_id");
                return new ScheduleRevision(
                        result.getLong("id"), result.getLong("section_id"),
                        result.getInt("revision_no"),
                        ScheduleRevisionStatus.valueOf(result.getString("status")),
                        result.getLong("created_by_user_id"),
                        instant(result.getTimestamp("created_at")), publisher,
                        instant(result.getTimestamp("published_at")),
                        loadSlots(connection, revisionId));
            }
        }
    }

    private List<ScheduleConflict> findPublicationConflicts(
            Connection connection, LockedRevision draft, List<ScheduleSlot> slots)
            throws SQLException {
        List<ScheduleConflict> conflicts = new ArrayList<>();
        for (ScheduleSlot slot : slots) {
            findTeacherConflicts(connection, draft, slot, conflicts);
            findRoomConflicts(connection, draft, slot, conflicts);
        }
        return List.copyOf(conflicts);
    }

    private void findTeacherConflicts(Connection connection, LockedRevision draft,
                                      ScheduleSlot slot, List<ScheduleConflict> conflicts)
            throws SQLException {
        String sql = conflictSql("section.teacher_user_id = ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, draft.termId());
            statement.setLong(2, draft.sectionId());
            statement.setLong(3, draft.teacherUserId());
            bindOverlap(statement, 4, slot);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conflicts.add(conflict(ConflictKind.TEACHER, result, slot));
                }
            }
        }
    }

    private void findRoomConflicts(Connection connection, LockedRevision draft,
                                   ScheduleSlot slot, List<ScheduleConflict> conflicts)
            throws SQLException {
        String sql = conflictSql("LOWER(TRIM(schedule.classroom)) = LOWER(TRIM(?))");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, draft.termId());
            statement.setLong(2, draft.sectionId());
            statement.setString(3, slot.classroom());
            bindOverlap(statement, 4, slot);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conflicts.add(conflict(ConflictKind.CLASSROOM, result, slot));
                }
            }
        }
    }

    private String conflictSql(String dimension) {
        return """
                SELECT section.id, section.section_code, teacher.display_name
                  FROM class_schedules schedule
                  JOIN course_section_schedule_revisions revision
                    ON revision.id = schedule.revision_id
                   AND revision.status = 'PUBLISHED'
                  JOIN course_sections section ON section.id = schedule.section_id
                  JOIN users teacher ON teacher.id = section.teacher_user_id
                 WHERE section.term_id = ? AND section.id <> ? AND
                """ + dimension + """

                   AND schedule.day_of_week = ?
                   AND schedule.start_period <= ? AND schedule.end_period >= ?
                   AND schedule.start_week <= ? AND schedule.end_week >= ?
                 ORDER BY section.id
                """;
    }

    private ScheduleConflict conflict(ConflictKind kind, ResultSet result,
                                      ScheduleSlot slot) throws SQLException {
        String display = kind == ConflictKind.TEACHER
                ? result.getString("display_name") : slot.classroom();
        return new ScheduleConflict(kind, result.getLong("id"), display,
                slot.dayOfWeek(), slot.startPeriod(), slot.endPeriod(),
                slot.startWeek(), slot.endWeek());
    }

    private void bindOverlap(PreparedStatement statement, int start,
                             ScheduleSlot slot) throws SQLException {
        statement.setInt(start, slot.dayOfWeek());
        statement.setInt(start + 1, slot.endPeriod());
        statement.setInt(start + 2, slot.startPeriod());
        statement.setInt(start + 3, slot.endWeek());
        statement.setInt(start + 4, slot.startWeek());
    }

    private List<NotificationDraft> scheduleNotifications(
            Connection connection, long sectionId, long operatorId,
            String operatorName) throws SQLException {
        List<NotificationDraft> drafts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT section.teacher_user_id AS recipient_user_id, course.course_name
                  FROM course_sections section
                  JOIN courses course ON course.id = section.course_id
                 WHERE section.id = ?
                UNION
                SELECT student.user_id AS recipient_user_id, course.course_name
                  FROM course_sections section
                  JOIN courses course ON course.id = section.course_id
                  JOIN course_enrollments enrollment ON enrollment.section_id = section.id
                  JOIN student_profiles student ON student.id = enrollment.student_id
                 WHERE section.id = ? AND enrollment.status = 'ENROLLED'
                 ORDER BY recipient_user_id
                """)) {
            statement.setLong(1, sectionId);
            statement.setLong(2, sectionId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String courseName = result.getString("course_name");
                    drafts.add(new NotificationDraft(
                            result.getLong("recipient_user_id"), operatorId,
                            NotificationType.SCHEDULE_CHANGED, NotificationSource.ACADEMIC,
                            "课表变更通知",
                            operatorName + "已发布《" + courseName + "》的新课表，请及时查看。",
                            NotificationTarget.ACADEMIC_SCHEDULE, sectionId));
                }
            }
        }
        return List.copyOf(drafts);
    }

    private void validateSlots(List<ScheduleSlot> slots, boolean requireNonEmpty) {
        Objects.requireNonNull(slots, "课表时段不能为空");
        if (requireNonEmpty && slots.isEmpty()) {
            throw new IllegalArgumentException("发布课表至少需要一个上课时段");
        }
        if (slots.size() > 30) throw new IllegalArgumentException("上课时段不能超过 30 个");
        for (int left = 0; left < slots.size(); left++) {
            Objects.requireNonNull(slots.get(left), "上课时段不能为空");
            for (int right = left + 1; right < slots.size(); right++) {
                if (slots.get(left).overlaps(slots.get(right))) {
                    throw new IllegalArgumentException("同一教学班的上课时段不能互相重叠");
                }
            }
        }
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record LockedRevision(long id, long sectionId, int revisionNo,
                                  long termId, long teacherUserId) { }

    public enum ConflictKind { TEACHER, CLASSROOM }

    public record ScheduleDraft(long revisionId, long sectionId, int revisionNo,
                                List<ScheduleSlot> slots) {
        public ScheduleDraft { slots = List.copyOf(slots); }
    }

    public record ScheduleRevision(long id, long sectionId, int revisionNo,
                                   ScheduleRevisionStatus status,
                                   long createdByUserId, Instant createdAt,
                                   Long publishedByUserId, Instant publishedAt,
                                   List<ScheduleSlot> slots) {
        public ScheduleRevision { slots = List.copyOf(slots); }
    }

    public record ScheduleConflict(ConflictKind kind, long relatedEntityId,
                                   String displayName, int dayOfWeek,
                                   int startPeriod, int endPeriod,
                                   int startWeek, int endWeek) { }

    public record SaveScheduleDraft(long sectionId, long revisionId,
                                    int expectedRevisionNo,
                                    List<ScheduleSlot> slots) {
        public SaveScheduleDraft { slots = List.copyOf(slots); }
    }

    public record PublishSchedule(long sectionId, long revisionId,
                                  int expectedRevisionNo) { }

    public static final class ScheduleConflictException extends RuntimeException {
        private final List<ScheduleConflict> conflicts;

        public ScheduleConflictException(List<ScheduleConflict> conflicts) {
            super("课表存在时间冲突");
            this.conflicts = List.copyOf(conflicts);
        }

        public List<ScheduleConflict> conflicts() { return conflicts; }
    }
}
