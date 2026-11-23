-- 移除意大利语后重新指定「启用语言」。
--
-- V1 把 'it' 标为 active=1，V22 删除该行后没有任何语言是启用状态，
-- 依赖 active 标志的查询（例如标签编辑的默认语言）会拿到 null。
-- 界面默认语言是中文，因此这里把 zh 设为唯一的启用语言。
--
-- 幂等：重复执行结果相同。

UPDATE languages SET active = 0;

UPDATE languages SET active = 1 WHERE code = 'zh';
