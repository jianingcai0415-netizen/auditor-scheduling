# 订单审批 + 求解流程重构方案

> 状态：**已实施（2026-10-20，Flyway v24）**
> 日期：2026-10-20
> 背景：地图功能暴露出「98.6% 的班次从未经过发布流程」。经讨论确认系统定位是
> **「分配即生效」**，因此把审批从执行层搬到需求层，并让求解器成为唯一的选人入口。
>
> **拍板结论**：审批粒度=订单；求解单位=时间窗；未批准订单跳过并提示；
> 「保存结果 = 定稿」；方案状态由 4 态收敛为 `DRAFT` / `EXECUTED` / `SUPERSEDED`。

---

## 一、现状核查（全部为代码/数据实测）

### 1.1 数据结构：三层，但没有审批态

```
audit_orders（订单，123 条，status 全 = ACTIVE）
  └── audit_order_phases（阶段，367 条，status 全 = READY）
        └── shifts（班次，740 条，611 条挂到阶段上）
              └── employee_id（谁去做 —— 分配就写在这里）
```

一个订单典型展开成 **6 条班次**（如 `ORD-TEST-0001` → 6 条）。
`audit_orders.status` 与 `audit_order_phases.status` 都是**固定值**，不具备流转能力——
**「订单草案」这个概念目前在数据层不存在。**

### 1.2 审批能力装在了执行层

完整状态机只在 `schedule_plans`：

| 状态 | 数量 |
|---|---|
| DRAFT | 4 |
| PUBLISHED | 3 |
| SUPERSEDED | 9 |

而 `plan_assignments` 只有 311 条，对应 693 条已分配班次 —— **方案体系覆盖不到一半的班次**，
其余班次是通过求解器直接写入的。

补充：`schedule_plans.status` 是 `TEXT NOT NULL DEFAULT 'DRAFT'`，**没有 CHECK 约束**，
因此扩展状态值不需要重建表（这一点对 SQLite 很关键，重建表是高风险操作）。

### 1.3 草案的粒度是「时间窗」，但已有按订单建草案的能力

- `POST /schedule-plans` 接收 `windowStart/windowEnd`，把窗口内**所有**班次拉进方案
  （`SchedulePlanResource.java:155/216`）。
- **但 `POST /schedule-plans/from-order/{orderId}` 已经存在**
  （`SchedulePlanResource.java:197`）：按订单取班次、建方案、`source='ORDER'`、写快照，
  前端已有入口（`WorkflowPage.tsx:555`，提示「已生成仅包含该订单的排程草案」）。

**这是一个重要发现：用户想要的「订单草案」能力已经实现，只是它被放在了"挑完候选人之后"，
而不是流程起点。**

### 1.4 求解器完全绕过方案

```
GET  /demo-data/generate          → 取输入（按 structureId + 时间窗）
POST /schedules                   → 提交求解，返回 jobId（EmployeesScheduleResource:136）
GET  /schedules/{jobId}           → 轮询结果
POST /demo-data/save-assignments  → 直接写 shifts.employee_id
```

求解器**不知道 plan 的存在**，保存结果也不回写方案 —— 这是"两条写入路径"的根因。

### 1.5 `published_plan_id` 没有计算依赖

全部消费方仅 3 处：发布时写入、旧方案标 SUPERSEDED、求解器改动后清空。
地图 / 候选打分 / 风险校验 / 报表**均不读取**。

→ 它只是版本标记，不是生效开关。

---

## 二、目标流程

```
① 填写订单（草稿）           ← 人工，含客户/项目/阶段/时间/地点/人数
        ↓
② 提交审批 → 批准 / 退回      ← 审批人；批准的是"需求"，不含具体人员
        ↓
③ 排班（求解）               ← 只对已批准订单的班次求解，跨订单统一优化
        ↓
④ 保存结果                   ← 写入 employee_id（此刻生效）+ 自动生成快照
        ↓
   定稿（无需人工点击）
```

关键点：

- **审批在选人之前**，审的是"要不要接、什么时候、要几个人"；
- **选人只有一个入口**：求解器（叠加现有打分权重 + AI 初筛）；
- **保存即定稿**，快照自动生成，不再需要第二次人工确认；
- 地图/报表继续按 `employee_id` 实算 —— **不需要任何改动**。

---

## 三、改动清单

### 3.1 数据迁移 `V24`（一次，低风险）

`audit_orders` 增列（只加列，不改语义，可回滚）：

| 列 | 类型 | 说明 |
|---|---|---|
| `approval_status` | TEXT | `DRAFT` / `SUBMITTED` / `APPROVED` / `REJECTED` |
| `submitted_at` | TEXT | 提交时间 |
| `approved_at` | TEXT | 批准/退回时间 |
| `approved_by` | TEXT | 审批人 |
| `reject_reason` | TEXT | 退回原因 |

存量处理：**123 条全部置为 `APPROVED`**（它们已在运行，不能要求补审，否则等于停业）。

同步要求：`db/migration/sqlite/V24__*.sql` 与 `db/migration/postgresql/V24__*.sql`
文件名一致、DDL 契约一致（由 `MigrationSchemaParityTest` 强制）。

### 3.2 后端

| # | 改动 | 文件 | 规模 | 风险 |
|---|---|---|---|---|
| B1 | 订单实体加 `approvalStatus` 等字段 | `AuditOrderEntity` | 小 | 低 |
| B2 | 订单审批端点：`submit` / `approve` / `reject` | `AuditOrderResource`(+3) | 小 | 低 |
| B3 | 订单 DTO 返回审批态与时间 | `AuditOrderResource` | 小 | 低 |
| B4 | 求解输入过滤：`generate` 只取已批准订单的班次 | `EmployeeScheduleDemoResource:164` | 中 | **中** |
| B5 | `save-assignments` 支持 `planId`：回写方案快照 + 置定稿态 | 同上 `:1513` | 中 | **中** |
| B6 | `createFromOrder` 增加前置校验：订单必须 `APPROVED` | `SchedulePlanResource:197` | 小 | 低 |
| B7 | 审计事件：`AUDIT_ORDER_SUBMITTED/APPROVED/REJECTED` | `AuditTrailService` 调用点 | 小 | 低 |

**B4 的兼容设计（重要）**：新增配置 `app.solver.require-approved-orders`，**默认 `false`**。
关闭时行为与现在完全一致；打开后才过滤。这样可以先上线代码、观察，再灰度切换，
避免一次性改变所有人的求解结果。

**B5 的兼容设计**：`planId` 为可选参数。
- 不传 → 现状行为（只写 `employee_id`）；
- 传 → 额外回写 `schedule_plan_assignments.employee_id`、`plan.status = 'EXECUTED'`、
  `executed_at`（新列），并记录快照。

回写安全性：`schedule_plan_assignments` 有 `UNIQUE (plan_id, shift_id)`，
所以回写是按 `(planId, shiftId)` 的 **upsert**，不会产生重复行；一个班次若已属于另一个
`EXECUTED` 方案，则跳过并记 WARN（见第六节）。

### 3.3 前端

| # | 改动 | 文件 | 规模 |
|---|---|---|---|
| F1 | 订单列表加「审批状态」列 + 提交/批准/退回按钮 | `WorkflowPage`（orders tab） | 中 |
| F2 | 新建订单默认 `DRAFT`，表单底部「保存草案 / 保存并提交」 | `WorkflowPage` | 小 |
| F3 | 首页四张卡改为：待提交 / 待审批 / 待排班 / 已定稿 | `WorkflowPage` 顶部 | 小 |
| F4 | 「按订单建草案」入口前移到订单行（不再要求先挑候选） | `WorkflowPage` | 小 |
| F5 | 排班页求解按钮旁显示「本次将排 N 个已批准订单 / M 条班次」 | `ShiftsPage` | 中 |
| F6 | 保存结果后提示「已定稿，快照已生成」并刷新方案 | `ShiftsPage` | 小 |
| F7 | 新增状态 `EXECUTED` 的标签/颜色/筛选映射 | `WorkflowPage`、`workflow.ts` | 小 |

### 3.4 不需要改的部分（已确认）

- **地图**：按 `employee_id` 实算，口径不变；
- **候选打分**：`locationScore` 已用 `TravelMatrix`，AI 初筛逻辑不变，只是从"主路径"变成"审批后求解"的一环；
- **`schedule_plans` 的修订链/并发保护/过期校验**：全部保留，只是角色从「业务草案」转为「求解批次」。

### 3.5 测试

| 测试 | 覆盖点 |
|---|---|
| `AuditOrderApprovalTest`（新增） | DRAFT→SUBMITTED→APPROVED/REJECTED 流转、非法跃迁 409 |
| `SolverInputFilterTest`（新增） | 开关关闭时行为不变；打开时只取 APPROVED 订单班次 |
| `SaveAssignmentsPlanWritebackTest`（新增） | 传 planId 时回写快照与状态；不传时行为不变 |
| `MigrationSchemaParityTest` | V24 双引擎 DDL 一致（已有） |
| `MapOverviewResourceTest` | 回归：口径不受审批态影响 |

---

## 四、需要你拍板的三个分叉

### 分叉 1：审批的粒度是订单还是阶段？

- **A. 只批订单**（推荐）：一次操作批整单，阶段自动跟随。
  123 个订单 = 123 次审批；阶段 367 条不用管。
- **B. 订单 + 阶段分别批**：更细，但操作量 ×3，且阶段之间通常同进同退。

### 分叉 2：求解的单位是「方案」还是「时间窗」？

- **A. 按时间窗**（推荐，即现状）：跨订单统一优化，能发现"同一时段抢同一个人"。
  求解时只过滤掉未批准订单的班次。
- **B. 按方案**：一次只算一个草案。审批语义更清晰，但撞车风险要靠人工规避。

### 分叉 3：未批准订单在求解时怎么处理？

- **A. 跳过并提示**（推荐）：一个窗口里往往混着已批和待批，阻断会让整批算不了。
- **B. 拒绝整批**：更严格，但实操中容易卡住。

---

## 四·五、`schedule_plans` 的状态机怎么收敛（P4，可选）

审批搬到订单层后，方案的四个状态里有两个会失去意义：

| 现状态 | 现状含义 | 审批上移后 |
|---|---|---|
| `DRAFT` | 待完善 | 保留 —— 求解批次 |
| `REVIEW` | 待审批 | **冗余**（审批在订单层） |
| `APPROVED` | 已批准 | **冗余** |
| `PUBLISHED` | 已发布（替代旧版） | 可由「已定稿」承担 |
| `SUPERSEDED` | 被新版本替代 | 保留 —— 版本链 |

收敛建议：**`DRAFT` → `EXECUTED` 两态 + `SUPERSEDED`**。
`DRAFT` = 这批班次正在/等待排人；`EXECUTED` = 已排定，快照封存。

好处是职责彻底分开：**订单管"要不要做"，方案管"谁来做、做到什么程度"**。
但这是 P4 的事，P1~P3 期间保留现有状态机不动，避免一次改太多。

---

## 五、分期实施建议

| 期 | 内容 | 可独立上线 | 风险 |
|---|---|---|---|
| **P1** | V24 迁移 + 订单审批端点 + 前端按钮（**不做任何流程强制**） | 是 | 低 |
| **P2** | 求解过滤开关（默认关）+ `save-assignments` 回写 `planId` | 是 | 中 |
| **P3** | 主路径调整（F3/F4/F5/F6）+ 打开过滤开关 | 是 | 中 |
| **P4**（可选） | 清理 `schedule_plans` 中不再使用的环节（如 REJECTED 独立态） | 是 | 中 |

P1 上线后系统行为**完全不变**，只是多了审批字段和按钮——可以放心先观察数据。

---

## 六、风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 打开 B4 过滤后，部分班次"没人排" | 用户觉得系统少算了 | 开关默认关；打开前先在界面上显示被跳过的订单数；保留开关可即时回退 |
| B5 回写时多个方案覆盖同一班次 | 快照与实现不一致 | 回写前校验：一个班次只能属于一个 EXECUTED 方案，冲突则跳过并记 WARN |
| 存量 123 单直接置 APPROVED 掩盖了历史 | 无法知道哪些真审过 | 迁移同时写审计事件 `AUDIT_ORDER_APPROVAL_BACKFILLED`，标注为迁移补录 |
| V24 迁移失败 | 启动受阻 | 只加列，失败可重建；上线前用 `databases/backups/` 快照演练 |

回滚：V24 只增列不改语义 → 代码回退即可，数据库无需回滚。

---

## 七、工作量粗估

| 部分 | 文件数 | 说明 |
|---|---|---|
| 迁移 | 2 | sqlite + postgresql |
| 后端 | 3~4 | AuditOrderResource、EmployeeScheduleDemoResource、SchedulePlanResource、实体 |
| 前端 | 2 | WorkflowPage、ShiftsPage |
| 测试 | 3 | 新增 3 个 + 回归 |
| 文档 | 2 | ARCHITECTURE.md、USER-GUIDE.md 的流程章节 |

---

## 八、拍板结果与实施记录

四项全部拍板，且已于 2026-10-20 一次性实施（未分期）：

| 决策 | 结论 | 落地位置 |
|---|---|---|
| 审批粒度 | 只批订单 | `POST /audit-orders/{id}/submit\|approve\|reject` |
| 求解单位 | 按时间窗（跨订单优化） | `DemoDataRepository.generateDemoData(..., approvedOnly)` |
| 未批准订单 | 跳过并提示（不阻断整批） | 同上，`audit_order_code` 为空的班次保留 |
| 保存=定稿 | 接受，不再有人工发布 | `saveAssignments` → `finalizePlansForWindow` |
| 状态机收敛 | 4 态 → `DRAFT`/`EXECUTED`/`SUPERSEDED` | V24 迁移 + `SchedulePlanResource` |

### 实施清单（实际改动）

**数据库**：`V24__order_approval_and_plan_states.sql`（sqlite/postgresql 同文）
- `audit_orders` + `submitted_at` / `approved_at` / `approved_by` / `reject_reason`
- 订单 `ACTIVE`(123) → `APPROVED`
- 方案 `PUBLISHED`(3) → `EXECUTED`；`REVIEW`/`APPROVED` → `DRAFT`（本库无此二者）

**后端**
- `AuditOrderResource`：状态集扩为 6 值；三个审批端点；编辑订单在非 DRAFT/REJECTED 状态下
  只能改内容、不能改状态（防绕过审批），但允许置为 `CANCELLED`/`COMPLETED`
- `SchedulePlanResource`：删除 `submit`/`approve`/`reject`；`publish` 改为 `DRAFT → EXECUTED`（角色放宽到 DISPATCHER）
- `DemoDataRepository`：求解输入支持 `approvedOnly` 过滤
- `EmployeeScheduleDemoResource`：`save-assignments` 保存后回填快照并定稿；配置开关
  `app.solver.require-approved-orders`（**默认 false**）
- `SchedulePlanEntity.supersedeOverlapping(...)`：定稿与保存共用同一段替代逻辑

**前端**
- 订单列表：状态徽章、提交/批准/退回按钮、退回原因弹窗、审批时间展示
- 方案详情：步骤条缩为「草案 → 已定稿」；底部只保留「定稿 / 新建修订 / 删除草案」
- 顶部四卡改为：已批准订单 / 待排班草案 / 待审批订单 / 已定稿方案

**测试**（全部通过）
- `AuditOrderApprovalTest` 2/2：前向流转、非法跃迁 409、编辑不可绕过审批、退回后可重提
- `SaveAssignmentsFinalizationTest` 2/2：全部分配→自动定稿；部分分配→保持草案
- `InternalDispatchWorkflowTest` 1/1（已改造）：端到端 订单→审批→草案→定稿→修订→替代
- `MigrationSchemaParityTest`、`MapOverviewResourceTest`、`UiTranslationCatalogTest`、
  `DatabasePortabilityContractTest` 均通过

### 上线后状态（正式库）

| 项 | 值 |
|---|---|
| Flyway | v24 |
| 订单 | 123 条全部 `APPROVED`（迁移补录，`approvedAt` 为空表示"非人工批准"） |
| 方案 | 4 `DRAFT` / 3 `EXECUTED` / 9 `SUPERSEDED` |
| 开关 | `app.solver.require-approved-orders=false`（求解行为与改造前一致） |

### 遗留 / 下一步

1. **打开过滤开关**：待审批习惯建立后再把 `app.solver.require-approved-orders` 改为 true。
   打开后未批准订单的班次不会进入求解，但仍在排班页可见。
2. **地图不受影响**：口径仍按 `employee_id` 实算，不读订单状态（本次未改一行）。
3. `approvedBy` 为空的存量订单：前端不显示批准时间，属预期（迁移补录不伪造人工信息）。
