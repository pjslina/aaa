返回 List<OpMetric>，每个对象带上 List<OpMeasure>。
SELECT
    m.metric_id,
    m.metric_code,
    m.metric_name,
    m.currency,
    ms.id AS measure_id,
    ms.measure_code,
    ms.en_name,
    ms.cn_name,
    ms.unit,
    ms.fixed_value
FROM op_metric m
LEFT JOIN op_metric_rel r ON r.metric_id = m.metric_id AND r.rel_type = 3
LEFT JOIN op_measure ms ON r.rel_id = ms.id


功能：返回一个 Map，其中 key = metricCode + measureCode，value = 度量对象 JSON（或直接 measureCode）。

SELECT
    CONCAT(m.metric_code, ms.measure_code) AS map_key,
    ms.measure_code AS measure_code
FROM op_metric m
JOIN op_metric_rel r ON r.metric_id = m.metric_id AND r.rel_type = 3
JOIN op_measure ms ON r.rel_id = ms.id

功能：返回 List<OpMetric>，每个对象带：

List<String> orgLevels

List<String> domainCodes
-- orgLevels
SELECT DISTINCT
    m.metric_id,
    m.metric_code,
    o.org_level
FROM op_metric m
LEFT JOIN op_metric_rel r ON r.metric_id = m.metric_id AND r.rel_type = 2
LEFT JOIN op_metric_org o ON r.rel_id = o.id;

-- domainCodes
SELECT DISTINCT
    m.metric_id,
    m.metric_code,
    d.domain_code
FROM op_metric m
LEFT JOIN op_metric_rel r ON r.metric_id = m.metric_id AND r.rel_type = 1
LEFT JOIN op_metric_domain d ON r.rel_id = d.id;

