# 审核员排程扩展

本项目已从通用员工排班扩展为可进行单部门内部试点的第三方检测/认证审核员调配系统。

## 数据如何表达

- “技能”代表一个认证方案或专业资格，例如 QMS、EMS、OHSMS、FSMS、ISMS。
- 审核员与技能的关联现在包含证书编号、级别、生效日、失效日和状态。只有状态为  
  `ACTIVE` 或 `EXPIRING`，且证书有效期完整覆盖任务时段，才视为满足硬性资格要求。
- 正式业务对象为审核订单、订单阶段和阶段资格要求。阶段保存时间、地点、人数、主审核员、  
  连续阶段组和资格组合；系统确定性地生成相同数量的 `shifts` 名额供求解器使用。
- 地点可保存经纬度。审核员可保存当前位置、外派结束时间、工作前缓冲和工作后缓冲。

## 实时状态

审核员临时外派或状态变化时，调用：

```http
PUT /demo-data/employees/{employeeId}/status?structureId={structureId}
Content-Type: application/json

{
  "operationalStatus": "ASSIGNED",
  "busyUntil": "2026-10-12T18:00:00",
  "currentLocationId": 12,
  "bufferBeforeMinutes": 30,
  "bufferAfterMinutes": 60
}
```

可用状态为 `AVAILABLE`、`ASSIGNED`、`TRAVELLING`、`UNAVAILABLE`。状态写入时会自动更新  
`statusUpdatedAt`。排程结果提交时必须带上求解时看到的状态版本；如果期间有人被外派，后端返回  
HTTP 409 和 `EMPLOYEE_STATUS_CHANGED`，防止旧排程覆盖最新现场状态。

排程工作台通过 `GET /events/dispatch` 的 SSE 通道接收人员状态、订单、导入和方案版本变化；  
写入仍走 REST。SSE 只负责提示重新读取，发布时后端仍会重新校验版本和全部硬约束。

## 求解器硬约束

1. 任务所需资格必须存在、状态可用，且有效期覆盖整个任务。
2. `busyUntil` 加工作后缓冲、预计转场时间和工作前缓冲后，必须不晚于任务开始。
3. 同一审核员的两个任务之间也必须容纳前后缓冲及转场时间。
4. 经纬度齐全时，以球面距离 × 1.25 道路系数、60 km/h 和 20 分钟固定开销估算转场；  
   不同地点但坐标缺失时保守按 120 分钟计算。
5. 主审核员名额要求有效的 `LEAD_AUDITOR`、包含 `LEAD`、`组长` 或 `主任` 的资格级别。
6. 同一连续阶段组内，相同名额序号必须保持同一审核员；人数变化时仅保留两阶段共有的名额。
7. 阶段资格的 `minimumCount` 会转换为对应数量的带资格名额，`leadOnly` 仅落到首个主审核员名额。

## Top-K 候选人模拟评分

### 按订单号直接查询（推荐入口）

1. 登录后在右上角选择测试数据所在的部门。
2. 点击顶部“审核调配”，默认进入“按单号找候选”。
3. 在订单号框输入完整编号，例如 `ORD-TEST-0117`（忽略大小写和首尾空格）。
4. 选择 Top 3、Top 5、Top 10 或 Top 20，点击“查询最优候选人”，也可以直接按 Enter。
5. 结果按订单阶段和人员名额展开；每个名额分别显示主审核员/审核员要求、候选排名、综合分、  
   五项分数、预计转场时间和入选原因；同时显示已检查人数、合格人数以及各类淘汰原因统计。
6. 点击“生成该订单排程草案”，系统只捕获当前订单的人员名额，不会把同一日期的其他订单混入草案；  
   新草案中的名额全部从“未分配”开始，不继承测试工作簿里模拟的既有人员，避免把导入基线误认为当前推荐。
7. 在自动打开的草案中逐个名额选择并锁定人员，填写结构化选择原因后提交审批；发布前会按最新状态重新校验。

工作台顶部的四个数字卡片分别汇总进行中订单、待完成人员选择的草案、等待审批方案和已发布方案，  
点击卡片中的操作文字可直接跳到对应列表。订单列表可按订单号、客户、项目或负责人搜索；方案列表默认  
只显示待处理状态，也可切换为草案、待审批、已批准、已发布或全部状态。

在“订单与阶段”展开订单后，可直接点击“编辑订单”修改客户、负责人、优先级和订单状态，点击阶段行末  
的“编辑”可修改时间、地点、人数、资格、执行方式和阶段状态。订单必须为“进行中”，且至少有一个  
“就绪”阶段，才能查询候选或生成新草案；已取消/已完成订单以及待就绪/已取消/已完成阶段不会混入  
候选结果。取消只是业务状态变更，历史方案和操作日志不会被删除。

草案详情用完成度条显示“已分配/总名额”，并把未分配或存在风险的行高亮。只要仍有人员缺口、资格/时间/  
转场风险，或者人员状态、订单阶段版本已发生变化，系统就会同时在界面和服务端禁止提交审批；审批和发布  
前还会再次执行相同校验，避免仅靠按钮状态保护业务数据。

这个入口不需要先打开“排班管理”，也不需要点击月历上的 `Solve`。订单必须已经包含阶段，阶段必须有  
时间、地点、人数和资格要求；若提示订单不存在，应检查当前部门和完整订单号是否正确。

系统先用资格、有效期、主审核员要求、实时人员状态、不可用时间、已有任务冲突、个人缓冲和转场可达性等  
硬约束淘汰不可用人员，再对合格人员计算模拟综合分并返回 Top-K。默认 `K=5`，界面可切换  
Top 3、Top 5、Top 10 和 Top 20；接口允许 `1–20`。

系统初始权重为：能力 40%、地理位置 30%、计划窗口负荷 15%、连续阶段一致性 10%、年龄 5%。  
能力分依据有效资格数量、必需资格命中和主审核员资格；位置分依据离线估算转场分钟数；负荷分依据  
当前方案内已安排工时；连续性分依据同一连续阶段组、相同名额序号是否延续同一人。

位置分的算法是 `100 − min(基准, 转场分钟) × 100/基准`：同城 100 分，转场达到基准即 0 分，中间线性。  
基准由 `app.dispatch.location-score-baseline-minutes` 配置，默认 **2880 分钟（48 小时）**，  
与返程空窗期同一把尺子，覆盖实测最远 2777 分钟。这个值不能设得太小——曾经写死 240 分钟（4 小时），  
导致所有跨城候选一律 0 分，30% 的位置权重退化成「同城/不同城」二值开关。

“按单号找候选”页面会显示当前部门评分规则。调度员可点击“调整权重”，五项合计必须等于 100%，  
并填写调整依据。每次保存都会创建新的不可覆盖版本（v1、v2……），旧版本可通过  
`GET /candidate-weights/history?structureId={id}` 追溯；候选结果会回显本次实际使用的版本和权重，  
不会把新规则追溯套用到已经生成的候选结果或已发布方案。未保存部门规则时明确显示系统默认 v0。

当前人员表尚未采集年龄，因此年龄项对所有人按中性 50 分处理，返回值中  
`ageDataAvailable=false`，不会伪造年龄差异。真实试点明确合法合规的人事政策后，再接入年龄或更合适的  
工作能力字段并调整权重。候选列表会显示综合分、各分项分数和自然语言原因，供负责人审核；该排名是  
基于当前模拟权重的启发式排序，不代表数学证明的全局最优解。

人工锁定候选人时，先选择“负责人确认、客户或项目连续性、行业经验、语言、转场、负荷、临时外派、  
候选人不可参加、系统评分不符合实际”等标准原因，再按需补充说明。当前用于审批和审计留痕；后续可在  
积累足够记录后统计推荐接受率和人工改选原因，但不会由系统未经审核自动修改权重。

按订单号查询接口示例：

```http
GET /schedule-plans/order-candidates?structureId=1&orderCode=ORD-TEST-0117&limit=5
```

已有排程草案内对某个名额换人的接口：

```http
GET /schedule-plans/{planId}/assignments/{assignmentId}/candidates?structureId=1&limit=5
```

如需通过接口临时试验权重，可传入 `abilityWeight`、`locationWeight`、`workloadWeight`、  
`continuityWeight` 和 `ageWeight`；未传值时使用当前部门版本。临时参数不会保存，也不会创建新版本。  
正式调整使用 `PUT /candidate-weights?structureId={id}`，保存权重要求非负、总和为 100%，且必须填写  
`changeReason`，操作会写入审计日志。

当前转场计算是离线估算，适合测试和初始排程。生产使用时建议接入企业地图/路径规划服务，按实时交通、  
交通方式和跨城规则返回旅行时间。

## AI 候选初筛（可选）

AI 是规则候选之后的第二层辅助判断，不代替硬约束和人工审批。使用顺序为：输入订单号并查询候选 →  
系统先实时检查资格、时间、状态、缓冲和转场 → 点击“AI 初筛复核” → 人工比较 AI 建议与规则分数 →  
生成草案并手工锁定人员。AI 不能把被规则淘汰的人加回候选，也不能自动锁人、审批或发布。

没有密钥时现有候选查询和完整审批流程照常工作。项目根目录的 `.env` 已预设为 DeepSeek；在本机打开  
该文件，把密钥粘贴到 `DISPATCH_AI_API_KEY=` 后面，然后重启后端即可：

```dotenv
DISPATCH_AI_ENABLED=true
DISPATCH_AI_PROVIDER=deepseek
DISPATCH_AI_API_KEY=请填入真实密钥
DISPATCH_AI_MODEL=deepseek-chat
DISPATCH_AI_BASE_URL=https://api.deepseek.com
```

`.env` 已加入 `.gitignore`，只保存在当前电脑；`.env.example` 是可以复制的无密钥模板。不要把密钥粘贴  
到网页、业务代码、Excel 或数据库。浏览器只能读取“已配置/未配置”和模型名称，密钥仅由后端进程读取。  
请求使用 Responses API 的严格 JSON Schema 输出，并设置 `store=false`。发送给模型的  
数据只包含订单/阶段要求、匿名化人员编号、规则分数、转场分钟、工时和入选依据；姓名、联系方式、  
证书编号不会发送到外部服务。后端还会逐条验证 AI 返回的 `shiftId` 和 `employeeId`，越出本次规则候选  
白名单的响应会被整体拒绝。

相关接口：

- `GET /schedule-plans/ai-status`：查看 AI 是否可用、供应商和模型。
- `POST /schedule-plans/order-candidates/ai-review?structureId={id}&orderCode={code}&limit=5`：对当前  
  Top-K 合格候选执行 AI 复核；成功调用会写入操作日志。

应用通过 `CandidateAiProvider` 接口隔离业务与模型，并使用兼容 Responses API 的通用实现。DeepSeek 与  
OpenAI 可通过 `.env` 的供应商、模型和 Base URL 切换，排程、审批和前端候选流程无需重写。

## 测试数据

工作区已有 500 名完全合成的审核员测试数据（不对应真实个人）：

- [`samples/synthetic_auditor_test_dataset_500.xlsx`](samples/synthetic_auditor_test_dataset_500.xlsx)
- `../outputs/01a0ec19-9fbf-7b40-a5b8-633acc5fb7c8/synthetic_auditor_test_dataset_500.json`

数据覆盖 1,790 条资格、30 个地点、900 条转场矩阵、空闲/请假例外、已有外派任务和多阶段订单。  
系统工作台提供固定模板、上传预检查、工作表/行/字段级错误、错误 CSV 下载和确认导入。  
重复导入按员工编号、订单编号、阶段编号和资格关联更新，不重复新增。模板可从  
`GET /imports/template` 下载，现有 500 人合成数据可先用于字段与性能验收。

## 订单与排程版本接口

- `/audit-orders`：订单、阶段和岗位名额维护。
- `/imports`：模板下载、预检查、错误下载和确认导入。
- `/schedule-plans`：捕获草案、人工换人/锁定、提交、批准、退回、发布和创建修订版。
- `/schedule-plans/order-candidates/ai-review`：对确定性合格候选执行可选的 AI 二次初筛。
- `/candidate-weights`：读取和版本化保存部门候选评分规则；`/history` 查询历史版本。
- `/audit-events`：审批人和管理员查看不可由业务接口修改的操作记录。
- `/events/dispatch`：实时变化通知。

方案状态为 `DRAFT → REVIEW → APPROVED → PUBLISHED`。发布后的调整通过 `revise` 生成新版本；  
重叠窗口内原发布版本自动标记为 `SUPERSEDED`。导入确认和发布前均要求安全备份成功。

紧急换人时，打开已发布方案，在对应人员行点击“紧急换人”。系统先创建草案修订版，再只显示通过  
当前硬约束的替补候选；选择原因默认使用“临时外派变化”，可补充业务说明。替补锁定后需要重新执行  
提交审批、批准、发布。新版本发布前原版本继续有效；新版本发布成功后，原版本才标记为  
`SUPERSEDED`，因此不会直接篡改已发布历史。

## 权限

- `ADMIN`：系统配置、用户、备份及全部业务动作。
- `DISPATCHER`：数据导入、订单阶段、求解草案、人工调整和提交审批。
- `APPROVER`：查看风险与日志、批准、退回和发布。
- `VIEWER`：只读查看订单和方案。
- `CAPOSALA`：保留为旧数据兼容角色，业务权限按 `DISPATCHER` 处理。

## 仓库约定

**提交信息用英文，遵循 Conventional Commits。** 结构是 `<type>(<scope>): <subject>`：主题用祈使句、
小写开头、不加句号、控制在 50 字符左右；空一行写正文（每行 ≤ 72 字符），正文只回答"为什么改、
影响什么"，不复述 diff。`type` 只用 `feat` / `fix` / `docs` / `refactor` / `test` / `chore` /
`ci` / `perf` / `build`，`scope` 取模块名（`db`、`frontend`、`seed`、`repo`…），跨模块就省略；
破坏性变更在 type 后加 `!`，并在 footer 写 `BREAKING CHANGE:`。

```
fix(db): boolean literals and REAL column width

JPQL rendered `active = true` as the SQL literal `true`, but the column is
INTEGER in both engines: SQLite reads it as 1, PostgreSQL rejects it with
"operator does not exist: integer = boolean".

V21 declared the travel-matrix coordinates as REAL, which is 8 bytes in SQLite
and 4 in PostgreSQL, so `WHERE from_lat = ?` never matched the stored value and
the cache upsert degenerated into an insert. V27 widens both engines.
```

**路径一律 ASCII。** 目录名、文件名、分支名只用英文；中文只出现在 README、文档正文和界面文案里。
中文路径在 Windows 解压、CI 脚本、URL 编码、`git ls-tree` 这些环节都踩过坑，`测试数据/` 因此
改成了 `docs/samples/`。

**界面文案不散落在组件里。** 新增文案只写 `src/main/resources/i18n/ui-translations.tsv`（每个键
必须补齐 en/fr/es/de/zh 五语，由 `UiTranslationCatalogTest` 强制）；历史硬编码走
`frontend/src/i18n/uiText.ts` 的词典。**数据本身不翻译**：审核员姓名、城市、订单项目名来自数据库，
切英文后仍是原文。
