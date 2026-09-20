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
        ServerSettings settings = ServerSettings.current();
        return new ServerConfig(
                settings.integer("VCAMPUS_SERVER_PORT", "server.port", 9090),
                settings.integer("VCAMPUS_SERVER_THREADS", "server.threads", 32));
    }
}
