package com.vcampus.client.fx.system;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.model.SystemTermConfig;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Socket 客户端和超级管理员会话实现的系统管理网关。
 *
 * @since 1.0
 */
public final class SocketSystemGateway implements SystemGateway {
    private final VCampusClient client;
    private final String token;

    public SocketSystemGateway(VCampusClient client, String token) {
        this.client = Objects.requireNonNull(client, "client");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token");
        this.token = token;
    }

    @Override
    public ResponseMessage config() throws IOException {
        return requireSuccess(client.systemConfig(token));
    }

    @Override
    public void updateTerm(SystemTermConfig term) throws IOException {
        requireSuccess(client.updateSystemTerm(token, Objects.requireNonNull(term, "term")));
    }

    @Override
    public void updatePeriods(List<ClassPeriodTime> periods) throws IOException {
        requireSuccess(client.updateSystemPeriods(token, List.copyOf(periods)));
    }

    @Override
    public ResponseMessage search(String userKeyword, String actionPrefix, String resultCode,
                                  LocalDate fromDate, LocalDate toDate, int page) throws IOException {
        return requireSuccess(client.searchSystemAudit(token, userKeyword, actionPrefix, resultCode,
                fromDate, toDate, page));
    }

    private ResponseMessage requireSuccess(ResponseMessage response) throws IOException {
        if (response == null) throw new IOException("服务器未返回数据");
        if (!response.success()) throw new IOException(response.message());
        return response;
    }
}
