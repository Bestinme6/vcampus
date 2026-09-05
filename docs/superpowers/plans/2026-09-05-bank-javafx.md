# Bank JavaFX Implementation Plan

> 使用 executing-plans 在当前任务串行执行；用户已明确不使用子代理。

**Goal:** 将已确认的 B1/T1 草图接入正式银行业务。
**Architecture:** 既有服务与事务 + 向后兼容只读查询 + 类型化客户端网关 + FX 控制器与视图。
**Tech Stack:** Java 21, JavaFX, CSS, Socket, JDBC, MySQL, JUnit/H2。
**Spec:** ../specs/2026-09-05-bank-javafx-design.md

## 全局约束

不修改原工作目录；不直连 MySQL；不新增备注；不使用示例余额；
不修改流水记录；保持原有 Swing 接口契约。main 基线 8a81de6 已通过 clean verify。

## 1. 服务端查询契约

- [x] 先扩展 BankRepositoryTest、BankServiceTest：只读预检不开户；普通账号不能扩大查询；
  管理员 scope=mine 强制本人；日期/关键字/编号筛选及全结果聚合。
- [x] common/protocol/Actions.java 添加 BANK_RECIPIENT_GET、BANK_LEDGER_ORDER。
- [x] server/database/BankStore.java：Recipient、向后兼容 LedgerQuery/ LedgerPage。
- [x] BankRepository.java：精确查用户、过滤参数绑定、受限订单关联。
- [x] BankService.java、RequestRouter.java：动作、会话及查询范围校验。
- [x] 验证：mvn -pl vcampus-server -am -Dtest=BankRepositoryTest,BankServiceTest -Dsurefire.failIfNoSpecifiedTests=false test。

## 2. 类型化网关和转账状态

- [x] client/network/VCampusClient.java 添加新动作封装，不改变旧封装。
- [x] fx/bank/BankData.java、BankGateway.java、SocketBankGateway.java 解码账户、流水、回执；
  业务拒绝与网络/响应异常分别处理。
- [x] fx/bank/BankOperation.java 保留目标、金额、UUID、提交/结果待确认状态；
  重试复用相同参数，阻止提交期间并发操作。
- [x] BankDataTest、BankOperationTest 检查恶意/畸形响应、MoneyPolicy 与幂等重试。

## 3. 原生视图与异步协调

- [x] fx/bank/BankView.java 模块导航、页面容器、状态；BankUi.java 和 bank.css 统一样式。
- [x] BankHomeView.java 余额卡与最近流水；BankTransferView.java 三步表单与真实回执。
- [x] BankLedgerView.java 服务器筛选/分页/详情；BankAdminView.java 账户检索与二次确认。
- [x] BankController.java 会话作用域协调，查询 generation 丢弃过期响应，
  资金操作独立保留结果，不因离开页面遗失待确认编号。
- [x] BankControllerTest 验证后台执行、登出隔离、重复点击和未知结果。

## 4. 集成与交付验证

- [x] CampusApplication.java 路由 bank/bank-ledger 接原生页面；登出关闭控制器和执行器。
- [x] LegacyModuleBridge.java 新增银行通知回调，保留旧构造器与 Swing fallback。
- [x] 运行 BankVisualTest 生成真正 JavaFX 截图并检查首页、转账、流水、管理员及冻结状态。
- [x] 更新 docs/requirements.md、docs/bank.md；执行 mvn clean verify、git diff --check。
- [x] 提交银行功能分支，保留工作树供用户启动验收；不自动合并或推送。
