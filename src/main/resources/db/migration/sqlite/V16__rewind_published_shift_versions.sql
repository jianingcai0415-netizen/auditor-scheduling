-- Repairs phantom SCHEDULE_PLAN_STALE on already published/superseded plans.
-- Publishing writes the shifts themselves, which bumps shifts.version; before the publish
-- endpoint rewound the snapshot, those plans' own rows compared stale snapshot versions
-- against the just-bumped shift versions and displayed "订单阶段已被修改" on every row.
-- Draft/review plans are untouched: their version mismatch is a real staleness signal.
UPDATE schedule_plan_assignments
SET shift_version = (SELECT s.version FROM shifts s WHERE s.id = schedule_plan_assignments.shift_id)
WHERE plan_id IN (SELECT id FROM schedule_plans WHERE status IN ('PUBLISHED', 'SUPERSEDED'));
