package com.vcampus.client.fx.shop;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import com.vcampus.common.protocol.Actions;
import java.math.BigDecimal;
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

    @Test void sellerReviewAndAdminActionsNeverAcceptActorIds()throws Exception{
        RecordingClient client=new RecordingClient();
        client.responses.add(ResponseMessage.success("x","ok",Map.of("itemId","7")));
        client.responses.add(ResponseMessage.success("x","ok",Map.of("row",orderRow("SHIPPED"))));
        client.responses.add(ResponseMessage.success("x","ok",Map.of("row.0",reviewRow())));
        client.responses.add(ResponseMessage.success("x","ok",Map.of("row",orderRow("CANCELLED"))));
        var gateway=new SocketSecondhandGateway(client,"token");
        gateway.create(new SecondhandData.ListingDraft(null,"旧教材","含少量笔记","BOOK","九成新",new BigDecimal("12.00")));
        gateway.ship(10L);
        gateway.createReview(new SecondhandData.ReviewDraft("00000000-0000-0000-0000-000000000001",10L,5,"交易顺利，描述准确",List.of()));
        gateway.adminResolve(10L,SecondhandData.AdminResolution.REFUND,"00000000-0000-0000-0000-000000000002","双方协商退款");
        assertEquals(List.of(Actions.SHOP_USED_ITEM_CREATE,Actions.SHOP_USED_ORDER_SHIP,
                Actions.SHOP_USED_REVIEW_CREATE,Actions.SHOP_USED_ADMIN_ORDER_RESOLVE),
                client.requests.stream().map(RequestMessage::action).toList());
        for(RequestMessage request:client.requests){assertTrue(!request.parameters().containsKey("sellerId"));assertTrue(!request.parameters().containsKey("buyerId"));}
        assertEquals("REFUND",client.requests.get(3).parameters().get("resolution"));
    }

    @Test void itemImageUploadUsesSecondhandCommitPlanVocabulary()throws Exception{
        RecordingClient client=new RecordingClient();String hash="a".repeat(64);
        client.responses.add(ResponseMessage.success("x","ok",Map.of("uploadId","up-1","itemId","7","expectedBytes","8","chunkBytes","8")));
        client.responses.add(ResponseMessage.success("x","ok",Map.of()));
        client.responses.add(ResponseMessage.success("x","ok",Map.of()));
        client.responses.add(ResponseMessage.success("x","ok",Map.of("count","1","image.0",RowCodec.encode("91","image/png","8",hash,"0","true"))));
        var gateway=new SocketSecondhandGateway(client,"token");
        var ticket=gateway.uploadStart(7L,"image/png",8);gateway.uploadChunk(ticket.uploadId(),0,new byte[8]);gateway.uploadComplete(ticket.uploadId());
        var images=gateway.uploadImages(7L,List.of(new SecondhandData.ImagePlanItem(null,ticket.uploadId(),true)));
        RequestMessage commit=client.requests.get(3);assertEquals(Actions.SHOP_USED_IMAGE_COMMIT,commit.action());
        assertEquals("1",commit.parameters().get("itemCount"));assertTrue(commit.parameters().containsKey("item.0"));assertEquals(91L,images.getFirst().id());
    }

    private static String orderRow(String status){return RowCodec.encode("10","USED-10","7","2","1","12.00",status,status.equals("CANCELLED")?"REFUNDED":"HELD","旧教材","BOOK","九成新","卖家","买家","13800000000","江苏省南京市测试路1号","2026-09-01T00:00:00Z",status.equals("SHIPPED")?"2026-09-02T00:00:00Z":"","",status.equals("CANCELLED")?"2026-09-02T00:00:00Z":"");}
    private static String reviewRow(){return RowCodec.encode("44","10","7","1","2","买家","5","交易顺利，描述准确","NORMAL","0","false","2026-09-03T00:00:00Z","2026-09-03T00:00:00Z");}

    private static final class RecordingClient extends VCampusClient {
        final List<RequestMessage> requests=new ArrayList<>();final List<ResponseMessage> responses=new ArrayList<>();
        RecordingClient(){super("localhost",1);}
        @Override public ResponseMessage send(RequestMessage request)throws IOException{requests.add(request);return responses.removeFirst();}
    }
}
