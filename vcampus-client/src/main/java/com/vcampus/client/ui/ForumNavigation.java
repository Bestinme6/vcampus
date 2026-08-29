package com.vcampus.client.ui;

import com.vcampus.common.model.ForumSort;

import java.util.Objects;

final class ForumNavigation {
    private HomeQuery homeQuery = new HomeQuery(null, "", ForumSort.LATEST_REPLY, 1);
    private Long currentPostId;

    void rememberHome(HomeQuery query) {
        homeQuery = Objects.requireNonNull(query, "query");
    }

    void openPost(long postId) {
        if (postId < 1) throw new IllegalArgumentException("postId");
        currentPostId = postId;
    }

    HomeQuery backHome() {
        currentPostId = null;
        return homeQuery;
    }

    Long currentPostId() {
        return currentPostId;
    }

    HomeQuery homeQuery() {
        return homeQuery;
    }

    record HomeQuery(Long sectionId, String keyword, ForumSort sort, int page) {
        HomeQuery {
            keyword = Objects.requireNonNullElse(keyword, "");
            sort = Objects.requireNonNull(sort, "sort");
            if (page < 1) throw new IllegalArgumentException("page");
        }
    }
}
