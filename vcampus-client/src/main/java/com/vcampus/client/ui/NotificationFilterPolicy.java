package com.vcampus.client.ui;

import com.vcampus.common.model.NotificationSource;

final class NotificationFilterPolicy {
    private String keyword = "";
    private NotificationSource source;
    private Boolean read;
    private int page = 1;
    private int pageSize = 10;
    private int total;

    String keyword() {
        return keyword;
    }

    NotificationSource source() {
        return source;
    }

    Boolean read() {
        return read;
    }

    int page() {
        return page;
    }

    int totalPages() {
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    void changeKeyword(String value) {
        keyword = value == null ? "" : value.trim();
        page = 1;
    }

    void changeSource(NotificationSource value) {
        source = value;
        page = 1;
    }

    void changeRead(Boolean value) {
        read = value;
        page = 1;
    }

    void goToPage(int requestedPage) {
        page = Math.max(1, Math.min(requestedPage, totalPages()));
    }

    void applyTotal(int value, int serverPageSize) {
        if (value < 0 || serverPageSize < 1) {
            throw new IllegalArgumentException("消息分页数据无效");
        }
        total = value;
        pageSize = serverPageSize;
        page = Math.min(page, totalPages());
    }

    boolean canGoPrevious() {
        return page > 1;
    }

    boolean canGoNext() {
        return page < totalPages();
    }
}
