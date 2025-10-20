package org.example.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.OpMeasure;
import org.example.entity.OpMetric;
import org.example.mapper.OpMetricMapper;
import org.example.service.OpMetricService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 指标度量服务实现
 */
@Slf4j
@Service
public class OpMetricServiceImpl implements OpMetricService {

    @Autowired
    private OpMetricMapper opMetricMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 需求1：查询指标列表（包含度量列表）
     * 优化策略：
     * 1. 先查询所有指标
     * 2. 批量查询所有指标关联的度量（一次SQL）
     * 3. 在内存中组装数据
     */
    @Override
    @Cacheable(value = "metricsWithMeasures")
    public List<OpMetric> listMetricsWithMeasures() {
        log.info("开始查询指标列表（包含度量）");
        long startTime = System.currentTimeMillis();

        // 1. 查询所有指标
        List<Long> metricIds = opMetricMapper.selectAllMetricIds();
        if (metricIds == null || metricIds.isEmpty()) {
            log.warn("没有查询到任何指标");
            return Collections.emptyList();
        }

        List<OpMetric> metrics = opMetricMapper.selectMetricsByIds(metricIds);
        log.info("查询到 {} 个指标", metrics.size());

        // 2. 批量查询所有度量（一次SQL，避免N+1问题）
        List<Map<String, Object>> measuresData = (List<Map<String, Object>>) 
                opMetricMapper.selectMeasuresByMetricIds(metricIds);

        // 3. 按指标ID分组度量
        Map<Long, List<OpMeasure>> measureMap = new HashMap<>();
        for (Map<String, Object> row : measuresData) {
            Long metricId = ((Number) row.get("metric_id")).longValue();
            OpMeasure measure = convertToMeasure(row);
            
            measureMap.computeIfAbsent(metricId, k -> new ArrayList<>()).add(measure);
        }

        // 4. 组装数据
        for (OpMetric metric : metrics) {
            List<OpMeasure> measures = measureMap.get(metric.getMetricId());
            metric.setMeasures(measures != null ? measures : new ArrayList<>());
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("查询完成，耗时: {}ms", duration);

        return metrics;
    }

    /**
     * 需求2：返回Map<metricCode+measureCode, OpMeasure的JSON字符串>
     * 优化策略：
     * 1. 复用需求1的数据
     * 2. 在内存中转换为Map
     */
    @Override
    @Cacheable(value = "metricMeasureMap")
    public Map<String, String> getMetricMeasureMap() {
        log.info("开始构建指标度量Map");
        long startTime = System.currentTimeMillis();

        // 复用需求1的查询结果
        List<OpMetric> metrics = listMetricsWithMeasures();

        Map<String, String> resultMap = new HashMap<>();

        for (OpMetric metric : metrics) {
            if (metric.getMeasures() == null || metric.getMeasures().isEmpty()) {
                continue;
            }

            for (OpMeasure measure : metric.getMeasures()) {
                String key = metric.getMetricCode() + "_" + measure.getMeasureCode();
                String value = convertMeasureToString(measure);
                resultMap.put(key, value);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("构建Map完成，共 {} 条记录，耗时: {}ms", resultMap.size(), duration);

        return resultMap;
    }

    /**
     * 需求3：查询指标列表（包含域编码和组织层级）
     * 优化策略：
     * 1. 先查询所有指标
     *