-- Removes the legacy "specialists" module from the dispatch product: affinity rules are dropped,
-- roster data cleared, and the solver weight column removed. The roster table and the
-- locations.specialist_id FK column themselves must stay in place on both engines: SQLite refuses
-- to drop a column used by the table's own FOREIGN KEY definition, and PostgreSQL refuses to drop
-- a table still referenced by that FK. With every code path for the module deleted, the leftover
-- empty roster and the FK column are inert.
DROP TABLE IF EXISTS operator_specialist_affinity;
UPDATE locations SET specialist_id = NULL;
DELETE FROM specialists;
ALTER TABLE solver_settings DROP COLUMN avoid_specialist_weight;
