package com.vcampus.server.database;

import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;

import java.sql.SQLException;
import java.util.List;

public interface SystemManagementStore {
    record Snapshot(List<SystemTermConfig> terms, List<ClassPeriodTime> periods) {
        public Snapshot {
            terms = List.copyOf(terms);
            periods = List.copyOf(periods);
        }
    }

    Snapshot configuration() throws SQLException;

    boolean updateTerm(SystemTermConfig term, long operatorUserId) throws SQLException;

    void updatePeriods(List<ClassPeriodTime> periods, long operatorUserId) throws SQLException;
}
