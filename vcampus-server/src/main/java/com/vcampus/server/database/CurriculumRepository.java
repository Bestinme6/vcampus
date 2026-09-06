package com.vcampus.server.database;

import com.vcampus.common.model.CourseRequirementType;
import com.vcampus.common.model.CurriculumPlanPolicy;
import com.vcampus.common.model.CurriculumPlanStatus;
import com.vcampus.server.database.AcademicRepository.AcademicRuleException;

import java.math.BigDecimal;
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

public final class CurriculumRepository {
    private final ConnectionFactory connectionFactory;

    public CurriculumRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
    }

    public CurriculumPage search(CurriculumQuery query) throws SQLException {
        Objects.requireNonNull(query);
        long majorId = query.majorId() == null ? 0 : query.majorId();
        String status = query.status() == null ? "" : query.status().name();
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        String pattern = "%" + keyword + "%";
        String where = """
                 WHERE (? = 0 OR plan.major_id = ?)
                   AND (? = '' OR plan.status = ?)
                   AND (? = '' OR plan.plan_name LIKE ? OR major.major_name LIKE ?)
                """;
        try (Connection connection = connectionFactory.openConnection()) {
            int total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM curriculum_plans plan"
                            + " JOIN majors major ON major.id = plan.major_id" + where)) {
                bindSearch(statement, majorId, status, keyword, pattern);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getInt(1);
                }
            }
            List<CurriculumSummary> rows = new ArrayList<>();
            String sql = """
                    SELECT plan.id, plan.major_id, major.major_name, plan.plan_name,
                           plan.version_no, plan.enrollment_year_start,
                           plan.enrollment_year_end, plan.status,
                           (SELECT COUNT(*) FROM curriculum_plan_courses course
                             WHERE course.plan_id = plan.id) AS course_count
                      FROM curriculum_plans plan
                      JOIN majors major ON major.id = plan.major_id
                    """ + where + " ORDER BY major.major_name, plan.version_no DESC"
                    + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindSearch(statement, majorId, status, keyword, pattern);
                statement.setInt(8, query.pageSize());
                statement.setInt(9, (query.page() - 1) * query.pageSize());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) rows.add(readSummary(result));
                }
            }
            return new CurriculumPage(rows, query.page(), query.pageSize(), total);
        }
    }

    public CurriculumDetail get(long planId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            return loadDetail(connection, planId);
        }
    }

    public long create(CreateCurriculum command, long operatorId) throws SQLException {
        validateCreate(command);
        try (Connection connection = connectionFactory.openConnection()) {
            return insertPlan(connection, command.majorId(), command.planName(),
                    command.versionNo(), command.enrollmentYearStart(),
                    command.enrollmentYearEnd(), operatorId);
        }
    }

    public void update(UpdateCurriculum command, long operatorId) throws SQLException {
        Objects.requireNonNull(command);
        validateName(command.planName());
        CurriculumPlanPolicy.validateYears(
                command.enrollmentYearStart(), command.enrollmentYearEnd());
        inTransaction(connection -> {
            LockedPlan plan = lockPlan(connection, command.planId());
            CurriculumPlanPolicy.requireEditable(plan.status());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE curriculum_plans
                       SET plan_name = ?, enrollment_year_start = ?, enrollment_year_end = ?
                     WHERE id = ?
                    """)) {
                statement.setString(1, command.planName().trim());
                statement.setInt(2, command.enrollmentYearStart());
                statement.setInt(3, command.enrollmentYearEnd());
                statement.setLong(4, command.planId());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public long copy(long sourcePlanId, CreateCurriculum target, long operatorId)
            throws SQLException {
        Objects.requireNonNull(target);
        validateName(target.planName());
        CurriculumPlanPolicy.validateYears(
                target.enrollmentYearStart(), target.enrollmentYearEnd());
        return inTransaction(connection -> {
            LockedPlan source = lockPlan(connection, sourcePlanId);
            if (target.majorId() != source.majorId()) {
                throw new AcademicRuleException("培养方案只能复制到同一专业");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id
                      FROM curriculum_plans
                     WHERE major_id = ?
                     ORDER BY id
                     FOR UPDATE
                    """)) {
                statement.setLong(1, source.majorId());
                try (ResultSet ignored = statement.executeQuery()) {
                    while (ignored.next()) {
                        // Lock every version for this major before allocating the next version.
                    }
                }
            }
            int nextVersion;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(MAX(version_no), 0) + 1
                      FROM curriculum_plans
                     WHERE major_id = ?
                    """)) {
                statement.setLong(1, source.majorId());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    nextVersion = result.getInt(1);
                }
            }
            long copyId = insertPlan(connection, source.majorId(), target.planName(),
                    nextVersion, target.enrollmentYearStart(),
                    target.enrollmentYearEnd(), operatorId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO curriculum_plan_courses
                        (plan_id, course_id, requirement_type, recommended_term_number)
                    SELECT ?, course_id, requirement_type, recommended_term_number
                      FROM curriculum_plan_courses
                     WHERE plan_id = ?
                    """)) {
                statement.setLong(1, copyId);
                statement.setLong(2, sourcePlanId);
                statement.executeUpdate();
            }
            return copyId;
        });
    }

    public void addCourse(long planId, long courseId, CourseRequirementType type,
                          int recommendedTerm, long operatorId) throws SQLException {
        validateCourse(type, recommendedTerm);
        inTransaction(connection -> {
            requireEditablePlan(connection, planId);
            requireEnabledCourse(connection, courseId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO curriculum_plan_courses
                        (plan_id, course_id, requirement_type, recommended_term_number)
                    VALUES (?, ?, ?, ?)
                    """)) {
                bindCourse(statement, planId, courseId, type, recommendedTerm);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void updateCourse(long planId, long courseId, CourseRequirementType type,
                             int recommendedTerm, long operatorId) throws SQLException {
        validateCourse(type, recommendedTerm);
        inTransaction(connection -> {
            requireEditablePlan(connection, planId);
            requireEnabledCourse(connection, courseId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE curriculum_plan_courses
                       SET requirement_type = ?, recommended_term_number = ?
                     WHERE plan_id = ? AND course_id = ?
                    """)) {
                statement.setString(1, type.name());
                statement.setInt(2, recommendedTerm);
                statement.setLong(3, planId);
                statement.setLong(4, courseId);
                if (statement.executeUpdate() != 1) {
                    throw new AcademicRuleException("培养方案中不存在该课程");
                }
            }
            return null;
        });
    }

    public void removeCourse(long planId, long courseId, long operatorId) throws SQLException {
        inTransaction(connection -> {
            requireEditablePlan(connection, planId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM curriculum_plan_courses WHERE plan_id = ? AND course_id = ?")) {
                statement.setLong(1, planId);
                statement.setLong(2, courseId);
                if (statement.executeUpdate() != 1) {
                    throw new AcademicRuleException("培养方案中不存在该课程");
                }
            }
            return null;
        });
    }

    public void publish(long planId, long operatorId) throws SQLException {
        inTransaction(connection -> {
            LockedPlan plan = lockPlan(connection, planId);
            CurriculumPlanPolicy.requireEditable(plan.status());
            lockPublishedPlans(connection, plan.majorId());
            requirePublishableCourses(connection, planId);
            rejectOverlappingPublishedRange(connection, plan);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE curriculum_plans
                       SET status = 'PUBLISHED', published_by_user_id = ?,
                           published_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                    """)) {
                statement.setLong(1, operatorId);
                statement.setLong(2, planId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void archive(long planId, long operatorId) throws SQLException {
        inTransaction(connection -> {
            LockedPlan plan = lockPlan(connection, planId);
            if (!CurriculumPlanPolicy.canTransition(
                    plan.status(), CurriculumPlanStatus.ARCHIVED)) {
                throw new IllegalStateException("只有已发布培养方案可以归档");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                      FROM student_profiles
                     WHERE major_id = ?
                       AND enrollment_year BETWEEN ? AND ?
                       AND status IN ('ENROLLED', 'SUSPENDED')
                    """)) {
                statement.setLong(1, plan.majorId());
                statement.setInt(2, plan.yearFrom());
                statement.setInt(3, plan.yearTo());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    if (result.getInt(1) > 0) {
                        throw new AcademicRuleException("仍有在籍学生适用该培养方案，不能归档");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE curriculum_plans SET status = 'ARCHIVED' WHERE id = ?")) {
                statement.setLong(1, planId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public MatchedCurriculum matchForStudent(long userId) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            List<CurriculumSummary> matches = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT plan.id, plan.major_id, major.major_name, plan.plan_name,
                           plan.version_no, plan.enrollment_year_start,
                           plan.enrollment_year_end, plan.status,
                           (SELECT COUNT(*) FROM curriculum_plan_courses course
                             WHERE course.plan_id = plan.id) AS course_count
                      FROM student_profiles student
                      JOIN curriculum_plans plan
                        ON plan.major_id = student.major_id
                       AND student.enrollment_year BETWEEN plan.enrollment_year_start
                                                       AND plan.enrollment_year_end
                       AND plan.status = 'PUBLISHED'
                      JOIN majors major ON major.id = plan.major_id
                     WHERE student.user_id = ?
                     ORDER BY plan.id
                    """)) {
                statement.setLong(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) matches.add(readSummary(result));
                }
            }
            if (matches.isEmpty()) throw new AcademicRuleException("未配置适用培养方案");
            if (matches.size() > 1) {
                throw new AcademicRuleException("培养方案配置存在重叠，请联系教务管理员");
            }
            CurriculumSummary plan = matches.getFirst();
            return new MatchedCurriculum(plan, loadCourses(connection, plan.id()));
        }
    }

    private CurriculumDetail loadDetail(Connection connection, long planId) throws SQLException {
        String sql = """
                SELECT plan.id, plan.major_id, major.major_name, plan.plan_name,
                       plan.version_no, plan.enrollment_year_start,
                       plan.enrollment_year_end, plan.status,
                       plan.created_by_user_id, plan.created_at,
                       plan.published_by_user_id, publisher.display_name AS published_by,
                       plan.published_at
                  FROM curriculum_plans plan
                  JOIN majors major ON major.id = plan.major_id
                  LEFT JOIN users publisher ON publisher.id = plan.published_by_user_id
                 WHERE plan.id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, planId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AcademicRuleException("培养方案不存在");
                Long publisherId = nullableLong(result, "published_by_user_id");
                return new CurriculumDetail(
                        result.getLong("id"), result.getLong("major_id"),
                        result.getString("major_name"), result.getString("plan_name"),
                        result.getInt("version_no"), result.getInt("enrollment_year_start"),
                        result.getInt("enrollment_year_end"),
                        CurriculumPlanStatus.valueOf(result.getString("status")),
                        result.getLong("created_by_user_id"),
                        instant(result.getTimestamp("created_at")), publisherId,
                        result.getString("published_by"),
                        instant(result.getTimestamp("published_at")),
                        loadCourses(connection, planId));
            }
        }
    }

    private List<CurriculumCourse> loadCourses(Connection connection, long planId)
            throws SQLException {
        List<CurriculumCourse> courses = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT course.id, course.course_code, course.course_name, course.credits,
                       item.requirement_type, item.recommended_term_number
                  FROM curriculum_plan_courses item
                  JOIN courses course ON course.id = item.course_id
                 WHERE item.plan_id = ?
                 ORDER BY item.recommended_term_number, course.course_code
                """)) {
            statement.setLong(1, planId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    courses.add(new CurriculumCourse(
                            result.getLong("id"), result.getString("course_code"),
                            result.getString("course_name"), result.getBigDecimal("credits"),
                            CourseRequirementType.valueOf(result.getString("requirement_type")),
                            result.getInt("recommended_term_number")));
                }
            }
        }
        return List.copyOf(courses);
    }

    private long insertPlan(Connection connection, long majorId, String planName,
                            int versionNo, int yearFrom, int yearTo, long operatorId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO curriculum_plans
                    (major_id, plan_name, version_no, enrollment_year_start,
                     enrollment_year_end, status, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, majorId);
            statement.setString(2, planName.trim());
            statement.setInt(3, versionNo);
            statement.setInt(4, yearFrom);
            statement.setInt(5, yearTo);
            statement.setLong(6, operatorId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Database did not return curriculum id");
                return keys.getLong(1);
            }
        }
    }

    private LockedPlan lockPlan(Connection connection, long planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, major_id, status, enrollment_year_start, enrollment_year_end
                  FROM curriculum_plans
                 WHERE id = ?
                 FOR UPDATE
                """)) {
            statement.setLong(1, planId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AcademicRuleException("培养方案不存在");
                return new LockedPlan(result.getLong("id"), result.getLong("major_id"),
                        CurriculumPlanStatus.valueOf(result.getString("status")),
                        result.getInt("enrollment_year_start"),
                        result.getInt("enrollment_year_end"));
            }
        }
    }

    private void lockPublishedPlans(Connection connection, long majorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM curriculum_plans
                 WHERE major_id = ? AND status = 'PUBLISHED'
                 ORDER BY id
                 FOR UPDATE
                """)) {
            statement.setLong(1, majorId);
            try (ResultSet ignored = statement.executeQuery()) {
                while (ignored.next()) {
                    // Reading each row acquires deterministic row locks.
                }
            }
        }
    }

    private void rejectOverlappingPublishedRange(Connection connection, LockedPlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM curriculum_plans
                 WHERE major_id = ? AND status = 'PUBLISHED' AND id <> ?
                   AND enrollment_year_start <= ? AND enrollment_year_end >= ?
                """)) {
            statement.setLong(1, plan.majorId());
            statement.setLong(2, plan.id());
            statement.setInt(3, plan.yearTo());
            statement.setInt(4, plan.yearFrom());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) > 0) {
                    throw new AcademicRuleException("同一专业的已发布培养方案适用年份不能重叠");
                }
            }
        }
    }

    private void requirePublishableCourses(Connection connection, long planId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN course.enabled = FALSE THEN 1 ELSE 0 END) AS disabled
                  FROM curriculum_plan_courses item
                  JOIN courses course ON course.id = item.course_id
                 WHERE item.plan_id = ?
                """)) {
            statement.setLong(1, planId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt("total") == 0) {
                    throw new AcademicRuleException("培养方案至少需要一门课程");
                }
                if (result.getInt("disabled") > 0) {
                    throw new AcademicRuleException("培养方案包含已停用课程");
                }
            }
        }
    }

    private void requireEditablePlan(Connection connection, long planId) throws SQLException {
        CurriculumPlanPolicy.requireEditable(lockPlan(connection, planId).status());
    }

    private void requireEnabledCourse(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled FROM courses WHERE id = ?")) {
            statement.setLong(1, courseId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AcademicRuleException("课程不存在");
                if (!result.getBoolean("enabled")) {
                    throw new AcademicRuleException("不能添加已停用课程");
                }
            }
        }
    }

    private void validateCreate(CreateCurriculum command) {
        Objects.requireNonNull(command);
        if (command.majorId() <= 0) throw new IllegalArgumentException("专业不能为空");
        validateName(command.planName());
        if (command.versionNo() <= 0) throw new IllegalArgumentException("版本号必须为正整数");
        CurriculumPlanPolicy.validateYears(
                command.enrollmentYearStart(), command.enrollmentYearEnd());
    }

    private void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 120) {
            throw new IllegalArgumentException("培养方案名称长度必须为 1—120 个字符");
        }
    }

    private void validateCourse(CourseRequirementType type, int recommendedTerm) {
        Objects.requireNonNull(type, "课程要求类型不能为空");
        if (recommendedTerm < 1 || recommendedTerm > 12) {
            throw new IllegalArgumentException("建议学期必须在 1—12 之间");
        }
    }

    private void bindSearch(PreparedStatement statement, long majorId, String status,
                            String keyword, String pattern) throws SQLException {
        statement.setLong(1, majorId);
        statement.setLong(2, majorId);
        statement.setString(3, status);
        statement.setString(4, status);
        statement.setString(5, keyword);
        statement.setString(6, pattern);
        statement.setString(7, pattern);
    }

    private void bindCourse(PreparedStatement statement, long planId, long courseId,
                            CourseRequirementType type, int recommendedTerm)
            throws SQLException {
        statement.setLong(1, planId);
        statement.setLong(2, courseId);
        statement.setString(3, type.name());
        statement.setInt(4, recommendedTerm);
    }

    private CurriculumSummary readSummary(ResultSet result) throws SQLException {
        return new CurriculumSummary(
                result.getLong("id"), result.getLong("major_id"),
                result.getString("major_name"), result.getString("plan_name"),
                result.getInt("version_no"), result.getInt("enrollment_year_start"),
                result.getInt("enrollment_year_end"),
                CurriculumPlanStatus.valueOf(result.getString("status")),
                result.getInt("course_count"));
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

    private record LockedPlan(long id, long majorId, CurriculumPlanStatus status,
                              int yearFrom, int yearTo) {
    }

    public record CurriculumQuery(Long majorId, CurriculumPlanStatus status, String keyword,
                                  int page, int pageSize) {
        public CurriculumQuery {
            if (page < 1) throw new IllegalArgumentException("页码必须为正整数");
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("每页数量必须在 1—100 之间");
            }
        }
    }

    public record CurriculumPage(List<CurriculumSummary> rows, int page,
                                 int pageSize, int total) {
        public CurriculumPage {
            rows = List.copyOf(rows);
        }
    }

    public record CurriculumSummary(long id, long majorId, String majorName,
                                    String planName, int versionNo,
                                    int enrollmentYearStart, int enrollmentYearEnd,
                                    CurriculumPlanStatus status, int courseCount) {
    }

    public record CurriculumDetail(long id, long majorId, String majorName,
                                   String planName, int versionNo,
                                   int enrollmentYearStart, int enrollmentYearEnd,
                                   CurriculumPlanStatus status, long createdByUserId,
                                   Instant createdAt, Long publishedByUserId,
                                   String publishedBy, Instant publishedAt,
                                   List<CurriculumCourse> courses) {
        public CurriculumDetail {
            courses = List.copyOf(courses);
        }
    }

    public record CurriculumCourse(long courseId, String courseCode, String courseName,
                                   BigDecimal credits, CourseRequirementType requirementType,
                                   int recommendedTermNumber) {
    }

    public record CreateCurriculum(long majorId, String planName, int versionNo,
                                   int enrollmentYearStart, int enrollmentYearEnd) {
    }

    public record UpdateCurriculum(long planId, String planName,
                                   int enrollmentYearStart, int enrollmentYearEnd) {
    }

    public record MatchedCurriculum(CurriculumSummary plan,
                                    List<CurriculumCourse> courses) {
        public MatchedCurriculum {
            courses = List.copyOf(courses);
        }
    }
}
