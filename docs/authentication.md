# 统一登录与权限设计

## 登录流程

1. Swing 客户端通过 `auth.login` 发送账号和密码；
2. 应用服务器使用参数化 SQL 和 MySQL `BINARY` 精确匹配登录名，因此大小写不同不能登录；
3. `PasswordHasher` 使用 PBKDF2-HMAC-SHA256 和独立随机盐验证密码；
4. 认证成功后，服务端创建 256 位随机会话令牌，有效期为 8 小时；
5. 服务端验证角色组合合法后创建会话，客户端按基础身份和附加角色展示工作台；
6. 退出时客户端发送 `auth.logout`，服务端立即销毁令牌；
7. 登录成功、失败、停用和退出操作写入 `audit_logs`。

密码不会以明文写入数据库或日志。当前课程演示采用普通 TCP Socket，跨不可信网络部署前还需要增加 TLS。

## Eclipse 初始化步骤

### 1. 初始化数据库

使用 MySQL Workbench 依次执行：

```text
database/schema.sql
database/seed.sql
```

如果此前已经初始化过数据库，可再次执行最新 `database/schema.sql`，以补建教师档案等新表；脚本使用 `CREATE TABLE IF NOT EXISTS`，不会删除现有数据。然后再执行最新 `database/seed.sql` 补齐角色和基础数据。

### 2. 创建管理员运行配置

在 Eclipse 中右键 `AdminBootstrapMain.java`，选择 `Run As -> Run Configurations...`，打开 `Environment` 页签并添加：

```text
VCAMPUS_DB_USER              你的 MySQL 用户名
VCAMPUS_DB_PASSWORD          你的 MySQL 密码
VCAMPUS_BOOTSTRAP_USERNAME   admin
VCAMPUS_BOOTSTRAP_DISPLAY_NAME 系统管理员
VCAMPUS_BOOTSTRAP_PASSWORD   自定义的至少 8 位密码
```

运行成功后控制台会显示管理员 ID。随后删除 `VCAMPUS_BOOTSTRAP_PASSWORD`，避免密码长期保存在 Eclipse 运行配置中。

### 3. 配置并启动服务端

为 `ServerMain` 的运行配置添加相同的 `VCAMPUS_DB_USER` 和 `VCAMPUS_DB_PASSWORD`，然后依次启动：

```text
ServerMain
ClientMain
```

默认数据库地址为 `jdbc:mysql://localhost:3306/vcampus`，如需修改可设置 `VCAMPUS_DB_URL`。

## 权限原则

- 每个账号必须且只能拥有 `STUDENT`、`TEACHER`、`SUPER_ADMIN` 中的一种基础身份；
- 学生只能附加 `LIBRARY_ADMIN`、`FORUM_ADMIN`；教师可以附加六种业务管理员角色；
- 超级管理员不能附加其他角色，其账号与系统管理能力也不会由业务角色并集获得；
- 用户名唯一性保持不区分大小写，但登录验证严格区分大小写，例如 `admin` 与 `ADMin` 不等价；
- 非法角色组合即使直接写入数据库，也会在登录和创建会话时被服务端拒绝；
- 学生和教师可以进入其日常使用模块；
- 客户端模块隐藏只改善使用体验，最终业务接口仍必须由服务端校验会话和权限。

账号创建、角色分配和角色变更界面将在下一阶段实现；在该界面投入使用前，不建议直接手工修改 `user_roles`，以免产生服务端会拒绝的非法组合。
