package com.vcampus.server.database;

public interface AuditStore {
    void record(Long userId, String action, String result, String clientAddress);
}
