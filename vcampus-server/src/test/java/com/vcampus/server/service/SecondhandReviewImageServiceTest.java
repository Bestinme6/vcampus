package com.vcampus.server.service;

import com.vcampus.common.model.UserRole;
import com.vcampus.common.protocol.RequestMessage;
import com.vcampus.server.config.SecondhandReviewImageConfig;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.image.ShopReviewImageStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondhandReviewImageServiceTest {
    @Test
    void usesDedicatedConfigAndClaimsOnlyOwnersCompletedUploads() {
        Path configured = SecondhandReviewImageConfig.fromEnvironment(
                Map.of("VCAMPUS_SECONDHAND_REVIEW_IMAGE_DIR", "build/used-review-images"))
                .root();
        assertTrue(configured.endsWith(Path.of("build", "used-review-images")));

        SessionManager sessions = new SessionManager();
        String owner = token(sessions, 2L);
        String other = token(sessions, 3L);
        FakeImages files = new FakeImages();
        var service = new SecondhandReviewImageService(files, null, sessions);

        var started = service.start(request(owner,
                Map.of("mimeType", "image/png", "byteSize", "3")));
        String upload = started.data().get("uploadId");
        assertTrue(service.chunk(request(owner, Map.of("uploadId", upload, "chunkIndex", "0",
                "contentBase64", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))).success());
        assertTrue(service.complete(request(owner, Map.of("uploadId", upload))).success());
        assertEquals(1, service.claim(2L, List.of(upload)).size());
        assertThrows(IllegalArgumentException.class, () -> service.claim(3L, List.of(upload)));
        assertFalse(service.start(request(other + "bad",
                Map.of("mimeType", "image/png", "byteSize", "3"))).success());
    }

    private static RequestMessage request(String token, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>(values); all.put("sessionToken", token);
        return RequestMessage.create("shop.used.review.image.test", all);
    }

    private static String token(SessionManager sessions, long id) {
        return sessions.create(new UserAccount(id, "user" + id, "h", "s", "用户" + id,
                true, false, Set.of(UserRole.STUDENT))).token();
    }

    private static final class FakeImages implements ShopReviewImageStore {
        @Override public ShopImageStore.UploadTicket start(long ownerId,String mime,long bytes){return new ShopImageStore.UploadTicket("upload",1,bytes,10,Instant.now().plusSeconds(60));}
        @Override public void append(long ownerId,String uploadId,int index,byte[] bytes){}
        @Override public Completed complete(long ownerId,String uploadId){return new Completed(uploadId,"used-full","used-thumb","image/png",3,"a".repeat(64),1,1);}
        @Override public ShopImageStore.ImageDescriptor describe(String key){throw new UnsupportedOperationException();}
        @Override public ShopImageStore.ImageRange readRange(String key,long offset,int max){throw new UnsupportedOperationException();}
        @Override public boolean deleteIfExists(String key){return true;}
        @Override public void cleanup(Set<String> keys,Instant now){}
    }
}
