package com.vcampus.client.ui;

final class UnreadBadgeFormatter {
    private UnreadBadgeFormatter() {
    }

    static String format(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("未读消息数不能为负数");
        }
        if (count == 0) {
            return "";
        }
        return count > 99 ? "99+" : Integer.toString(count);
    }
}
