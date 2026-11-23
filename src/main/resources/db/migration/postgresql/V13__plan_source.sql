-- Records how a plan came to exist so the dispatcher can tell the two draft
-- entries apart in the plan list:
--   WINDOW   - snapshot of the current shift assignments in a date window
--              (POST /schedule-plans, preserves whoever is on the shift today)
--   ORDER    - per-order draft starting from all-unassigned slots
--              (POST /schedule-plans/from-order/{orderId})
--   REVISION - editable copy of an existing plan (POST /schedule-plans/{id}/revise)
ALTER TABLE schedule_plans ADD COLUMN source TEXT NOT NULL DEFAULT 'WINDOW';

-- Backfill: revisions are exact (parent_plan_id set); from-order drafts are the
-- ones whose every slot is still unassigned, which is how that entry behaves.
UPDATE schedule_plans SET source = 'REVISION' WHERE parent_plan_id IS NOT NULL;

UPDATE schedule_plans SET source = 'ORDER'
WHERE parent_plan_id IS NULL
  AND id IN (SELECT plan_id FROM schedule_plan_assignments
             GROUP BY plan_id HAVING COUNT(*) > 0 AND COUNT(employee_id) = 0);
