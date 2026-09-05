package com.vcampus.server.service;

import com.vcampus.common.model.*;
import com.vcampus.common.protocol.*;
import com.vcampus.server.database.ForumCommunityStore;
import com.vcampus.server.model.UserAccount;
import com.vcampus.server.security.SessionManager;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ForumCommunityServiceTest {
    @Test void rejectsAnonymousAndInvalidStatesAndUsesSessionIdentity() {
        var sessions = new SessionManager();
        String token = sessions.create(new UserAccount(7,"test","hash","salt","同学",true,false,Set.of(UserRole.STUDENT))).token();
        var store = new Store();
        var service = new ForumCommunityService(store,sessions,Clock.fixed(Instant.parse("2026-08-31T16:01:00Z"),ZoneOffset.UTC));
        assertFalse(service.handle(RequestMessage.create("forum.like.set",Map.of("postId","1","enabled","true"))).success());
        assertFalse(service.handle(RequestMessage.create("forum.like.set",Map.of("sessionToken",token,"postId","1","enabled","yes"))).success());
        var response=service.handle(RequestMessage.create("forum.like.set",Map.of("sessionToken",token,"postId","1","enabled","true","userId","999")));
        assertTrue(response.success()); assertEquals(7,store.actor);
        assertEquals("1",response.data().get("likeCount"));
        assertTrue(service.handle(RequestMessage.create("forum.hot.list",Map.of("sessionToken",token))).success());
        assertEquals(Instant.parse("2026-08-31T16:00:00Z"),store.from);
        assertEquals(Instant.parse("2026-09-01T16:00:00Z"),store.to);
    }
    private static class Store implements ForumCommunityStore {
        long actor; Instant from,to;
        public Engagement engagement(long p,long v){return new Engagement(1,true,false);}
        public Engagement setLiked(long p,long v,boolean enabled){actor=v;return new Engagement(1,true,false);}
        public Engagement setBookmarked(long p,long v,boolean enabled){return new Engagement(0,false,enabled);}
        public FeedPage searchFeed(FeedQuery q){return new FeedPage(List.of(),q.page(),20,0);}
        public List<FeedRow> hotToday(long v,Instant from,Instant to){this.from=from;this.to=to;return List.of();}
    }
}
