package com.vcampus.server.support;

import com.vcampus.server.database.AuditStore;

import java.util.ArrayList;
import java.util.List;

public final class RecordingAuditStore implements AuditStore {
    public record Entry(Long userId, String action, String result, String clientAddress) { }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void record(Long userId, String action, String result, String clientAddress) {
        entries.add(new Entry(userId, action, result, clientAddress));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public Entry last() {
        return entries.getLast();
    }
}
