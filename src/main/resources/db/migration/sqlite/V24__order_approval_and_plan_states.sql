-- 订单审批上移到需求层；方案状态从 4 态收敛为 2 态。
--
-- 背景：系统定位是「分配即生效」——班次上写了 employee_id 人就到岗，
-- 不存在"发布后才生效"这一环。因此审批从「时间窗方案」搬到「订单」，
-- 方案退化为求解批次 / 执行快照，只保留 DRAFT / EXECUTED / SUPERSEDED。
--
-- 本次迁移做三件事：
--  1. 订单增补审批元数据（提交与批准是同一状态机上的两个时间点）。
--  2. ACTIVE 订单转为 APPROVED：它们已在运行，不能要求补审；
--     订单生命周期状态同时扩展为 DRAFT / SUBMITTED / APPROVED / REJECTED / CANCELLED / COMPLETED。
--  3. 方案状态收敛：REVIEW / APPROVED 退回 DRAFT（审批已不在方案层，这两个状态不会再产生），
--     PUBLISHED 转为 EXECUTED（语义由"已发布"变为"已定稿执行"）。
--
-- 幂等：UPDATE 均带 WHERE 条件，重复执行结果相同。

ALTER TABLE audit_orders ADD COLUMN submitted_at TEXT;

ALTER TABLE audit_orders ADD COLUMN approved_at TEXT;

ALTER TABLE audit_orders ADD COLUMN approved_by TEXT;

ALTER TABLE audit_orders ADD COLUMN reject_reason TEXT NOT NULL DEFAULT '';

UPDATE audit_orders SET status = 'APPROVED' WHERE status = 'ACTIVE';

UPDATE schedule_plans SET status = 'DRAFT' WHERE status IN ('REVIEW', 'APPROVED');

UPDATE schedule_plans SET status = 'EXECUTED' WHERE status = 'PUBLISHED';
