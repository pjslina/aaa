package com.example.meta.service.impl;

import com.example.meta.mapper.MetaMapper;
import com.example.meta.model.MetaMeasureVO;
import com.example.meta.model.MetaMetricVO;
import com.example.meta.service.MetaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class MetaServiceRedisImpl implements MetaService {

    private static final String KEY_METRICS_WITH_MEASURES = "meta:metrics:withMeasures";
    private static final String KEY_METRICS_WITH_ORG_DOMAIN = "meta:metrics:withOrgDomain";
    private static final String KEY_MEASURE_MAP = "meta:measureMap";

    private final MetaMapper metaMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public MetaServiceRedisImpl(MetaMapper metaMapper, RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.metaMapper = metaMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initCache() {
        log.info("? 系统启动，加载元数据到 Redis 中...");
        reloadCache();
        log.info("? Redis 缓存初始化完成");
    }

    @Override
    public synchronized void reloadCache() {
        log.info("? 正在重新加载 Redis 缓存...");

        List<MetaMetricVO> metrics1 = metaMapper.findMetricsWithMeasures();
        List<MetaMetricVO> metrics2 = metaMapper.findMetricsWithOrgAndDomain();
        List<MetaMeasureVO> measures = metaMapper.findAllMeasures();

        Map<String, MetaMeasureVO> measureMap = new HashMap<>();
        for (MetaMeasureVO m : measures) {
            measureMap.put(m.getMetricCode() + ":::" + m.getMeasureCode(), m);
        }

        redisTemplate.opsForValue().set(KEY_METRICS_WITH_MEASURES, metrics1, Duration.ofHours(24));
        redisTemplate.opsForValue().set(KEY_METRICS_WITH_ORG_DOMAIN, metrics2, Duration.ofHours(24));
        redisTemplate.opsForHash().putAll(KEY_MEASURE_MAP, measureMap);

        log.info("? Redis 缓存刷新完成");
    }

    @Override
    public List<MetaMetricVO> findMetricsWithMeasures(MetaMetricVO vo) {
        Object cache = redisTemplate.opsForValue().get(KEY_METRICS_WITH_MEASURES);
        if (cache != null) {
            return objectMapper.convertValue(cache, new TypeReference<>() {});
        }
        reloadCache();
        return metaMapper.findMetricsWithMeasures();
    }

    @Override
    public List<MetaMetricVO> findMetricsWithOrgAndDomain(MetaMetricVO vo) {
        Object cache = redisTemplate.opsForValue().get(KEY_METRICS_WITH_ORG_DOMAIN);
        if (cache != null) {
            return objectMapper.convertValue(cache, new TypeReference<>() {});
        }
        reloadCache();
        return metaMapper.findMetricsWithOrgAndDomain();
    }

    @Override
    public Map<String, MetaMeasureVO> findMeasureMap() {
        Map<Object, Object> map = redisTemplate.opsForHash().entries(KEY_MEASURE_MAP);
        if (!map.isEmpty()) {
            Map<String, MetaMeasureVO> result = new HashMap<>();
            for (Map.Entry<Object, Object> e : map.entrySet()) {
                result.put(e.getKey().toString(), objectMapper.convertValue(e.getValue(), MetaMeasureVO.class));
            }
            return result;
        }
        reloadCache();
        return findMeasureMap();
    }
}
