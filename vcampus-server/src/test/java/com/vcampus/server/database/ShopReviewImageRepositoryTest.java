package com.vcampus.server.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.server.config.DatabaseConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShopReviewImageRepositoryTest {
    @Test
    void publicReadsOnlyNormalReviewImagesWhileAdminCanInspectAll() throws Exception {
        String url = "jdbc:h2:mem:review_image_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shop_product_reviews(id BIGINT PRIMARY KEY,status VARCHAR(16))");
            statement.execute("CREATE TABLE shop_review_images(id BIGINT PRIMARY KEY,review_id BIGINT,position INT,"
                    + "storage_key VARCHAR(160),thumbnail_storage_key VARCHAR(160),mime_type VARCHAR(32),"
                    + "byte_size BIGINT,sha256 CHAR(64),width INT,height INT,created_at TIMESTAMP)");
            statement.execute("INSERT INTO shop_product_reviews VALUES(1,'NORMAL'),(2,'HIDDEN'),(3,'DELETED')");
            statement.execute("INSERT INTO shop_review_images VALUES"
                    + "(11,1,0,'n','nt','image/png',10,'" + "a".repeat(64) + "',1,1,CURRENT_TIMESTAMP),"
                    + "(12,2,0,'h','ht','image/png',10,'" + "b".repeat(64) + "',1,1,CURRENT_TIMESTAMP),"
                    + "(13,3,0,'d','dt','image/png',10,'" + "c".repeat(64) + "',1,1,CURRENT_TIMESTAMP)");
        }
        var repository = new ShopReviewImageRepository(new ConnectionFactory(
                new DatabaseConfig(url, "sa", "")));
        assertTrue(repository.find(11, false).isPresent());
        assertTrue(repository.find(12, false).isEmpty());
        assertTrue(repository.find(13, false).isEmpty());
        assertTrue(repository.find(12, true).isPresent());
        assertEquals(6, repository.storageKeys().size());
    }
}
