package com.vcampus.client.fx.system;

import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

public interface SystemGateway {
    ResponseMessage config() throws IOException;

    void updateTerm(SystemTermConfig term) throws IOException;

    void updatePeriods(List<ClassPeriodTime> periods) throws IOException;

    ResponseMessage search(String userKeyword, String actionPrefix, String resultCode,
                           LocalDate fromDate, LocalDate toDate, int page) throws IOException;
}
