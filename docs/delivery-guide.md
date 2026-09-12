# VCampus 交付指南

本文给出当前版本唯一的通用环境、构建、数据库和启动流程。各业务模块文档不再重复这些步骤。

## 1. 交付基线

- Java 21
- Maven 3.9 或更高版本
- MySQL 8.x
- Windows PowerShell（用于仓库内客户端启动脚本）
- 默认客户端：JavaFX
- 兼容客户端：Swing，仅用于兼容性检查，不作为课程演示主路径

系统始终保持三层结构：MySQL 数据库 → 应用服务器 → 桌面客户端。客户端不得直接连接 MySQL。

## 2. 构建

先关闭正在运行的 VCampus 服务端和客户端，再在仓库根目录执行：

```powershell
mvn clean verify
```

若只需在开发过程中快速复测，可执行：

```powershell
mvn test
```

`clean` 无法删除目标文件时，通常是运行中的 Java 进程仍占用 JAR。关闭对应服务端或客户端后重试，不要直接删除工作区。

## 3. 数据库准备

### 3.1 创建数据库和应用账号

首次部署时，运维人员应先在 MySQL 中创建 `vcampus` 数据库和具备所需 DDL/DML 权限的应用账号。项目不会代替数据库管理员创建 MySQL 实例、数据库账号或授权。

推荐通过环境变量提供连接信息：

```powershell
$env:VCAMPUS_DB_URL = "jdbc:mysql://127.0.0.1:3306/vcampus?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:VCAMPUS_DB_USER = "vcampus_app"
$env:VCAMPUS_DB_PASSWORD = "<本机数据库密码>"
```

不要把真实密码写入 README、脚本、`server.properties` 或 Git 提交。

### 3.2 自动初始化与升级

服务端启动时会按资源指纹检查并应用当前 `database/schema.sql` 和 `database/seed.sql`。历史迁移文件仅用于追溯或处理特殊旧库，不是当前新环境的常规启动步骤。

执行自动升级前应备份已有数据库。运行服务端的数据库账号必须具备当前脚本需要的建表、改表、索引和数据读写权限。

### 3.3 首个超级管理员

当数据库中还没有可登录管理员时，在 Eclipse 中使用与服务端相同的数据库环境变量运行：

```text
com.vcampus.server.AdminBootstrapMain
```

通过 `VCAMPUS_BOOTSTRAP_PASSWORD` 临时传入不少于 8 位的初始密码；可选配置：

- `VCAMPUS_BOOTSTRAP_USERNAME`，默认 `admin`
- `VCAMPUS_BOOTSTRAP_DISPLAY_NAME`，默认 `系统管理员`

初始化成功后立即清理终端会话中的密码变量，并通过账户管理维护后续演示账号。仓库不提供固定演示密码。

## 4. 启动

### 4.1 服务端

在 Eclipse 中运行：

```text
com.vcampus.server.ServerMain
```

常用服务端环境变量：

- `VCAMPUS_SERVER_PORT`：监听端口，默认 `9090`
- `VCAMPUS_SERVER_THREADS`：业务线程数
- `VCAMPUS_FORUM_IMAGE_ROOT`：论坛图片存储目录
- `VCAMPUS_PAPER_DIR`：论文 PDF 文件目录
- `VCAMPUS_SHOP_IMAGE_DIR`：商品图片目录

文件存储目录应位于可备份、可写的本地数据目录，不要指向源码资源目录。

### 4.2 JavaFX 客户端

先检查 JavaFX 运行时：

```powershell
.\scripts\run-client.ps1 -CheckRuntime
```

再启动默认客户端：

```powershell
.\scripts\run-client.ps1
```

如需连接其他服务端，可配置：

- `VCAMPUS_HOST`，默认 `127.0.0.1`
- `VCAMPUS_PORT`，默认 `9090`

兼容性检查可使用：

```powershell
.\scripts\run-client.ps1 -Swing
```

## 5. 数据与文件备份

交付前至少备份：

- MySQL `vcampus` 数据库
- 论坛图片目录
- 论文 PDF 文件目录
- 商品图片目录
- 演示账号与角色清单（单独安全保存，不进入仓库）

恢复时应保证数据库和文件目录来自同一备份时点，避免数据库记录存在但附件缺失。

## 6. 交付检查清单

- [ ] `mvn clean verify` 在关闭应用后通过
- [ ] `git status` 中没有密码、临时数据、日志或无意生成的截图
- [ ] 数据库已备份，服务端能自动完成当前 schema/seed 检查
- [ ] 超级管理员、教师/业务管理员和学生演示账号均可登录
- [ ] 服务端与客户端来自同一提交
- [ ] JavaFX 客户端启动脚本通过运行时检查
- [ ] 按 [测试指南](testing.md) 完成自动化与必要的 MySQL 验收
- [ ] 按 [演示指南](demo-guide.md) 完成一次完整彩排
- [ ] 交付包不包含 `.env`、真实密码、令牌、个人数据、运行日志和本地缓存

## 7. 常见问题

### 客户端无法连接

确认服务端已启动、主机和端口一致，并检查防火墙是否允许本机连接。连接失败不会授权客户端直接访问数据库。

### 数据表或字段缺失

确认服务端确实连接到预期数据库、应用账号具备升级权限，并查看服务端启动日志。不要盲目重复执行所有历史迁移脚本。

### 页面显示旧状态

关闭并重新启动来自同一构建产物的服务端和客户端；同时确认没有另一个旧服务端占用端口。
