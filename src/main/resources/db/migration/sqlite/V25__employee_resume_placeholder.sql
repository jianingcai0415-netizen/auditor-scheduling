-- ---------------------------------------------------------------------------
-- 审核员简历附件（占位）
-- ---------------------------------------------------------------------------
-- 业务背景：审核员档案需要保留一份简历，后续在人员详情里上传/替换 PDF。
-- 本期只落库结构与展示位，上传接口尚未开放，因此两列保持可空、默认 NULL。
--
-- 字段说明：
--   resume_file_name   附件文件名（含扩展名），NULL = 尚未上传
--   resume_uploaded_at 上传时间 'yyyy-MM-dd HH:mm:ss'，NULL = 尚未上传
--
-- 不在此表存二进制：附件走文件存储，数据库只留引用，避免备份体积随附件膨胀。
ALTER TABLE employees ADD COLUMN resume_file_name TEXT;

ALTER TABLE employees ADD COLUMN resume_uploaded_at TEXT;
