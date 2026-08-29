package com.vcampus.server;

import com.vcampus.server.config.ServerConfig;
import com.vcampus.server.network.VCampusServer;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.fromEnvironment();
        VCampusServer server = new VCampusServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "vcampus-shutdown"));
        server.start();
    }
}

