package com.vcampus.server.database;

import com.vcampus.common.model.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Reads forum aggregates and serializes interaction changes on the parent post. */
public final class ForumCommunityRepository implements ForumCommunityStore {
    private final ConnectionFactory connections;
    public ForumCommunityRepository(ConnectionFactory connections) { this.connections = connections; }

    @Override public Engagement engagement(long postId, long viewer) throws SQLException {
        try (Connection c = connections.openConnection()) {
            requireVisible(c, postId, false);
            return engagement(c, postId, viewer);
        }
    }
    @Override public Engagement setLiked(long postId, long actor, boolean enabled) throws SQLException {
        return setInteraction("forum_post_likes", postId, actor, enabled);
    }
    @Override public Engagement setBookmarked(long postId, long actor, boolean enabled) throws SQLException {
        return setInteraction("forum_post_bookmarks", postId, actor, enabled);
    }
    private Engagement setInteraction(String table, long postId, long actor, boolean enabled) throws SQLException {
        if (postId <= 0 || actor <= 0) throw new IllegalArgumentException("帖子或用户无效");
        try (Connection c = connections.openConnection()) {
            c.setAutoCommit(false);
            try {
                boolean visible = requireVisible(c, postId, !enabled);
                boolean present;
                try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM " + table + " WHERE post_id=? AND user_id=?")) {
                    s.setLong(1, postId); s.setLong(2, actor);
                    try (ResultSet r = s.executeQuery()) { present = r.next(); }
                }
                if (present != enabled) {
                    String sql = enabled ? "INSERT INTO " + table + " (post_id,user_id) VALUES (?,?)"
                            : "DELETE FROM " + table + " WHERE post_id=? AND user_id=?";
                    try (PreparedStatement s = c.prepareStatement(sql)) {
                        s.setLong(1, postId); s.setLong(2, actor); s.executeUpdate();
                    }
                }
                Engagement result = visible ? engagement(c, postId, actor) : new Engagement(0, false, false);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
    private boolean requireVisible(Connection c, long postId, boolean allowInvisible) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT p.status,s.enabled FROM forum_posts p "
                + "JOIN forum_sections s ON s.id=p.section_id WHERE p.id=? FOR UPDATE")) {
            s.setLong(1, postId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("帖子不存在或已不可访问");
                boolean visible = "NORMAL".equals(r.getString(1)) && r.getBoolean(2);
                if (!visible && !allowInvisible) throw new IllegalArgumentException("帖子不存在或已不可访问");
                return visible;
            }
        }
    }
    private Engagement engagement(Connection c, long postId, long viewer) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT (SELECT COUNT(*) FROM forum_post_likes WHERE post_id=?),
                       EXISTS(SELECT 1 FROM forum_post_likes WHERE post_id=? AND user_id=?),
                       EXISTS(SELECT 1 FROM forum_post_bookmarks WHERE post_id=? AND user_id=?)
                """)) {
            s.setLong(1, postId); s.setLong(2, postId); s.setLong(3, viewer);
            s.setLong(4, postId); s.setLong(5, viewer);
            try (ResultSet r = s.executeQuery()) { r.next(); return new Engagement(r.getInt(1), r.getBoolean(2), r.getBoolean(3)); }
        }
    }
    private String base() {
        return """
                SELECT p.*,s.name AS section_name,s.enabled,u.display_name,
                  (SELECT COUNT(*) FROM forum_post_likes l WHERE l.post_id=p.id) AS like_count,
                  CASE WHEN EXISTS(SELECT 1 FROM forum_post_likes l WHERE l.post_id=p.id AND l.user_id=?) THEN 1 ELSE 0 END AS liked,
                  CASE WHEN EXISTS(SELECT 1 FROM forum_post_bookmarks b WHERE b.post_id=p.id AND b.user_id=?) THEN 1 ELSE 0 END AS bookmarked
                FROM forum_posts p JOIN forum_sections s ON s.id=p.section_id
                JOIN users u ON u.id=p.author_user_id
                """;
    }
    @Override public FeedPage searchFeed(FeedQuery q) throws SQLException {
        if (q.viewerUserId() <= 0 || q.page() < 1 || q.page() > 100000 || q.pageSize() < 1 || q.pageSize() > 100)
            throw new IllegalArgumentException("分页参数无效");
        List<Object> args = new ArrayList<>(List.of(q.viewerUserId(), q.viewerUserId()));
        String where = q.scope() == ForumFeedScope.MINE ? " WHERE author_user_id=?" : " WHERE status='NORMAL' AND enabled=TRUE";
        if (q.scope() == ForumFeedScope.MINE) args.add(q.viewerUserId());
        if (q.scope() == ForumFeedScope.BOOKMARKS) where += " AND bookmarked=1";
        if (q.scope() == ForumFeedScope.ANNOUNCEMENTS) where += " AND is_announcement=TRUE";
        if (q.order() == ForumFeedOrder.FEATURED) where += " AND featured=TRUE";
        if (q.sectionId() != null) { where += " AND section_id=?"; args.add(q.sectionId()); }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            where += " AND (title LIKE ? ESCAPE '!' OR content LIKE ? ESCAPE '!' OR display_name LIKE ? ESCAPE '!')";
            String pattern = "%" + q.keyword().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.addAll(List.of(pattern, pattern, pattern));
        }
        String from = " FROM (" + base() + ") f" + where;
        String order = q.scope() == ForumFeedScope.HOME ? "pinned DESC," : "";
        if (q.scope() == ForumFeedScope.ANNOUNCEMENTS) order += "announced_at DESC,";
        if (q.order() == ForumFeedOrder.HOT) order += "(like_count + comment_count*2) DESC,";
        order += "created_at DESC,id DESC";
        try (Connection c = connections.openConnection()) {
            int total;
            try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*)" + from)) {
                bind(s, args); try (ResultSet r = s.executeQuery()) { r.next(); total = r.getInt(1); }
            }
            var paged = new ArrayList<>(args); paged.add(q.pageSize()); paged.add((q.page()-1)*q.pageSize());
            try (PreparedStatement s = c.prepareStatement("SELECT *" + from + " ORDER BY " + order + " LIMIT ? OFFSET ?")) {
                bind(s, paged);
                return new FeedPage(read(s), q.page(), q.pageSize(), total);
            }
        }
    }
    @Override public List<FeedRow> hotToday(long viewer, Instant from, Instant to) throws SQLException {
        String score = """
                ((SELECT COUNT(*) FROM forum_post_likes l WHERE l.post_id=f.id AND l.created_at>=? AND l.created_at<?)
                 + 2*(SELECT COUNT(*) FROM forum_comments c WHERE c.post_id=f.id AND c.status='NORMAL'
                        AND c.created_at>=? AND c.created_at<?))
                """;
        String sql = "SELECT * FROM (SELECT f.*," + score + " AS heat FROM (" + base()
                + ") f WHERE status='NORMAL' AND enabled=TRUE) h WHERE heat>0 ORDER BY heat DESC,created_at DESC,id DESC LIMIT 5";
        try (Connection c = connections.openConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, List.of(Timestamp.from(from),Timestamp.from(to),Timestamp.from(from),Timestamp.from(to),viewer,viewer));
            return read(s);
        }
    }
    private List<FeedRow> read(PreparedStatement s) throws SQLException {
        List<FeedRow> rows = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String content = r.getString("content");
                Timestamp last = r.getTimestamp("last_commented_at");
                var post = new ForumStore.PostRow(r.getLong("id"),r.getLong("section_id"),r.getString("section_name"),
                        r.getLong("author_user_id"),r.getString("display_name"),r.getString("title"),
                        content.length() > 120 ? content.substring(0,120) + "…" : content,
                        ForumContentStatus.valueOf(r.getString("status")),r.getBoolean("locked"),r.getBoolean("pinned"),
                        r.getBoolean("featured"),r.getInt("view_count"),r.getInt("comment_count"),
                        r.getTimestamp("created_at").toInstant(),last == null ? null : last.toInstant());
                rows.add(new FeedRow(post,new Engagement(r.getInt("like_count"),r.getBoolean("liked"),r.getBoolean("bookmarked")),r.getBoolean("is_announcement")));
            }
        }
        return rows;
    }
    private void bind(PreparedStatement s, List<Object> args) throws SQLException {
        for (int i=0;i<args.size();i++) s.setObject(i+1,args.get(i));
    }
}
