package com.vcampus.client.fx.forum;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.protocol.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForumGatewayTest {
    @Test void setLikeSendsTargetStateAndDecodesActualResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var received=new CompletableFuture<RequestMessage>();
            Thread worker=Thread.ofPlatform().start(()->{
                try(var socket=server.accept()) {
                    var request=MessageCodec.readRequest(new DataInputStream(socket.getInputStream()));
                    MessageCodec.writeResponse(new DataOutputStream(socket.getOutputStream()),ResponseMessage.success(request.requestId(),"成功",
                            Map.of("forumVersion","2","likeCount","12","liked","true","bookmarked","false")));
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
                Map.of("forumVersion","2","likeCount","-1","liked","yes","bookmarked","false"))));
    }
}
