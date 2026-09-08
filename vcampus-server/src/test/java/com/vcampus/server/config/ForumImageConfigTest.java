package com.vcampus.server.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ForumImageConfigTest {
    @Test void defaultsUseDedicatedFiveMegabyteStore(){var c=ForumImageConfig.fromEnvironment(Map.of());assertTrue(c.root().endsWith(Path.of("data","forum-images")));assertEquals(5L*1024*1024,c.maxImageBytes());}
    @Test void customRootIsNormalized(){var c=ForumImageConfig.fromEnvironment(Map.of("VCAMPUS_FORUM_IMAGE_ROOT","build/forum"));assertTrue(c.root().isAbsolute());assertTrue(c.root().endsWith(Path.of("build","forum")));}
}
