package com.vcampus.server.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ForumImageConfig(Path root,long maxImageBytes,int chunkBytes,long maxPixels,Duration uploadTtl) {
    public static final long DEFAULT_MAX_IMAGE_BYTES=5L*1024*1024;
    public static final int DEFAULT_CHUNK_BYTES=192*1024;
    public static final long DEFAULT_MAX_PIXELS=24_000_000L;
    public static final Duration DEFAULT_UPLOAD_TTL=Duration.ofMinutes(30);
    public ForumImageConfig {Objects.requireNonNull(root);Objects.requireNonNull(uploadTtl);root=root.toAbsolutePath().normalize();if(maxImageBytes<1||chunkBytes<1||chunkBytes>maxImageBytes||maxPixels<1||uploadTtl.isNegative()||uploadTtl.isZero())throw new IllegalArgumentException("论坛图片配置无效");}
    public static ForumImageConfig fromEnvironment(){ServerSettings settings=ServerSettings.current();return new ForumImageConfig(settings.path("VCAMPUS_FORUM_IMAGE_ROOT","storage.forum","data/forum-images"),DEFAULT_MAX_IMAGE_BYTES,DEFAULT_CHUNK_BYTES,DEFAULT_MAX_PIXELS,DEFAULT_UPLOAD_TTL);}
    public static ForumImageConfig fromEnvironment(Map<String,String> environment){String value=Objects.requireNonNull(environment).get("VCAMPUS_FORUM_IMAGE_ROOT");return new ForumImageConfig(value==null||value.isBlank()?Path.of("data","forum-images"):Path.of(value),DEFAULT_MAX_IMAGE_BYTES,DEFAULT_CHUNK_BYTES,DEFAULT_MAX_PIXELS,DEFAULT_UPLOAD_TTL);}
    public ShopImageConfig asStoreConfig(){return new ShopImageConfig(root,maxImageBytes,chunkBytes,maxPixels,uploadTtl);}
}
