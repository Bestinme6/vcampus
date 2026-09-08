package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.image.ForumImageStore;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ForumImageServiceTest {
    @Test void authenticatesUploadAndOnlyOwnerCanClaimCompletedImage(){
        SessionManager sessions=new SessionManager();String owner=sessions.create(account(7,"2026000007")).token();String other=sessions.create(account(8,"2026000008")).token();FakeImages images=new FakeImages();ForumImageService service=new ForumImageService(images,null,sessions);
        var start=service.start(request(owner,Map.of("mimeType","image/png","byteSize","3")));assertTrue(start.success());String id=start.data().get("uploadId");
        assertTrue(service.chunk(request(owner,Map.of("uploadId",id,"chunkIndex","0","contentBase64",Base64.getEncoder().encodeToString(new byte[]{1,2,3})))).success());
        assertTrue(service.complete(request(owner,Map.of("uploadId",id))).success());
        assertEquals(1,service.claim(7,List.of(id)).size());assertThrows(IllegalArgumentException.class,()->service.claim(8,List.of(id)));service.consume(7,List.of(id));assertThrows(IllegalArgumentException.class,()->service.claim(7,List.of(id)));
        assertFalse(service.start(request("missing",Map.of("mimeType","image/png","byteSize","3"))).success());assertEquals(1,images.starts);
    }
    private RequestMessage request(String token,Map<String,String> values){Map<String,String> all=new HashMap<>(values);all.put("sessionToken",token);return RequestMessage.create("forum.image.test",all);}
    private UserAccount account(long id,String username){return new UserAccount(id,username,"hash","salt","用户"+id,true,false,Set.of(UserRole.STUDENT));}
    private static final class FakeImages implements ForumImageStore{
        int starts;
        public ShopImageStore.UploadTicket start(long owner,String mime,long bytes){starts++;return new ShopImageStore.UploadTicket("upload",1,bytes,10,Instant.now().plusSeconds(60));}
        public void append(long owner,String id,int index,byte[] bytes){}
        public Completed complete(long owner,String id){return new Completed(id,"full","thumb","image/png",3,"a".repeat(64),1,1);}
        public ShopImageStore.ImageDescriptor describe(String key){throw new UnsupportedOperationException();}
        public ShopImageStore.ImageRange readRange(String key,long offset,int max){throw new UnsupportedOperationException();}
        public void cleanup(Set<String> keys,Instant now){}
    }
}
