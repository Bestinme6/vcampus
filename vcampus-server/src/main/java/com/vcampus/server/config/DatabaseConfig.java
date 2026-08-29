package com.vcampus.server.config;

public record DatabaseConfig(String url, String username, String password) {
    public static DatabaseConfig fromEnvironment() {
        return new DatabaseConfig(
                read("VCAMPUS_DB_URL",
                        "jdbc:mysql://localhost:3306/vcampus?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"),
                read("VCAMPUS_DB_USER", "vcampus_app"),
                read("VCAMPUS_DB_PASSWORD", ""));
    }

    private static String read(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }
}

