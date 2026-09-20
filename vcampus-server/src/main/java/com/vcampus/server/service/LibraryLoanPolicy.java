package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 图书借阅期限、续借资格和逾期判断的领域规则组件。
 *
 * @since 1.0
 */
public final class LibraryLoanPolicy {
    private LibraryLoanPolicy() {
    }

    public static LoanRule ruleFor(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        if (roles.contains(UserRole.TEACHER)) {
            return new LoanRule(10, Duration.ofDays(60), Duration.ofDays(30));
        }
        if (roles.contains(UserRole.STUDENT)) {
            return new LoanRule(5, Duration.ofDays(30), Duration.ofDays(15));
        }
        throw new IllegalArgumentException("当前身份不能借阅图书");
    }

    public record LoanRule(int maxLoans, Duration initialLoanDuration, Duration renewalDuration) {
    }
}
