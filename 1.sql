-- ==================== 可重复执行的DDL脚本 ====================

START TRANSACTION;

-- 删除已存在的表
DROP TABLE IF EXISTS op_metric_rel CASCADE;
DROP TABLE IF EXISTS op_measure CASCADE;
DROP TABLE IF EXISTS op_metric_domain CASCADE;
DROP TABLE IF EXISTS op_metric_org CASCADE;
DROP TABLE IF EXISTS op_metric CASCADE;

-- ==================== 1. 指标表 ====================
CREATE TABLE op_metric (
    metric_id BIGINT NOT NULL,
    metric_code VARCHAR(100) NOT NULL,
    metric_name VARCHAR(200) NOT NULL,
    currency VARCHAR(20),
    status CHAR(1) DEFAULT '1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_op_metric PRIMARY KEY (metric_id),
    CONSTRAINT uk_metric_code UNIQUE (metric_code)
);

-- 索引（数据量不大，只建必要索引）
CREATE INDEX idx_metric_code ON op_metric(metric_code);
CREATE INDEX idx_metric_status ON op_metric(status);

COMMENT ON TABLE op_metric IS '指标元数据表';
COMMENT ON COLUMN op_metric.metric_id IS '指标ID（主键，非自增）';
COMMENT ON COLUMN op_metric.metric_code IS '指标编码（唯一）';
COMMENT ON COLUMN op_metric.metric_name IS '指标名称';
COMMENT ON COLUMN op_metric.currency IS '货币类型';
COMMENT ON COLUMN op_metric.status IS '状态：0-停用，1-启用';

-- ==================== 2. 度量表 ====================
CREATE TABLE op_measure (
    id BIGINT NOT NULL,
    measure_code VARCHAR(100) NOT NULL,
    en_name VARCHAR(200),
    cn_name VARCHAR(200) NOT NULL,
    unit VARCHAR(50),
    fixed_value VARCHAR(100),
    status CHAR(1) DEFAULT '1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_op_measure PRIMARY KEY (id),
    CONSTRAINT uk_measure_code UNIQUE (measure_code)
);

-- 索引
CREATE INDEX idx_measure_code ON op_measure(measure_code);
CREATE INDEX idx_measure_status ON op_measure(status);

COMMENT ON TABLE op_measure IS '度量元数据表';
COMMENT ON COLUMN op_measure.id IS '度量ID（主键，非自增）';
COMMENT ON COLUMN op_measure.measure_code IS '度量编码（唯一）';
COMMENT ON COLUMN op_measure.en_name IS '英文名称';
COMMENT ON COLUMN op_measure.cn_name IS '中文名称';
COMMENT ON COLUMN op_measure.unit IS '单位';
COMMENT ON COLUMN op_measure.fixed_value IS '固定值';
COMMENT ON COLUMN op_measure.status IS '状态：0-停用，1-启用';

-- ==================== 3. 域表 ====================
CREATE TABLE op_metric_domain (
    id BIGINT NOT NULL,
    domain_code VARCHAR(100) NOT NULL,
    domain_name_cn VARCHAR(200) NOT NULL,
    domain_name_en VARCHAR(200),
    status CHAR(1) DEFAULT '1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_op_metric_domain PRIMARY KEY (id),
    CONSTRAINT uk_domain_code UNIQUE (domain_code)
);

-- 索引
CREATE INDEX idx_domain_code ON op_metric_domain(domain_code);
CREATE INDEX idx_domain_status ON op_metric_domain(status);

COMMENT ON TABLE op_metric_domain IS '指标域元数据表';
COMMENT ON COLUMN op_metric_domain.id IS '域ID（主键，非自增）';
COMMENT ON COLUMN op_metric_domain.domain_code IS '域编码（唯一）';
COMMENT ON COLUMN op_metric_domain.domain_name_cn IS '域中文名称';
COMMENT ON COLUMN op_metric_domain.domain_name_en IS '域英文名称';

-- ==================== 4. 组织层级表 ====================
CREATE TABLE op_metric_org (
    id BIGINT NOT NULL,
    org_level VARCHAR(50) NOT NULL,
    status CHAR(1) DEFAULT '1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_op_metric_org PRIMARY KEY (id),
    CONSTRAINT uk_org_level UNIQUE (org_level)
);

-- 索引
CREATE INDEX idx_org_level ON op_metric_org(org_level);
CREATE INDEX idx_org_status ON op_metric_org(status);

COMMENT ON TABLE op_metric_org IS '组织层级元数据表';
COMMENT ON COLUMN op_metric_org.id IS '组织层级ID（主键，非自增）';
COMMENT ON COLUMN op_metric_org.org_level IS '组织层级';

-- ==================== 5. 关联表 ====================
CREATE TABLE op_metric_rel (
    id BIGINT NOT NULL,
    metric_id BIGINT NOT NULL,
    rel_id BIGINT NOT NULL,
    rel_type INT NOT NULL,
    status CHAR(1) DEFAULT '1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_op_metric_rel PRIMARY KEY (id),
    CONSTRAINT uk_metric_rel UNIQUE (metric_id, rel_id, rel_type),
    CONSTRAINT fk_rel_metric FOREIGN KEY (metric_id) REFERENCES op_metric(metric_id) ON DELETE CASCADE
);

-- 索引（关键索引）
CREATE INDEX idx_rel_metric_id ON op_metric_rel(metric_id);
CREATE INDEX idx_rel_id ON op_metric_rel(rel_id);
CREATE INDEX idx_rel_type ON op_metric_rel(rel_type);
CREATE INDEX idx_rel_metric_type ON op_metric_rel(metric_id, rel_type);
CREATE INDEX idx_rel_status ON op_metric_rel(status);

COMMENT ON TABLE op_metric_rel IS '指标关联关系表';
COMMENT ON COLUMN op_metric_rel.id IS '关联ID（主键，非自增）';
COMMENT ON COLUMN op_metric_rel.metric_id IS '指标ID';
COMMENT ON COLUMN op_metric_rel.rel_id IS '关联ID（根据rel_type指向不同表）';
COMMENT ON COLUMN op_metric_rel.rel_type IS '关联类型：1-域(op_metric_domain)，2-组织层级(op_metric_org)，3-度量(op_measure)';
COMMENT ON COLUMN op_metric_rel.status IS '状态：0-停用，1-启用';

COMMIT;

-- ==================== 初始化测试数据 ====================

START TRANSACTION;

-- 清空数据
DELETE FROM op_metric_rel;
DELETE FROM op_measure;
DELETE FROM op_metric_domain;
DELETE FROM op_metric_org;
DELETE FROM op_metric;

-- 插入指标数据
INSERT INTO op_metric (metric_id, metric_code, metric_name, currency, status) VALUES
(1001, 'M001', '营业收入', 'CNY', '1'),
(1002, 'M002', '净利润', 'CNY', '1'),
(1003, 'M003', '员工数量', NULL, '1'),
(1004, 'M004', '客户满意度', NULL, '1');

-- 插入度量数据
INSERT INTO op_measure (id, measure_code, en_name, cn_name, unit, fixed_value, status) VALUES
(2001, 'MS001', 'Actual Value', '实际值', '元', NULL, '1'),
(2002, 'MS002', 'Target Value', '目标值', '元', NULL, '1'),
(2003, 'MS003', 'Growth Rate', '增长率', '%', NULL, '1'),
(2004, 'MS004', 'Count', '数量', '人', NULL, '1'),
(2005, 'MS005', 'Score', '评分', '分', NULL, '1'),
(2006, 'MS006', 'Percentage', '百分比', '%', NULL, '1');

-- 插入域数据
INSERT INTO op_metric_domain (id, domain_code, domain_name_cn, domain_name_en, status) VALUES
(3001, 'D001', '财务域', 'Financial Domain', '1'),
(3002, 'D002', '运营域', 'Operational Domain', '1'),
(3003, 'D003', '人力资源域', 'HR Domain', '1');

-- 插入组织层级数据
INSERT INTO op_metric_org (id, org_level, status) VALUES
(4001, 'GROUP', '1'),
(4002, 'COMPANY', '1'),
(4003, 'DEPARTMENT', '1'),
(4004, 'TEAM', '1');

-- 插入关联关系
-- 营业收入(M001)的关联
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- 关联度量
(5001, 1001, 2001, 3, '1'), -- 实际值
(5002, 1001, 2002, 3, '1'), -- 目标值
(5003, 1001, 2003, 3, '1'), -- 增长率
-- 关联域
(5004, 1001, 3001, 1, '1'), -- 财务域
-- 关联组织层级
(5005, 1001, 4001, 2, '1'), -- 集团
(5006, 1001, 4002, 2, '1'); -- 公司

-- 净利润(M002)的关联
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- 关联度量
(5007, 1002, 2001, 3, '1'), -- 实际值
(5008, 1002, 2002, 3, '1'), -- 目标值
-- 关联域
(5009, 1002, 3001, 1, '1'), -- 财务域
-- 关联组织层级
(5010, 1002, 4001, 2, '1'), -- 集团
(5011, 1002, 4002, 2, '1'), -- 公司
(5012, 1002, 4003, 2, '1'); -- 部门

-- 员工数量(M003)的关联
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- 关联度量
(5013, 1003, 2004, 3, '1'), -- 数量
-- 关联域
(5014, 1003, 3003, 1, '1'), -- 人力资源域
-- 关联组织层级
(5015, 1003, 4002, 2, '1'), -- 公司
(5016, 1003, 4003, 2, '1'), -- 部门
(5017, 1003, 4004, 2, '1'); -- 团队

-- 客户满意度(M004)的关联
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- 关联度量
(5018, 1004, 2005, 3, '1'), -- 评分
(5019, 1004, 2006, 3, '1'), -- 百分比
-- 关联域
(5020, 1004, 3002, 1, '1'), -- 运营域
-- 关联组织层级
(5021, 1004, 4001, 2, '1'), -- 集团
(5022, 1004, 4002, 2, '1'); -- 公司

COMMIT;

-- 验证数据
SELECT '指标数量: ' || COUNT(*) as info FROM op_metric WHERE status = '1';
SELECT '度量数量: ' || COUNT(*) as info FROM op_measure WHERE status = '1';
SELECT '域数量: ' || COUNT(*) as info FROM op_metric_domain WHERE status = '1';
SELECT '组织层级: ' || COUNT(*) as info FROM op_metric_org WHERE status = '1';
SELECT '关联关系: ' || COUNT(*) as info FROM op_metric_rel WHERE status = '1';