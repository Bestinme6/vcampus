package com.vcampus.common.model;

import com.vcampus.common.protocol.RowCodec;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AuditLogEntry(long id, Long userId, String username, String displayName,
                            String actionCode, String resultCode, String clientAddress,
                            Instant createdAt) {
    private static final int FIELD_COUNT = 8;

    public AuditLogEntry {
        if (id <= 0) {
            throw new IllegalArgumentException("审计记录编号必须为正数");
        }
        username = Objects.requireNonNullElse(username, "");
        displayName = Objects.requireNonNullElse(displayName, "");
        actionCode = requireText(actionCode, "审计动作不能为空");
        resultCode = requireText(resultCode, "审计结果不能为空");
        clientAddress = Objects.requireNonNullElse(clientAddress, "");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public String encode() {
        return RowCodec.encode(Long.toString(id), userId == null ? "" : userId.toString(), username,
                displayName, actionCode, resultCode, clientAddress, createdAt.toString());
    }

    public static AuditLogEntry decode(String encoded) {
        List<String> fields = RowCodec.decode(encoded);
        if (fields.size() != FIELD_COUNT) {
            throw new IllegalArgumentException("Invalid audit log field count");
        }
        try {
            Long userId = fields.get(1).isEmpty() ? null : Long.valueOf(fields.get(1));
            return new AuditLogEntry(Long.parseLong(fields.get(0)), userId, fields.get(2), fields.get(3),
                    fields.get(4), fields.get(5), fields.get(6), Instant.parse(fields.get(7)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid audit log row", exception);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
