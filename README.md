# VCampus

基于 C/S 架构的虚拟校园系统，使用 Java 21、JavaFX + CSS、Socket、多线程、I/O 流和 MySQL 8.0。新版门户通过 SwingNode 兼容已有 Swing 业务页面；客户端不直接连接数据库。

## 当前状态

当前已经实现工程骨架、统一登录、学籍、教务、消息中心、图书馆、商店、银行和论坛，具体验收状态见 [需求基线](docs/requirements.md)。包含：

- Maven 多模块结构；
- 客户端、服务端和公共协议的依赖边界；
- 长度前缀二进制 Socket 协议；
- 多线程 Socket 服务端；
- JavaFX 登录、首次改密、单窗口导航和按角色展示的工作台；登录插画采用浅蓝色东南大学大礼堂；
- “我的校园”提供本周日期切换、真实课表、借阅到期与待收货入口，以及借阅、订单和余额概览；
- 学籍、账号、教师信息、教务、消息中心、图书馆、商店、银行和论坛已迁移为原生 JavaFX + CSS；未迁移页面继续通过 SwingNode 兼容；
- `--swing` 仍保留完整旧界面入口，便于课程演示和回归；
- MySQL 账号、角色、审计表以及多角色关联；
- PBKDF2 密码哈希、数据库认证、8 小时服务端会话和安全退出；
- 区分大小写的登录验证，以及学生、教师、超级管理员互斥的基础身份规则；
- 首个管理员初始化工具；
- `system.ping` 连通性测试动作；
- 学生档案、联系方式和学籍状态管理；
- 教师个人信息查询及电话、邮箱维护；
- 全校课程库、按专业和连续入学年份生效的版本化培养方案，以及必修/选修和建议学期；
- 学生按培养方案查看课程，并在课程下选择、退选或原子切换具体教学班；
- 教学班招生范围、排课草稿、教师/教室冲突校验与发布后可见课表；
- 学生和教师使用星期一至星期日、每天 12 节的图形课表；
- 管理员通过图形课表排课，客户端禁选教师占用时间，服务端校验教师和教室冲突；
- 教师名单、成绩录入、成绩发布和学生成绩查询；
- 原生 JavaFX 消息中心：关键词、来源与已读筛选、分页、左右详情、点击标记已读、一键已读、单条软删除和业务深链；窄屏自动切换为上下布局；
- 排课、成绩发布、学籍状态及账号安全变更的事务内通知；
- 登录后立即刷新、随后每 10 秒刷新且不会重叠请求的未读消息角标；
- 各业务模块的动作命名约定以及协议、权限和规则测试。
- 原生 JavaFX 图书检索、馆藏管理、自助及管理员借还、一次续借、逾期阻断、到期提醒与学生归还提醒预约；
- 书目按编号、书名、分类和历史借阅次数排序，并显示可借、借出与累计借阅数据；图书馆通知可直达“我的借阅”或指定书目；
- 原生 JavaFX 学生本人学籍、学籍管理、教师信息和账号管理页面，以及学籍消息深链；
- 原生教务工作区，以及直达教师课表、学生课表、成绩页或指定教学班排课的消息深链；

目前门户、学籍、账号、教师信息、教务、消息中心、图书馆、商店、银行和论坛已使用原生 JavaFX；仍未迁移的页面继续通过 SwingNode 兼容。真实 MySQL 和多客户端验收仍按各模块文档进行。

## 模块

| 模块 | 作用 |
| --- | --- |
| `vcampus-common` | 客户端和服务端共享的协议、消息和枚举 |
| `vcampus-server` | Socket 服务、多线程处理、业务路由和 JDBC 数据访问 |
| `vcampus-client` | JavaFX 门户、Swing 兼容业务页面、Socket 网络请求 |
| `database` | MySQL 建表及演示数据脚本 |
| `docs` | 需求、架构和后续设计文档 |

## 在 Eclipse 中导入

1. 打开 Eclipse。
2. 选择 `File -> Import -> Maven -> Existing Maven Projects`。
3. Root Directory 选择本项目根目录。
4. 确认三个 Maven 模块都被选中并完成导入。
5. 确认项目 JRE 为 Java 21。
6. 已导入的旧工程需执行 `Maven -> Update Project`，下载 JavaFX 与图标依赖。

## 启动顺序

1. 先运行 `vcampus-server` 中的 `com.vcampus.server.ServerMain`。
2. 再运行 `vcampus-client` 中的 `com.vcampus.client.ClientMain`。
3. 客户端默认连接 `127.0.0.1:9090`。
4. 点击“测试连接”可以验证 Socket 通信。
5. 完成下方数据库初始化后，可以使用管理员账号登录。

### Windows 命令行构建和启动

在项目根目录使用 Java 21 执行：

```powershell
mvn clean verify
./scripts/run-client.ps1
```

若未全局安装 Maven，本项目已有本地工具时可使用：

```powershell
& './.tools/apache-maven-3.9.11/bin/mvn.cmd' -s './.tools/maven-settings.xml' clean verify
```

打包会将运行依赖复制到 `vcampus-client/target/lib`。启动脚本优先使用 `JAVA_HOME`，否则使用 PATH 中的 Java；`./scripts/run-client.ps1 -CheckRuntime` 可只检查模块解析，不打开窗口。旧界面使用 `./scripts/run-client.ps1 -Swing`，或向 `ClientMain` 传入 `--swing`。不要直接双击未包含依赖的客户端 JAR。

JavaFX 客户端初始地址可通过 `VCAMPUS_HOST`、`VCAMPUS_PORT` 设置，默认 `127.0.0.1:9090`；登录页的“连接设置”可修改并测试。登录成功后进入“我的校园”，左侧“工作台”保留所有按角色授权的业务入口。首次登录必须先修改密码。

新版“我的校园”需要配套服务端的学期起止日期元数据和只读余额接口，本次无需数据库迁移。界面效果与验证范围见 [JavaFX 设计与检查记录](docs/design/javafx-campus/design-qa.md)。

数据库结构现在由服务端启动时自动准备，更新论坛等模块不再需要逐个执行 SQL。详细功能、评论权限和双客户端验收步骤见 [校园论坛说明](docs/forum.md)。

当前通信协议适合本机课程演示。正式跨网络部署前，应为 Socket 增加 TLS，避免明文传输登录密码。服务端已经使用带盐 PBKDF2 密码哈希。

## 服务端环境变量

| 名称 | 默认值 | 说明 |
| --- | --- | --- |
| `VCAMPUS_SERVER_PORT` | `9090` | Socket 服务端口 |
| `VCAMPUS_SERVER_THREADS` | `32` | 客户端处理线程数 |
| `VCAMPUS_DB_URL` | `jdbc:mysql://localhost:3306/vcampus?...` | JDBC 地址 |
| `VCAMPUS_DB_USER` | `vcampus_app` | 数据库账号 |
| `VCAMPUS_DB_PASSWORD` | 空 | 数据库密码，不应提交到代码库 |
| `VCAMPUS_FORUM_IMAGE_ROOT` | `data/forum-images` | 论坛图片专用目录；单张上限 5 MiB，最多 2400 万像素，上传分块 192 KiB，暂存会话 30 分钟 |
| `VCAMPUS_PAPER_DIR` | `data/library-papers` | 论文 PDF 专用目录；应与 MySQL 一起备份，服务端账号需要读写权限 |
| `VCAMPUS_PAPER_MAX_BYTES` | `31457280` | 单个论文 PDF 最大字节数，默认 30 MiB，允许范围不超过 200 MiB |

## 数据库与首个管理员

服务端按打包的 `schema.sql` 和 `seed.sql` 指纹自动准备数据库。首次空库会建表并导入基础数据；脚本变化或缺表时会在现有数据库上补表、应用兼容升级并重跑幂等种子，**不会自动删除账号、课程、成绩或其他业务数据**。相同脚本的后续启动直接跳过初始化。

已有 MySQL 和 `vcampus` 数据库的同学，更新操作如下：

1. 关闭原服务端和客户端，拉取代码，执行 `mvn clean verify`；Eclipse 用户执行 `Maven -> Update Project`，确保 SQL 资源进入构建目录。
2. 为 `ServerMain` 和 `AdminBootstrapMain` 配置相同的 `VCAMPUS_DB_URL`、`VCAMPUS_DB_USER`、`VCAMPUS_DB_PASSWORD`。MySQL 账号需要对目标库拥有建表、修改结构及业务读写权限。
3. 仅在数据库中还没有超级管理员时运行 `com.vcampus.server.AdminBootstrapMain`。同名超级管理员已存在时，程序会提示无需重复创建并正常退出。
4. 运行 `com.vcampus.server.ServerMain`，再启动配套客户端。服务端会自动应用尚未安装的数据库脚本。

无需手动执行 `schema.sql`、`seed.sql` 或历史迁移脚本。数据库必须已存在（通常就是同学原来的 `vcampus` 库）；自动初始化不会安装 MySQL、创建数据库或创建 MySQL 用户。若只有 MySQL、尚无数据库，先由 MySQL 管理员创建空库并授权一次。

SQL 脚本随服务端 JAR 一起发布，无需从项目根目录启动。支持标准单库 JDBC 地址（库名使用字母、数字、下划线，最多 46 字符），会拒绝系统库和无法确定的目标。Workbench 脚本中的 `USE vcampus` 不影响自动初始化的实际目标；自动执行始终使用 JDBC 指定的库。

若缺权限、脚本执行失败或初始化锁超时，服务端会停止启动，不会通过删表重试。解决日志中提示的问题后重新启动即可继续；MySQL DDL 不能整体回滚，因此结构升级前仍应备份。升级时须关闭连接同一数据库的所有旧服务端，两个不同版本不应共用一个库。

管理员初始化程序支持以下环境变量：

| 名称 | 默认值 | 说明 |
| --- | --- | --- |
| `VCAMPUS_BOOTSTRAP_USERNAME` | `admin` | 管理员登录名 |
| `VCAMPUS_BOOTSTRAP_DISPLAY_NAME` | `系统管理员` | 界面显示名称 |
| `VCAMPUS_BOOTSTRAP_PASSWORD` | 无 | 至少 8 位；只用于初始化进程，不写入代码或数据库明文 |

Eclipse 控制台不能安全隐藏密码，因此在 Eclipse 中运行初始化程序时必须设置 `VCAMPUS_BOOTSTRAP_PASSWORD`。管理员创建成功后应从运行配置中删除这个临时环境变量。

历史 `database/migrations/` 脚本保留用于结构演进记录；自动准备使用完整 schema/seed 中的幂等建表和兼容升级语句。必须同时部署配套的服务端和客户端。

### 自动初始化测试

普通 `mvn clean verify` 不连接 MySQL。需要验证真实 MySQL 初始化与保留式升级时，在专用测试实例上设置数据库连接变量，并设置 `VCAMPUS_BOOTSTRAP_MYSQL_TEST=true` 后运行：

```powershell
mvn -pl vcampus-server -am '-Dtest=DatabaseScriptsTest,DatabaseBootstrapperMysqlTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试会创建并清理随机 `vcampus_test_` 数据库及一个临时只读用户，测试账号需要建库、删库、创建用户及授权权限。不要为此扩大日常应用账号权限；使用专用测试 MySQL 实例。普通运行服务端不需要建库或创建用户权限。

## 已实现模块说明

- 统一登录与权限：[docs/authentication.md](docs/authentication.md)
- 虚拟学籍管理：[docs/student-management.md](docs/student-management.md)
- 虚拟教务管理：[docs/academic-management.md](docs/academic-management.md)
- 教务 JavaFX 设计检查：[docs/design/javafx-academic/design-qa.md](docs/design/javafx-academic/design-qa.md)
- 教师个人信息：[docs/teacher-profile.md](docs/teacher-profile.md)
- 消息中心：[docs/message-center.md](docs/message-center.md)
- 消息中心 JavaFX 设计检查：[docs/design/javafx-notification/design-qa.md](docs/design/javafx-notification/design-qa.md)
- 虚拟图书馆：[docs/library.md](docs/library.md)
- 图书馆 JavaFX 设计检查：[docs/design/javafx-library/design-qa.md](docs/design/javafx-library/design-qa.md)
- 虚拟商店：[docs/shop.md](docs/shop.md)
- 虚拟银行：[docs/bank.md](docs/bank.md)
- 校园论坛：[docs/forum.md](docs/forum.md)
- JavaFX 门户：[docs/design/javafx-campus/design-qa.md](docs/design/javafx-campus/design-qa.md)

在线课堂不在本次课程作业范围内。
