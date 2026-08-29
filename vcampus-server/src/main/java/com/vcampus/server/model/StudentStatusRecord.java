package com.vcampus.server.model;

import com.vcampus.common.model.StudentStatus;

import java.time.Instant;

public record StudentStatusRecord(
        long id,
        StudentStatus oldStatus,
        StudentStatus newStatus,
        String reason,
        String operatorName,
        Instant changedAt) {
}
