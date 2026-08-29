# VCampus 虚拟图书馆模块设计

## 1. 背景与目标

VCampus 当前已经具备统一登录、角色组合、工作台、消息中心、学籍和教务模块，但图书馆仅有 `LIBRARY` 模块入口和 `LIBRARY_ADMIN` 角色，尚无数据库表、公共协议、服务端业务或 Swing 页面。

本次迭代建设一个适合课程演示、可以完整闭环的虚拟图书馆。目标包括：

- 学生和教师检索图书及可借馆藏；
- 学生和教师自助借阅、归还和续借；
- 查询本人当前借阅、历史借阅和逾期记录；
- 图书管理员维护书目、实体馆藏及馆藏状态；
- 图书管理员通过用户账号和馆藏条码代办借还；
- 图书管理员跨用户查询借阅记录；
- 在消息中心发送临近到期和逾期提醒；
- 在多个客户端并发操作时保证同一本馆藏不会被重复借出。

第一版不包含预约排队、罚款、电子书、采购、盘点和复杂统计报表。

## 2. 已确认的业务规则

### 2.1 借还模式

采用混合模式：

- 普通学生和教师可以自助借阅、归还和续借；
- 图书管理员可以通过借阅人账号和馆藏条码代办借阅、归还；
- 管理员代办仍遵守借阅人的额度、期限、逾期和续借规则，不能静默绕过；
- 异常馆藏处理使用专用操作并记录原因及审计日志。

### 2.2 借阅期限与额度

| 基础身份 | 同时借阅上限 | 初始借期 | 最大续借次数 | 每次续借 |
| --- | ---: | ---: | ---: | ---: |
| 学生 | 5 本 | 30 天 | 1 次 | 15 天 |
| 教师 | 10 本 | 60 天 | 1 次 | 30 天 |

规则由服务端 `LibraryLoanPolicy` 统一执行。客户端展示服务端返回的当前规则，不自行决定用户是否可以借阅或续借。

### 2.3 逾期与续借

- 未归还且当前时间晚于 `due_at` 的借阅实时视为逾期；
- 逾期用户可以查看记录和归还图书，但不能新增借阅或续借；
- 未逾期借阅只能续借一次；
- 续借期限从原 `due_at` 开始延长，不从操作时间重新计算；
- 第一版不计算罚款。

### 2.4 角色与权限

- `STUDENT`、`TEACHER`：检索图书、查看本人借阅、自助借阅、自助归还、自助续借；
- `LIBRARY_ADMIN`、`SUPER_ADMIN`：书目、馆藏和借阅管理，以及管理员代办借还；
- 兼任 `LIBRARY_ADMIN` 的学生或教师同时拥有普通借阅能力和管理能力；
- `SUPER_ADMIN` 只有管理能力，不作为普通借阅人；
- 每次写操作都由服务端重新校验会话、角色、记录所有权和当前数据库状态。

## 3. 总体架构

图书馆继续遵守项目的三层结构：

```text
Swing LibraryFrame
        |
        | MessageCodec / library.*
        v
RequestRouter -> LibraryService -> CatalogStore / LoanStore
                                      |
                                      v
                         CatalogRepository / LoanRepository
                                      |
                                      v
                                    MySQL
```

公共协议、枚举和权限判断放在 `vcampus-common`；会话校验、业务规则、事务、定时任务和 JDBC 放在 `vcampus-server`；Swing 页面、响应解析和异步网络调用放在 `vcampus-client`。客户端不连接 MySQL。

服务端组件职责如下：

- `LibraryService`：会话、权限、参数、所有权校验和业务编排；
- `LibraryLoanPolicy`：借阅额度、借期、续借和逾期阻断规则；
- `LibraryCatalogStore`、`LibraryLoanStore`：服务层所依赖的两类持久化接口，支持单元测试替身；
- `LibraryCatalogRepository`、`LibraryLoanRepository`：分别负责书目馆藏查询，以及借还行锁、事务和数据库记录映射；
- `LibraryOverdueNotifier`：扫描临近到期与逾期记录并写入站内消息；
- `RequestRouter`：将 `library.*` 动作路由到 `LibraryService`。

## 4. 数据模型

### 4.1 `books` 书目表

一条记录表示一种具体版本的图书：

- `id BIGINT`：主键；
- `isbn VARCHAR(20)`：标准化后的 ISBN，非空且唯一；
- `title VARCHAR(200)`：书名；
- `authors VARCHAR(300)`：作者展示文本；
- `publisher VARCHAR(160)`：出版社；
- `publish_year SMALLINT`：出版年份，可空；
- `category VARCHAR(80)`：分类；
- `description VARCHAR(1000)`：简介，可空；
- `enabled BOOLEAN`：是否允许继续流通；
- `created_at`、`updated_at`：审计时间。

ISBN 在 Java 中去除空格和连字符后校验 ISBN-10 或 ISBN-13；ISBN-10 的校验字符 `X` 仅允许出现在末位。第一版不拆分作者关联表。

### 4.2 `book_copies` 馆藏表

一条记录表示一本实体馆藏：

- `id BIGINT`：主键；
- `book_id BIGINT`：所属书目；
- `barcode CHAR(10)`：唯一条码，格式为 `B` 加 9 位数字；
- `shelf_location VARCHAR(80)`：馆藏位置；
- `status VARCHAR(16)`：当前馆藏状态；
- `status_reason VARCHAR(255)`：异常状态原因，可空；
- `created_at`、`updated_at`：审计时间。

馆藏状态为：

- `AVAILABLE`：可借；
- `ON_LOAN`：借出；
- `LOST`：遗失；
- `DAMAGED`：损坏；
- `WITHDRAWN`：下架。

书目被停用后不再允许新增借阅，但已有借阅仍可归还。存在借阅历史的书目和馆藏不物理删除；书目使用 `enabled=false`，馆藏使用 `WITHDRAWN`。

### 4.3 `library_loans` 借阅表

一条记录表示一次借阅周期：

- `id BIGINT`：主键；
- `copy_id BIGINT`：具体馆藏；
- `borrower_user_id BIGINT`：借阅人账号；
- `borrowed_at TIMESTAMP`：借出时间；
- `initial_due_at TIMESTAMP`：初始到期时间；
- `due_at TIMESTAMP`：续借后的当前到期时间；
- `renewal_count TINYINT`：续借次数；
- `returned_at TIMESTAMP`：借阅关闭时间，未关闭时为空；
- `return_condition VARCHAR(16)`：`NORMAL` 或 `LOST`，未关闭时为空；
- `channel VARCHAR(16)`：`SELF_SERVICE` 或 `ADMIN_DESK`；
- `checkout_operator_user_id BIGINT`：办理借出操作的账号；
- `return_operator_user_id BIGINT`：办理关闭操作的账号，可空；
- `due_notice_sent_at TIMESTAMP`：本次期限的临近到期提醒发送时间，可空；
- `overdue_notice_sent_at TIMESTAMP`：逾期提醒发送时间，可空；
- `created_at`：记录创建时间。

`returned_at` 表示流通记录已经关闭；当 `return_condition=LOST` 时，馆藏转为 `LOST`，并不表示实体书已经归还。

MySQL 使用生成列保证一本馆藏最多只有一条活动借阅：

```sql
active_copy_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN returned_at IS NULL THEN copy_id ELSE NULL END) STORED,
UNIQUE KEY uk_library_active_copy (active_copy_id)
```

MySQL 唯一索引允许多个 `NULL`，因此历史借阅可以保留，同时活动馆藏保持唯一。

### 4.4 索引

至少建立以下索引：

- `books(isbn)`、`books(title)`、`books(category, enabled)`；
- `book_copies(barcode)`、`book_copies(book_id, status)`；
- `library_loans(borrower_user_id, returned_at, due_at)`；
- `library_loans(copy_id, borrowed_at)`；
- 活动馆藏生成列唯一索引。

第一版作者使用 `LIKE` 检索；课程演示数据量不引入全文搜索引擎。

## 5. 公共协议

所有动作使用 `library.` 前缀：

| 动作 | 权限 | 用途 |
| --- | --- | --- |
| `library.catalog.search` | 普通借阅或管理 | 按书名、作者、ISBN、分类分页检索书目及可借数量 |
| `library.catalog.get` | 普通借阅或管理 | 查看书目详情与汇总馆藏状态 |
| `library.loan.my` | 普通借阅 | 查询本人当前、逾期或历史借阅 |
| `library.loan.borrow` | 普通借阅 | 自助借阅并自动分配可用馆藏 |
| `library.loan.return` | 普通借阅 | 归还本人活动借阅 |
| `library.loan.renew` | 普通借阅 | 续借本人活动借阅 |
| `library.admin.book.create` | 图书管理 | 新增书目 |
| `library.admin.book.update` | 图书管理 | 修改书目 |
| `library.admin.book.set-enabled` | 图书管理 | 启用或停用书目 |
| `library.admin.copy.search` | 图书管理 | 查询实体馆藏 |
| `library.admin.copy.create` | 图书管理 | 新增实体馆藏 |
| `library.admin.copy.set-status` | 图书管理 | 设置无活动借阅馆藏的异常状态 |
| `library.admin.loan.search` | 图书管理 | 跨用户分页查询借阅记录 |
| `library.admin.circulation.preview` | 图书管理 | 按 `BORROW` 或 `RETURN` 校验用户与馆藏并返回代办前预览，不修改业务数据 |
| `library.admin.loan.borrow` | 图书管理 | 按用户账号和馆藏条码代办借阅 |
| `library.admin.loan.return` | 图书管理 | 按条码代办正常归还或遗失关闭 |

请求和响应沿用 `MessageCodec` 的扁平键值结构，分页列表沿用 `RowCodec`。查询响应包含 `rows`、`page`、`pageSize` 和 `total`；当前借阅响应同时返回适用于当前用户的借阅上限和期限。

服务端返回可读业务消息，例如：

- “借阅数量已达上限”；
- “存在逾期图书，请先归还”；
- “该馆藏刚刚被其他用户借出”；
- “该借阅已经续借过一次”；
- “当前记录不属于该用户”；
- “该馆藏状态不允许借阅”。

数据库异常不向客户端暴露 SQL、表名或堆栈信息。

## 6. 核心事务与并发

### 6.1 自助借阅

1. 校验会话及学生或教师基础身份；
2. 先使用 `SELECT ... FOR UPDATE` 锁定借阅人的 `users` 行，再查询活动借阅，检查逾期和额度；锁定用户行可以在当前借阅数为零时仍然串行化同一用户的并发借阅；
3. 在指定书目下使用 `SELECT ... FOR UPDATE SKIP LOCKED` 选择一条 `AVAILABLE` 馆藏；
4. 重新确认书目启用、馆藏可用；
5. 创建 `library_loans`；
6. 将馆藏更新为 `ON_LOAN`；
7. 写审计日志；
8. 一次提交。

管理员代办借阅执行相同规则，只是借阅人和具体馆藏由管理员输入，`channel=ADMIN_DESK`，操作人记录管理员账号。

### 6.2 归还

1. 锁定活动借阅和馆藏；
2. 自助归还时校验借阅记录属于当前用户；
3. 填写 `returned_at`、`return_condition` 和操作人；
4. 正常归还将馆藏恢复为 `AVAILABLE`；管理员遗失关闭将馆藏设置为 `LOST` 并要求原因；
5. 写审计日志；
6. 一次提交。

### 6.3 续借

1. 先锁定借阅人的 `users` 行，再锁定活动借阅，使同一用户的借阅和续借采用一致的加锁顺序；
2. 校验记录所有权、未逾期、用户无其他逾期记录且 `renewal_count=0`；
3. 根据基础身份从原 `due_at` 延长 15 或 30 天；
4. 将 `renewal_count` 加一，并将 `due_notice_sent_at` 清空，使新期限可以再次发送一次临近到期提醒；
5. 写审计日志并提交。

不允许客户端传入最终到期时间、续借次数或馆藏状态。

## 7. 到期与逾期通知

公共通知枚举扩展为：

- `NotificationType.LIBRARY_DUE_SOON`；
- `NotificationType.LIBRARY_OVERDUE`；
- `NotificationSource.LIBRARY`；
- `NotificationTarget.LIBRARY_LOANS`。

`LibraryOverdueNotifier` 在服务端启动后运行一次，之后每小时运行一次：

- 未归还、距离 `due_at` 不超过 3 天且尚未发送本期限提醒时，发送一次临近到期通知；
- 未归还、已超过 `due_at` 且尚未发送逾期提醒时，发送一次逾期通知；
- 每次处理都锁定借阅记录，在同一个数据库事务和同一个 `Connection` 上调用 `NotificationWriter`，随后更新对应发送时间；
- 多个扫描周期或多个服务线程不得为同一借阅重复创建同类通知。

通知详情可跳转到 `LibraryFrame` 的“我的借阅”页面。归还后不再发送提醒。

## 8. Swing 客户端设计

使用统一 `LibraryFrame`，按角色组合显示页面：

- 普通用户：图书检索、我的借阅；
- 图书管理员：书目馆藏、借还办理、借阅查询；
- 兼任图书管理员的学生或教师：同时显示普通用户和管理页面；
- 超级管理员：只显示管理页面。

组件拆分如下：

- `LibraryFrame`：窗口、角色导航和页面切换；
- `LibraryCatalogPanel`：组合检索、分页、详情和自助借阅；
- `MyLibraryLoansPanel`：当前、即将到期、逾期和历史借阅，以及归还和续借；
- `LibraryInventoryPanel`：书目及馆藏维护；
- `LibraryCirculationPanel`：管理员按账号和条码代办；
- `LibraryLoanManagementPanel`：跨用户借阅查询；
- `LibraryViewData`：集中解析服务端响应。

所有 Socket 调用在后台执行，提交期间禁用相关按钮；完成后通过 Swing Event Dispatch Thread 更新控件。写操作成功后重新读取服务端状态，不在客户端自行增减库存。

自助借阅在提交前显示书名、借期和预计到期日；管理员代办必须先展示借阅人身份、当前额度、逾期状态和馆藏状态，再允许提交。异常状态变更需要填写原因。

## 9. 异常处理

- 登录失效：提示重新登录；
- 权限不足：返回统一权限提示，不返回管理数据；
- 参数、ISBN、条码错误：客户端即时提示，服务端再次校验；
- 并发冲突：提示馆藏已被借出并刷新查询结果；
- 重复点击：提交期间禁用按钮，服务端事务仍作为最终保护；
- 网络或数据库故障：保留当前页面数据，显示可重试提示；
- 非法状态转换：拒绝操作并返回当前馆藏或借阅状态；
- 服务端日志保留异常详情，客户端不显示内部错误。

## 10. 测试设计

### 10.1 公共模块

- 图书条码和 ISBN 校验；
- `LibraryCopyStatus`、`LibraryLoanChannel` 协议词汇稳定性；
- 普通借阅与图书管理权限组合；
- 新增动作通过现有 `MessageCodec` 往返兼容。

### 10.2 服务层

- 学生最多 5 本、教师最多 10 本；
- 学生借期 30 天、教师借期 60 天；
- 不同身份续借分别延长 15 天和 30 天；
- 第二次续借被拒绝；
- 任一逾期记录阻止新增借阅和续借；
- 普通用户不能操作他人借阅；
- 图书管理员可以代办但不能绕过借阅规则；
- 超级管理员可以管理馆藏但不能作为普通借阅人。

### 10.3 数据库与并发

- 借阅、归还和续借事务保持借阅记录与馆藏状态一致；
- 两个客户端争抢最后一本馆藏时恰好一个成功；
- 同一本馆藏不能存在两条活动借阅；
- 归还历史记录不被覆盖；
- 到期和逾期通知各发送一次；
- 续借后允许针对新期限重新发送一次临近到期通知；
- 通知写入和发送标记在同一事务中提交或回滚。

### 10.4 客户端

- `LibraryViewData` 能解析正常、空列表和损坏响应；
- 不同角色组合显示正确页面；
- 服务端返回不可续借时按钮禁用；
- 网络调用不在 Swing EDT 上执行，界面更新回到 EDT；
- 写操作期间按钮防重复提交，成功后刷新服务端数据。

## 11. 数据库、文档与交付

实现时新增 `database/migrations/003_library.sql`，并同步更新：

- `database/schema.sql`：三张图书馆业务表及通知约束；
- `database/seed.sql`：脱敏书目、馆藏和借阅演示数据；
- `docs/requirements.md`：仅在功能和验收完成后更新模块状态；
- 图书馆模块说明文档：记录操作流程、权限、环境配置和演示账号。

关键借还和异常馆藏操作写入现有 `audit_logs`。数据库凭据继续通过环境变量提供，不在源码、迁移或演示数据中写入真实信息。

## 12. 验收场景

课程演示至少覆盖以下场景：

1. 学生按书名检索并自助借阅，系统自动分配唯一条码馆藏；
2. 第二个客户端争抢最后一本馆藏并收到可读冲突提示；
3. 学生查看本人借阅并成功续借一次，第二次续借被拒绝；
4. 存在逾期记录的用户不能继续借阅，但可以归还；
5. 图书管理员新增书目和实体馆藏；
6. 图书管理员按用户账号和条码代办借阅及归还；
7. 图书管理员将异常归还登记为遗失并填写原因；
8. 临近到期或逾期通知出现在消息中心，并能跳转到“我的借阅”；
9. 普通用户请求管理动作时由服务端拒绝；
10. 客户端断线或数据库不可用时显示可恢复的错误提示。
