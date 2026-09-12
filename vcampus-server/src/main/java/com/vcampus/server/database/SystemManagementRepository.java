package com.vcampus.server.database;

import com.vcampus.common.model.AcademicTermStatus;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SystemManagementRepository implements SystemManagementStore {
    private final ConnectionFactory connectionFactory;

    public SystemManagementRepository(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Snapshot configuration() throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            List<SystemTermConfig> terms = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id,academic_year,term_number,term_name,start_date,end_date,
                           selection_start,selection_end,drop_deadline,status
                      FROM academic_terms
                     ORDER BY academic_year DESC, term_number DESC, id DESC
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    terms.add(new SystemTermConfig(rows.getLong("id"), rows.getString("academic_year"),
                            rows.getInt("term_number"), rows.getString("term_name"),
                            rows.getDate("start_date").toLocalDate(), rows.getDate("end_date").toLocalDate(),
                            rows.getTimestamp("selection_start").toLocalDateTime(),
                            rows.getTimestamp("selection_end").toLocalDateTime(),
                            rows.getTimestamp("drop_deadline").toLocalDateTime(),
                            AcademicTermStatus.valueOf(rows.getString("status"))));
                }
            }
            List<ClassPeriodTime> periods = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT period_number,start_time,end_time
                      FROM class_period_times ORDER BY period_number
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Time start = rows.getTime("start_time");
                    Time end = rows.getTime("end_time");
                    periods.add(new ClassPeriodTime(rows.getInt("period_number"),
                            start == null ? null : start.toLocalTime(), end == null ? null : end.toLocalTime()));
                }
            }
            return new Snapshot(terms, periods);
        }
    }

    @Override
    public boolean updateTerm(SystemTermConfig term, long operatorUserId) throws SQLException {
        String sql = """
                UPDATE academic_terms
                   SET term_name=?, start_date=?, end_date=?, selection_start=?, selection_end=?,
                       drop_deadline=?, status=?
                 WHERE id=? AND academic_year=? AND term_number=?
                """;
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, term.termName());
            statement.setDate(2, Date.valueOf(term.startDate()));
            statement.setDate(3, Date.valueOf(term.endDate()));
            statement.setTimestamp(4, Timestamp.valueOf(term.selectionStart()));
            statement.setTimestamp(5, Timestamp.valueOf(term.selectionEnd()));
            statement.setTimestamp(6, Timestamp.valueOf(term.dropDeadline()));
            statement.setString(7, term.status().name());
            statement.setLong(8, term.id());
            statement.setString(9, term.academicYear());
            statement.setInt(10, term.termNumber());
            return statement.executeUpdate() == 1;
        }
    }

    @Override
    public void updatePeriods(List<ClassPeriodTime> periods, long operatorUserId) throws SQLException {
        requireCompletePeriods(periods);
        try (Connection connection = connectionFactory.openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE class_period_times
                       SET start_time=?, end_time=?, updated_by_user_id=?
                     WHERE period_number=?
                    """)) {
                for (ClassPeriodTime period : periods) {
                    setTime(statement, 1, period.startTime());
                    setTime(statement, 2, period.endTime());
                    statement.setLong(3, operatorUserId);
                    statement.setInt(4, period.periodNumber());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("节次配置不存在: " + period.periodNumber());
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private void requireCompletePeriods(List<ClassPeriodTime> periods) {
        if (periods == null || periods.size() != 12) {
            throw new IllegalArgumentException("必须包含第 1—12 节配置");
        }
        Set<Integer> numbers = new HashSet<>();
        for (ClassPeriodTime period : periods) {
            numbers.add(period.periodNumber());
        }
        if (numbers.size() != 12) {
            throw new IllegalArgumentException("节次配置不能重复或缺失");
        }
    }

    private void setTime(PreparedStatement statement, int index, java.time.LocalTime value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIME);
        } else {
            statement.setTime(index, Time.valueOf(value));
        }
    }
}
