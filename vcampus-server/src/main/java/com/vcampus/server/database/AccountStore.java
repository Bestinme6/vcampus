package com.vcampus.server.database;

import com.vcampus.common.model.Gender;
import com.vcampus.common.model.UserRole;
import com.vcampus.server.model.AccountSummary;
import com.vcampus.server.security.PasswordHasher.PasswordHash;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountStore {
    AccountPage search(String keyword, UserRole identity, Boolean enabled, int page, int pageSize)
            throws SQLException;

    AccountReferences referenceData() throws SQLException;

    long createStudent(CreateStudentAccount command, PasswordHash password, long operatorUserId)
            throws SQLException;

    long createTeacher(CreateTeacherAccount command, PasswordHash password) throws SQLException;

    Optional<AccountSummary> findManageableById(long userId) throws SQLException;

    MutationResult replaceAdministrativeRoles(
            long userId, UserRole baseIdentity, Set<UserRole> roles,
            long operatorUserId, String operatorDisplayName) throws SQLException;

    MutationResult setEnabled(
            long userId, boolean enabled,
            long operatorUserId, String operatorDisplayName) throws SQLException;

    MutationResult resetPassword(
            long userId, PasswordHash password,
            long operatorUserId, String operatorDisplayName) throws SQLException;

    enum MutationResult {
        NOT_FOUND,
        UNCHANGED,
        CHANGED
    }

    record AccountPage(List<AccountSummary> rows, int page, int pageSize, int total) {
        public AccountPage { rows = List.copyOf(rows); }
    }

    record ReferenceItem(long id, long parentId, String code, String name, int year) {
    }

    record AccountReferences(
            List<ReferenceItem> departments,
            List<ReferenceItem> majors,
            List<ReferenceItem> classes) {
        public AccountReferences {
            departments = List.copyOf(departments);
            majors = List.copyOf(majors);
            classes = List.copyOf(classes);
        }
    }

    record CreateStudentAccount(
            String studentNumber,
            String fullName,
            Gender gender,
            LocalDate birthDate,
            long departmentId,
            long majorId,
            long classId,
            int enrollmentYear,
            String phone,
            String email,
            String address,
            Set<UserRole> roles) {
        public CreateStudentAccount { roles = Set.copyOf(roles); }
    }

    record CreateTeacherAccount(
            String teacherNumber,
            String fullName,
            long departmentId,
            String professionalTitle,
            String phone,
            String email,
            Set<UserRole> roles) {
        public CreateTeacherAccount { roles = Set.copyOf(roles); }
    }
}
