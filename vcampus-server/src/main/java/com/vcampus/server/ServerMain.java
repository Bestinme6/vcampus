package com.vcampus.server;

import com.vcampus.server.config.ServerConfig;
import com.vcampus.server.network.VCampusServer;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.config.ServerSettings;
import com.vcampus.server.database.DatabaseBootstrapper;

/**
 * VCampus 应用服务器进程入口。
 *
 * <p>启动顺序为：读取环境配置、初始化或升级数据库结构、组装服务依赖、监听 Socket。
 * JVM 关闭钩子会释放监听端口、线程池和后台清理任务。</p>
 *
 * @since 1.0
 */
public final class ServerMain {
    private ServerMain() {
    }

    /**
     * 启动应用服务器。
     *
     * @param args 当前版本不使用命令行参数
     * @throws Exception 配置、数据库初始化或 Socket 监听失败时抛出
     */
    public static void main(String[] args) throws Exception {
        ServerSettings settings = ServerSettings.installAdjacent(ServerMain.class);
        System.out.println(settings.hasFile()
                ? "已加载服务端配置：" + settings.source()
                : "未找到 server.properties，使用默认配置：" + settings.baseDirectory());
        ServerConfig config = ServerConfig.fromEnvironment();
        new DatabaseBootstrapper(DatabaseConfig.fromEnvironment()).initialize();
        VCampusServer server = new VCampusServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "vcampus-shutdown"));
        server.start();
    }
}
