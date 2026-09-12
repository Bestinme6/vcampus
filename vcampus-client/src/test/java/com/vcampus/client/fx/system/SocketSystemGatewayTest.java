package com.vcampus.client.fx.system;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ClassPeriodTime;
import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SocketSystemGatewayTest {
    @Test
    void gatewaySendsTokenAndEncodedPeriodSnapshot() throws Exception {
        CapturingClient client = new CapturingClient(ResponseMessage.success("r", "ok", Map.of()));
        SystemGateway gateway = new SocketSystemGateway(client, "admin-token");

        gateway.updatePeriods(java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(number -> new ClassPeriodTime(number, null, null)).toList());

        assertEquals(Actions.SYSTEM_CONFIG_UPDATE_PERIODS, client.request.action());
        assertEquals("admin-token", client.request.parameters().get("sessionToken"));
        assertEquals("12", client.request.parameters().get("period.count"));
        assertEquals(new ClassPeriodTime(12, null, null),
                ClassPeriodTime.decode(client.request.parameters().get("period.11")));
    }

    @Test
    void failedResponseBecomesReadableIoFailure() {
        SystemGateway gateway = new SocketSystemGateway(new CapturingClient(
                ResponseMessage.failure("r", "没有系统管理权限")), "token");
        assertEquals("没有系统管理权限", assertThrows(IOException.class, gateway::config).getMessage());
    }

    private static final class CapturingClient extends VCampusClient {
        private final ResponseMessage response;
        private RequestMessage request;

        private CapturingClient(ResponseMessage response) {
            super("localhost", 1);
            this.response = response;
        }

        @Override
        public ResponseMessage send(RequestMessage request) {
            this.request = request;
            return response;
        }
    }
}
