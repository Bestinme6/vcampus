package com.vcampus.common.model;

import java.util.Objects;

public final class StudentStatusPolicy {
    private StudentStatusPolicy() {
    }

    public static boolean canTransition(StudentStatus oldStatus, StudentStatus newStatus) {
        Objects.requireNonNull(oldStatus, "oldStatus");
        Objects.requireNonNull(newStatus, "newStatus");
        if (oldStatus == newStatus) {
            return true;
        }
        return switch (oldStatus) {
            case ENROLLED -> newStatus == StudentStatus.SUSPENDED
                    || newStatus == StudentStatus.WITHDRAWN
                    || newStatus == StudentStatus.GRADUATED;
            case SUSPENDED -> newStatus == StudentStatus.ENROLLED
                    || newStatus == StudentStatus.WITHDRAWN;
            case WITHDRAWN, GRADUATED -> false;
        };
    }
}
