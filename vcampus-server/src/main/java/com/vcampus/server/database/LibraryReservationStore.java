package com.vcampus.server.database;

import com.vcampus.common.model.LibraryReservationStatus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public interface LibraryReservationStore {
    long create(long borrowerUserId, long bookId, Instant now) throws SQLException;
    boolean cancel(long borrowerUserId, long reservationId) throws SQLException;
    ReservationPage search(long borrowerUserId, LibraryReservationStatus status, int page, int pageSize) throws SQLException;

    record ReservationRecord(long id, long bookId, String catalogCode, String title,
                             LibraryReservationStatus status, Instant createdAt, Instant notifiedAt) {}
    record ReservationPage(List<ReservationRecord> rows, int page, int pageSize, int total) {
        public ReservationPage { rows = List.copyOf(rows); }
    }
}
