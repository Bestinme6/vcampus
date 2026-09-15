package com.vcampus.server.database;

import com.vcampus.common.model.AuditLogEntry;

import java.sql.SQLException;
import java.util.List;

@FunctionalInterface
public interface SecurityActivityStore {
    List<AuditLogEntry> recentForUser(long userId, int limit) throws SQLException;
}
