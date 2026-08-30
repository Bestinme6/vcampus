# VCampus

基于 C/S 架构的虚拟校园系统，使用 Java 21、Swing、Socket、多线程、I/O 流和 MySQL 8.0。

## 当前状态

当前已经完成工程骨架、统一登录、虚拟学籍、虚拟教务、消息中心和虚拟图书馆，包含：

- Maven 多模块结构；
- 客户端、服务端和公共协议的依赖边界；
- 长度前缀二进制 Socket 协议；
- 多线程 Socket 服务端；
- 白色 Swing 登录窗口和按基础身份、附加角色动态展示模块的单窗口工作台；
- MySQL 账号、角色、审计表以及多角色关联；
- PBKDF2 密码哈希、数据库认证、8 小时服务端会话和安全退出；
- 区分大小写的登录验证，以及学生、教师、超级管理员互斥的基础身份规则；
- 首个管理员初始化工具；
- `system.ping` 连通性测试动作；
- 学生档案、联系方式和学籍状态管理；
- 教师个人信息查询及电话、邮箱维护；
- 多时段课程与教学班管理、选退课冲突校验；
- 学生和教师使用星期一至星期日、每天 12 节的图形课表；
- 管理员通过图形课表排课，客户端禁选教师占用时间，服务端校验教师和教室冲突；
- 教师名单、成绩录入、成绩发布和学生成绩查询；
- 可检索、分页、查看详情和标记已读的消息中心；
- 排课、成绩发布、学籍状态及账号安全变更的事务内通知；
- 登录后立即刷新、随后每 10 秒刷新且不会重叠请求的未读消息角标；
- 各业务模块的动作命名约定以及协议、权限和规则测试。
- 图书检索、馆藏管理、自助及管理员借还、一次续借、逾期阻断与到期提醒；
- 按读者和图书管理员角色动态显示、嵌入工作台右侧的图书馆页面，以及直接定位“我的借阅”的消息中心深链；
- 嵌入工作台右侧的学生本人学籍、学籍管理和教师档案页面，以及学籍消息深链；
- 嵌入工作台右侧的教务页面，以及直接定位教师课表或学生成绩的消息深链；

统一登录、虚拟学籍管理、虚拟教务管理、消息中心和虚拟图书馆已经完成；商店、银行和论坛将在后续按模块迭代。

## 模块

| 模块 | 作用 |
| --- | --- |
| `vcampus-common` | 客户端和服务端共享的协议、消息和枚举 |
| `vcampus-server` | Socket 服务、多线程处理、业务路由和 JDBC 数据访问 |
| `vcampus-client` | Swing 客户端、网络请求和界面 |
| `database` | MySQL 建表及演示数据脚本 |
| `docs` | 需求、架构和后续设计文档 |

## 在 Eclipse 中导入

1. 打开 Eclipse。
2. 选择 `File -> Import -> Maven -> Existing Maven Projects`。
3. Root Directory 选择本项目根目录。
4. 确认三个 Maven 模块都被选中并完成导入。
5. 确认项目 JRE 为 Java 21。

## 启动顺序

1. 先运行 `vcampus-server` 中的 `com.vcampus.server.ServerMain`。
2. 再运行 `vcampus-client` 中的 `com.vcampus.client.ClientMain`。
3. 客户端默认连接 `127.0.0.1:9090`。
4. 点击“测试连接”可以验证 Socket 通信。
5. 完成下方数据库初始化后，可以使用管理员账号登录。

当前通信协议适合本机课程演示。正式跨网络部署前，应为 Socket 增加 TLS，禁止明文传输登录密码，并在服务端使用专用密码哈希算法保存密码。

## 服务端环境变量

| 名称 | 默认值 | 说明 |
| --- | --- | --- |
| `VCAMPUS_SERVER_PORT` | `9090` | Socket 服务端口 |
| `VCAMPUS_SERVER_THREADS` | `32` | 客户端处理线程数 |
| `VCAMPUS_DB_URL` | `jdbc:mysql://localhost:3306/vcampus?...` | JDBC 地址 |
| `VCAMPUS_DB_USER` | `vcampus_app` | 数据库账号 |
| `VCAMPUS_DB_PASSWORD` | 空 | 数据库密码，不应提交到代码库 |

## 数据库与首个管理员

1. 在 MySQL Workbench 中依次执行 `database/schema.sql` 和 `database/seed.sql`。
2. 在 Eclipse 中打开 `Run -> Run Configurations -> Java Application`，为服务端和管理员初始化程序配置数据库环境变量。
3. 先运行一次 `com.vcampus.server.AdminBootstrapMain` 创建管理员，再运行服务端。

管理员初始化程序支持以下环境变量：

| 名称 | 默认值 | 说明 |
| --- | --- | --- |
| `VCAMPUS_BOOTSTRAP_USERNAME` | `admin` | 管理员登录名 |
| `VCAMPUS_BOOTSTRAP_DISPLAY_NAME` | `系统管理员` | 界面显示名称 |
| `VCAMPUS_BOOTSTRAP_PASSWORD` | 无 | 至少 8 位；只用于初始化进程，不写入代码或数据库明文 |

Eclipse 控制台不能安全隐藏密码，因此在 Eclipse 中运行初始化程序时必须设置 `VCAMPUS_BOOTSTRAP_PASSWORD`。管理员创建成功后应从运行配置中删除这个临时环境变量。

如果电脑上已经存在旧版 VCampus 数据库，不要删除原有数据；先停服并备份，再按文件编号执行尚未应用的迁移。其中消息中心为 `database/migrations/002_notifications.sql`，图书馆为 `database/migrations/003_library.sql`、`004_library_receipt_notifications.sql`、`010_library_usability.sql` 和 `011_library_damaged_returns.sql`。本次图书馆书目查询协议和归还状态均有扩展，迁移完成后必须同时部署配套的服务端和客户端，不能新旧版本混用。

## 已实现模块说明

- 统一登录与权限：[docs/authentication.md](docs/authentication.md)
- 虚拟学籍管理：[docs/student-management.md](docs/student-management.md)
- 虚拟教务管理：[docs/academic-management.md](docs/academic-management.md)
- 教师个人信息：[docs/teacher-profile.md](docs/teacher-profile.md)
- 消息中心：[docs/message-center.md](docs/message-center.md)
- 虚拟图书馆：[docs/library.md](docs/library.md)

后续依次开发商店、银行和论坛。在线课堂不在本次课程作业范围内。
