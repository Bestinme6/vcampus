package com.vcampus.server.config;

public record DatabaseConfig(String url, String username, String password) {
    public static DatabaseConfig fromEnvironment() {
        ServerSettings settings = ServerSettings.current();
        return new DatabaseConfig(
                settings.value("VCAMPUS_DB_URL", "db.url",
                        "jdbc:mysql://localhost:3306/vcampus?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"),
                settings.value("VCAMPUS_DB_USER", "db.user", "vcampus_app"),
                settings.value("VCAMPUS_DB_PASSWORD", "db.password", ""));
    }
}
