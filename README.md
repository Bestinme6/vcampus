# VCampus 虚拟校园系统

VCampus 是一个面向课程设计与演示的 Java 21 桌面系统，采用 MySQL 数据库、Java Socket 应用服务器和 JavaFX 客户端组成的三层架构。

当前默认交付入口为 JavaFX 客户端；Swing 客户端通过 `-Swing` 参数保留为兼容入口，不作为主演示路径。在线课堂不在当前交付范围内。

## 功能模块

- 认证、首次改密、登录后账号安全、会话与账户角色管理
- 学生档案与教师档案
- 课程、教学班、排课、选课与成绩
- 消息发布、收件箱、已读/未读、删除、分页与未读角标
- 图书检索、借阅、预约、归还、罚款与管理员工作台
- 校园商店、购物车、订单、库存、退款与校园银行联动
- 校园银行账户、充值、调整、流水和幂等交易
- 校园论坛板块、发帖、评论、互动、举报、审核与附件
- 超级管理员维护学期与每日 12 节作息，并按用户、动作、结果和日期查询统一审计日志

## 快速开始

准备 JDK 21、支持 Java 21 的 Eclipse（含 m2e）和 MySQL 8.x。命令行构建还需要 Maven 3.9+；只使用 Eclipse 时可通过内置的 Maven Build 执行相同目标。

### Eclipse 导入

1. 克隆仓库后选择 `File -> Import -> Maven -> Existing Maven Projects`；
2. `Root Directory` 选择包含根 `pom.xml` 的 `vcampus` 目录，勾选父工程和三个子模块后完成导入；
3. 在 `Window -> Preferences -> Java -> Installed JREs` 中确认工作区使用 JDK 21，而不是仅有 JRE；
4. 右键父工程，执行 `Maven -> Update Project...`，再执行 `Project -> Clean`；
5. 可在父工程选择 `Run As -> Maven build...`，Goals 填写 `clean verify`，或在仓库根目录执行：

```powershell
mvn clean verify
```

仓库刻意不提交 Eclipse 的 `.project`、`.classpath` 和 `.settings`；m2e 会根据各模块 `pom.xml` 生成本机工程配置。不要使用 `Existing Projects into Workspace` 导入。

使用 MySQL 管理员连接创建一个全新的 `vcampus` 数据库和应用账号（密码请自行设置，不要提交到 Git）：

```sql
CREATE DATABASE vcampus CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'vcampus_app'@'localhost' IDENTIFIED BY '<自定义数据库密码>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON vcampus.* TO 'vcampus_app'@'localhost';
```

在 Eclipse 的 `Run Configurations... -> Environment` 中为 `AdminBootstrapMain` 和 `ServerMain` 提供相同连接信息；PowerShell 中的等价设置如下：

```powershell
$env:VCAMPUS_DB_URL = "jdbc:mysql://127.0.0.1:3306/vcampus?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:VCAMPUS_DB_USER = "vcampus_app"
$env:VCAMPUS_DB_PASSWORD = "<本机数据库密码>"
```

服务端启动时会自动检查并应用当前 schema/seed。若数据库中尚无管理员，先为 `AdminBootstrapMain` 的环境增加一次性的 `VCAMPUS_BOOTSTRAP_PASSWORD`（至少 8 位），再按顺序以 `Run As -> Java Application` 运行：

1. `com.vcampus.server.AdminBootstrapMain`（仅首次运行）；
2. `com.vcampus.server.ServerMain`；
3. `com.vcampus.client.ClientMain`。

初始化管理员后应从 Eclipse 运行配置中删除 `VCAMPUS_BOOTSTRAP_PASSWORD`。默认 Socket 端口为 `9090`；客户端不需要数据库环境变量。

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
