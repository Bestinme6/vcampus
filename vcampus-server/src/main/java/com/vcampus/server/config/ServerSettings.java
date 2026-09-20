package com.vcampus.server.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Configuration loaded from {@code server.properties} beside the executable JAR. */
public final class ServerSettings {
    public static final String FILE_NAME = "server.properties";
    private static volatile ServerSettings current = defaults();

    private final Path baseDirectory;
    private final Path source;
    private final Properties properties;
    private final Map<String, String> environment;

    private ServerSettings(Path baseDirectory, Path source, Properties properties,
                           Map<String, String> environment) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.source = source;
        this.properties = properties;
        this.environment = Map.copyOf(environment);
    }

    public static ServerSettings current() {
        return current;
    }

    public static ServerSettings installAdjacent(Class<?> anchor)
            throws IOException, URISyntaxException {
        Objects.requireNonNull(anchor, "anchor");
        Path location = Path.of(anchor.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().normalize();
        Path base = Files.isRegularFile(location) ? location.getParent()
                : Path.of("").toAbsolutePath().normalize();
        current = load(base, System.getenv());
        return current;
    }

    public static ServerSettings load(Path baseDirectory, Map<String, String> environment)
            throws IOException {
        Path base = Objects.requireNonNull(baseDirectory, "baseDirectory")
                .toAbsolutePath().normalize();
        Path source = base.resolve(FILE_NAME);
        Properties properties = new Properties();
        if (Files.exists(source)) {
            if (!Files.isRegularFile(source)) {
                throw new IOException("服务端配置不是普通文件：" + source);
            }
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return new ServerSettings(base, source, properties,
                Objects.requireNonNull(environment, "environment"));
    }

    public String value(String environmentName, String propertyName, String defaultValue) {
        if (environment.containsKey(environmentName)) {
            return environment.get(environmentName);
        }
        return properties.getProperty(propertyName, defaultValue);
    }

    public int integer(String environmentName, String propertyName, int defaultValue) {
        String value = value(environmentName, propertyName, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value.strip());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(propertyName + " 必须是整数", exception);
        }
    }

    public long longValue(String environmentName, String propertyName, long defaultValue) {
        String value = value(environmentName, propertyName, Long.toString(defaultValue));
        try {
            return Long.parseLong(value.strip());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(propertyName + " 必须是整数", exception);
        }
    }

    public Path path(String environmentName, String propertyName, String defaultRelativePath) {
        String configured = value(environmentName, propertyName, defaultRelativePath);
        Path path = Path.of(configured.strip());
        return (path.isAbsolute() ? path : baseDirectory.resolve(path)).normalize();
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public Path source() {
        return source;
    }

    public boolean hasFile() {
        return Files.isRegularFile(source);
    }

    private static ServerSettings defaults() {
        Path base = Path.of("").toAbsolutePath().normalize();
        return new ServerSettings(base, base.resolve(FILE_NAME), new Properties(), System.getenv());
    }
}
