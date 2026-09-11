# VCampus 虚拟校园系统

VCampus 是一个面向课程设计与演示的 Java 21 桌面系统，采用 MySQL 数据库、Java Socket 应用服务器和 JavaFX 客户端组成的三层架构。

当前默认交付入口为 JavaFX 客户端；Swing 客户端通过 `-Swing` 参数保留为兼容入口，不作为主演示路径。在线课堂不在当前交付范围内。

## 功能模块

- 认证、首次改密、会话与账户角色管理
- 学生档案与教师档案
- 课程、教学班、排课、选课、成绩与考试
- 消息发布、收件箱、已读/未读、删除、分页与未读角标
- 图书检索、借阅、预约、归还、罚款与管理员工作台
- 校园商店、购物车、订单、库存、退款与校园银行联动
- 校园银行账户、充值、调整、流水和幂等交易
- 校园论坛板块、发帖、评论、互动、举报、审核与附件

## 快速开始

准备 Java 21、Maven 3.9+ 和 MySQL 8.x，在仓库根目录执行：

```powershell
mvn clean verify
```

在 MySQL 中创建 `vcampus` 数据库和应用账号，并通过环境变量提供连接信息：

```powershell
$env:VCAMPUS_DB_URL = "jdbc:mysql://127.0.0.1:3306/vcampus?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:VCAMPUS_DB_USER = "vcampus_app"
$env:VCAMPUS_DB_PASSWORD = "<本机数据库密码>"
```

服务端启动时会自动检查并应用当前 schema/seed。若数据库中尚无管理员，在 Eclipse 中运行 `com.vcampus.server.AdminBootstrapMain` 完成一次性初始化；随后运行 `com.vcampus.server.ServerMain`。默认 Socket 端口为 `9090`。

启动默认客户端：

```powershell
.\scripts\run-client.ps1 -CheckRuntime
.\scripts\run-client.ps1
```

完整的权限要求、数据备份、环境变量和故障处理见 [交付指南](docs/delivery-guide.md)。仓库不提供或保存固定数据库密码与演示密码。

## 工程结构

- `vcampus-common`：共享协议、DTO、枚举和 `MessageCodec`
- `vcampus-server`：Socket、多线程、业务服务、权限校验和 JDBC
- `vcampus-client`：JavaFX/Swing 界面与客户端网络通信
- `database`：当前 schema、seed 与历史迁移资源
- `scripts`：本地启动辅助脚本
- `docs`：交付、测试、演示、模块与设计文档

## 文档入口

- [文档导航](docs/README.md)
- [需求与实现状态](docs/requirements.md)
- [系统架构](docs/architecture.md)
- [交付指南](docs/delivery-guide.md)
- [测试指南](docs/testing.md)
- [演示指南](docs/demo-guide.md)
- [交付进度](docs/milestones.md)

## 安全边界

客户端不直连 MySQL，权限校验在服务端执行，跨模块共享协议使用长度前缀二进制帧而非 Java 原生对象序列化。当前 Socket 传输未包含 TLS；若部署到非可信网络，应先增加传输加密或使用受控网络通道。
