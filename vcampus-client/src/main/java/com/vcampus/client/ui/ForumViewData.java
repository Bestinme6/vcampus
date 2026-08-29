package com.vcampus.client.ui;

import com.vcampus.common.model.ForumContentStatus;
import com.vcampus.common.model.ForumModerationAction;
import com.vcampus.common.model.ForumTargetType;
import com.vcampus.common.protocol.ResponseMessage;
import com.vcampus.common.protocol.RowCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ForumViewData {
    private static final String ERROR = "服务器返回的论坛数据格式不正确";

    private ForumViewData() {
    }

    public static List<SectionRow> sections(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            int count = nonNegative(data, "count");
            List<SectionRow> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 6);
                rows.add(new SectionRow(
                        longValue(fields, 0), fields.get(1), fields.get(2), fields.get(3),
                        intValue(fields, 4), bool(fields.get(5))));
            }
            return List.copyOf(rows);
        } catch (Exception exception) {
            throw malformed(exception);
        }
    }

    public static PostPage postPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            int count = nonNegative(data, "count");
            List<PostRow> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 15);
                rows.add(new PostRow(
                        longValue(fields, 0), longValue(fields, 1), fields.get(2),
                        longValue(fields, 3), fields.get(4), fields.get(5), fields.get(6),
                        ForumContentStatus.valueOf(fields.get(7)), bool(fields.get(8)),
                        bool(fields.get(9)), bool(fields.get(10)), intValue(fields, 11),
                        intValue(fields, 12), Instant.parse(fields.get(13)),
                        nullableInstant(fields.get(14))));
            }
            return new PostPage(rows, positive(data, "page"),
                    positive(data, "pageSize"), nonNegative(data, "total"));
        } catch (Exception exception) {
            throw malformed(exception);
        }
    }

    public static PostDetail postDetail(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            return new PostDetail(
                    longValue(data, "id"), longValue(data, "sectionId"),
                    required(data, "sectionName"), longValue(data, "authorUserId"),
                    required(data, "authorDisplayName"), required(data, "title"),
                    required(data, "content"),
                    ForumContentStatus.valueOf(required(data, "status")),
                    bool(required(data, "locked")), bool(required(data, "pinned")),
                    bool(required(data, "featured")), intValue(data, "viewCount"),
                    intValue(data, "commentCount"),
                    Instant.parse(required(data, "createdAt")),
                    Instant.parse(required(data, "updatedAt")),
                    nullableInstant(data.get("lastCommentedAt")),
                    bool(required(data, "canDelete")));
        } catch (Exception exception) {
            throw malformed(exception);
        }
    }

    public static CommentPage commentPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            int count = nonNegative(data, "count");
            List<CommentRow> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 8);
                rows.add(new CommentRow(
                        longValue(fields, 0), longValue(fields, 1), longValue(fields, 2),
                        fields.get(3), fields.get(4),
                        ForumContentStatus.valueOf(fields.get(5)),
                        Instant.parse(fields.get(6)), bool(fields.get(7))));
            }
            return new CommentPage(rows, positive(data, "page"),
                    positive(data, "pageSize"), nonNegative(data, "total"));
        } catch (Exception exception) {
            throw malformed(exception);
        }
    }

    public static AdminContentPage adminContentPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            int count = nonNegative(data, "count");
            List<AdminContentRow> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 13);
                rows.add(new AdminContentRow(
                        ForumTargetType.valueOf(fields.get(0)), longValue(fields, 1),
                        nullableLong(fields.get(2)), fields.get(3), longValue(fields, 4),
                        fields.get(5), fields.get(6), fields.get(7),
                        ForumContentStatus.valueOf(fields.get(8)), bool(fields.get(9)),
                        bool(fields.get(10)), bool(fields.get(11)),
                        Instant.parse(fields.get(12))));
            }
            return new AdminContentPage(rows, positive(data, "page"),
                    positive(data, "pageSize"), nonNegative(data, "total"));
        } catch (Exception exception) {
            throw malformed(exception);
        }
    }

    public static ModerationLogPage moderationLogPage(ResponseMessage response) {
        try {
            Map<String, String> data = data(response);
            int count = nonNegative(data, "count");
            List<ModerationLogRow> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                List<String> fields = row(data, index, 8);
                rows.add(new ModerationLogRow(
                        longValue(fields, 0), longValue(fields, 1), fields.get(2),
                        ForumTargetType.valueOf(fields.get(3)), longValue(fields, 4),
                        ForumModerationAction.valueOf(fields.get(5)), fields.get(6),
                        Instant.parse(fields.get(7))));
            }
            return new ModerationLogPage(rows, positive(data, "page"),
                    positive(data, "pageSize"), nonNegative(data, "total"));
        } catch (Exception exception) {
            throw malformed(exception);
        }
    }

    private static Map<String, String> data(ResponseMessage response) {
        if (response == null || !response.success()) throw new IllegalArgumentException();
        return response.data();
    }

    private static List<String> row(Map<String, String> data, int index, int size) {
        List<String> fields = RowCodec.decode(required(data, "row." + index));
        if (fields.size() != size) throw new IllegalArgumentException();
        return fields;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException();
        return value;
    }

    private static int intValue(Map<String, String> data, String key) {
        return Integer.parseInt(required(data, key));
    }

    private static int intValue(List<String> fields, int index) {
        return Integer.parseInt(fields.get(index));
    }

    private static int nonNegative(Map<String, String> data, String key) {
        int value = intValue(data, key);
        if (value < 0) throw new IllegalArgumentException();
        return value;
    }

    private static int positive(Map<String, String> data, String key) {
        int value = intValue(data, key);
        if (value < 1) throw new IllegalArgumentException();
        return value;
    }

    private static long longValue(Map<String, String> data, String key) {
        return Long.parseLong(required(data, key));
    }

    private static long longValue(List<String> fields, int index) {
        return Long.parseLong(fields.get(index));
    }

    private static Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private static Instant nullableInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static boolean bool(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException();
    }

    private static IllegalArgumentException malformed(Exception exception) {
        return new IllegalArgumentException(ERROR, exception);
    }

    public record SectionRow(long id, String code, String name,
                             String description, int sortOrder, boolean enabled) {
    }

    public record PostRow(long id, long sectionId, String sectionName, long authorUserId,
                          String authorDisplayName, String title, String summary,
                          ForumContentStatus status, boolean locked, boolean pinned,
                          boolean featured, int viewCount, int commentCount,
                          Instant createdAt, Instant lastCommentedAt) {
    }

    public record PostPage(List<PostRow> rows, int page, int pageSize, int total) {
        public PostPage {
            rows = List.copyOf(rows);
        }
    }

    public record PostDetail(long id, long sectionId, String sectionName,
                             long authorUserId, String authorDisplayName, String title,
                             String content, ForumContentStatus status, boolean locked,
                             boolean pinned, boolean featured, int viewCount,
                             int commentCount, Instant createdAt, Instant updatedAt,
                             Instant lastCommentedAt, boolean canDelete) {
    }

    public record CommentRow(long id, long postId, long authorUserId,
                             String authorDisplayName, String content,
                             ForumContentStatus status, Instant createdAt,
                             boolean canDelete) {
    }

    public record CommentPage(List<CommentRow> rows, int page, int pageSize, int total) {
        public CommentPage {
            rows = List.copyOf(rows);
        }
    }

    public record AdminContentRow(ForumTargetType targetType, long id, Long parentId,
                                  String sectionName, long authorUserId,
                                  String authorDisplayName, String title, String content,
                                  ForumContentStatus status, boolean locked, boolean pinned,
                                  boolean featured, Instant createdAt) {
    }

    public record AdminContentPage(List<AdminContentRow> rows, int page,
                                   int pageSize, int total) {
        public AdminContentPage {
            rows = List.copyOf(rows);
        }
    }

    public record ModerationLogRow(long id, long operatorUserId,
                                   String operatorDisplayName, ForumTargetType targetType,
                                   long targetId, ForumModerationAction action,
                                   String reason, Instant createdAt) {
    }

    public record ModerationLogPage(List<ModerationLogRow> rows, int page,
                                    int pageSize, int total) {
        public ModerationLogPage {
            rows = List.copyOf(rows);
        }
    }
}
