# VCampus 校园论坛模块设计

日期：2026-08-28  
状态：已实现，待本机 MySQL 双客户端验收  
范围：论坛一期（板块、帖子、评论、软删除与人工审核）

## 1. 目标与边界

论坛模块用于已登录的学生、教师和管理员进行校内交流。实现必须保持现有三层结构：Swing 客户端通过现有 Socket 协议访问应用服务器，只有服务端可以通过 JDBC 访问 MySQL。

一期包含：

- 浏览启用的板块、帖子列表和帖子详情；
- 按关键词、板块和排序方式检索帖子并分页；
- 已登录用户发帖、评论以及软删除自己的帖子或评论；
- 论坛管理员管理板块，隐藏或恢复帖子和评论，锁定或解锁帖子，设置置顶和精华；
- 记录全部管理操作，保留被删除或隐藏内容以便追溯；
- 将现有三张论坛原型落地为 Swing 页面：论坛首页、帖子详情、内容管理。

一期不包含点赞、收藏、图片或附件上传、私信、举报、敏感词自动审核及论坛事件通知。这些功能可以在不破坏一期表结构的前提下后续扩展。

## 2. 角色与权限

所有 `forum.*` 请求必须先校验有效会话。权限由服务端判定，客户端仅负责隐藏或禁用无权限控件。

| 操作 | 普通已登录用户 | 内容作者 | `FORUM_ADMIN` | `SUPER_ADMIN` |
| --- | --- | --- | --- | --- |
| 查看正常内容 | 允许 | 允许 | 允许 | 允许 |
| 发帖、评论 | 允许 | 允许 | 允许 | 允许 |
| 删除自己的内容 | 不适用 | 允许，软删除 | 允许 | 允许 |
| 查看隐藏内容 | 不允许 | 不允许 | 允许 | 允许 |
| 隐藏、恢复任意内容 | 不允许 | 不允许 | 允许 | 允许 |
| 锁定、置顶、设为精华 | 不允许 | 不允许 | 允许 | 允许 |
| 管理板块 | 不允许 | 不允许 | 允许 | 允许 |
| 查看管理日志 | 不允许 | 不允许 | 允许 | 允许 |

锁定帖子后，普通用户和作者都不能继续评论；管理员仍可执行管理操作。用户删除帖子时，其评论不物理删除，但帖子及其评论不再出现在普通列表和详情中。`HIDDEN` 内容可以由管理员恢复为 `NORMAL`；作者主动删除形成的 `DELETED` 内容只供管理员审计，不通过一期界面恢复。

## 3. 数据模型

### 3.1 `forum_sections`

- `id BIGINT`：主键；
- `code VARCHAR(40)`：稳定且唯一的板块代码；
- `name VARCHAR(80)`：展示名称；
- `description VARCHAR(255)`：板块简介；
- `sort_order INT`：排序；
- `enabled BOOLEAN`：是否对普通用户开放；
- `created_by_user_id BIGINT NULL`、`created_at`、`updated_at`：追踪字段，系统预置板块的创建人为空。

板块不物理删除，停用后不允许新发帖；已有内容仍可由管理员查看。

### 3.2 `forum_posts`

- `id BIGINT`：主键；
- `section_id BIGINT`、`author_user_id BIGINT`：板块与作者外键；
- `title VARCHAR(160)`、`content TEXT`：标题与正文；
- `status VARCHAR(16)`：`NORMAL`、`DELETED`、`HIDDEN`；
- `locked BOOLEAN`、`pinned BOOLEAN`、`featured BOOLEAN`；
- `view_count INT`、`comment_count INT`：演示所需统计字段；
- `created_at`、`updated_at`、`last_commented_at`、`deleted_at`。

普通列表仅返回 `NORMAL` 状态且所属板块启用的帖子。排序优先级为置顶、最后回复时间、帖子编号。帖子详情读取成功后由服务端原子增加浏览量。

### 3.3 `forum_comments`

- `id BIGINT`：主键；
- `post_id BIGINT`、`author_user_id BIGINT`：帖子与作者外键；
- `content VARCHAR(2000)`；
- `status VARCHAR(16)`：`NORMAL`、`DELETED`、`HIDDEN`；
- `created_at`、`updated_at`、`deleted_at`。

一期评论为单层时间线，不实现楼中楼。新增或状态变化时，由同一数据库事务维护帖子的 `comment_count` 和 `last_commented_at`。

### 3.4 `forum_moderation_logs`

- `id BIGINT`：主键；
- `operator_user_id BIGINT`：执行管理员；
- `target_type VARCHAR(16)`：`SECTION`、`POST`、`COMMENT`；
- `target_id BIGINT`；
- `action VARCHAR(32)`：如 `HIDE`、`RESTORE`、`LOCK`、`PIN`、`FEATURE`、`SECTION_UPDATE`；
- `reason VARCHAR(255)`：隐藏等违规处理必须填写；
- `created_at`。

日志只追加、不更新、不删除。管理员操作与日志写入放在同一事务中。

## 4. 协议设计

共享动作常量放入 `vcampus-common` 的 `Actions`，全部使用 `forum.` 前缀：

- `forum.section.list`
- `forum.post.search`
- `forum.post.get`
- `forum.post.create`
- `forum.post.delete`
- `forum.comment.list`
- `forum.comment.create`
- `forum.comment.delete`
- `forum.admin.section.save`
- `forum.admin.section.setEnabled`
- `forum.admin.content.search`
- `forum.admin.post.moderate`
- `forum.admin.comment.moderate`
- `forum.admin.log.search`

请求继续使用 `RequestMessage` 的字符串参数，并始终携带 `sessionToken`。列表响应沿用 `count`、`row.N`、`page`、`pageSize`、`total` 约定，每行通过 `RowCodec` 编码。帖子正文、评论正文和管理原因等用户输入不拼接 SQL，只通过参数化语句写入。

分页大小由服务端固定，客户端传页码。服务端要求标题去除首尾空白后为 4 至 160 个字符、正文为 1 至 10000 个字符、评论为 1 至 2000 个字符、管理原因为 2 至 255 个字符；同时校验板块名称等管理字段，忽略客户端上传的作者、计数、状态和权限相关字段。

## 5. 服务端结构

新增：

- `ForumAccessPolicy`：位于 common，表达普通访问和管理权限；
- `ForumService`：负责会话、输入、权限、状态迁移与响应编码；
- `ForumStore`：面向服务层的数据库接口，便于使用内存替身测试；
- `ForumRepository`：JDBC 查询、事务及并发一致性；
- 论坛记录模型与查询对象：位于 server，不泄露 JDBC 类型给客户端。

`VCampusServer` 构造 `ForumRepository` 和 `ForumService`，`RequestRouter` 将 `forum.*` 动作分派给论坛服务。帖子删除、评论新增、评论删除、管理员审核均使用事务；计数更新采用条件更新或重新统计，避免双客户端操作造成负数或重复计数。

## 6. Swing 客户端

工作台 `ModuleCode.FORUM` 改为真实嵌入式路由，并懒加载 `ForumModulePanel`。该面板使用内部 `CardLayout` 承载三类页面：

### 6.1 论坛首页

- 左侧或顶部提供板块筛选；
- 中部展示置顶、精华和普通帖子列表；
- 提供关键词搜索、最新回复/最新发布排序、分页和刷新；
- “发布帖子”弹窗选择板块并填写标题、正文；
- 点击帖子进入详情，不另开顶层窗口。

### 6.2 帖子详情

- 展示板块、标题、作者、发布时间、状态、浏览数和正文；
- 分页展示评论时间线；
- 未锁定时提供评论输入；
- 作者可删除自己的帖子或评论；
- 返回操作保持首页原有筛选和页码。

### 6.3 内容管理

仅 `FORUM_ADMIN` 和 `SUPER_ADMIN` 显示，包含板块、帖子、评论和操作日志四个管理视图。管理员可以筛选正常、隐藏或已删除内容，执行隐藏、恢复、锁定、置顶、精华与板块启停。需要原因的操作使用确认对话框收集原因。

所有 Socket 请求通过 `CompletableFuture` 或项目现有后台执行模式运行，加载期间禁用相关按钮；结果和错误统一通过 `SwingUtilities.invokeLater` 更新。组件关闭或页面切换后，过期响应不得覆盖新查询结果。

## 7. 错误与并发处理

- 会话失效：返回“登录已过期，请重新登录”；
- 目标不存在或普通用户无权查看：统一返回“内容不存在或不可访问”，避免泄露隐藏内容；
- 已锁定、已删除或已隐藏：服务端拒绝新增评论或重复状态操作；
- 板块停用：拒绝普通发帖；
- 双客户端同时审核：更新语句检查当前状态，后到请求得到可读的冲突提示；
- 数据库异常：记录服务端错误摘要，客户端只看到通用提示；
- 搜索输入中的 `%`、`_` 等字符按字面量转义。

## 8. 测试与验收

自动化测试至少覆盖：

- `forum.*` 路由及未知动作；
- 会话缺失、普通用户与管理员权限；
- 发帖、检索、分页、帖子详情和评论；
- 作者只能软删除自己的内容；
- 管理员隐藏、恢复、锁定、置顶、精华和板块启停；
- 锁帖禁止普通评论，隐藏内容不向普通用户泄露；
- 管理动作与日志、评论计数在同一事务提交；
- `RowCodec` 字段兼容中文、换行和特殊字符；
- `ModuleCode.FORUM` 工作台路由和管理员标签策略；
- Swing 后台请求完成后在 EDT 更新。

验收流程使用两个客户端账号：普通用户发帖和评论，管理员在另一客户端隐藏、恢复和锁帖，普通客户端刷新后应立即得到正确状态。最终运行根目录 `mvn clean verify`，并在本机 MySQL 8.0.44 执行 schema 后完成一次人工联调。

## 9. 文档与交付状态

实现时同步修改 `database/schema.sql`，并将 `docs/requirements.md` 的校园论坛状态更新为“开发完成，待本机 MySQL 双客户端验收”或实际验收结果。由于当前目录没有 Git 元数据，设计文档和后续改动无法在此工作区创建提交；若之后恢复 Git 仓库，再按模块范围提交。
