package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.server.service.LibraryLoanPolicy.LoanRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibraryLoanPolicyTest {
    @Test
    void returnsApprovedStudentAndTeacherRules() {
        assertEquals(new LoanRule(5, Duration.ofDays(30), Duration.ofDays(15)),
                LibraryLoanPolicy.ruleFor(Set.of(UserRole.STUDENT)));
        assertEquals(new LoanRule(10, Duration.ofDays(60), Duration.ofDays(30)),
                LibraryLoanPolicy.ruleFor(Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN)));
        assertThrows(IllegalArgumentException.class,
                () -> LibraryLoanPolicy.ruleFor(Set.of(UserRole.SUPER_ADMIN)));
        assertThrows(NullPointerException.class, () -> LibraryLoanPolicy.ruleFor(null));
    }
}
