package com.vcampus.client.fx;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SocketAccountSecurityGatewayTest {
    @Test
    void sendsTheSessionTokenForOverviewAndPasswordChange() throws Exception {
        CapturingClient client = new CapturingClient();
        AccountSecurityGateway gateway = new SocketAccountSecurityGateway(client, "session-token");

        gateway.overview();
        assertEquals(Actions.AUTH_SECURITY_OVERVIEW, client.request.action());
        assertEquals("session-token", client.request.parameters().get("sessionToken"));

        gateway.changePassword("current-password".toCharArray(), "new-password-123".toCharArray());
        assertEquals(Actions.AUTH_CHANGE_PASSWORD, client.request.action());
        assertEquals("current-password", client.request.parameters().get("currentPassword"));
        assertEquals("new-password-123", client.request.parameters().get("newPassword"));
    }

    @Test
    void preservesTheServerMessageWhenOverviewFails() {
        CapturingClient client = new CapturingClient();
        client.overviewFailure = true;
        AccountSecurityGateway gateway = new SocketAccountSecurityGateway(client, "expired-token");

        var error = assertThrows(java.io.IOException.class, gateway::overview);

        assertEquals("登录已过期，请重新登录", error.getMessage());
    }

    private static final class CapturingClient extends VCampusClient {
        private RequestMessage request;
        private boolean overviewFailure;

        private CapturingClient() { super("localhost", 1); }

        @Override public ResponseMessage send(RequestMessage request) {
            this.request = request;
            if (Actions.AUTH_SECURITY_OVERVIEW.equals(request.action())) {
                if (overviewFailure) {
                    return ResponseMessage.failure(request.requestId(), "登录已过期，请重新登录");
                }
                return ResponseMessage.success(request.requestId(), "ok", Map.of(
                        "userId", "2", "username", "student", "displayName", "学生", "roles", "STUDENT",
                        "expiresAt", "2026-09-13T10:00:00Z", "row.count", "0"));
            }
            return ResponseMessage.success(request.requestId(), "ok", Map.of());
        }
    }
}
