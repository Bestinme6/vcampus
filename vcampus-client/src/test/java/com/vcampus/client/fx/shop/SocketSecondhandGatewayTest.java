package com.vcampus.client.fx.shop;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketSecondhandGatewayTest {
    @Test
    void buyRetryForwardsSameOperationIdAndInlineAddress() throws Exception {
        RecordingClient client = new RecordingClient();
        client.responses.add(ResponseMessage.success("x","ok",Map.of(
                "orderId","10","orderNo","USED-10","status","PAID","duplicate","false")));
        client.responses.add(ResponseMessage.success("x","ok",Map.of(
                "orderId","10","orderNo","USED-10","status","PAID","duplicate","true")));
        var gateway = new SocketSecondhandGateway(client, "token");
        var address = new ShippingAddressChoice(null,null,
                new ShippingAddressValue("买家","13800000000","江苏省南京市测试路1号"));

        gateway.buy(7L,"00000000-0000-0000-0000-000000000001",address);
        var retry = gateway.buy(7L,"00000000-0000-0000-0000-000000000001",address);

        assertEquals(client.requests.get(0).parameters().get("operationId"),
                client.requests.get(1).parameters().get("operationId"));
        assertEquals("inline",client.requests.get(0).parameters().get("addressMode"));
        assertTrue(retry.duplicate());
    }

    private static final class RecordingClient extends VCampusClient {
        final List<RequestMessage> requests=new ArrayList<>();final List<ResponseMessage> responses=new ArrayList<>();
        RecordingClient(){super("localhost",1);}
        @Override public ResponseMessage send(RequestMessage request)throws IOException{requests.add(request);return responses.removeFirst();}
    }
}
