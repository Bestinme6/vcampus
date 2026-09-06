# VCampus 架构设计

## 三层结构

```text
+-----------------------+
| JavaFX 桌面客户端       |
| 登录、导航、业务界面    |
+-----------+-----------+
            | TCP Socket / VCampus 协议
+-----------v-----------+
| Java 应用服务器         |
| 会话、权限、业务、线程池 |
+-----------+-----------+
            | JDBC
+-----------v-----------+
| MySQL 8.0              |
| 账号、业务数据、审计记录 |
+-----------------------+
```

## 代码模块依赖

```text
vcampus-client ----> vcampus-common <---- vcampus-server ----> MySQL Connector/J
```

客户端与服务端只能共享协议和通用模型，客户端不能依赖服务端模块。

## JavaFX 门户与 Swing 兼容层

`ClientMain` 默认启动 `CampusApplication`，`--swing` 保留旧入口。JavaFX 管理登录、首次强制改密、会话、侧栏、工作台和“我的校园”；`LegacyModuleBridge` 在 Swing EDT 上延迟创建并复用原有业务面板，通过 SwingNode 放入同一个窗口。

JavaFX 与 Swing 仅在各自 UI 线程更新，Socket 请求在后台执行。注销时停止未读轮询、关闭旧业务面板的所属对话框、清除页面并请求服务端注销；会话代次和日期请求代次阻止迟到响应覆盖新状态。未读刷新复用已有的不重叠轮询器。

`CampusDashboardLoader` 聚合既有本人业务查询。教务参考数据保留原有 `term.N` 三字段行，另加 `term.N.startDate`、`term.N.endDate`；银行新增 `bank.account.summary`，只读查询已有账户，不触发按需开户。这两项是无表结构变化的增量协议扩展，门户需与配套服务端一起更新。

教务由 `AcademicController`、类型化 `AcademicGateway` 和原生 JavaFX 角色工作区负责。模型分为全局 `courses`、按专业/入学年份版本化的 `curriculum_plans` 与方案课程，以及每学期实际开设的 `course_sections`。培养方案决定必修/选修，教学班决定教师、容量、招生范围和课表；学生在匹配课程下选择一个教学班。排课写入修订草稿，只有服务端完成教师和教室冲突检查后才发布。`academic.enrollment.switchSection` 在一个 JDBC 事务内锁定原班、新班和选课记录，失败时保持原选择与双方人数不变。

教务 Socket 调用在专用后台执行器运行，JavaFX 更新回到 Application Thread；页面切换、注销或关闭会递增请求代次并取消迟到回调。正常入口使用原生路由，消息可深链到角色课表、成绩或 `academic-section/{id}`；`--swing` 继续使用旧 `AcademicModulePanel`。既有数据库必须按序应用 `database/migrations/014_academic_curriculum_javafx.sql`。

图书馆由 `LibraryController` 和原生 JavaFX 读者/管理员视图负责。所有 Socket 请求在专用后台执行器运行，并通过请求代次丢弃离开页面或注销后的迟到响应。书目排序由服务端白名单枚举生成 `ORDER BY`，在分页前作用于完整结果集；历史借阅次数从借阅记录聚合。预约、正常归还、提醒写入和预约状态变更由服务端 JDBC 事务处理，客户端不接触数据库。

## 网络协议

`MessageCodec` 使用固定魔数、协议版本、消息类型、长度前缀字符串和键值参数传输请求与响应。

优点：

- 直接体现 Socket 与输入输出流技术；
- 避免不安全的 Java 原生对象反序列化；
- 可以限制单字段长度和参数数量；
- 后续可在保持帧格式的前提下扩展认证会话和文件分块。

## 服务端并发

服务端使用固定线程池处理客户端连接。每个连接可以连续发送多个请求；路由器根据 `action` 将请求交给相应业务服务。

## 开发顺序

1. 通信协议与服务器连接测试；
2. 用户、角色、权限和统一登录；
3. 学籍与教务；
4. 图书馆；
5. 商店与虚拟银行；
6. 论坛；
7. 服务器 GUI、审计、报表、文档和演示数据。

在线课堂不在本次课程作业的交付范围内。
