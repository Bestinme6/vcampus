package com.vcampus.server.database;

import com.vcampus.common.model.ForumFeedScope;
import com.vcampus.common.model.ForumFeedOrder;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public interface ForumCommunityStore {
    record Engagement(int likeCount, boolean liked, boolean bookmarked) { }
    record FeedQuery(long viewerUserId, ForumFeedScope scope, ForumFeedOrder order,
                     Long sectionId, String keyword, int page, int pageSize) { }
    record FeedRow(ForumStore.PostRow post, Engagement engagement, boolean announcement) { }
    record FeedPage(List<FeedRow> rows, int page, int pageSize, int total) {
        public FeedPage { rows = List.copyOf(rows); }
    }
    Engagement engagement(long postId, long viewerUserId) throws SQLException;
    Engagement setLiked(long postId, long actorUserId, boolean enabled) throws SQLException;
    Engagement setBookmarked(long postId, long actorUserId, boolean enabled) throws SQLException;
    FeedPage searchFeed(FeedQuery query) throws SQLException;
    List<FeedRow> hotToday(long viewerUserId, Instant fromInclusive, Instant toExclusive) throws SQLException;
}
