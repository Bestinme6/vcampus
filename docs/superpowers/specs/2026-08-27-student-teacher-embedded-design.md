# 学籍与教师档案嵌入设计

## 目标

在图书馆嵌入式试点通过后，将工作台“学”和“师”入口迁移到 `MainFrame` 右侧内容区，并将学籍通知的“查看学籍信息”深链改为定位嵌入式学生档案。

## 范围

- 学生点击“学籍信息”进入本人学籍面板。
- 教师学籍管理员及超级管理员点击“学籍管理”进入管理面板。
- 教师点击“教师信息”进入本人教师档案面板。
- 学籍消息深链只对学生身份开放，并进入本人学籍面板。
- 原 `StudentFrame` 和 `TeacherProfileFrame` 保留为薄兼容窗口。
- 不修改服务端、数据库、协议、权限与既有表单行为。

## 结构

`StudentModulePanel` 承接原 `StudentFrame` 的所有字段、查询、编辑和状态历史逻辑，并根据角色继续决定显示管理员视图还是本人视图。`TeacherProfileModulePanel` 承接教师档案展示和联系方式修改。

两个面板都接受 `Runnable backToWorkspace`；嵌入主窗口时显示“返回工作台”，兼容窗口传入空回调并不显示该按钮。主内容容器继续惰性创建并缓存：学生本人、学籍管理、教师档案使用不同页面键，避免多角色教师的两个入口互相覆盖。

## 路由

- `PERSONAL_PROFILE`：学生路由至 `student-profile`，教师路由至 `teacher-profile`。
- `STUDENT_STATUS`：路由至 `student-management`。
- `NotificationTarget.STUDENT_PROFILE`：路由至 `student-profile`。

## 验收标准

1. “学”和“师”入口均不再打开顶层窗口。
2. 返回工作台后重新进入时复用原面板。
3. 学生、教师、学籍管理员和超级管理员仍看到正确内容。
4. 修改联系方式、状态确认、历史详情与结果反馈仍使用对话框。
5. 学籍通知深链定位本人学籍。
6. `mvn clean verify` 全部通过。

