package com.vcampus.client.fx.bank;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BankGatewayTest {
    @Test void gatewayExplicitlyRequestsPersonalLedgerAndUsesSessionIdentity()throws Exception{
        RecordingClient client=new RecordingClient();
        client.reply=ResponseMessage.success("r","",Map.of("page","1","pageSize","10","total","0","count","0","income","0.00","expense","0.00"));
        BankGateway gateway=new SocketBankGateway(client,"session");
        gateway.ledger(BankData.Query.mine());
        assertEquals(Actions.BANK_LEDGER_SEARCH,client.request.action());
        assertEquals("mine",client.request.parameters().get("scope"));
        assertEquals("session",client.request.parameters().get("sessionToken"));
        assertFalse(client.request.parameters().containsKey("userId"));
        client.reply=ResponseMessage.success("r","",Map.of("balanceAfter","8.00","referenceNo","same-operation","duplicate","true"));
        gateway.transfer("teacher","12.00","same-operation");
        assertEquals(Actions.BANK_TRANSFER_CREATE,client.request.action());
        assertEquals(Map.of("sessionToken","session","recipientUsername","teacher","amount","12.00","operationId","same-operation"),client.request.parameters());
    }
    @Test void recipientLookupUsesReadOnlyActionAndDoesNotTransferMoney()throws Exception{
        RecordingClient client=new RecordingClient();
        client.reply=ResponseMessage.success("r","",Map.of("username","teacher","displayName","李老师","self","false"));
        var recipient=new SocketBankGateway(client,"session").recipient("teacher");
        assertEquals("李老师",recipient.displayName());
        assertEquals(Actions.BANK_RECIPIENT_GET,client.request.action());
        assertEquals(Map.of("sessionToken","session","recipientUsername","teacher"),client.request.parameters());
    }
    static class RecordingClient extends VCampusClient{
        RequestMessage request;ResponseMessage reply;
        RecordingClient(){super("unused.invalid",1);}
        @Override public ResponseMessage send(RequestMessage request){this.request=request;return reply;}
    }
}
