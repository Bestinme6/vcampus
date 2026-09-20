package com.vcampus.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSettingsTest {
    @TempDir Path directory;

    @Test
    void loadsUtf8PropertiesBesideJarAndAnchorsRelativeDataPaths() throws Exception {
        Files.writeString(directory.resolve(ServerSettings.FILE_NAME), """
                db.url=jdbc:mysql://127.0.0.1:3306/vcampus
                db.user=vcampus_app
                db.password=课程演示密码
                server.port=9191
                storage.forum=data/forum-images
                """, StandardCharsets.UTF_8);

        ServerSettings settings = ServerSettings.load(directory, Map.of());

        assertTrue(settings.hasFile());
        assertEquals("课程演示密码",
                settings.value("VCAMPUS_DB_PASSWORD", "db.password", ""));
        assertEquals(9191, settings.integer("VCAMPUS_SERVER_PORT", "server.port", 9090));
        assertEquals(directory.resolve("data/forum-images").toAbsolutePath().normalize(),
                settings.path("VCAMPUS_FORUM_IMAGE_ROOT", "storage.forum", "data/forum-images"));
    }

    @Test
    void environmentOverridesFileAndAbsolutePathsRemainAbsolute() throws Exception {
        Files.writeString(directory.resolve(ServerSettings.FILE_NAME), """
                db.user=file-user
                storage.shop=data/shop-images
                """, StandardCharsets.UTF_8);
        Path override = directory.resolve("external-shop").toAbsolutePath();

        ServerSettings settings = ServerSettings.load(directory, Map.of(
                "VCAMPUS_DB_USER", "environment-user",
                "VCAMPUS_SHOP_IMAGE_DIR", override.toString()));

        assertEquals("environment-user",
                settings.value("VCAMPUS_DB_USER", "db.user", "fallback"));
        assertEquals(override.normalize(),
                settings.path("VCAMPUS_SHOP_IMAGE_DIR", "storage.shop", "data/shop-images"));
    }

    @Test
    void missingFileUsesDefaultsAndInvalidNumbersFailClearly() throws Exception {
        ServerSettings settings = ServerSettings.load(directory, Map.of());

        assertFalse(settings.hasFile());
        assertEquals(9090, settings.integer("VCAMPUS_SERVER_PORT", "server.port", 9090));
        assertThrows(IllegalArgumentException.class,
                () -> ServerSettings.load(directory, Map.of("VCAMPUS_SERVER_PORT", "nine"))
                        .integer("VCAMPUS_SERVER_PORT", "server.port", 9090));
    }
}
