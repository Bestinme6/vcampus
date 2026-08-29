package com.vcampus.server.database;

import java.sql.SQLException;
import java.time.Instant;

public interface LibraryNoticeStore {
    int sendDueSoon(Instant now, Instant deadline, int batchSize) throws SQLException;
    int sendOverdue(Instant now, int batchSize) throws SQLException;
}
