package org.example.service;

import org.example.entity.OpMetric;

import java.util.List;
import java.util.Map;

/**
 * 指标度量服务接口
 */
public interface OpMetricService {

    /**
     * 需求1：查询指标列表（包含度量列表）
     * 每个OpMetric对象包含List<OpMeasure>
     *
     * @return 指标列表
     */
    List<OpMetric> listMetricsWithMeasures();

    /**
     * 需求2：返回Map<metricCode+measureCode, OpMeasure>
     * key格式：metricCode + "_" + measureCode
     * value：OpMeasure对象的JSON字符串或toString()
     *
     * @return Map<String, String>
     */
    Map<String, String> getMetricMeasureMap();

    /**
     * 需求3：查询指标列表（包含域编码和组织层级）
     * 每个OpMetric包含：
     * - List<String> orgLevels
     * - List<String> domainCodes
     *
     * @return 指标列表
     */
    List<OpMetric> listMetricsWithDomainsAndOrgs();

    /**
     * 根据指标ID列表查询指标（包含度量）
     *
     * @param metricIds 指标ID列表
     * @return 指标列表
     */
    List<OpMetric> listMetricsWithMeasuresByIds(List<Long> metricIds);
}