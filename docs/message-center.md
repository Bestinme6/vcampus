# VCampus 消息中心

消息中心为正常登录且已经完成首次改密的用户提供站内通知。客户端只通过 Socket 请求应用服务器，不能直接读取 MySQL。

## 已实现功能

- 按关键词、来源和已读状态检索消息；
- 每页固定显示 10 条，按创建时间和消息 ID 倒序排列；
- 查看完整消息内容、单条标记已读和一键全部已读；
- 登录后立即查询未读数，此后每 10 秒查询一次；
- 同一时刻最多存在一个未读数请求，网络缓慢时不会重复堆积；
- 请求失败时保留上一次角标，不弹出错误窗口，并在下一周期自动重试；
- 关闭主窗口或退出登录后停止轮询；
- 未读角标显示 `1` 至 `99`，超过 99 条显示 `99+`。
- 提供“论坛通知”来源筛选，论坛详情可通过“查看帖子”打开关联帖子。

首版不支持删除、归档、私聊、附件、消息订阅或任意网址跳转。

## 业务事件矩阵

| 业务事件 | 接收者 | 消息类型 | 来源 | 固定跳转目标 |
| --- | --- | --- | --- | --- |
| 创建教学班并完成排课 | 任课教师 | `SCHEDULE_ASSIGNED` | `ACADEMIC` | `TEACHER_SCHEDULE` |
| 发布最终成绩 | 教学班中状态为 `ENROLLED` 的学生 | `GRADE_PUBLISHED` | `ACADEMIC` | `STUDENT_GRADES` |
| 学籍状态实际发生变化 | 对应学生 | `STUDENT_STATUS_CHANGED` | `STUDENT_STATUS` | `STUDENT_PROFILE` |
| 管理员角色实际发生变化 | 目标账号 | `ROLES_CHANGED` | `ACCOUNT_SECURITY` | `NONE` |
| 启用账号 | 目标账号 | `ACCOUNT_ENABLED` | `ACCOUNT_SECURITY` | `NONE` |
| 停用账号 | 目标账号 | `ACCOUNT_DISABLED` | `ACCOUNT_SECURITY` | `NONE` |
| 重置密码 | 目标账号 | `PASSWORD_RESET` | `ACCOUNT_SECURITY` | `NONE` |
| 借阅成功 | 借阅人 | `LIBRARY_BORROWED` | `LIBRARY` | `LIBRARY_LOANS` |
| 续借成功 | 借阅人 | `LIBRARY_RENEWED` | `LIBRARY` | `LIBRARY_LOANS` |
| 正常归还成功 | 借阅人 | `LIBRARY_RETURNED` | `LIBRARY` | `LIBRARY_LOANS` |
| 登记遗失完成 | 借阅人 | `LIBRARY_LOST` | `LIBRARY` | `LIBRARY_LOANS` |
| 三天内到期 | 借阅人 | `LIBRARY_DUE_SOON` | `LIBRARY` | `LIBRARY_LOANS` |
| 已经逾期 | 借阅人 | `LIBRARY_OVERDUE` | `LIBRARY` | `LIBRARY_LOANS` |
| 他人成功评论帖子 | 发帖人 | `FORUM_POST_COMMENTED` | `FORUM` | `FORUM_POST`（关联帖子 ID） |
| 管理员成功隐藏、恢复、锁定、解锁、置顶、取消置顶、设为精华或取消精华帖子 | 发帖人 | `FORUM_POST_MODERATED` | `FORUM` | `FORUM_POST`（关联帖子 ID） |
| 管理员成功隐藏或恢复评论 | 评论作者 | `FORUM_COMMENT_MODERATED` | `FORUM` | `FORUM_POST`（评论所属帖子 ID） |

保存成绩草稿不会发送消息，只有“发布最终成绩”才会通知学生；重复的角色配置或启停状态不会生成重复消息。论坛中的自评论、自管理、失败操作和未改变状态的重复操作不会生成通知；作者自行删除内容也不通知自己。

## 数据库升级

全新数据库：

1. 在 MySQL Workbench 中执行 `database/schema.sql`；
2. 再执行 `database/seed.sql`。

已经使用旧版 VCampus 数据库：

1. 先备份课程演示数据；
2. 在 MySQL Workbench 中重新执行最新的 `database/schema.sql`；
3. 再执行最新的 `database/seed.sql`；
4. 确认 `notifications` 表的三个检查约束已经包含图书馆和论坛枚举值；
5. 重启应用服务器和客户端。

最新版 `schema.sql` 使用 `CREATE TABLE IF NOT EXISTS` 保留已有业务数据，并会重建通知表的具名检查约束，因此重复执行不会删除已有消息。编号迁移脚本仍保留给需要按版本增量部署的环境；课程演示环境可以统一执行最新版 `schema.sql` 和 `seed.sql`，不需要重新创建数据库。

## 权限与隐私边界

- 服务端只使用当前会话的用户 ID 查询消息，忽略客户端伪造的接收者 ID；
- 获取详情和标记已读都校验消息所有权，其他用户的消息统一返回“消息不存在”；
- 强制修改初始密码期间不能访问消息中心；
- 消息跳转使用客户端枚举映射，只能打开教师课表、学生成绩、学籍信息、“我的借阅”或关联论坛帖子，不能执行服务器传来的 URL、类名或文件路径；
- 论坛帖子深链仍通过服务端权限校验；帖子已隐藏或删除且当前用户无权查看时，客户端提示“该帖子当前不可访问”并返回论坛首页；
- 密码重置消息不包含临时密码、密码哈希或盐值。

## 事务规则与后续模块接入

排课、成绩发布、学籍状态、账号安全修改、图书馆借还以及论坛评论与审核均在业务自身的 JDBC 事务中通过 `NotificationWriter` 写消息。论坛的内容状态、评论统计、审核日志和通知必须一起提交或一起回滚。

以后开发商店和银行时，也必须在各自业务事务的同一个 `Connection` 上调用 `NotificationWriter`。禁止业务提交成功后再单独建立连接补发消息，否则会出现业务成功但消息丢失的不一致状态。

## 手工验收流程

1. 启动 MySQL、`com.vcampus.server.ServerMain` 和两个 `com.vcampus.client.ClientMain`；
2. 用超级管理员创建教学班，任课教师应在 10 秒内看到未读角标并能从详情打开教师课表；
3. 教师发布最终成绩，在选学生应收到成绩消息，退课学生不应收到；
4. 学籍管理员修改学生状态，学生应看到旧状态、新状态和原因；
5. 超级管理员修改角色、启停账号或重置密码，目标账号应收到相应安全消息；
6. 验证检索、来源筛选、已读筛选、分页、单条已读和一键已读；
7. 停止服务器后等待 10 秒，界面不得弹出轮询错误；退出客户端后不应继续产生网络请求。
8. 用户 B 评论用户 A 的帖子，A 应收到一条论坛通知并能打开正确帖子；A 自评不应产生通知；
9. 管理员依次执行八种帖子管理动作，并隐藏、恢复 B 的评论，对应作者应逐条收到通知；自管理、失败或无变化操作不通知；
10. 隐藏或删除帖子后，普通用户从旧通知打开时应看到不可访问提示并返回论坛首页。
