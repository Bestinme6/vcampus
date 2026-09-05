# Forum JavaFX Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将论坛迁移为真实联网的 JavaFX 页面，修复评论删除与审核边界，并实现点赞、收藏、回复、热榜及公告。

**Architecture:** 保留 common → server → client 的现有职责与长度前缀 Socket 协议。现有 ForumStore/ForumRepository 继续负责发帖、评论、审核；新 ForumCommunityStore/ForumCommunityRepository 负责互动、个人列表与聚合查询。JavaFX 使用独立网关、控制器和视图，通过现有会话接入门户；旧 Swing 保留回退与权限兼容。

**Tech Stack:** Java 21, JavaFX 21, CSS, Ikonli Feather, Maven, JUnit 5, JDBC, MySQL；现有 H2 测试仅覆盖兼容 SQL，不能冒充 MySQL 验证。

**Spec:** `docs/superpowers/specs/2026-08-31-forum-javafx-upgrade-design.md`（用户已确认）。

## Global Constraints

- 保留三层 C/S、Java 21、现有 Socket 协议和消息中心。
- 不做匿名、图片上传、投票、聊天或全站 UI 重写。
- 按钮文字统一黑色 #111111；主要按钮用浅蓝 #DCE8FF。
- 页面背景 #F4F7FC，卡片白色，主文字 #172139，辅助文字 #64748B，边框 #DCE5F2，强调蓝 #2F5FCB。
- 页面标题 26px，帖子标题 20px，正文 15px，辅助文字 13px。
- 页面内边距 28px，分栏间距 24px，卡片间距 16px，卡片内边距 22px，圆角 12px。
- 可用内容区宽度达到 1080px 时显示右侧 280px 信息栏；小窗口保留公告入口。
- 所有网络请求在后台执行器，所有新 UI 更新在 FX Application Thread；保留请求代次和会话代次检查。
- 不改动现有无关工作区变更、不替用户启动正式服务端、不修改真实数据库或推送远端。
- 使用独立 forum.css，选择器以 `.forum-root` 开头，不改全局登录页 primary 配色。
- 最新 schema.sql + seed.sql 支持新库及旧库重复执行，不重置已有业务数据。

## 执行前检查与命令

当前目录有尚未提交的 JavaFX 门户和其他模块改动，其中 CampusApplication.java、LegacyModuleBridge.java 为未跟踪文件。本轮必须基于这些当前文件增量修改，不能从 HEAD 恢复、覆盖或只复制已提交代码。执行前依据 using-git-worktrees 与用户确认工作区选择；不能在隔离目录遗漏这些依赖。

PowerShell 命令前缀（仅本进程变量，不覆盖系统环境变量）：

```powershell
$forumMvn = 'C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\apache-maven-3.9.16\bin\mvn.cmd'
$forumRepo = '-Dmaven.repo.local=C:\Users\Bestinme\Documents\Codex\2026-08-25\wo\work\tools\m2-repository'
& $forumMvn $forumRepo clean verify
```

每个任务先写失败测试、运行并确认失败原因，再实现和重跑。下面的 Java 片段为测试核心与实现约束；放入指明的已有测试夹具或新增测试类中，补全标准 import，不复制生产逻辑进测试。提交只包含本任务文件/差异，不使用 `git add .`，不把其他人工作归入本次提交；重叠文件的整文件提交要先确认归属。

---

## Task 1: 修复评论可见性与作者删除边界

**Files**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumPostDetailPanel.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumCommentActionPolicy.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`
- Test: `vcampus-server/src/test/java/com/vcampus/server/service/ForumServiceTest.java`
- Create test: `vcampus-client/src/test/java/com/vcampus/client/ui/ForumCommentActionPolicyTest.java`

**Interfaces:** 保持 ForumStore.deleteComment(long,long,boolean)、CommentQuery、CommentRow 的现有签名兼容，但管理员标记不再赋予他人评论删除权限。新增 `ForumCommentActionPolicy.deletionWarning(ForumViewData.CommentRow): String`，可删除返回空串；否则返回未选择、状态变化或无权删除的中文提示。

- [ ] 在现有 ForumRepositoryTest 的 repository/SQL 夹具中添加：

```java
@Test void administratorCannotDeleteAnotherAuthorsComment() throws SQLException {
    long post = repository.createPost(1L, new CreatePost(1L, "权限测试", "正文"));
    long comment = repository.createComment(post, 2L, "作者评论");
    assertEquals(MutationResult.FORBIDDEN, repository.deleteComment(comment, 3L, true));
    assertFalse(repository.listComments(new CommentQuery(post, 3L, true, 1, 20))
            .rows().getFirst().canDelete());
}
@Test void deletedCommentIsAbsentFromAdministratorReadingList() throws SQLException {
    long post = repository.createPost(1L, new CreatePost(1L, "列表测试", "正文"));
    long comment = repository.createComment(post, 2L, "作者评论");
    repository.deleteComment(comment, 2L, false);
    assertEquals(0, repository.listComments(new CommentQuery(post, 3L, true, 1, 20)).total());
    assertEquals("DELETED", scalarString("SELECT status FROM forum_comments WHERE id=" + comment));
}
```

- [ ] 运行 `& $forumMvn $forumRepo -pl vcampus-server -am '-Dtest=ForumRepositoryTest,ForumServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，预期新增断言失败，保留失败证据。
- [ ] 将日常查询条件固定为 `c.status = 'NORMAL'`；canDelete 仅为 `status == NORMAL && author == viewerUserId`；删除事务验证 `state.authorUserId() == actorUserId`。保留管理员独立审核查询与 HIDE/RESTORE 事务。
- [ ] 补充隐藏评论、管理员删除自己评论、管理页保留删除记录、DELETED 不可恢复、普通用户伪造管理员参数的测试。将服务测试中的会话角色分别设为 STUDENT、STUDENT+FORUM_ADMIN、SUPER_ADMIN，验证仍使用服务端会话决定权限。
- [ ] 策略测试断言 null 返回“请先选择一条评论”；非 NORMAL 返回“这条评论的状态已变化，请刷新后重试”；正常但 canDelete=false 返回“只能删除自己发布的评论；管理他人评论请到内容管理中选择隐藏”。Swing 删除前确认，取消不发送请求。
- [ ] 运行上述服务端测试与 `ForumCommentActionPolicyTest,ForumViewDataTest`，通过后审阅差异；提交独立论坛修复，不夹带已有 Swing 生命周期改动。

## Task 2: 数据库与幂等点赞、收藏

**Files**
- Modify: `database/schema.sql`, `database/seed.sql`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityStore.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityRepository.java`
- Create tests: `vcampus-server/src/test/java/com/vcampus/server/database/ForumCommunityRepositoryTest.java`, `ForumCommunitySchemaTest.java`
- Modify test fixtures: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`

**Interfaces:** 在 ForumCommunityStore 中定义 `record Engagement(int likeCount, boolean liked, boolean bookmarked)`，`Engagement engagement(long postId,long viewerUserId)`，`Engagement setLiked(long postId,long actorUserId,boolean enabled)`，`Engagement setBookmarked(long postId,long actorUserId,boolean enabled)`，均 throws SQLException。实现构造器为 `ForumCommunityRepository(ConnectionFactory connections)`。不可访问使用现有业务异常惯例返回明确失败，不以零计数掩盖。

- [ ] 新测试夹具创建 UUID 命名 H2 数据库、用户 1/2/3、板块1及正常帖，通过现有 ConnectionFactory 与 ForumRepository 创建业务数据。不要读取真实 MySQL 配置。
- [ ] 幂等测试核心：

```java
var community = new ForumCommunityRepository(connections);
community.setLiked(postId, 2L, true);
assertEquals(1, community.setLiked(postId, 2L, true).likeCount());
assertTrue(community.engagement(postId, 2L).liked());
assertFalse(community.engagement(postId, 1L).liked());
assertEquals(0, community.setLiked(postId, 2L, false).likeCount());
assertEquals(0, community.setLiked(postId, 2L, false).likeCount());
```

- [ ] 运行新测试，先确认缺少功能失败。schema 新建 forum_post_likes/forum_post_bookmarks，字段 post_id、user_id、created_at；复合主键(post_id,user_id)，分别外键关联帖子和用户；增加 user_id,created_at,post_id 索引供个人列表，点赞另加 created_at,post_id 索引。
- [ ] 在事务内先锁定帖子，验证 NORMAL 且板块启用，再按目标状态插入/删除并返回实际计数，唯一键兜底。取消收藏允许清理本人不可访问帖的收藏；不可访问帖响应不得泄露正文。不同用户并发、同用户重复操作均不得重复计数。
- [ ] 测试正常、隐藏、删除、禁用板块、非法ID、取消收藏及并发交错；验证不写 notifications。schema 的新字段升级使用 information_schema 检查后 PREPARE/EXECUTE，不假定 CREATE TABLE IF NOT EXISTS 会升级旧表。
- [ ] 将评论 reply_to_comment_id NULL、帖子 is_announcement 默认0和 announced_at NULL 一并加入新库定义及旧库幂等升级，reply_to_comment_id 有索引与自引用外键。添加公告索引。后续任务实现语义。
- [ ] seed 只补缺失的演示分类/内容，不修改已有互动，不创建伪造热榜数值；保留现有 sql_safe_updates 保存/恢复。测试静态 SQL 覆盖只作为辅助，不声称执行验证。
- [ ] 跑 repository/schema 测试；有获授权的隔离 MySQL 才运行旧库→schema两遍→seed两遍与数据保留验证，否则记为未验证。审阅本任务差异并提交独立新文件和SQL。

## Task 3: 评论回复与通知去重

**Files**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumStore.java`, `ForumRepository.java`, `ForumNotificationFactory.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/ForumService.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/NotificationType.java`
- Modify: `database/schema.sql`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumRepositoryTest.java`, `ForumNotificationFactoryTest.java`, `ForumMigrationTest.java`

**Interfaces:** 增加 `createComment(long postId,long authorUserId,String content,Long replyToCommentId)`；原三参数方法委托新方法并传 null。CommentRow 增加 replyToCommentId、replyToDisplayName、replyTargetVisible，保留旧八参数构造器。普通评论协议仍编码原八字段，新增旁路键 `reply.<index>`（RowCodec编码目标ID、显示名、可见布尔），避免破坏旧解码器。

- [ ] 给现有 repository 夹具写回复测试，第三用户回复第二用户时通知用户1（楼主）和用户2（被回复者）；修改 schema 夹具支持新增列。先运行新测试确认失败。

```java
long post = repository.createPost(1L, new CreatePost(1L, "回复测试", "正文"));
long parent = repository.createComment(post, 2L, "原评论");
executeUpdate("DELETE FROM notifications");
repository.createComment(post, 3L, "回复内容", parent);
assertEquals(2, scalarInt("SELECT COUNT(*) FROM notifications"));
assertEquals(1, scalarInt("SELECT COUNT(*) FROM notifications WHERE recipient_user_id=2"));
```

- [ ] 新增 `FORUM_COMMENT_REPLIED` 通知类型，schema 初始与重建 CHECK 都包含它。回复通知标题“您的评论收到一条回复”，目标仍 FORUM_POST、related_entity_id 为帖子ID，内容包含帖子标题和回复文字。
- [ ] 事务锁顺序统一先帖子后评论，验证锁定状态、目标正常且属于同帖，再插入评论与通知。所有评论写事务和审核事务检查锁顺序，避免引入与统计更新相反的加锁顺序。
- [ ] Factory 新增 `List<NotificationDraft> commentNotifications(long postAuthorId,long commenterId,String commenterName,long postId,String postTitle,String comment,Long replyAuthorId)`：先将回复通知放入按 recipientId 的 LinkedHashMap，再 putIfAbsent 楼主通知，移除 commenterId；一并写入当前事务。
- [ ] 补充回复楼主仅一条、回复自己不通知自己、楼主自回复、跨帖目标、已隐藏目标、目标后续隐藏不暴露原文、消息写入异常回滚等断言。返回数据不带不可见目标正文。
- [ ] 运行 ForumRepositoryTest、ForumNotificationFactoryTest、ForumServiceTest、ForumMigrationTest 与公共通知协议测试；全绿后审阅差异并提交。

## Task 4: 首页聚合查询、个人列表、热榜与公告

**Files**
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumCommunityStore.java`, `ForumCommunityRepository.java`
- Create: `vcampus-common/src/main/java/com/vcampus/common/model/ForumFeedScope.java`, `ForumFeedOrder.java`
- Modify: `vcampus-common/src/main/java/com/vcampus/common/model/ForumModerationAction.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/database/ForumRepository.java`, `ForumNotificationFactory.java`
- Modify: `database/schema.sql`
- Test: `vcampus-server/src/test/java/com/vcampus/server/database/ForumCommunityRepositoryTest.java`, `ForumRepositoryTest.java`, `ForumNotificationFactoryTest.java`

**Interfaces:** scope = HOME/MINE/BOOKMARKS/ANNOUNCEMENTS，order = LATEST/HOT/FEATURED。ForumCommunityStore 定义 `FeedQuery(long viewerUserId,ForumFeedScope scope,ForumFeedOrder order,Long sectionId,String keyword,int page,int pageSize)`；`FeedRow(ForumStore.PostRow post,Engagement engagement,boolean announcement)`；`FeedPage(List<FeedRow> rows,int page,int pageSize,int total)`；方法 `FeedPage searchFeed(FeedQuery query)` 和 `List<FeedRow> hotToday(long viewerUserId,Instant fromInclusive,Instant toExclusive)` 均 throws SQLException。公告沿用 `moderatePost`，增加 ANNOUNCE/UNANNOUNCE 枚举。

- [ ] 新查询测试使用两名作者和两条帖子，给旧帖点赞，断言 HOT 将其提前，LATEST 仍以新帖在前；MINE 与 BOOKMARKS 均按 viewerUserId 隔离。先运行确认失败。

```java
var query = new ForumCommunityStore.FeedQuery(2L, ForumFeedScope.BOOKMARKS,
        ForumFeedOrder.LATEST, null, "", 1, 20);
community.setBookmarked(postId, 2L, true);
assertEquals(postId, community.searchFeed(query).rows().getFirst().post().id());
assertEquals(1, community.searchFeed(query).total());
```

- [ ] 查询使用相同 WHERE 构建 COUNT 和分页结果；JOIN聚合子查询或 EXISTS 避免点赞×评论行数膨胀。HOME置顶优先；MINE显示本人所有状态但不可见帖不进入日常详情；其他 scope 仅正常、启用板块。关键字匹配标题/正文/作者，绑定参数。
- [ ] HOT 分数固定 `like_count + comment_count * 2`；热榜用当日区间内有效点赞和正常评论，不使用全量累计。零分不入榜，最多5条，不应用置顶优先。服务端以 Clock 与 Asia/Shanghai 算日界，SQL绑定 UTC Instant，禁止依赖 MySQL会话日期猜测校园当天。
- [ ] 公告修改权限仅 manager；要求2—255字原因并校验 NORMAL。事务更新标记/时间、审核日志、通知；无变化不重复通知。取消后清空公告时间。帖子被隐藏时不在公告栏；恢复后按原公告标记重新可见。schema 更新日志动作约束，通知使用已有 FORUM_POST_MODERATED。
- [ ] 测试分页边界、同分ID稳定排序、搜索作者、禁用板块、隐藏收藏、自己的删除记录、当日0点和跨日、零互动、不重复count、普通用户伪造公告请求；通知内容包含原因。
- [ ] 运行相关 repository、factory、schema 测试并审阅差异，提交独立改动。

## Task 5: Socket接口与JavaFX数据网关

**Files**
- Modify: `vcampus-common/src/main/java/com/vcampus/common/protocol/Actions.java`
- Create: `vcampus-server/src/main/java/com/vcampus/server/service/ForumCommunityService.java`
- Modify: `vcampus-server/src/main/java/com/vcampus/server/service/RequestRouter.java`, `ForumService.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/network/VCampusClient.java`
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumGateway.java`, `SocketForumGateway.java`, `ForumData.java`
- Create tests: `vcampus-server/src/test/java/com/vcampus/server/service/ForumCommunityServiceTest.java`, `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumGatewayTest.java`
- Modify test: `vcampus-server/src/test/java/com/vcampus/server/service/RequestRouterForumTest.java`

**Interfaces:** 新 Actions 常量 FORUM_FEED_SEARCH=`forum.feed.search`、FORUM_HOT_LIST=`forum.hot.list`、FORUM_ENGAGEMENT_GET=`forum.engagement.get`、FORUM_LIKE_SET=`forum.like.set`、FORUM_BOOKMARK_SET=`forum.bookmark.set`。VCampusClient 新方法均返回 ResponseMessage、throws IOException：`searchForumFeed(String token,String scope,String order,Long sectionId,String keyword,int page)`、`listForumHot(String token)`、`getForumEngagement(String token,long postId)`、`setForumLiked(String token,long postId,boolean enabled)`、`setForumBookmarked(String token,long postId,boolean enabled)`；createForumComment 增加带 Long replyToCommentId 的重载。

**Typed gateway:** 使用客户端 ForumData 内嵌 record 定义 `FeedFilter(ForumFeedScope scope,ForumFeedOrder order,Long sectionId,String keyword,int page)`、`Engagement(int likeCount,boolean liked,boolean bookmarked)`、`FeedRow(ForumViewData.PostRow post,Engagement engagement,boolean announcement)`、`FeedPage(List<FeedRow> rows,int page,int pageSize,int total)`、`Comment(ForumViewData.CommentRow row,Long replyToId,String replyToName,boolean replyVisible)`、`CommentPage(List<Comment> rows,int page,int pageSize,int total)`。ForumGateway 暴露 `sections()`, `feed(FeedFilter)`, `hot()`, `post(long)`, `comments(long,int)`, `engagement(long)`, `setLiked(long,boolean)`, `setBookmarked(long,boolean)`, `createPost(long,String,String)`, `createComment(long,String,Long)`, `deletePost(long)`, `deleteComment(long)`，均 throws IOException；返回依次为 List<ForumViewData.SectionRow>、FeedPage、List<FeedRow>、ForumViewData.PostDetail、CommentPage、Engagement、Engagement、Engagement、long、long、void、void。

管理网关方法（均 throws IOException）：`List<ForumViewData.SectionRow> adminSections()`、`long saveSection(Map<String,String> values)`、`void setSectionEnabled(long sectionId,boolean enabled)`、`ForumViewData.AdminContentPage adminContent(ForumTargetType target,ForumContentStatus status,String keyword,int page)`、`void moderatePost(long postId,ForumModerationAction action,String reason)`、`void moderateComment(long commentId,ForumModerationAction action,String reason)`、`ForumViewData.ModerationLogPage moderationLogs(int page)`。这些方法调用已有对应VCampusClient方法，拒绝失败响应；管理员板块查询传includeDisabled=true，普通sections传false。

- [ ] 用测试端口启动临时 Socket 测试服务，捕获真实 MessageCodec 请求，断言请求动作和参数；不连接正式端口。服务测试使用 SessionManager 和记录型 store 验证登录角色，不能信任请求中的 userId。
- [ ] 固定新 feed `row.N` 使用现有15字段 PostRow 编码；`engagement.N` 编码 likeCount、liked、bookmarked、announcement。新响应加 `forumVersion=2`。新网关缺少版本或字段时返回“论坛服务版本不兼容，请更新服务端”，不默认为0。旧接口字段数不变。
- [ ] 测试失败后实现新 service、路由构造接线和客户端方法。固定新 feed 每页20；旧接口分页保持原有10兼容。从会话取得 viewerUserId，并严格解析 enabled、scope、order、正整数ID。
- [ ] 网关同步方法只能由后台调用；业务失败保留中文提示，传输失败单独提示；未经授权不得暴露管理列表。公告复用新的枚举审核动作，RESTORE可空原因沿用既有规则，ANNOUNCE必须原因。
- [ ] 对 reply 旁路键、通知新枚举、论坛未知动作、过期会话、恶意参数、超长正文做协议和服务测试。添加旧客户端解码新服务端旧接口响应的兼容测试。
- [ ] 运行 `RequestRouterForumTest,ForumCommunityServiceTest,ForumGatewayTest,ForumViewDataTest`，通过后审阅改动并分文件提交。

## Task 6: 原生JavaFX首页、详情、编辑器及状态控制

**Files**
- Create under `vcampus-client/src/main/java/com/vcampus/client/fx/forum/`: `ForumController.java`, `ForumView.java`, `ForumHomeView.java`, `ForumPostCard.java`, `ForumDetailView.java`, `ForumEditorView.java`, `ForumAsync.java`
- Create: `vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css`
- Create tests under `vcampus-client/src/test/java/com/vcampus/client/fx/forum/`: `ForumViewTest.java`, `ForumControllerTest.java`, `ForumFixtures.java`

**Interfaces:** `ForumController(ForumGateway gateway, Set<UserRole> roles, ExecutorService requests, Runnable onWorkspace)`，`Parent view()`、`void openHome()`、`void openPost(long postId)`、`void deactivate()`、`void close()`。构造/导航/close只在FX线程执行，不关闭外部executor。ForumAsync接受后台executor及UI调度器，提交 Callable，成功/失败回调检查自身generation；deactivate/close失效旧代次。ForumView 是模块根 BorderPane，管理各页面与局部导航。

- [ ] 按现有 FxUiTest 启动工具包方式，在新测试类提供 FX线程执行工具。测试先定位 `#forum-root`、`#forum-search`、`#forum-publish`，普通用户无管理入口，管理员有入口，点击卡片打开正确帖子。测试数据只在 ForumFixtures，不加生产演示开关。
- [ ] 添加实际布局断言：

```java
view.resize(1000, 760);
view.applyCss(); view.layout();
assertFalse(view.lookup("#forum-sidebar").isManaged());
view.resize(1220, 800);
view.applyCss(); view.layout();
assertTrue(view.lookup("#forum-sidebar").isManaged());
```

- [ ] 运行新增测试确认失败，再实现可测试的纯视图与事件回调。采用BorderPane+单一ScrollPane主滚动，卡片列表只为当前页。搜索、筛选、个人列表复用首页视图；右栏加载与主列表失败隔离。
- [ ] 从已批准配色和间距实现限定 `.forum-root` CSS。按钮实际 textFill 必须为#111111，选中靠浅色背景、边框和图标；可交互文字也遵守黑字要求。发布按钮、输入字段设定ID和accessibleText，键盘焦点清晰。
- [ ] 详情使用Label/TextFlow纯文本，长文换行，正常评论平铺并标注回复对象。长标题不会遮住操作；删除/审核放菜单中，避免按钮堆满每条评论。锁定时显示原因说明并禁用输入，不用通用连接错误。
- [ ] 编辑器验证板块、标题和正文；使用服务端相同长度上限，显示字数、禁用重复提交、失败保留内容。导航离开时确认丢弃。Controller维护FeedFilter和scrollValue，返回恢复；成功操作重新读取服务器计数。
- [ ] 测试快速切换分类晚返回覆盖、注销后响应、重复点击发布、失败重试、空列表、单条/多页、取消删除、回复取消、隐藏原评论、缺少学院信息、嵌入HTML当纯文本。后台测试用可控CompletableFuture，断言真实视图结果而非只验证mock次数。
- [ ] 运行 ForumViewTest、ForumControllerTest、ForumGatewayTest；取得可运行首页和详情后进行截图检查再继续，不把数据假值当成已完成的联网功能。

## Task 7: 管理页面与门户、消息中心集成

**Files**
- Create: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumAdminView.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/forum/ForumController.java`, `ForumGateway.java`, `SocketForumGateway.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/fx/CampusApplication.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/LegacyModuleBridge.java`
- Modify: `vcampus-client/src/main/java/com/vcampus/client/ui/ForumAdminPanel.java`
- Create test: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumIntegrationTest.java`
- Modify tests: `vcampus-client/src/test/java/com/vcampus/client/ui/LegacyModuleBridgeTest.java`, `NotificationForumUiTest.java`

**Interfaces:** LegacyModuleBridge 新增构造器重载，追加 `Consumer<NotificationDestination> externalForumNavigation`；原构造器保留以兼容Swing测试。FORUM_POST通知在配置回调时走回调，否则走旧Swing论坛。FX侧经 onFx(sessionGeneration,...) 打开 ForumController；不得在EDT直接操作FX节点。

- [ ] 先补通知路由测试：传入FORUM_POST、合法帖子ID，观察外部回调收到相同ID；注销后不回调；SHOP_ORDERS仍走原商店，不被新论坛逻辑拦截。
- [ ] 管理UI测试覆盖 NORMAL显示隐藏，HIDDEN显示恢复，DELETED两者禁用；状态中文；公告设置/取消需原因。板块管理保留代码、名称、描述、排序、启用，旁边解释代码是内部标识、排序值小靠前；审核日志只读分页。
- [ ] 测试失败后实现JavaFX管理页，复用已有管理接口，新增公告动作。新增管理网关接口明确参数与旧VCampusClient一致，所有调用仍由Controller后台执行。
- [ ] CampusApplication 缓存每个会话一个 ForumController；openRoute("forum")直达原生视图；切换离开失效旧请求；clearSession关闭控制器并清理引用。跨Swing通知跳转先回FX再换模块。其他业务模块继续SwingNode。
- [ ] 旧Swing评论菜单采用Task1策略；ForumAdminPanel状态显示中文并禁用非法动作，保留现有LegacyUiLifecycle保护。不要覆盖已有异步或注销补丁。
- [ ] 运行JavaFX集成与既有门户/桥接测试，确认首次改密前不发论坛请求、登出不再写UI、消息仍刷新未读数。审阅增量差异，不整批提交未跟踪门户代码。

## Task 8: 视觉验收、完整回归及使用说明

**Files**
- Create: `vcampus-client/src/test/java/com/vcampus/client/fx/forum/ForumVisualTest.java`
- Modify: `docs/requirements.md`, `README.md`
- Create: `docs/forum.md`
- Modify if needed: `vcampus-client/src/main/resources/com/vcampus/client/fx/forum/forum.css`

- [ ] 使用测试UI构造首页、详情、编辑器、管理员视图，场景1440×900、1280×800，以及内容区减去现有220px门户侧栏的真实宽度；在test target/forum-screenshots输出PNG。通过SwingFXUtils和ImageIO生成，不向生产目录写假数据。

```java
Scene scene = new Scene(root, 1220, 900);
root.applyCss(); root.layout();
Path output = Path.of("target", "forum-screenshots", "home-wide.png");
Files.createDirectories(output.getParent());
ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", output.toFile());
```

- [ ] 用view_image逐张检查真实JavaFX截图，与用户参考图比较标题/卡片密度、间距、边框、对齐与视觉层次；修正后重跑截图。截图应涵盖长标题、长评论、空数据、网络错误、加载中，不能只看首屏正常数据。
- [ ] 以新JVM的125%渲染比例再运行视觉测试并注明模拟缩放；若无法验证真实Windows缩放，不声称已通过人工DPI验收。检查按钮实际CSS文字颜色、最小窗不裁切、焦点可见。
- [ ] 运行根目录 `& $forumMvn $forumRepo clean verify`，统计实际测试数与失败数；如有基线已有失败，分别列出，不顺手修改其他模块。
- [ ] 检查数据库初始化的非破坏性幂等性；无隔离MySQL时清楚记录未实测。用户操作说明保留“先运行最新schema.sql，再运行seed.sql；重启服务端和客户端”，不执行真实库。
- [ ] docs/forum.md记录功能、评论删除与隐藏区别、排序/热榜口径、公告权限、通知去重；requirements只把已完成验证项标记完成。附手动联调清单：普通用户发帖→另一人评论/回复→消息深链→收藏/点赞→管理员隐藏→普通列表消失→管理页恢复。
- [ ] 最终审阅仅本任务增量、检查无密码/个人信息、git diff --check。通过verification-before-completion后才宣称完成；未实现/未验证项必须列明，不以计划文件替代功能交付。

## 计划自检

- 视觉规范与首页/详情/发帖/个人内容：Task6、Task8。
- 删除、隐藏、保留审核与兼容旧客户端：Task1、Task7。
- 唯一点赞收藏、回复通知、热榜、公告、数据升级：Task2—Task5。
- 线程、生命周期、消息深链：Task5—Task7。
- 无损现有变更、真实SQL边界、运行与演示说明：执行前检查、Task8。

## 实际执行记录（2026-08-31）

用户选择“1”：在当前目录按顺序执行，保留当前未提交的门户和其他模块变更。未创建新工作区，未启动正式服务端，未操作真实数据库，未提交或推送本轮混合工作区。

以下为交付进度；上文逐步清单保留为原始设计目标，测试组织和提交步骤以本记录的实际执行方式为准。

- [x] Task 1：日常评论固定只读 NORMAL；删除只允许本人；Swing 删除确认和管理状态兼容。
- [x] Task 2—4：schema 幂等结构升级、点赞/收藏、个人列表、回复通知去重、热榜、公告。事务用 H2 自动化验证；追加多人重复点赞和跨帖锁等待回归。
- [x] Task 5：新版 Socket 动作、会话校验、版本 2 数据网关；临时测试 Socket 验证 MessageCodec。
- [x] Task 6—7：原生 JavaFX 页面、后台请求代次控制、消息深链、门户会话清理和管理入口。
- [x] Task 8 视觉部分：首页 1220/1060/780px、详情及评论区、发帖、管理、空/错误/加载场景截图；小窗口按钮省略和管理表格过小问题已以失败测试复现并修复。
- [x] 使用说明：新增 docs/forum.md；README 和 requirements 更新论坛状态与 schema → seed → 同时重启步骤。
- [x] 全量构建与回归：399 项自动化测试通过，0 失败/错误/跳过；125% 场景截图复查通过，区别于真实 Windows DPI 验收。
- [ ] 本机真实 MySQL 旧库重复升级、新库初始化、双客户端流程及真实 Windows DPI 人工验收：未执行，留给用户按说明验收。

实现差异：

- 为复用已验证的 JDBC 夹具，新增互动、聚合、回复和公告仓储测试集中在 ForumRepositoryTest，而不是复制一套 ForumCommunityRepositoryTest；SQL 结构测试放在 ForumMigrationTest。
- UI 测试组织为 ForumUiTest、ForumControllerTest、ForumGatewayTest、ForumAsyncTest、ForumVisualTest，并复用 LegacyModuleBridgeTest 检查消息路由。
- 消息桥接增加 `LongConsumer` 仅传帖子编号，而非扩大包内 NotificationDestination 的可见性；Swing 回退构造器保留。
- 自动生成截图只存在测试 target 目录；测试名字、摘要和热度不写入生产数据库。
- 本轮新增文件与已有未跟踪门户一起运行；没有把用户既有变更整文件提交。完整回归结果、缩放检查边界见 docs/forum.md。
