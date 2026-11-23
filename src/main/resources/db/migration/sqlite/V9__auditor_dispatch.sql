ALTER TABLE employees ADD COLUMN operational_status TEXT NOT NULL DEFAULT 'AVAILABLE';
ALTER TABLE employees ADD COLUMN busy_until TEXT;
ALTER TABLE employees ADD COLUMN status_updated_at TEXT;
ALTER TABLE employees ADD COLUMN current_location_id INTEGER;
ALTER TABLE employees ADD COLUMN buffer_before_minutes INTEGER NOT NULL DEFAULT 30;
ALTER TABLE employees ADD COLUMN buffer_after_minutes INTEGER NOT NULL DEFAULT 30;

ALTER TABLE employee_skills ADD COLUMN certificate_number TEXT NOT NULL DEFAULT '';
ALTER TABLE employee_skills ADD COLUMN qualification_level TEXT NOT NULL DEFAULT 'AUDITOR';
ALTER TABLE employee_skills ADD COLUMN valid_from TEXT;
ALTER TABLE employee_skills ADD COLUMN valid_until TEXT;
ALTER TABLE employee_skills ADD COLUMN qualification_status TEXT NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE locations ADD COLUMN latitude REAL;
ALTER TABLE locations ADD COLUMN longitude REAL;

ALTER TABLE shifts ADD COLUMN audit_order_code TEXT NOT NULL DEFAULT '';
ALTER TABLE shifts ADD COLUMN audit_stage TEXT NOT NULL DEFAULT '';

UPDATE employee_skills
SET certificate_number = 'LEGACY-' || employee_id || '-' || skill_id
WHERE certificate_number = '';

CREATE INDEX idx_employees_busy_until ON employees(busy_until);
CREATE INDEX idx_employee_skills_validity ON employee_skills(employee_id, skill_id, qualification_status, valid_until);
CREATE INDEX idx_shifts_audit_order_stage ON shifts(audit_order_code, audit_stage);
