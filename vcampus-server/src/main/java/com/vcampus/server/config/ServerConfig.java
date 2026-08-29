package com.vcampus.server.config;

public record ServerConfig(int port, int workerThreads) {
    public ServerConfig {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (workerThreads < 1 || workerThreads > 512) {
            throw new IllegalArgumentException("workerThreads must be between 1 and 512");
        }
    }

    public static ServerConfig fromEnvironment() {
        return new ServerConfig(
                readInteger("VCAMPUS_SERVER_PORT", 9090),
                readInteger("VCAMPUS_SERVER_THREADS", 32));
    }

    private static int readInteger(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }
}

