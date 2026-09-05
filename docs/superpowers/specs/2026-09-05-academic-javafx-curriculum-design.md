# 教务、培养方案与 JavaFX 升级设计

**日期：** 2026-09-05  
**状态：** 已批准  
**适用项目：** VCampus

## 1. 背景

VCampus 已有课程、教学班、上课时段、选退课、学生与教师课表、名单和成绩发布流程。客户端已有 JavaFX 门户，图书馆和论坛已有原生 JavaFX 页面；教务模块仍由 JavaFX 主窗口通过 `SwingNode` 承载旧 Swing 页面。

本次升级在保持 MySQL → 应用服务器 → Socket 客户端三层结构的前提下，引入按培养方案判定的必修/选修属性、教学班选择与原子换班、带草稿和发布版本的智能辅助排课，并把教务主流程分阶段迁移到原生 JavaFX。

## 2. 目标与非目标

### 2.1 目标

- 课程只在全校课程库创建一次，必修/选修由专业培养方案决定。
- 一个培养方案可覆盖同一专业的连续多个入学年份，并支持复制和版本化发布。
- 学生先选择课程，再从该课程的开放教学班中选择教师和上课时间。
- 换班在单个数据库事务中完成，失败时保留原教学班名额。
- 教务管理员通过 JavaFX 周课表人工排课，系统实时辅助并在服务端强制校验冲突。
- 排课修改先保存为草稿；新版本发布前，教师和学生继续看到旧的已发布版本。
- 学生、教师、教务管理员和超级管理员获得与角色匹配的原生 JavaFX 工作区。
- 保持现有长度前缀 `MessageCodec` 协议，不使用 Java 原生对象序列化，客户端不连接数据库。

### 2.2 非目标

- 不实现基于约束求解器的全自动排课。
- 不在本次加入候补队列、复杂先修课网络、重修成绩替换或毕业审核。
- 不一次性删除旧 Swing 教务页面；在原生 JavaFX 功能完整验收前保留 `--swing` 回退。
- 不改变现有统一成绩换算规则和发布后只读规则。

## 3. 核心领域决策

### 3.1 课程、培养方案和教学班分层

课程库描述课程自身的稳定属性，如课程号、名称、学分和学时。新增课程时不要求管理员选择必修或选修。

培养方案描述某专业、某一入学年份范围的修读要求。同一课程可以在不同培养方案中分别为必修或选修。培养方案课程还记录建议修读学期。

教学班描述某学期课程的具体教学实施，包括教师、容量、开放对象和已发布排课。一个教学班可以面向多个专业，不为不同专业复制课程或教学班。

### 3.2 必修属性与选班方式分离

`REQUIRED`/`ELECTIVE` 只表达毕业培养要求。本期所有课程均由学生手动选择具体教学班；必修课不会自动分班。未来若增加行政班统一安排，应另建选课方式属性，不改变必修/选修语义。

### 3.3 人工排课与系统辅助

管理员在 JavaFX 周课表中选择星期、节次、周次、教室和教师。客户端提供占用提示和冲突格禁用，服务端在保存及发布时重新校验。客户端提示不构成最终业务授权或一致性保证。

## 4. 总体架构

数据流保持如下边界：

```text
JavaFX Academic View
    → AcademicController
    → AcademicGateway / SocketAcademicGateway
    → VCampusClient / MessageCodec
    → RequestRouter / AcademicService
    → AcademicRepository
    → MySQL
```

共享协议动作、领域枚举和无客户端依赖的校验策略放入 `vcampus-common`。Socket、会话校验、业务事务和 JDBC 访问留在 `vcampus-server`。JavaFX 节点、控制器、网关适配器和异步生命周期留在 `vcampus-client`。

教务路由完成原生迁移后由 `CampusApplication` 直接打开 `AcademicController` 管理的视图。迁移期间，默认 JavaFX 启动方式按完整业务链切换入口，不能把一个尚未迁移完整的写流程拆在 Swing 和 JavaFX 两端；显式 `--swing` 启动方式保留为兼容回退。

## 5. 数据模型

实现使用 `database/migrations/014_academic_curriculum_javafx.sql`，避免与现有商店设计预留的 013 号迁移冲突，并同步更新 `database/schema.sql`。

### 5.1 培养方案

`curriculum_plans`：

- `id`
- `major_id`
- `plan_name`
- `version_no`
- `enrollment_year_start`
- `enrollment_year_end`
- `status`：`DRAFT`、`PUBLISHED`、`ARCHIVED`
- `created_by_user_id`、`created_at`、`published_by_user_id`、`published_at`

同一专业的已发布方案不得存在重叠的入学年份范围。草稿可编辑；已发布方案不可原地修改，只能复制为更高版本。仍有 `ENROLLED` 或 `SUSPENDED` 学生落在适用范围内的方案不得归档；归档方案只用于历史查询，不再参与当前学生的方案匹配。

`curriculum_plan_courses`：

- `id`
- `plan_id`
- `course_id`
- `requirement_type`：`REQUIRED`、`ELECTIVE`
- `recommended_term_number`

`(plan_id, course_id)` 唯一。建议学期限定为 1—12，满足本科培养方案展示需求。

学生适用方案由 `student_profiles.major_id` 和 `student_profiles.enrollment_year` 匹配。若不存在唯一已发布方案，学生选课查询返回明确的“未配置适用培养方案”业务错误，不能回退到全校默认类型。

### 5.2 教学班开放范围

`course_section_targets`：

- `id`
- `section_id`
- `major_id`
- `enrollment_year_start`
- `enrollment_year_end`

一个教学班可以有多个开放范围。没有显式范围时，允许所有已发布培养方案中包含该课程且学籍状态允许选课的学生选择；配置范围后还必须同时满足范围和培养方案条件。

### 5.3 排课版本

`course_section_schedule_revisions`：

- `id`
- `section_id`
- `revision_no`
- `status`：`DRAFT`、`PUBLISHED`、`SUPERSEDED`
- `created_by_user_id`、`created_at`、`published_by_user_id`、`published_at`

排课时段属于一个排课版本。现有 `class_schedules` 数据迁移为每个教学班的首个已发布版本；表结构增加 `revision_id`，并以包含 `section_id` 的组合外键保证时段、版本和教学班一致，同时保留通过教学班和时间查询所需索引。

每个教学班最多有一个草稿版本和一个当前已发布版本。发布新版本时，旧版本在同一事务内转为 `SUPERSEDED`。学生和教师课表只读取当前 `PUBLISHED` 版本。

教学班的 `PLANNED`、`OPEN`、`CLOSED`、`COMPLETED` 状态继续表达选课和教学生命周期，不与排课版本状态混用。

### 5.4 共享枚举

在 `vcampus-common` 增加：

```text
CourseRequirementType: REQUIRED, ELECTIVE
CurriculumPlanStatus: DRAFT, PUBLISHED, ARCHIVED
ScheduleRevisionStatus: DRAFT, PUBLISHED, SUPERSEDED
```

## 6. 服务动作与事务

新增动作继续使用 `academic.` 前缀：

- `academic.curriculum.search/get/create/update/copy/publish/archive`
- `academic.curriculum.course.add/update/remove`
- `academic.section.targets.get/save`
- `academic.schedule.draft.get/save/publish`
- `academic.enrollment.switchSection`

现有 `academic.enrollment.available` 响应按课程分组所需字段扩展：课程 ID、培养方案要求类型、建议学期，以及各教学班的教师、排课、容量、开放范围匹配和冲突状态。新增字段采用附加键或带版本的行结构，保留现有客户端可读取的字段顺序。

### 6.1 培养方案发布

发布事务锁定同专业可能重叠的方案，校验年份范围、课程有效性和唯一性，然后写入发布人和发布时间。已发布方案不允许更新或删除课程关系。

### 6.2 排课草稿保存与发布

保存草稿时校验时段自身合法性和同一教学班内部重叠。发布时锁定教学班及其排课版本，重新检查教师和教室在星期、节次、周次上的冲突，并使用请求携带的版本号防止管理员覆盖他人的更新。

发布成功后生成面向受影响教师和在选学生的教务通知。失败时保留草稿并返回具体冲突对象与时段。

### 6.3 原子换班

`academic.enrollment.switchSection` 接收原教学班 ID 和目标教学班 ID。服务端按 ID 固定顺序锁定两个教学班，确认它们属于同一学期和同一课程，并验证学生当前选中原班。

容量、教学班状态、选课时间、培养方案范围和时间冲突检查均针对目标班执行；时间冲突计算忽略即将退出的原班。验证通过后，在同一事务内恢复或创建目标选课记录、退出原记录并更新两个教学班人数。任何步骤失败均整体回滚。

## 7. 权限

- 学生：查看适用培养方案结果、可选教学班、本人已发布课表和成绩；执行选课、退课和换班。
- 教师：查看本人已发布课表、教学班、名单和成绩；按现有规则录入及发布成绩。
- 教务管理员：管理课程、培养方案、教学班开放范围、排课草稿和排课发布，并保留现有代发布成绩能力。
- 超级管理员：拥有全部教务能力。

所有权限在服务端基于会话角色重新校验。客户端角色控制只负责导航和可见性。

## 8. JavaFX 客户端

### 8.1 代码结构

```text
vcampus-client/src/main/java/com/vcampus/client/fx/academic/
├─ AcademicGateway.java
├─ SocketAcademicGateway.java
├─ AcademicController.java
├─ AcademicData.java
├─ AcademicWorkspaceView.java
├─ CourseCatalogView.java
├─ CurriculumPlanView.java
├─ SectionManagementView.java
├─ ScheduleEditorView.java
├─ EnrollmentCenterView.java
├─ StudentScheduleView.java
└─ TeacherWorkspaceView.java
```

视图使用 Java 代码构造控件并通过 CSS 定义视觉样式，与当前原生 JavaFX 门户、图书馆和论坛保持一致，不新增 FXML 体系。

### 8.2 管理员工作区

- 课程库：分页搜索、新增、编辑和启停课程。
- 培养方案：按专业与年份范围筛选，复制旧方案，批量添加课程，批量设置要求类型和建议学期，发布前显示完整性检查。
- 开课管理：从建议学期课程生成教学班，设置教师、容量和开放范围。
- 智能排课：在 7 天 × 12 节周视图中拖拽选择时段，切换周次范围，显示教师和教室占用，并支持复制教学班及时段。
- 发布中心：集中列出无教师、无排课、冲突、容量异常和未发布草稿，确认后发布排课。

### 8.3 学生工作区

选课中心先显示课程卡片，再展开具体教学班。课程卡片显示必修/选修、学分、建议学期和修读状态；教学班显示教师、周次、时间、教室、余量和冲突状态。选择前在侧栏预览加入后的课表。

“我的课程”提供退课和换班。“我的课表”支持按教学周切换，只显示已发布排课。“我的成绩”继续只显示已发布成绩。

### 8.4 教师工作区

教师可查看周课表、当天课程、本人教学班、名单和成绩。排课新版本发布后通过通知进入相应教学班或课表位置。

### 8.5 异步生命周期

所有 Socket 请求在专用后台执行器运行，JavaFX 节点只在 JavaFX Application Thread 更新。控制器为加载请求维护代次标识；切换模块、退出登录或控制器关闭后，旧代次回调被丢弃。页面统一提供加载、空数据、可重试错误和会话失效状态，并保留尚未成功提交的表单内容。

## 9. 迁移顺序

1. 增加公共枚举、数据库迁移、Repository、Service、Router 和协议兼容测试。
2. 上线原生 JavaFX 课程库、培养方案、开课管理、智能排课和发布中心。
3. 上线原生 JavaFX 学生选课、课表预览、退课、换班、个人课表和成绩。
4. 上线原生 JavaFX 教师课表、教学班、名单和成绩，并完成通知深链。
5. 全部角色流程通过验收后，将 `academic` 默认路由切换为原生 JavaFX；旧 Swing 页面仅由 `--swing` 启动方式访问。

每一阶段只在默认 JavaFX 启动方式中迁移完整可用的业务链；显式 `--swing` 模式继续调用相同的服务端动作和规则，作为兼容及课堂演示回退。

## 10. 错误处理与并发

- 客户端即时校验用于交互反馈，服务端校验是最终依据。
- 业务错误区分教学班已满、选课窗口关闭、培养方案不匹配、教师冲突、教室冲突、个人课表冲突和并发版本过期。
- 数据库异常只记录服务端诊断信息，客户端收到不包含 SQL 和内部结构的中文错误。
- 培养方案和排课发布使用事务、行锁及版本校验。
- 选课、退课和换班在事务内维护 `enrolled_count`，并保留现有容量约束。
- 网络失败不假定请求失败；客户端重新加载服务端状态后再允许重复提交，避免重复写入。

## 11. 测试与验收

### 11.1 自动化测试

- `vcampus-common`：新枚举、要求类型显示策略、协议向后兼容。
- `vcampus-server`：培养方案年份匹配、发布不可变、重叠范围拒绝、开放范围、排课版本发布及冲突、权限和通知。
- 数据库并发：教学班容量不超限；换班成功时人数正确；换班失败时原记录和人数不变。
- Router：所有新增动作的未登录、无权限、非法参数、成功和业务失败响应。
- `vcampus-client`：数据解码、课程分组、角色导航、异步代次、退出登录清理和通知深链。
- JavaFX：加载、空数据、网络错误、表单保留、不同角色页面以及 1000、1220、1440 像素宽度截图。

### 11.2 验收场景

1. 同一课程在两个专业培养方案中分别显示为必修和选修。
2. 管理员复制旧方案、调整年份范围和课程要求后发布，新生匹配新方案，原学生仍匹配历史方案。
3. 一个课程开设多个教学班，学生可比较教师、时间和余量后选择其中一个。
4. 同一学期不能同时选择同一课程的两个教学班。
5. 换入满班或冲突班失败时，学生仍保留原班。
6. 管理员无法保存或发布教师、教室重叠的排课。
7. 修改已发布排课时，学生在新版本发布前继续看到旧课表。
8. 新排课发布后，教师和学生收到通知并能深链到正确页面。
9. 各角色不能通过直接构造请求越权。
10. 根目录执行 `mvn clean verify` 全部通过，实际 JavaFX 截图完成视觉检查。

## 12. 文档交付

实现过程中同步更新：

- `docs/requirements.md`：按阶段更新功能状态。
- `docs/academic-management.md`：记录培养方案、换班、排课版本和 JavaFX 操作说明。
- `database/schema.sql` 与 `database/migrations/014_academic_curriculum_javafx.sql`：保持全量和增量结构一致。
- JavaFX 教务设计截图与视觉检查记录：保存到 `docs/design/javafx-academic/`。
