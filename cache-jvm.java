package com.example.meta.service.impl;

import com.example.meta.mapper.MetaMapper;
import com.example.meta.model.MetaMeasureVO;
import com.example.meta.model.MetaMetricVO;
import com.example.meta.service.MetaService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MetaServiceMemoryImpl implements MetaService {

    private final MetaMapper metaMapper;

    private static final Map<String, MetaMeasureVO> MEASURE_MAP = new ConcurrentHashMap<>();
    private static final List<MetaMetricVO> METRICS_WITH_MEASURES = new ArrayList<>();
    private static final List<MetaMetricVO> METRICS_WITH_ORG_DOMAIN = new ArrayList<>();

    public MetaServiceMemoryImpl(MetaMapper metaMapper) {
        this.metaMapper = metaMapper;
    }

    @PostConstruct
    public void initCache() {
        log.info("? ϵͳ����������Ԫ���ݵ��ڴ���...");
        reloadCache();
        log.info("? �ڴ滺���ʼ����ɣ�measureMap={} metrics={}",
                MEASURE_MAP.size(), METRICS_WITH_MEASURES.size());
    }

    @Override
    public synchronized void reloadCache() {
        log.info("? �������¼���Ԫ���ݻ���...");

        MEASURE_MAP.clear();
        METRICS_WITH_MEASURES.clear();
        METRICS_WITH_ORG_DOMAIN.clear();

        // �����ݿ��ѯ
        List<MetaMetricVO> metrics1 = metaMapper.findMetricsWithMeasures();
        List<MetaMetricVO> metrics2 = metaMapper.findMetricsWithOrgAndDomain();
        List<MetaMeasureVO> measures = metaMapper.findAllMeasures();

        // ���浽�ڴ�
        METRICS_WITH_MEASURES.addAll(metrics1);
        METRICS_WITH_ORG_DOMAIN.addAll(metrics2);
        for (MetaMeasureVO m : measures) {
            MEASURE_MAP.put(m.getMetricCode() + ":::" + m.getMeasureCode(), m);
        }

        log.info("? Ԫ���ݻ������¼�����ɣ�");
    }

    @Override
    public List<MetaMetricVO> findMetricsWithMeasures(MetaMetricVO vo) {
        return Collections.unmodifiableList(METRICS_WITH_MEASURES);
    }

    @Override
    public List<MetaMetricVO> findMetricsWithOrgAndDomain(MetaMetricVO vo) {
        return Collections.unmodifiableList(METRICS_WITH_ORG_DOMAIN);
    }

    @Override
    public Map<String, MetaMeasureVO> findMeasureMap() {
        return Collections.unmodifiableMap(MEASURE_MAP);
    }
}
