package com.vcampus.client.fx.notification;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocketNotificationGatewayTest {
    @Test void forwardsExistingNotificationOperationsAndParsesResponses() throws Exception {
        RecordingClient client=new RecordingClient();
        NotificationGateway gateway=new SocketNotificationGateway(client,"token");

        var page=gateway.search("成绩",NotificationSource.ACADEMIC,false,2);
        assertEquals(List.of("token","成绩","ACADEMIC","false","2"),client.searchArgs);
        assertEquals("成绩已发布",page.rows().getFirst().title());

        assertEquals("请进入成绩页面查看。",gateway.detail(7).content());
        assertEquals(3,gateway.unreadCount());
        gateway.markRead(7);gateway.markUnread(8);gateway.markAllRead();gateway.delete(7);
        assertEquals(List.of(7L),client.marked);
        assertEquals(List.of(8L),client.markedUnread);
        assertEquals(1,client.markAllCalls);
        assertEquals(List.of(7L),client.deleted);
    }

    private static final class RecordingClient extends VCampusClient {
        List<String> searchArgs=List.of();List<Long> marked=List.of();List<Long> markedUnread=List.of();List<Long> deleted=List.of();int markAllCalls;
        RecordingClient(){super("localhost",1);}
        @Override public ResponseMessage searchNotifications(String token,String keyword,NotificationSource source,Boolean read,int page){
            searchArgs=List.of(token,keyword,source.name(),read.toString(),Integer.toString(page));
            return ResponseMessage.success("r","ok",Map.of("page","2","pageSize","10","total","1","count","1","row.0",
                    RowCodec.encode("7","GRADE_PUBLISHED","ACADEMIC","成绩已发布","请查看完整内容","STUDENT_GRADES","","false","2026-09-09T00:00:00Z")));
        }
        @Override public ResponseMessage getNotification(String token,long id){return ResponseMessage.success("r","ok",Map.of(
                "id",Long.toString(id),"type","GRADE_PUBLISHED","source","ACADEMIC","title","成绩已发布","content","请进入成绩页面查看。",
                "target","STUDENT_GRADES","relatedEntityId","","isRead","false","readAt","","createdAt","2026-09-09T00:00:00Z"));}
        @Override public ResponseMessage unreadNotificationCount(String token){return ResponseMessage.success("r","ok",Map.of("unreadCount","3"));}
        @Override public ResponseMessage markNotificationRead(String token,long id){marked=List.of(id);return ResponseMessage.success("r","ok",Map.of());}
        @Override public ResponseMessage markNotificationUnread(String token,long id){markedUnread=List.of(id);return ResponseMessage.success("r","ok",Map.of());}
        @Override public ResponseMessage markAllNotificationsRead(String token){markAllCalls++;return ResponseMessage.success("r","ok",Map.of());}
        @Override public ResponseMessage deleteNotification(String token,long id){deleted=List.of(id);return ResponseMessage.success("r","ok",Map.of());}
    }
}
