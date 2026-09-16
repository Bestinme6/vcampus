package com.vcampus.client.fx.forum;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.ForumBoostTier;
import com.vcampus.common.protocol.*;
import java.math.BigDecimal;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForumGatewayTest {
    @Test void createBoostSendsOnlyServerTrustedInputsAndDecodesReceipt() throws Exception {
        UUID operationId=UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var received=new CompletableFuture<RequestMessage>();
            Thread worker=Thread.ofPlatform().start(()->{
                try(var socket=server.accept()) {
                    var request=MessageCodec.readRequest(new DataInputStream(socket.getInputStream()));
                    MessageCodec.writeResponse(new DataOutputStream(socket.getOutputStream()),
                            ResponseMessage.success(request.requestId(),"成功",Map.of(
                                    "boostId","31","bankLedgerId","41","bankReference","FB-"+operationId,
                                    "amount","5.00","initialHeat","50","balanceAfter","45.00",
                                    "activeBoostCount","2","activeHeat","75","duplicate","false")));
                    received.complete(request);
                } catch(Exception e){received.completeExceptionally(e);}
            });
            var gateway=new SocketForumGateway(new VCampusClient("127.0.0.1",server.getLocalPort()),"test-session");
            var result=gateway.createBoost(11,ForumBoostTier.FIVE,operationId);
            assertEquals(new BigDecimal("5.00"),result.amount());
            assertEquals(50,result.initialHeat());
            assertEquals("FB-"+operationId,result.bankReference());
            var request=received.get(5,TimeUnit.SECONDS);
            assertEquals(Actions.FORUM_BOOST_CREATE,request.action());
            assertEquals("11",request.parameters().get("postId"));
            assertEquals("FIVE",request.parameters().get("tier"));
            assertEquals(operationId.toString(),request.parameters().get("operationId"));
            assertEquals("test-session",request.parameters().get("sessionToken"));
            for(String forbidden:List.of("amount","heat","initialHeat","expiresAt","createdAt","userId"))
                assertFalse(request.parameters().containsKey(forbidden));
            worker.join(5000);
        }
    }

    @Test void setLikeSendsTargetStateAndDecodesActualResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var received=new CompletableFuture<RequestMessage>();
            Thread worker=Thread.ofPlatform().start(()->{
                try(var socket=server.accept()) {
                    var request=MessageCodec.readRequest(new DataInputStream(socket.getInputStream()));
                    MessageCodec.writeResponse(new DataOutputStream(socket.getOutputStream()),ResponseMessage.success(request.requestId(),"成功",
                            Map.of("forumVersion","3","likeCount","12","liked","true","bookmarked","false")));
                    received.complete(request);
                } catch(Exception e){received.completeExceptionally(e);}
            });
            var gateway=new SocketForumGateway(new VCampusClient("127.0.0.1",server.getLocalPort()),"test-session");
            var result=gateway.setLiked(9,true);
            assertEquals(12,result.likeCount()); assertTrue(result.liked());
            var request=received.get(5,TimeUnit.SECONDS);
            assertEquals("forum.like.set",request.action());
            assertEquals(Map.of("sessionToken","test-session","postId","9","enabled","true"),request.parameters());
            worker.join(5000);
        }
    }
    @Test void missingVersionAndMalformedEngagementDoNotBecomeFakeZeroCounts() {
        assertThrows(IllegalArgumentException.class,()->ForumData.engagement(ResponseMessage.success("x","ok",Map.of())));
        assertThrows(IllegalArgumentException.class,()->ForumData.engagement(ResponseMessage.success("x","ok",
                Map.of("forumVersion","3","likeCount","-1","liked","yes","bookmarked","false"))));
    }
    @Test void reportRowsDecodeWithoutLosingMultilineContent() {
        String row=RowCodec.encode("81","POST","7","7","标题\n正文","11","张同学",
                "MISLEADING","信息不准确","PENDING","","","2026-09-08T02:00:00Z","");
        var page=ForumData.reports(ResponseMessage.success("x","ok",Map.of(
                "count","1","page","1","pageSize","10","total","1","row.0",row)));
        assertEquals(1,page.rows().size());assertEquals("标题\n正文",page.rows().getFirst().content());
        assertEquals(com.vcampus.common.model.ForumReportStatus.PENDING,page.rows().getFirst().status());
    }
}
