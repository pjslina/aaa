-- ==================== ���ظ�ִ�е�DDL�ű� ====================

START TRANSACTION;

-- ɾ���Ѵ��ڵı�
DROP TABLE IF EXISTS op_metric_rel CASCADE;
DROP TABLE IF EXISTS op_measure CASCADE;
DROP TABLE IF EXISTS op_metric_domain CASCADE;
DROP TABLE IF EXISTS op_metric_org CASCADE;
DROP TABLE IF EXISTS op_metric CASCADE;

-- ==================== 1. ָ��� ====================
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

-- ����������������ֻ����Ҫ������
CREATE INDEX idx_metric_code ON op_metric(metric_code);
CREATE INDEX idx_metric_status ON op_metric(status);

COMMENT ON TABLE op_metric IS 'ָ��Ԫ���ݱ�';
COMMENT ON COLUMN op_metric.metric_id IS 'ָ��ID����������������';
COMMENT ON COLUMN op_metric.metric_code IS 'ָ����루Ψһ��';
COMMENT ON COLUMN op_metric.metric_name IS 'ָ������';
COMMENT ON COLUMN op_metric.currency IS '��������';
COMMENT ON COLUMN op_metric.status IS '״̬��0-ͣ�ã�1-����';

-- ==================== 2. ������ ====================
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

-- ����
CREATE INDEX idx_measure_code ON op_measure(measure_code);
CREATE INDEX idx_measure_status ON op_measure(status);

COMMENT ON TABLE op_measure IS '����Ԫ���ݱ�';
COMMENT ON COLUMN op_measure.id IS '����ID����������������';
COMMENT ON COLUMN op_measure.measure_code IS '�������루Ψһ��';
COMMENT ON COLUMN op_measure.en_name IS 'Ӣ������';
COMMENT ON COLUMN op_measure.cn_name IS '��������';
COMMENT ON COLUMN op_measure.unit IS '��λ';
COMMENT ON COLUMN op_measure.fixed_value IS '�̶�ֵ';
COMMENT ON COLUMN op_measure.status IS '״̬��0-ͣ�ã�1-����';

-- ==================== 3. ��� ====================
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

-- ����
CREATE INDEX idx_domain_code ON op_metric_domain(domain_code);
CREATE INDEX idx_domain_status ON op_metric_domain(status);

COMMENT ON TABLE op_metric_domain IS 'ָ����Ԫ���ݱ�';
COMMENT ON COLUMN op_metric_domain.id IS '��ID����������������';
COMMENT ON COLUMN op_metric_domain.domain_code IS '����루Ψһ��';
COMMENT ON COLUMN op_metric_domain.domain_name_cn IS '����������';
COMMENT ON COLUMN op_metric_domain.domain_name_en IS '��Ӣ������';

-- ==================== 4. ��֯�㼶�� ====================
CREATE TABLE op_metric_org (
    id BIGINT NOT NULL,
    org_level VARCHAR(50) NOT NULL,
    status CHAR(1) DEFAULT '1',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_op_metric_org PRIMARY KEY (id),
    CONSTRAINT uk_org_level UNIQUE (org_level)
);

-- ����
CREATE INDEX idx_org_level ON op_metric_org(org_level);
CREATE INDEX idx_org_status ON op_metric_org(status);

COMMENT ON TABLE op_metric_org IS '��֯�㼶Ԫ���ݱ�';
COMMENT ON COLUMN op_metric_org.id IS '��֯�㼶ID����������������';
COMMENT ON COLUMN op_metric_org.org_level IS '��֯�㼶';

-- ==================== 5. ������ ====================
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

-- �������ؼ�������
CREATE INDEX idx_rel_metric_id ON op_metric_rel(metric_id);
CREATE INDEX idx_rel_id ON op_metric_rel(rel_id);
CREATE INDEX idx_rel_type ON op_metric_rel(rel_type);
CREATE INDEX idx_rel_metric_type ON op_metric_rel(metric_id, rel_type);
CREATE INDEX idx_rel_status ON op_metric_rel(status);

COMMENT ON TABLE op_metric_rel IS 'ָ�������ϵ��';
COMMENT ON COLUMN op_metric_rel.id IS '����ID����������������';
COMMENT ON COLUMN op_metric_rel.metric_id IS 'ָ��ID';
COMMENT ON COLUMN op_metric_rel.rel_id IS '����ID������rel_typeָ��ͬ��';
COMMENT ON COLUMN op_metric_rel.rel_type IS '�������ͣ�1-��(op_metric_domain)��2-��֯�㼶(op_metric_org)��3-����(op_measure)';
COMMENT ON COLUMN op_metric_rel.status IS '״̬��0-ͣ�ã�1-����';

COMMIT;

-- ==================== ��ʼ���������� ====================

START TRANSACTION;

-- �������
DELETE FROM op_metric_rel;
DELETE FROM op_measure;
DELETE FROM op_metric_domain;
DELETE FROM op_metric_org;
DELETE FROM op_metric;

-- ����ָ������
INSERT INTO op_metric (metric_id, metric_code, metric_name, currency, status) VALUES
(1001, 'M001', 'Ӫҵ����', 'CNY', '1'),
(1002, 'M002', '������', 'CNY', '1'),
(1003, 'M003', 'Ա������', NULL, '1'),
(1004, 'M004', '�ͻ������', NULL, '1');

-- �����������
INSERT INTO op_measure (id, measure_code, en_name, cn_name, unit, fixed_value, status) VALUES
(2001, 'MS001', 'Actual Value', 'ʵ��ֵ', 'Ԫ', NULL, '1'),
(2002, 'MS002', 'Target Value', 'Ŀ��ֵ', 'Ԫ', NULL, '1'),
(2003, 'MS003', 'Growth Rate', '������', '%', NULL, '1'),
(2004, 'MS004', 'Count', '����', '��', NULL, '1'),
(2005, 'MS005', 'Score', '����', '��', NULL, '1'),
(2006, 'MS006', 'Percentage', '�ٷֱ�', '%', NULL, '1');

-- ����������
INSERT INTO op_metric_domain (id, domain_code, domain_name_cn, domain_name_en, status) VALUES
(3001, 'D001', '������', 'Financial Domain', '1'),
(3002, 'D002', '��Ӫ��', 'Operational Domain', '1'),
(3003, 'D003', '������Դ��', 'HR Domain', '1');

-- ������֯�㼶����
INSERT INTO op_metric_org (id, org_level, status) VALUES
(4001, 'GROUP', '1'),
(4002, 'COMPANY', '1'),
(4003, 'DEPARTMENT', '1'),
(4004, 'TEAM', '1');

-- ���������ϵ
-- Ӫҵ����(M001)�Ĺ���
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- ��������
(5001, 1001, 2001, 3, '1'), -- ʵ��ֵ
(5002, 1001, 2002, 3, '1'), -- Ŀ��ֵ
(5003, 1001, 2003, 3, '1'), -- ������
-- ������
(5004, 1001, 3001, 1, '1'), -- ������
-- ������֯�㼶
(5005, 1001, 4001, 2, '1'), -- ����
(5006, 1001, 4002, 2, '1'); -- ��˾

-- ������(M002)�Ĺ���
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- ��������
(5007, 1002, 2001, 3, '1'), -- ʵ��ֵ
(5008, 1002, 2002, 3, '1'), -- Ŀ��ֵ
-- ������
(5009, 1002, 3001, 1, '1'), -- ������
-- ������֯�㼶
(5010, 1002, 4001, 2, '1'), -- ����
(5011, 1002, 4002, 2, '1'), -- ��˾
(5012, 1002, 4003, 2, '1'); -- ����

-- Ա������(M003)�Ĺ���
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- ��������
(5013, 1003, 2004, 3, '1'), -- ����
-- ������
(5014, 1003, 3003, 1, '1'), -- ������Դ��
-- ������֯�㼶
(5015, 1003, 4002, 2, '1'), -- ��˾
(5016, 1003, 4003, 2, '1'), -- ����
(5017, 1003, 4004, 2, '1'); -- �Ŷ�

-- �ͻ������(M004)�Ĺ���
INSERT INTO op_metric_rel (id, metric_id, rel_id, rel_type, status) VALUES
-- ��������
(5018, 1004, 2005, 3, '1'), -- ����
(5019, 1004, 2006, 3, '1'), -- �ٷֱ�
-- ������
(5020, 1004, 3002, 1, '1'), -- ��Ӫ��
-- ������֯�㼶
(5021, 1004, 4001, 2, '1'), -- ����
(5022, 1004, 4002, 2, '1'); -- ��˾

COMMIT;

-- ��֤����
SELECT 'ָ������: ' || COUNT(*) as info FROM op_metric WHERE status = '1';
SELECT '��������: ' || COUNT(*) as info FROM op_measure WHERE status = '1';
SELECT '������: ' || COUNT(*) as info FROM op_metric_domain WHERE status = '1';
SELECT '��֯�㼶: ' || COUNT(*) as info FROM op_metric_org WHERE status = '1';
SELECT '������ϵ: ' || COUNT(*) as info FROM op_metric_rel WHERE status = '1';