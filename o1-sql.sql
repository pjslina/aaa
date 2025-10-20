-- 指标表
CREATE TABLE op_metric (
    metric_id BIGINT PRIMARY KEY,
    metric_code VARCHAR(64) UNIQUE,
    metric_name VARCHAR(255),
    currency VARCHAR(32)
);

-- 度量表
CREATE TABLE op_measure (
    id BIGINT PRIMARY KEY,
    measure_code VARCHAR(64),
    en_name VARCHAR(255),
    cn_name VARCHAR(255),
    unit VARCHAR(64),
    fixed_value VARCHAR(64)
);

-- 组织层级表
CREATE TABLE op_metric_org (
    id BIGINT PRIMARY KEY,
    org_level VARCHAR(64)
);

-- 领域表
CREATE TABLE op_metric_domain (
    id BIGINT PRIMARY KEY,
    domain_code VARCHAR(64),
    domain_name_cn VARCHAR(255),
    domain_name_en VARCHAR(255)
);

-- 关联表
CREATE TABLE op_metric_rel (
    id BIGINT PRIMARY KEY,
    metric_id BIGINT,
    rel_id BIGINT,
    rel_type INT  -- 1: domain, 2: org, 3: measure
);
