package com.vcampus.server.database;

import com.vcampus.common.model.ForumFeedOrder;
import com.vcampus.common.model.ForumFeedScope;
import com.vcampus.server.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForumCommunityRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private ConnectionFactory connections;
    private ForumCommunityRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new ConnectionFactory(new DatabaseConfig(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        createSchema();
        seed();
        repository = new ForumCommunityRepository(
                connections, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void latestStaysChronologicalWhileHotUsesBoostAndKeepsPinFirst() throws Exception {
        var latest = repository.searchFeed(query(ForumFeedOrder.LATEST));
        var hot = repository.searchFeed(query(ForumFeedOrder.HOT));

        assertEquals(List.of(1L, 2L, 4L, 3L), ids(latest.rows()));
        assertEquals(List.of(1L, 3L, 2L, 4L), ids(hot.rows()));
        assertEquals(1, hot.rows().get(1).boost().activeCount());
        assertEquals(25, hot.rows().get(1).boost().activeHeat());
        assertEquals(0, hot.rows().get(3).boost().activeHeat());
    }

    @Test
    void todayHotUsesTodayInteractionsAndActiveBoostsFromBeforeDayStart() throws Exception {
        Instant shanghaiStart = Instant.parse("2026-09-14T16:00:00Z");
        Instant shanghaiEnd = Instant.parse("2026-09-15T16:00:00Z");

        var rows = repository.hotToday(9L, shanghaiStart, shanghaiEnd);

        assertEquals(List.of(1L, 3L), ids(rows));
        assertEquals(1, rows.get(1).boost().activeCount());
    }

    private ForumCommunityStore.FeedQuery query(ForumFeedOrder order) {
        return new ForumCommunityStore.FeedQuery(
                9L, ForumFeedScope.HOME, order, null, "", 1, 20);
    }

    private List<Long> ids(List<ForumCommunityStore.FeedRow> rows) {
        return rows.stream().map(row -> row.post().id()).toList();
    }

    private void createSchema() throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
            statement.execute("CREATE TABLE forum_sections(id BIGINT PRIMARY KEY,name VARCHAR(80),enabled BOOLEAN)");
            statement.execute("CREATE TABLE forum_posts(id BIGINT PRIMARY KEY,section_id BIGINT,author_user_id BIGINT," +
                    "title VARCHAR(160),content VARCHAR(1000),status VARCHAR(16),locked BOOLEAN,pinned BOOLEAN," +
                    "featured BOOLEAN,view_count INT,comment_count INT,created_at TIMESTAMP,updated_at TIMESTAMP," +
                    "last_commented_at TIMESTAMP,is_announcement BOOLEAN,announced_at TIMESTAMP)");
            statement.execute("CREATE TABLE forum_post_likes(post_id BIGINT,user_id BIGINT,created_at TIMESTAMP," +
                    "PRIMARY KEY(post_id,user_id))");
            statement.execute("CREATE TABLE forum_post_bookmarks(post_id BIGINT,user_id BIGINT,created_at TIMESTAMP," +
                    "PRIMARY KEY(post_id,user_id))");
            statement.execute("CREATE TABLE forum_comments(id BIGINT PRIMARY KEY,post_id BIGINT,status VARCHAR(16)," +
                    "created_at TIMESTAMP)");
            statement.execute("CREATE TABLE forum_post_boosts(id BIGINT PRIMARY KEY,post_id BIGINT," +
                    "initial_heat INT,created_at TIMESTAMP,expires_at TIMESTAMP)");
        }
    }

    private void seed() throws SQLException {
        execute("INSERT INTO users VALUES(9,'浏览者'),(10,'作者')");
        execute("INSERT INTO forum_sections VALUES(1,'校园',TRUE)");
        insertPost(1, "置顶", true, 20, NOW.minusSeconds(3 * 3600));
        insertPost(2, "最新", false, 0, NOW.minusSeconds(3600));
        insertPost(3, "跨日助力", false, 0, NOW.minusSeconds(10 * 3600));
        insertPost(4, "过期助力", false, 0, NOW.minusSeconds(2 * 3600));
        try (Connection connection = connections.openConnection();
             PreparedStatement like = connection.prepareStatement(
                     "INSERT INTO forum_post_likes VALUES(?,?,?)");
             PreparedStatement boost = connection.prepareStatement(
                     "INSERT INTO forum_post_boosts VALUES(?,?,?,?,?)")) {
            like.setLong(1, 1); like.setLong(2, 9);
            like.setTimestamp(3, Timestamp.from(NOW.minusSeconds(2 * 3600)));
            like.executeUpdate();
            boost.setLong(1, 1); boost.setLong(2, 3); boost.setInt(3, 100);
            boost.setTimestamp(4, Timestamp.from(NOW.minusSeconds(24 * 3600)));
            boost.setTimestamp(5, Timestamp.from(NOW.plusSeconds(24 * 3600)));
            boost.executeUpdate();
            boost.setLong(1, 2); boost.setLong(2, 4); boost.setInt(3, 150);
            boost.setTimestamp(4, Timestamp.from(NOW.minusSeconds(48 * 3600)));
            boost.setTimestamp(5, Timestamp.from(NOW));
            boost.executeUpdate();
        }
    }

    private void insertPost(long id, String title, boolean pinned, int views, Instant created)
            throws SQLException {
        String sql = "INSERT INTO forum_posts VALUES(?,1,10,?,'正文','NORMAL',FALSE,?,FALSE,?,0,?,?,NULL,FALSE,NULL)";
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, title);
            statement.setBoolean(3, pinned);
            statement.setInt(4, views);
            statement.setTimestamp(5, Timestamp.from(created));
            statement.setTimestamp(6, Timestamp.from(created));
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
