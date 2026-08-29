package com.vcampus.client.ui;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForumViewDataTest {
    @Test
    void decodesPostRowsWithoutBreakingChineseOrNewlines() {
        Map<String, String> data = page();
        data.put("row.0", RowCodec.encode(
                "7", "2", "校园生活", "11", "张同学", "食堂窗口建议",
                "第一行\n第二行", "NORMAL", "false", "true", "false",
                "25", "3", "2026-08-28T08:00:00Z", "2026-08-28T09:00:00Z"));

        ForumViewData.PostPage result = ForumViewData.postPage(ok(data));

        assertEquals("第一行\n第二行", result.rows().getFirst().summary());
        assertTrue(result.rows().getFirst().pinned());
    }

    @Test
    void decodesPostDetailAndNullableReplyTime() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", "7");
        data.put("sectionId", "2");
        data.put("sectionName", "校园生活");
        data.put("authorUserId", "11");
        data.put("authorDisplayName", "张同学");
        data.put("title", "食堂窗口建议");
        data.put("content", "正文");
        data.put("status", "NORMAL");
        data.put("locked", "false");
        data.put("pinned", "false");
        data.put("featured", "true");
        data.put("viewCount", "26");
        data.put("commentCount", "0");
        data.put("createdAt", "2026-08-28T08:00:00Z");
        data.put("updatedAt", "2026-08-28T08:00:00Z");
        data.put("lastCommentedAt", "");
        data.put("canDelete", "true");

        ForumViewData.PostDetail detail = ForumViewData.postDetail(ok(data));

        assertEquals(ForumContentStatus.NORMAL, detail.status());
        assertNull(detail.lastCommentedAt());
        assertTrue(detail.canDelete());
    }

    @Test
    void decodesCommentOwnershipFlag() {
        Map<String, String> data = page();
        data.put("row.0", RowCodec.encode(
                "31", "7", "11", "张同学", "评论正文", "NORMAL",
                "2026-08-28T09:00:00Z", "true"));

        var result = ForumViewData.commentPage(ok(data));

        assertTrue(result.rows().getFirst().canDelete());
        assertEquals("评论正文", result.rows().getFirst().content());
    }

    @Test
    void rejectsRowsWithUnexpectedFieldCount() {
        Map<String, String> data = page();
        data.put("row.0", RowCodec.encode("7", "字段不足"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ForumViewData.postPage(ok(data)));

        assertEquals("服务器返回的论坛数据格式不正确", error.getMessage());
    }

    private Map<String, String> page() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("page", "1");
        data.put("pageSize", "10");
        data.put("total", "1");
        data.put("count", "1");
        return data;
    }

    private ResponseMessage ok(Map<String, String> data) {
        return ResponseMessage.success("request", "查询成功", data);
    }
}
