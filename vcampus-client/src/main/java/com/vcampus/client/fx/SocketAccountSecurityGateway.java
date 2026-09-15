package com.vcampus.client.fx;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.ResponseMessage;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

final class SocketAccountSecurityGateway implements AccountSecurityGateway {
    private final VCampusClient client;
    private final String token;

    SocketAccountSecurityGateway(VCampusClient client, String token) {
        this.client = Objects.requireNonNull(client, "client");
        this.token = Objects.requireNonNull(token, "token");
    }

    @Override
    public AccountSecurityData.Summary overview() throws IOException {
        ResponseMessage response = client.securityOverview(token);
        requireSuccess(response);
        return AccountSecurityData.from(response);
    }

    @Override
    public void changePassword(char[] currentPassword, char[] newPassword) throws IOException {
        char[] current = Arrays.copyOf(currentPassword, currentPassword.length);
        char[] next = Arrays.copyOf(newPassword, newPassword.length);
        try {
            requireSuccess(client.changePassword(token, current, next));
        } finally {
            Arrays.fill(current, '\0');
            Arrays.fill(next, '\0');
        }
    }

    private void requireSuccess(ResponseMessage response) throws IOException {
        if (response == null) throw new IOException("服务器未返回响应");
        if (!response.success()) throw new IOException(response.message());
    }
}
