-- 清理意大利语数据并中文化演示数据。
--
-- 背景：本系统源自一个意大利医疗排班演示，早期数据（科室名、员工姓名、
-- 语言目录、邮件模板等）仍是意大利语。本迁移一次性清除这些内容，
-- 并把二级部门的地点改为中国城市。
--
-- 幂等性：全部为 UPDATE / DELETE，行不存在时为空操作，可安全重复执行。

-- ---------------------------------------------------------------------
-- 1. 移除意大利语语言及其译文（界面语言仅保留 en/fr/es/de/zh）
-- ---------------------------------------------------------------------
DELETE FROM localizzazioni
 WHERE language_id IN (SELECT id FROM languages WHERE code = 'it');

DELETE FROM languages WHERE code = 'it';

-- 2. 其余语言的显示名原本也是意大利语（Inglese/Francese/Spagnolo/Tedesco）
UPDATE languages SET description = 'English'   WHERE code = 'en';
UPDATE languages SET description = 'Français'  WHERE code = 'fr';
UPDATE languages SET description = 'Español'   WHERE code = 'es';
UPDATE languages SET description = 'Deutsch'   WHERE code = 'de';
UPDATE languages SET description = '简体中文'   WHERE code = 'zh';

-- ---------------------------------------------------------------------
-- 3. 部门名称与地址
-- ---------------------------------------------------------------------
UPDATE structures SET name = '中国审核中心', address = '示例市示例路 1 号', phone = '010-5550 1000'
 WHERE name = 'Poliambulatorio Centro';
UPDATE structures SET name = '北区审核中心', address = '示例市示例路 1 号', phone = '010-5550 2000'
 WHERE name = 'Poliambulatorio Nord';

-- ---------------------------------------------------------------------
-- 4. 删除 14 个意大利语科室（无员工，仅剩已过期的历史班次）
-- ---------------------------------------------------------------------
UPDATE employees SET current_location_id = NULL
 WHERE current_location_id IN (SELECT id FROM locations
        WHERE structure_id = 1 AND name IN ('Medicina dello Sport1', 'Ortopedia', 'Oftalmologia', 'Ortottista', 'Neurologia', 'Dermatologia', 'Odontoiatria', 'Otorinolangoringoiatria', 'Cardiologia', 'Terapia Iniettiva', 'Sterilizzazione', 'CH. PRE-OPERATORIA', 'Medicina dello Sport2', 'Medicina dello Sport3'));

DELETE FROM shift_skills
 WHERE shift_id IN (SELECT s.id FROM shifts s JOIN locations l ON l.id = s.location_id
        WHERE l.structure_id = 1 AND l.name IN ('Medicina dello Sport1', 'Ortopedia', 'Oftalmologia', 'Ortottista', 'Neurologia', 'Dermatologia', 'Odontoiatria', 'Otorinolangoringoiatria', 'Cardiologia', 'Terapia Iniettiva', 'Sterilizzazione', 'CH. PRE-OPERATORIA', 'Medicina dello Sport2', 'Medicina dello Sport3'));

DELETE FROM schedule_plan_assignments
 WHERE shift_id IN (SELECT s.id FROM shifts s JOIN locations l ON l.id = s.location_id
        WHERE l.structure_id = 1 AND l.name IN ('Medicina dello Sport1', 'Ortopedia', 'Oftalmologia', 'Ortottista', 'Neurologia', 'Dermatologia', 'Odontoiatria', 'Otorinolangoringoiatria', 'Cardiologia', 'Terapia Iniettiva', 'Sterilizzazione', 'CH. PRE-OPERATORIA', 'Medicina dello Sport2', 'Medicina dello Sport3'));

DELETE FROM shifts
 WHERE location_id IN (SELECT id FROM locations
        WHERE structure_id = 1 AND name IN ('Medicina dello Sport1', 'Ortopedia', 'Oftalmologia', 'Ortottista', 'Neurologia', 'Dermatologia', 'Odontoiatria', 'Otorinolangoringoiatria', 'Cardiologia', 'Terapia Iniettiva', 'Sterilizzazione', 'CH. PRE-OPERATORIA', 'Medicina dello Sport2', 'Medicina dello Sport3'));

DELETE FROM location_skills
 WHERE location_id IN (SELECT id FROM locations
        WHERE structure_id = 1 AND name IN ('Medicina dello Sport1', 'Ortopedia', 'Oftalmologia', 'Ortottista', 'Neurologia', 'Dermatologia', 'Odontoiatria', 'Otorinolangoringoiatria', 'Cardiologia', 'Terapia Iniettiva', 'Sterilizzazione', 'CH. PRE-OPERATORIA', 'Medicina dello Sport2', 'Medicina dello Sport3'));

DELETE FROM locations
 WHERE structure_id = 1 AND name IN ('Medicina dello Sport1', 'Ortopedia', 'Oftalmologia', 'Ortottista', 'Neurologia', 'Dermatologia', 'Odontoiatria', 'Otorinolangoringoiatria', 'Cardiologia', 'Terapia Iniettiva', 'Sterilizzazione', 'CH. PRE-OPERATORIA', 'Medicina dello Sport2', 'Medicina dello Sport3');

-- ---------------------------------------------------------------------
-- 5. 二级部门的地点改为中国城市（原来都是意大利语诊室名）
-- ---------------------------------------------------------------------
UPDATE locations SET name = '石家庄', latitude = 38.0428, longitude = 114.5149
 WHERE structure_id = 2 AND code = 'N001';

UPDATE locations SET name = '呼和浩特', latitude = 40.8424, longitude = 111.749
 WHERE structure_id = 2 AND code = 'N002';

UPDATE locations SET name = '兰州', latitude = 36.0611, longitude = 103.8343
 WHERE structure_id = 2 AND code = 'N003';

UPDATE locations SET name = '西宁', latitude = 36.6171, longitude = 101.7782
 WHERE structure_id = 2 AND code = 'N004';

UPDATE locations SET name = '银川', latitude = 38.4872, longitude = 106.2309
 WHERE structure_id = 2 AND code = 'N005';

UPDATE locations SET name = '拉萨', latitude = 29.652, longitude = 91.1721
 WHERE structure_id = 2 AND code = 'N007';

UPDATE locations SET name = '海口', latitude = 20.0444, longitude = 110.1999
 WHERE structure_id = 2 AND code = 'N009';

UPDATE locations SET name = '贵阳', latitude = 26.647, longitude = 106.6302
 WHERE structure_id = 2 AND code = 'N012';

-- ---------------------------------------------------------------------
-- 6. 员工姓名改为中文测试姓名（原为意大利姓名）
-- ---------------------------------------------------------------------
UPDATE employees SET first_name = '审核员8001', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP001';
UPDATE employees SET first_name = '审核员8002', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP002';
UPDATE employees SET first_name = '审核员8003', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP003';
UPDATE employees SET first_name = '审核员8004', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP004';
UPDATE employees SET first_name = '审核员8005', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP005';
UPDATE employees SET first_name = '审核员8006', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP006';
UPDATE employees SET first_name = '审核员8007', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP007';
UPDATE employees SET first_name = '审核员8008', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP008';
UPDATE employees SET first_name = '审核员8009', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP009';
UPDATE employees SET first_name = '审核员8010', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP010';
UPDATE employees SET first_name = '审核员8011', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP011';
UPDATE employees SET first_name = '审核员8012', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP012';
UPDATE employees SET first_name = '审核员8013', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP013';
UPDATE employees SET first_name = '审核员8014', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP014';
UPDATE employees SET first_name = '审核员8015', last_name = '测试'
 WHERE structure_id = 1 AND code = 'EMP015';
UPDATE employees SET first_name = '审核员8016', last_name = '测试'
 WHERE structure_id = 1 AND code = 'mr1222';
UPDATE employees SET first_name = '审核员8017', last_name = '测试'
 WHERE structure_id = 1 AND code = 'MR1';
UPDATE employees SET first_name = '审核员8018', last_name = '测试'
 WHERE structure_id = 1 AND code = 'M3e';
UPDATE employees SET first_name = '审核员8019', last_name = '测试'
 WHERE structure_id = 1 AND code = 'SF';
UPDATE employees SET first_name = '审核员8020', last_name = '测试'
 WHERE structure_id = 1 AND code = 'DA';
UPDATE employees SET first_name = '审核员8021', last_name = '测试'
 WHERE structure_id = 1 AND code = 'CZ';
UPDATE employees SET first_name = '审核员8022', last_name = '测试'
 WHERE structure_id = 1 AND code = 'CC';

UPDATE employees SET first_name = '审核员9001', last_name = '测试', email = 'n2-001@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-001';
UPDATE employees SET first_name = '审核员9002', last_name = '测试', email = 'n2-002@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-002';
UPDATE employees SET first_name = '审核员9003', last_name = '测试', email = 'n2-003@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-003';
UPDATE employees SET first_name = '审核员9004', last_name = '测试', email = 'n2-004@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-004';
UPDATE employees SET first_name = '审核员9005', last_name = '测试', email = 'n2-005@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-005';
UPDATE employees SET first_name = '审核员9006', last_name = '测试', email = 'n2-006@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-006';
UPDATE employees SET first_name = '审核员9007', last_name = '测试', email = 'n2-007@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-007';
UPDATE employees SET first_name = '审核员9008', last_name = '测试', email = 'n2-008@example.invalid'
 WHERE structure_id = 2 AND code = 'N2-008';

UPDATE employees SET email = 'emp0001@example.invalid'
 WHERE structure_id = 1 AND email = 'adalgisa.abbagnale@example.com';

-- ---------------------------------------------------------------------
-- 7. 技能名称（Medico1 是意大利语）
-- ---------------------------------------------------------------------
UPDATE skills SET name = '医师资格' WHERE name = 'Medico1';

-- ---------------------------------------------------------------------
-- 8. 班次模板描述
-- ---------------------------------------------------------------------
UPDATE shift_template_headers SET description = '标准周排班模板'
 WHERE description = 'Turni standard settimana tipo';
UPDATE shift_template_headers SET description = '夏季缩减排班'
 WHERE description = 'Copertura ridotta estiva';

-- ---------------------------------------------------------------------
-- 9. 邮件模板与发件人（占位符同时改为中文）
-- ---------------------------------------------------------------------
UPDATE email_templates SET subject = '排班通知（{{期间}}）',
       body = '<p>{{姓名}}，您好：<br>附件是您在 {{期间}} 的排班表。</p>'
 WHERE subject LIKE '%Turni%' OR body LIKE '%Ciao%'
    OR subject LIKE '%{{Giorno}}%' OR body LIKE '%{{Nominativo}}%';

UPDATE email_settings SET mail_from = '审核调度系统 <noreply@example.com>'
 WHERE mail_from LIKE 'Scheduler%' OR mail_from LIKE '%Turni%';

-- ---------------------------------------------------------------------
-- 10. 历史邮件日志中的意大利语期间标签与附件名
-- ---------------------------------------------------------------------
UPDATE email_log SET period_label = '2026-06-29 – 2026-07-05'
 WHERE period_label LIKE '%Giu%' OR period_label LIKE '%Lug%';
UPDATE email_log SET sent_to = 'n2-007@example.invalid'
 WHERE sent_to = 'fiorella.fabbroni@example.com';
UPDATE email_log SET sent_to = 'emp0001@example.invalid'
 WHERE sent_to = 'adalgisa.abbagnale@example.com';
UPDATE email_log SET filename = '排班_审核员9007_2026-06-29.pdf'
 WHERE filename LIKE 'turni%Fiorella%';
UPDATE email_log SET filename = '排班_审核员8001_2026-06-29.pdf'
 WHERE filename LIKE 'turni%Adalgisa%';

-- ---------------------------------------------------------------------
-- 11. 标签描述改为中文：本目录的描述列现等于中文译文，
--     因此直接用 zh 翻译回填，避免在 SQL 里重复 1000 多条文案。
-- ---------------------------------------------------------------------
UPDATE labels SET description = (
        SELECT lz.value FROM localizzazioni lz
         WHERE lz.entity_type = 'labels' AND lz.entity_id = labels.id
           AND lz.language_id = (SELECT id FROM languages WHERE code = 'zh'))
 WHERE EXISTS (
        SELECT 1 FROM localizzazioni lz
         WHERE lz.entity_type = 'labels' AND lz.entity_id = labels.id
           AND lz.language_id = (SELECT id FROM languages WHERE code = 'zh'));
