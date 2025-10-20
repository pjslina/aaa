package org.example.service;

import org.example.dto.OpMetric;
import org.example.mapper.OpMetricMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OpMetricService {

    private final OpMetricMapper opMetricMapper;

    public OpMetricService(OpMetricMapper opMetricMapper) {
        this.opMetricMapper = opMetricMapper;
    }

    /** 1? 查询指标+度量 */
    public List<OpMetric> getMetricsWithMeasures() {
        return opMetricMapper.selectMetricsWithMeasures();
    }

    /** 2? 查询 Map<metricCode+measureCode, OpMeasure> */
    public Map<String, String> getMetricMeasureMap() {
        List<Map<String, String>> list = opMetricMapper.selectMetricMeasureMap();
        return list.stream().collect(Collectors.toMap(
                m -> m.get("key"),
                m -> m.get("value")
        ));
    }

    /** 3? 查询指标列表含 orgLevels 和 domainCodes */
    public List<OpMetric> getMetricsWithDomainAndOrg() {
        List<Map<String, Object>> domainList = opMetricMapper.selectMetricDomainCodes();
        List<Map<String, Object>> orgList = opMetricMapper.selectMetricOrgLevels();

        // 组装
        Map<Long, List<String>> domainMap = domainList.stream()
                .collect(Collectors.groupingBy(
                        m -> ((Number) m.get("metricId")).longValue(),
                        Collectors.mapping(m -> (String) m.get("domainCode"), Collectors.toList())
                ));

        Map<Long, List<String>> orgMap = orgList.stream()
                .collect(Collectors.groupingBy(
                        m -> ((Number) m.get("metricId")).longValue(),
                        Collectors.mapping(m -> (String) m.get("orgLevel"), Collectors.toList())
                ));

        List<OpMetric> metrics = opMetricMapper.selectMetricsWithMeasures();
        metrics.forEach(m -> {
            m.setDomainCodes(domainMap.getOrDefault(m.getMetricId(), Collections.emptyList()));
            m.setOrgLevels(orgMap.getOrDefault(m.getMetricId(), Collections.emptyList()));
        });

        return metrics;
    }
}
