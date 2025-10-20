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
 * ָ���������ʵ��
 */
@Slf4j
@Service
public class OpMetricServiceImpl implements OpMetricService {

    @Autowired
    private OpMetricMapper opMetricMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * ����1����ѯָ���б����������б�
     * �Ż����ԣ�
     * 1. �Ȳ�ѯ����ָ��
     * 2. ������ѯ����ָ������Ķ�����һ��SQL��
     * 3. ���ڴ�����װ����
     */
    @Override
    @Cacheable(value = "metricsWithMeasures")
    public List<OpMetric> listMetricsWithMeasures() {
        log.info("��ʼ��ѯָ���б�����������");
        long startTime = System.currentTimeMillis();

        // 1. ��ѯ����ָ��
        List<Long> metricIds = opMetricMapper.selectAllMetricIds();
        if (metricIds == null || metricIds.isEmpty()) {
            log.warn("û�в�ѯ���κ�ָ��");
            return Collections.emptyList();
        }

        List<OpMetric> metrics = opMetricMapper.selectMetricsByIds(metricIds);
        log.info("��ѯ�� {} ��ָ��", metrics.size());

        // 2. ������ѯ���ж�����һ��SQL������N+1���⣩
        List<Map<String, Object>> measuresData = (List<Map<String, Object>>) 
                opMetricMapper.selectMeasuresByMetricIds(metricIds);

        // 3. ��ָ��ID�������
        Map<Long, List<OpMeasure>> measureMap = new HashMap<>();
        for (Map<String, Object> row : measuresData) {
            Long metricId = ((Number) row.get("metric_id")).longValue();
            OpMeasure measure = convertToMeasure(row);
            
            measureMap.computeIfAbsent(metricId, k -> new ArrayList<>()).add(measure);
        }

        // 4. ��װ����
        for (OpMetric metric : metrics) {
            List<OpMeasure> measures = measureMap.get(metric.getMetricId());
            metric.setMeasures(measures != null ? measures : new ArrayList<>());
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("��ѯ��ɣ���ʱ: {}ms", duration);

        return metrics;
    }

    /**
     * ����2������Map<metricCode+measureCode, OpMeasure��JSON�ַ���>
     * �Ż����ԣ�
     * 1. ��������1������
     * 2. ���ڴ���ת��ΪMap
     */
    @Override
    @Cacheable(value = "metricMeasureMap")
    public Map<String, String> getMetricMeasureMap() {
        log.info("��ʼ����ָ�����Map");
        long startTime = System.currentTimeMillis();

        // ��������1�Ĳ�ѯ���
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
        log.info("����Map��ɣ��� {} ����¼����ʱ: {}ms", resultMap.size(), duration);

        return resultMap;
    }

    /**
     * ����3����ѯָ���б�������������֯�㼶��
     * �Ż����ԣ�
     * 1. �Ȳ�ѯ����ָ��
     *