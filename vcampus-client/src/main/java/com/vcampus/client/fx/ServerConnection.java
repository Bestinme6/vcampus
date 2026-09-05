package com.vcampus.client.fx;

import java.util.Map;

record ServerConnection(String host, int port) {
    ServerConnection {
        if (host == null || host.isBlank() || host.length() > 253)
            throw new IllegalArgumentException("请输入有效的服务器地址");
        host = host.trim();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应在 1—65535 之间");
    }
    static ServerConnection parse(String host, String port) {
        try { return new ServerConnection(host, Integer.parseInt(port.trim())); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("端口必须是 1—65535 的整数"); }
    }
    static ServerConnection defaults(Map<String,String> environment) {
        return parse(environment.getOrDefault("VCAMPUS_HOST", "127.0.0.1"),
                environment.getOrDefault("VCAMPUS_PORT", "9090"));
    }
}
