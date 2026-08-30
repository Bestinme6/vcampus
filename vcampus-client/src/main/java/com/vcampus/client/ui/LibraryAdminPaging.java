package com.vcampus.client.ui;

final class LibraryAdminPaging {
    private int page = 1;
    private int pageSize = 10;
    private int total;

    int page() {
        return page;
    }

    void update(int page, int pageSize, int total) {
        this.page = Math.max(1, page);
        this.pageSize = Math.max(1, pageSize);
        this.total = Math.max(0, total);
    }

    void reset() {
        page = 1;
    }

    boolean canPrevious() {
        return page > 1;
    }

    boolean canNext() {
        return page * pageSize < total;
    }

    int previous() {
        if (canPrevious()) page--;
        return page;
    }

    int next() {
        if (canNext()) page++;
        return page;
    }

    String label() {
        return "第 " + page + " 页 · 共 " + total + " 项";
    }
}
