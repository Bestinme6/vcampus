package com.vcampus.client.ui;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.NotificationType;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationViewDataTest {
    @Test
    void parsesACompleteNotificationPage() {
        Map<String, String> data = pageData(2);
        data.put("row.0", RowCodec.encode(
                "7", "GRADE_PUBLISHED", "ACADEMIC", "成绩发布",
                "最终成绩已发布", "STUDENT_GRADES", "31", "false",
                "2026-08-26T10:00:00Z"));
        data.put("row.1", RowCodec.encode(
                "8", "PASSWORD_RESET", "ACCOUNT_SECURITY", "密码重置通知",
                "密码已重置", "NONE", "", "true",
                "2026-08-26T11:00:00Z"));

        NotificationViewData.NotificationPage page =
                NotificationViewData.NotificationPage.parse(success(data));

        assertEquals(2, page.total());
        assertEquals(10, page.pageSize());
        assertEquals(7L, page.rows().getFirst().id());
        assertEquals(NotificationType.GRADE_PUBLISHED, page.rows().getFirst().type());
        assertEquals(NotificationSource.ACADEMIC, page.rows().getFirst().source());
        assertEquals(NotificationTarget.STUDENT_GRADES, page.rows().getFirst().target());
        assertEquals(31L, page.rows().getFirst().relatedEntityId());
        assertFalse(page.rows().getFirst().read());
        assertEquals(Instant.parse("2026-08-26T10:00:00Z"),
                page.rows().getFirst().createdAt());
        assertNull(page.rows().get(1).relatedEntityId());
    }

    @Test
    void parsesCompleteDetailIncludingNullableFields() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", "9");
        data.put("type", "STUDENT_STATUS_CHANGED");
        data.put("source", "STUDENT_STATUS");
        data.put("title", "学籍状态变更");
        data.put("content", "您的学籍状态已发生变化。");
        data.put("target", "STUDENT_PROFILE");
        data.put("relatedEntityId", "");
        data.put("isRead", "false");
        data.put("readAt", "");
        data.put("createdAt", "2026-08-26T12:00:00Z");

        NotificationViewData.NotificationDetail detail =
                NotificationViewData.NotificationDetail.parse(success(data));

        assertEquals(9L, detail.id());
        assertEquals(NotificationTarget.STUDENT_PROFILE, detail.target());
        assertNull(detail.relatedEntityId());
        assertNull(detail.readAt());
        assertEquals("您的学籍状态已发生变化。", detail.content());
    }

    @Test
    void rejectsMalformedRowsInsteadOfDisplayingPartialMessages() {
        Map<String, String> data = pageData(1);
        data.put("row.0", RowCodec.encode("7", "GRADE_PUBLISHED"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> NotificationViewData.NotificationPage.parse(success(data)));

        assertEquals("服务器返回的消息数据格式不正确", exception.getMessage());
    }

    @Test
    void rejectsInvalidNumbersEnumsBooleansAndTimes() {
        Map<String, String> data = pageData(1);
        data.put("row.0", RowCodec.encode(
                "7", "UNKNOWN", "ACADEMIC", "标题", "摘要", "NONE", "",
                "not-boolean", "not-an-instant"));

        assertThrows(IllegalArgumentException.class,
                () -> NotificationViewData.NotificationPage.parse(success(data)));

        Map<String, String> negativeTotal = pageData(0);
        negativeTotal.put("total", "-1");
        assertThrows(IllegalArgumentException.class,
                () -> NotificationViewData.NotificationPage.parse(success(negativeTotal)));
    }

    @Test
    void preservesReadableServerFailureMessage() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> NotificationViewData.NotificationPage.parse(
                        ResponseMessage.failure("request", "登录已过期，请重新登录")));

        assertEquals("登录已过期，请重新登录", exception.getMessage());
    }

    @Test
    void parsesUnreadCountAndRejectsNegativeValues() {
        assertEquals(12, NotificationViewData.unreadCount(success(Map.of("unreadCount", "12"))));
        assertThrows(IllegalArgumentException.class,
                () -> NotificationViewData.unreadCount(success(Map.of("unreadCount", "-1"))));
    }

    @Test
    void parsesLibraryLoanDeepLink() {
        Map<String, String> data = pageData(1);
        data.put("row.0", RowCodec.encode(
                "18", "LIBRARY_DUE_SOON", "LIBRARY", "图书即将到期",
                "请按时归还", "LIBRARY_LOANS", "501", "false",
                "2026-08-27T00:00:00Z"));

        var row = NotificationViewData.NotificationPage.parse(success(data)).rows().getFirst();
        assertEquals(NotificationTarget.LIBRARY_LOANS, row.target());
        assertEquals(NotificationType.LIBRARY_DUE_SOON, row.type());
    }

    @Test
    void parsesForumPostDeepLink() {
        Map<String, String> data = pageData(1);
        data.put("row.0", RowCodec.encode(
                "19", "FORUM_POST_COMMENTED", "FORUM", "您的帖子收到一条新评论",
                "李老师评论了您的帖子", "FORUM_POST", "41", "false",
                "2026-08-29T00:00:00Z"));

        var row = NotificationViewData.NotificationPage.parse(success(data)).rows().getFirst();
        assertEquals(NotificationSource.FORUM, row.source());
        assertEquals(NotificationTarget.FORUM_POST, row.target());
        assertEquals(41L, row.relatedEntityId());
    }

    private Map<String, String> pageData(int count) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", "1");
        data.put("pageSize", "10");
        data.put("total", Integer.toString(count));
        data.put("count", Integer.toString(count));
        return data;
    }

    private ResponseMessage success(Map<String, String> data) {
        return ResponseMessage.success("request", "查询成功", data);
    }
}
