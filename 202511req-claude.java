��Ϊ����Java����ר�Һͼܹ�ʦ������Ϊ�����һ�������ܡ�����չ����ά�����������������
�����ܹ�����
1. ��������ģ��
package com.example.domain;

import lombok.Data;
import java.io.Serializable;
import java.util.*;

/**
 * ǰ�˲�ѯ����
 */
@Data
public class MetricQueryVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Set<String> metricCodes;
    private Set<String> periodIds;
    private Set<String> orgCodes;
    private Set<String> orgLevels;
    private Set<String> domainCodes;
    private Integer sceneType;
}

/**
 * Ԫ���ݶ��󣨴�Redis���أ�
 */
@Data
public class Metadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /** ָ��Ԫ����: metricCode -> MetricMeta */
    private Map<String, MetricMeta> metricMetaMap;
    
    /** ����Ԫ����: measureCode -> MeasureMeta */
    private Map<String, MeasureMeta> measureMetaMap;
    
    /** ����Ԫ����: domainCode -> DomainMeta */
    private Map<String, DomainMeta> domainMetaMap;
    
    /** ��֯�㼶Ԫ����: orgLevel -> OrgLevelMeta */
    private Map<String, OrgLevelMeta> orgLevelMetaMap;
    
    /** ָ��-��֯�㼶����: metricCode -> Set<orgLevel> */
    private Map<String, Set<String>> metricOrgLevelMap;
    
    /** ָ��-�������: metricCode -> Set<domainCode> */
    private Map<String, Set<String>> metricDomainMap;
    
    /** ָ��-��������: metricCode -> List<measureCode> */
    private Map<String, List<String>> metricMeasureMap;
}

/**
 * ���������Ķ���
 */
@Data
public class ProcessContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;
    private MetricQueryVO queryVO;
    private Metadata metadata;
    private Long startTime;
    
    /** ��չ���� */
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
    
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}

/**
 * �������ݶ���
 */
@Data
public class MeasureData implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String measureCode;
    private String measureUnit;
    private String currency;
    private String value;
    private Map<String, Object> extAttributes;
}

/**
 * ���ظ�ǰ�˵����ݶ���
 */
@Data
public class MeasureDataVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String periodId;
    
    /**
     * Key: metricCode + ":::" + domainCode + ":::" + measureCode
     * Value: List<MeasureData>
     */
    private Map<String, List<MeasureData>> dataMap;
    
    public MeasureDataVO() {
        this.dataMap = new ConcurrentHashMap<>();
    }
}

2. API����Դ����
package com.example.datasource;

import com.example.domain.ProcessContext;
import java.util.List;

/**
 * ����Դ�ӿ�
 * @param <T> ԭʼ��������
 */
public interface IDataSource<T> {
    
    /**
     * ����Դ����
     */
    String getSourceName();
    
    /**
     * �Ƿ���Ҫ��ҳ
     */
    boolean needPagination();
    
    /**
     * ��ȡÿҳ��С
     */
    default int getPageSize() {
        return 100;
    }
    
    /**
     * ����API�������
     */
    Object buildApiRequest(ProcessContext context, int pageNum);
    
    /**
     * ��ѯ��һҳ����ȡ������
     */
    PageResult<T> queryFirstPage(ProcessContext context);
    
    /**
     * ��ѯָ��ҳ
     */
    List<T> queryPage(ProcessContext context, int pageNum);
    
    /**
     * һ���Բ�ѯȫ��������ҳ��
     */
    List<T> queryAll(ProcessContext context);
    
    /**
     * ��ҳ���
     */
    @lombok.Data
    class PageResult<T> {
        private Long total;
        private List<T> data;
        
        public int getTotalPages(int pageSize) {
            if (total == null || total == 0 || pageSize == 0) {
                return 0;
            }
            return (int) Math.ceil((double) total / pageSize);
        }
    }
}

3. ���ݹ���������
package com.example.filter;

import com.example.domain.ProcessContext;
import java.util.List;

/**
 * ���ݹ������ӿ�
 * @param <T> ԭʼ��������
 */
public interface IDataFilter<T> {
    
    /**
     * ����������
     */
    String getFilterName();
    
    /**
     * ���ȼ�������ԽС���ȼ�Խ�ߣ�
     */
    default int getPriority() {
        return 100;
    }
    
    /**
     * �Ƿ������ڸ�����Դ
     */
    boolean isApplicable(String sourceName, ProcessContext context);
    
    /**
     * ִ�й���
     * @param rawData ԭʼ����
     * @param context ������
     * @return ���˺������
     */
    List<T> filter(List<T> rawData, ProcessContext context);
}

4. ����ת��������
package com.example.converter;

import com.example.domain.MeasureData;
import com.example.domain.ProcessContext;

/**
 * ����ת�����ӿ�
 * @param <T> ԭʼ��������
 */
public interface IDataConverter<T> {
    
    /**
     * ת��������
     */
    String getConverterName();
    
    /**
     * �Ƿ�����
     */
    boolean isApplicable(String sourceName, ProcessContext context);
    
    /**
     * ת������
     * @param rawData ԭʼ����
     * @param context ������
     * @return ת����Ķ�������
     */
    ConvertedData convert(T rawData, ProcessContext context);
    
    /**
     * ת���������
     */
    @lombok.Data
    class ConvertedData {
        private String periodId;
        private String metricCode;
        private String domainCode;
        private String measureCode;
        private MeasureData measureData;
        
        /**
         * ��ȡMap��Key
         */
        public String getMapKey() {
            return metricCode + ":::" + domainCode + ":::" + measureCode;
        }
    }
}

5. Ԫ���ݼ�����
package com.example.service;

import com.example.domain.Metadata;
import com.example.domain.MetricQueryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Ԫ���ݼ�����
 */
@Slf4j
@Component
public class MetadataLoader {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * ���ػ��棨����Redis���ʣ�
     */
    private final ConcurrentHashMap<String, Metadata> localCache = new ConcurrentHashMap<>();
    
    /**
     * ����Ԫ����
     */
    public Metadata loadMetadata(MetricQueryVO queryVO) {
        String cacheKey = buildCacheKey(queryVO);
        
        // 1. �ȴӱ��ػ����ȡ
        Metadata metadata = localCache.get(cacheKey);
        if (metadata != null) {
            log.debug("�ӱ��ػ������Ԫ����");
            return metadata;
        }
        
        // 2. ��Redis����
        metadata = loadFromRedis(queryVO);
        
        // 3. ���뱾�ػ���
        localCache.put(cacheKey, metadata);
        
        return metadata;
    }
    
    /**
     * ��Redis����Ԫ����
     */
    private Metadata loadFromRedis(MetricQueryVO queryVO) {
        log.info("��Redis����Ԫ����");
        
        Metadata metadata = new Metadata();
        
        // TODO: ��Redis���ظ���Ԫ����
        // metadata.setMetricMetaMap(...)
        // metadata.setMeasureMetaMap(...)
        // ...
        
        return metadata;
    }
    
    /**
     * ��������Key
     */
    private String buildCacheKey(MetricQueryVO queryVO) {
        // ���ݲ�ѯ��������Key
        return "metadata:" + queryVO.getSceneType();
    }
    
    /**
     * ������ػ���
     */
    public void clearLocalCache() {
        localCache.clear();
    }
}

6. ���ݴ���ܵ�

package com.example.pipeline;

import com.example.converter.IDataConverter;
import com.example.datasource.IDataSource;
import com.example.domain.MeasureData;
import com.example.domain.MeasureDataVO;
import com.example.domain.ProcessContext;
import com.example.filter.IDataFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ���ݴ���ܵ�
 * ����������Դ����������
 */
@Slf4j
public class DataProcessPipeline<T> {
    
    private final IDataSource<T> dataSource;
    private final List<IDataFilter<T>> filters;
    private final IDataConverter<T> converter;
    private final ProcessContext context;
    private final ITaskExecutorService taskExecutor;
    
    public DataProcessPipeline(IDataSource<T> dataSource,
                              List<IDataFilter<T>> filters,
                              IDataConverter<T> converter,
                              ProcessContext context,
                              ITaskExecutorService taskExecutor) {
        this.dataSource = dataSource;
        this.filters = filters;
        this.converter = converter;
        this.context = context;
        this.taskExecutor = taskExecutor;
    }
    
    /**
     * ִ�йܵ�����
     */
    public List<IDataConverter.ConvertedData> execute() {
        log.info("��ʼִ�����ݹܵ�: {}", dataSource.getSourceName());
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. ��ȡ����ԭʼ����
            List<T> allRawData = fetchAllData();
            
            // 2. ��������
            List<T> filteredData = filterData(allRawData);
            
            // 3. ת������
            List<IDataConverter.ConvertedData> convertedData = convertData(filteredData);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("���ݹܵ�ִ�����: {}, ��ʱ: {}ms, ԭʼ����: {}, ���˺�: {}, ת����: {}",
                    dataSource.getSourceName(), duration, 
                    allRawData.size(), filteredData.size(), convertedData.size());
            
            return convertedData;
            
        } catch (Exception e) {
            log.error("���ݹܵ�ִ��ʧ��: {}", dataSource.getSourceName(), e);
            throw new RuntimeException("���ݹܵ�ִ��ʧ��", e);
        }
    }
    
    /**
     * ��ȡ��������
     */
    private List<T> fetchAllData() {
        if (!dataSource.needPagination()) {
            log.info("����Դ����Ҫ��ҳ: {}", dataSource.getSourceName());
            return dataSource.queryAll(context);
        }
        
        return fetchDataWithPagination();
    }
    
    /**
     * ��ҳ��ȡ���ݣ����У�
     */
    private List<T> fetchDataWithPagination() {
        // 1. ��ѯ��һҳ
        IDataSource.PageResult<T> firstPage = dataSource.queryFirstPage(context);
        List<T> allData = new CopyOnWriteArrayList<>(firstPage.getData());
        
        int totalPages = firstPage.getTotalPages(dataSource.getPageSize());
        log.info("����Դ: {}, ��ҳ��: {}, �ܼ�¼: {}", 
                dataSource.getSourceName(), totalPages, firstPage.getTotal());
        
        if (totalPages <= 1) {
            return allData;
        }
        
        // 2. ���в�ѯʣ��ҳ
        List<CompletableFuture<List<T>>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            
            CompletableFuture<List<T>> future = taskExecutor.submitTask(() -> {
                try {
                    List<T> pageData = dataSource.queryPage(context, currentPage);
                    log.debug("��ѯ��{}ҳ��ɣ�������: {}", currentPage, pageData.size());
                    return pageData;
                } catch (Exception e) {
                    log.error("��ѯ��{}ҳʧ��", currentPage, e);
                    return Collections.emptyList();
                }
            }, "��ѯ��" + currentPage + "ҳ");
            
            futures.add(future);
        }
        
        // 3. �ȴ������������
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 4. �ռ����
        for (CompletableFuture<List<T>> future : futures) {
            try {
                allData.addAll(future.get());
            } catch (Exception e) {
                log.error("��ȡ��ҳ����ʧ��", e);
            }
        }
        
        return allData;
    }
    
    /**
     * ��������
     */
    private List<T> filterData(List<T> rawData) {
        if (filters == null || filters.isEmpty()) {
            return rawData;
        }
        
        String sourceName = dataSource.getSourceName();
        
        // ��ȡ���õĹ�����������
        List<IDataFilter<T>> applicableFilters = filters.stream()
                .filter(f -> f.isApplicable(sourceName, context))
                .sorted(Comparator.comparingInt(IDataFilter::getPriority))
                .collect(Collectors.toList());
        
        // ����ִ�й�����
        List<T> currentData = rawData;
        for (IDataFilter<T> filter : applicableFilters) {
            log.debug("ִ�й�����: {}", filter.getFilterName());
            currentData = filter.filter(currentData, context);
            log.debug("���˺�������: {}", currentData.size());
        }
        
        return currentData;
    }
    
    /**
     * ת������
     */
    private List<IDataConverter.ConvertedData> convertData(List<T> filteredData) {
        if (converter == null || !converter.isApplicable(dataSource.getSourceName(), context)) {
            log.warn("û�����õ�ת����");
            return Collections.emptyList();
        }
        
        // ����ת��
        return filteredData.parallelStream()
                .map(data -> {
                    try {
                        return converter.convert(data, context);
                    } catch (Exception e) {
                        log.error("ת������ʧ��", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}

7. �ܵ�Э����
package com.example.orchestrator;

import com.example.converter.IDataConverter;
import com.example.domain.MeasureData;
import com.example.domain.MeasureDataVO;
import com.example.domain.MetricQueryVO;
import com.example.domain.ProcessContext;
import com.example.pipeline.DataProcessPipeline;
import com.example.pipeline.PipelineConfig;
import com.example.service.MetadataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * �ܵ�Э����
 * Э���������Դ�Ĳ��д���
 */
@Slf4j
@Component
public class PipelineOrchestrator {
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    @Autowired
    private ITaskExecutorService taskExecutor;
    
    @Autowired
    private PipelineConfigFactory configFactory;
    
    /**
     * ִ�ж�ܵ�����
     */
    public List<MeasureDataVO> executeMultiPipeline(MetricQueryVO queryVO) {
        log.info("��ʼִ�ж�ܵ�������ѯ����: {}", queryVO);
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. ����������
            ProcessContext context = buildContext(queryVO);
            
            // 2. ����Ԫ����
            context.setMetadata(metadataLoader.loadMetadata(queryVO));
            
            // 3. �����ܵ�����
            List<PipelineConfig> pipelineConfigs = configFactory.buildConfigs(context);
            
            // 4. ����ִ�����йܵ�
            List<CompletableFuture<List<IDataConverter.ConvertedData>>> futures = 
                    pipelineConfigs.stream()
                    .map(config -> taskExecutor.submitTask(() -> {
                        DataProcessPipeline pipeline = new DataProcessPipeline(
                                config.getDataSource(),
                                config.getFilters(),
                                config.getConverter(),
                                context,
                                taskExecutor
                        );
                        return pipeline.execute();
                    }, "ִ�йܵ�-" + config.getDataSource().getSourceName()))
                    .collect(Collectors.toList());
            
            // 5. �ȴ����йܵ����
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 6. �ռ����н��
            List<IDataConverter.ConvertedData> allConvertedData = new ArrayList<>();
            for (CompletableFuture<List<IDataConverter.ConvertedData>> future : futures) {
                try {
                    allConvertedData.addAll(future.get());
                } catch (Exception e) {
                    log.error("��ȡ�ܵ����ʧ��", e);
                }
            }
            
            // 7. �ۺ�����
            List<MeasureDataVO> result = aggregateData(allConvertedData);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("��ܵ�������ɣ��ܺ�ʱ: {}ms, �����: {}", duration, result.size());
            
            return result;
            
        } catch (Exception e) {
            log.error("��ܵ�����ʧ��", e);
            throw new RuntimeException("��ܵ�����ʧ��", e);
        }
    }
    
    /**
     * ����������
     */
    private ProcessContext buildContext(MetricQueryVO queryVO) {
        ProcessContext context = new ProcessContext();
        context.setRequestId(UUID.randomUUID().toString());
        context.setQueryVO(queryVO);
        context.setStartTime(System.currentTimeMillis());
        return context;
    }
    
    /**
     * �ۺ�����
     * ��periodId���飬����Map�ṹ
     */
    private List<MeasureDataVO> aggregateData(List<IDataConverter.ConvertedData> allData) {
        log.info("��ʼ�ۺ����ݣ���������: {}", allData.size());
        
        // 1. ��periodId����
        Map<String, List<IDataConverter.ConvertedData>> groupedByPeriod = allData.parallelStream()
                .collect(Collectors.groupingByConcurrent(
                        IDataConverter.ConvertedData::getPeriodId,
                        ConcurrentHashMap::new,
                        Collectors.toList()
                ));
        
        // 2. ����MeasureDataVO
        List<MeasureDataVO> result = groupedByPeriod.entrySet().parallelStream()
                .map(entry -> {
                    String periodId = entry.getKey();
                    List<IDataConverter.ConvertedData> periodData = entry.getValue();
                    
                    MeasureDataVO vo = new MeasureDataVO();
                    vo.setPeriodId(periodId);
                    
                    // ��Key����ϲ�
                    Map<String, List<MeasureData>> dataMap = periodData.stream()
                            .collect(Collectors.groupingBy(
                                    IDataConverter.ConvertedData::getMapKey,
                                    ConcurrentHashMap::new,
                                    Collectors.mapping(
                                            IDataConverter.ConvertedData::getMeasureData,
                                            Collectors.toCollection(CopyOnWriteArrayList::new)
                                    )
                            ));
                    
                    vo.setDataMap(dataMap);
                    return vo;
                })
                .collect(Collectors.toList());
        
        log.info("���ݾۺ���ɣ��������: {}", result.size());
        return result;
    }
}

8. �ܵ����ù���
package com.example.orchestrator;

import com.example.converter.IDataConverter;
import com.example.datasource.IDataSource;
import com.example.domain.ProcessContext;
import com.example.filter.IDataFilter;
import com.example.pipeline.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * �ܵ����ù���
 */
@Slf4j
@Component
public class PipelineConfigFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * �����ܵ������б�
     */
    public List<PipelineConfig> buildConfigs(ProcessContext context) {
        List<PipelineConfig> configs = new ArrayList<>();
        
        // 1. ��ȡ��������Դ
        Map<String, IDataSource> dataSourceMap = applicationContext.getBeansOfType(IDataSource.class);
        
        // 2. ��ȡ���й�����
        Map<String, IDataFilter> filterMap = applicationContext.getBeansOfType(IDataFilter.class);
        List<IDataFilter> allFilters = new ArrayList<>(filterMap.values());
        
        // 3. ��ȡ����ת����
        Map<String, IDataConverter> converterMap = applicationContext.getBeansOfType(IDataConverter.class);
        
        // 4. Ϊÿ������Դ��������
        for (IDataSource dataSource : dataSourceMap.values()) {
            PipelineConfig config = new PipelineConfig();
            config.setDataSource(dataSource);
            
            // ɸѡ���õĹ�����
            List<IDataFilter> applicableFilters = allFilters.stream()
                    .filter(f -> f.isApplicable(dataSource.getSourceName(), context))
                    .collect(Collectors.toList());
            config.setFilters(applicableFilters);
            
            // ɸѡ���õ�ת������ÿ������Դһ��ת������
            IDataConverter converter = converterMap.values().stream()
                    .filter(c -> c.isApplicable(dataSource.getSourceName(), context))
                    .findFirst()
                    .orElse(null);
            config.setConverter(converter);
            
            if (converter != null) {
                configs.add(config);
                log.info("�����ܵ�����: {}, ��������: {}", 
                        dataSource.getSourceName(), applicableFilters.size());
            } else {
                log.warn("����Դ {} û���ҵ����õ�ת����", dataSource.getSourceName());
            }
        }
        
        return configs;
    }
}

/**
 * �ܵ�����
 */
@lombok.Data
class PipelineConfig<T> {
    private IDataSource<T> dataSource;
    private List<IDataFilter<T>> filters;
    private IDataConverter<T> converter;
}

9. ����ʵ��ʾ��
package com.example.datasource.impl;

import com.example.datasource.IDataSource;
import com.example.domain.ProcessContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API����ԴAʵ��
 */
@Slf4j
@Component
public class ApiDataSourceA implements IDataSource<ApiDataA> {
    
    @Override
    public String getSourceName() {
        return "API_SOURCE_A";
    }
    
    @Override
    public boolean needPagination() {
        return true;
    }
    
    @Override
    public int getPageSize() {
        return 100;
    }
    
    @Override
    public Object buildApiRequest(ProcessContext context, int pageNum) {
        // TODO: ����API�������
        return null;
    }
    
    @Override
    public PageResult<ApiDataA> queryFirstPage(ProcessContext context) {
        log.info("��ѯ����ԴA��һҳ");
        // TODO: ����ʵ��API
        PageResult<ApiDataA> result = new PageResult<>();
        result.setTotal(1000L);
        result.setData(new ArrayList<>());
        return result;
    }
    
    @Override
    public List<ApiDataA> queryPage(ProcessContext context, int pageNum) {
        log.debug("��ѯ����ԴA��{}ҳ", pageNum);
        // TODO: ����ʵ��API
        return new ArrayList<>();
    }
    
    @Override
    public List<ApiDataA> queryAll(ProcessContext context) {
        return null;
    }
}

/**
 * API����ԴBʵ�֣�����Ҫ��ҳ��
 */
@Slf4j
@Component
public class ApiDataSourceB implements IDataSource<ApiDataB> {
    
    @Override
    public String getSourceName() {
        return "API_SOURCE_B";
    }
    
    @Override
    public boolean needPagination() {
        return false; // ����Ҫ��ҳ
    }
    
    @Override
    public Object buildApiRequest(ProcessContext context, int pageNum) {
        return null;
    }
    
    @Override
    public PageResult<ApiDataB> queryFirstPage(ProcessContext context) {
        return null;
    }
    
    @Override
    public List<ApiDataB> queryPage(ProcessContext context, int pageNum) {
        return null;
    }
    
    @Override
    public List<ApiDataB> queryAll(ProcessContext context) {
        log.info("��ѯ����ԴBȫ������");
        // TODO: ����ʵ��API
        return new ArrayList<>();
    }
}

10. ������ʵ��ʾ��
package com.example.filter.impl;

import com.example.domain.Metadata;
import com.example.domain.MetricQueryVO;
import com.example.domain.ProcessContext;
import com.example.filter.IDataFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ָ����������
 */
@Slf4j
@Component
public class MetricCodeFilter<T> implements IDataFilter<T> {
    
    @Override
    public String getFilterName() {
        return "MetricCodeFilter";
    }
    
    @Override
    public int getPriority() {
        return 1;
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        MetricQueryVO queryVO = context.getQueryVO();
        return queryVO.getMetricCodes() != null && !queryVO.getMetricCodes().isEmpty();
    }
    
    @Override
    public List<T> filter(List<T> rawData, ProcessContext context) {
        Set<String> metricCodes = context.getQueryVO().getMetricCodes();
        
        return rawData.stream()
                .filter(data -> {
                    String metricCode = extractMetricCode(data);
                    return metricCode != null && metricCodes.contains(metricCode);
                })
                .collect(Collectors.toList());
    }
    
    /**
     * ��ȡָ����루��Ҫ����ʵ����������ʵ�֣�
     */
    private String extractMetricCode(T data) {
        // TODO: ����ʵ������������ȡmetricCode
        return null;
    }
}

/**
 * ��֯�㼶������
 */
@Slf4j
@Component
public class OrgLevelFilter<T> implements IDataFilter<T> {
@Override
    public String getFilterName() {
        return "OrgLevelFilter";
    }
    
    @Override
    public int getPriority() {
        return 2;
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        MetricQueryVO queryVO = context.getQueryVO();
        return queryVO.getOrgLevels() != null && !queryVO.getOrgLevels().isEmpty();
    }
    
    @Override
    public List<T> filter(List<T> rawData, ProcessContext context) {
        Set<String> orgLevels = context.getQueryVO().getOrgLevels();
        Metadata metadata = context.getMetadata();
        
        return rawData.stream()
                .filter(data -> {
                    String metricCode = extractMetricCode(data);
                    String orgLevel = extractOrgLevel(data);
                    
                    // ����ָ���Ƿ�֧�ָ���֯�㼶
                    if (metadata.getMetricOrgLevelMap().containsKey(metricCode)) {
                        Set<String> supportedOrgLevels = metadata.getMetricOrgLevelMap().get(metricCode);
                        return orgLevels.contains(orgLevel) && supportedOrgLevels.contains(orgLevel);
                    }
                    
                    return orgLevels.contains(orgLevel);
                })
                .collect(Collectors.toList());
    }
    
    private String extractMetricCode(T data) {
        // TODO: ����ʵ������������ȡ
        return null;
    }
    
    private String extractOrgLevel(T data) {
        // TODO: ����ʵ������������ȡ
        return null;
    }
}

/**
 * ���������
 */
@Slf4j
@Component
public class DomainCodeFilter<T> implements IDataFilter<T> {
    
    @Override
    public String getFilterName() {
        return "DomainCodeFilter";
    }
    
    @Override
    public int getPriority() {
        return 3;
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        MetricQueryVO queryVO = context.getQueryVO();
        return queryVO.getDomainCodes() != null && !queryVO.getDomainCodes().isEmpty();
    }
    
    @Override
    public List<T> filter(List<T> rawData, ProcessContext context) {
        Set<String> domainCodes = context.getQueryVO().getDomainCodes();
        Metadata metadata = context.getMetadata();
        
        return rawData.stream()
                .filter(data -> {
                    String metricCode = extractMetricCode(data);
                    String domainCode = extractDomainCode(data);
                    
                    // ����ָ���Ƿ�֧�ָ�����
                    if (metadata.getMetricDomainMap().containsKey(metricCode)) {
                        Set<String> supportedDomains = metadata.getMetricDomainMap().get(metricCode);
                        return domainCodes.contains(domainCode) && supportedDomains.contains(domainCode);
                    }
                    
                    return domainCodes.contains(domainCode);
                })
                .collect(Collectors.toList());
    }
    
    private String extractMetricCode(T data) {
        return null;
    }
    
    private String extractDomainCode(T data) {
        return null;
    }
}

/**
 * ��������������
 */
@Slf4j
@Component
public class DataQualityFilter<T> implements IDataFilter<T> {
    
    @Override
    public String getFilterName() {
        return "DataQualityFilter";
    }
    
    @Override
    public int getPriority() {
        return 10; // �����ȼ������ִ��
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        return true; // ��������Դ������
    }
    
    @Override
    public List<T> filter(List<T> rawData, ProcessContext context) {
        return rawData.stream()
                .filter(this::isValidData)
                .collect(Collectors.toList());
    }
    
    /**
     * У����������
     */
    private boolean isValidData(T data) {
        // TODO: ʵ����������У���߼�
        // ���磺�ǿ�У�顢��ֵ��ΧУ���
        return true;
    }
}

11. ת����ʵ��ʾ��
package com.example.converter.impl;

import com.example.converter.IDataConverter;
import com.example.domain.MeasureData;
import com.example.domain.Metadata;
import com.example.domain.ProcessContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;

/**
 * API����ԴA��ת����
 */
@Slf4j
@Component
public class ApiDataAConverter implements IDataConverter<ApiDataA> {
    
    @Override
    public String getConverterName() {
        return "ApiDataAConverter";
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        return "API_SOURCE_A".equals(sourceName);
    }
    
    @Override
    public ConvertedData convert(ApiDataA rawData, ProcessContext context) {
        Metadata metadata = context.getMetadata();
        
        ConvertedData convertedData = new ConvertedData();
        
        // 1. ��ȡ������Ϣ
        convertedData.setPeriodId(rawData.getPeriodId());
        convertedData.setMetricCode(rawData.getMetricCode());
        convertedData.setDomainCode(rawData.getDomainCode());
        convertedData.setMeasureCode(rawData.getMeasureCode());
        
        // 2. ����MeasureData
        MeasureData measureData = new MeasureData();
        measureData.setMeasureCode(rawData.getMeasureCode());
        
        // ��Ԫ���ݻ�ȡ�����ĵ�λ�ͱ���
        if (metadata.getMeasureMetaMap().containsKey(rawData.getMeasureCode())) {
            MeasureMeta measureMeta = metadata.getMeasureMetaMap().get(rawData.getMeasureCode());
            measureData.setMeasureUnit(measureMeta.getUnit());
            measureData.setCurrency(measureMeta.getCurrency());
        }
        
        // ����ֵ��������Ҫ���Ӽ��㣩
        String calculatedValue = calculateValue(rawData, context);
        measureData.setValue(calculatedValue);
        
        // ��չ����
        measureData.setExtAttributes(new HashMap<>());
        
        convertedData.setMeasureData(measureData);
        
        return convertedData;
    }
    
    /**
     * �������ֵ
     */
    private String calculateValue(ApiDataA rawData, ProcessContext context) {
        // TODO: ʵ�ָ��ӵļ����߼�
        // ����������Ԫ�����еļ������
        return rawData.getValue();
    }
}

/**
 * API����ԴB��ת����
 */
@Slf4j
@Component
public class ApiDataBConverter implements IDataConverter<ApiDataB> {
    
    @Override
    public String getConverterName() {
        return "ApiDataBConverter";
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        return "API_SOURCE_B".equals(sourceName);
    }
    
    @Override
    public ConvertedData convert(ApiDataB rawData, ProcessContext context) {
        Metadata metadata = context.getMetadata();
        
        ConvertedData convertedData = new ConvertedData();
        convertedData.setPeriodId(rawData.getPeriod());
        convertedData.setMetricCode(rawData.getMetric());
        convertedData.setDomainCode(rawData.getDomain());
        convertedData.setMeasureCode(rawData.getMeasure());
        
        MeasureData measureData = new MeasureData();
        measureData.setMeasureCode(rawData.getMeasure());
        measureData.setValue(rawData.getAmount());
        
        // ��Ԫ���ݻ�ȡ������Ϣ
        if (metadata.getMeasureMetaMap().containsKey(rawData.getMeasure())) {
            MeasureMeta meta = metadata.getMeasureMetaMap().get(rawData.getMeasure());
            measureData.setMeasureUnit(meta.getUnit());
            measureData.setCurrency(meta.getCurrency());
        }
        
        convertedData.setMeasureData(measureData);
        
        return convertedData;
    }
}

12. Service��ʵ��
package com.example.service;

import com.example.domain.MeasureDataVO;
import com.example.domain.MetricQueryVO;
import com.example.orchestrator.PipelineOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ҵ�����ʵ��
 */
@Slf4j
@Service
public class MetricDataService {
    
    @Autowired
    private PipelineOrchestrator orchestrator;
    
    /**
     * ��ѯ��������
     */
    public List<MeasureDataVO> queryMeasureData(MetricQueryVO queryVO) {
        log.info("��ѯ�������ݣ���ѯ����: {}", queryVO);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // ִ�ж�ܵ�����
            List<MeasureDataVO> result = orchestrator.executeMultiPipeline(queryVO);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("��ѯ��ɣ���ʱ: {}ms, �����: {}", duration, result.size());
            
            return result;
            
        } catch (Exception e) {
            log.error("��ѯ��������ʧ��", e);
            throw new RuntimeException("��ѯʧ��", e);
        }
    }
}

13. Controller��ʵ��
package com.example.controller;

import com.example.domain.MeasureDataVO;
import com.example.domain.MetricQueryVO;
import com.example.service.MetricDataService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ָ�����ݲ�ѯ�ӿ�
 */
@Slf4j
@RestController
@RequestMapping("/api/metric")
@Api(tags = "ָ�����ݲ�ѯ")
public class MetricDataController {
    
    @Autowired
    private MetricDataService metricDataService;
    
    @PostMapping("/query")
    @ApiOperation("��ѯ��������")
    public ApiResponse<List<MeasureDataVO>> queryMeasureData(@RequestBody MetricQueryVO queryVO) {
        try {
            List<MeasureDataVO> result = metricDataService.queryMeasureData(queryVO);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("��ѯ��������ʧ��", e);
            return ApiResponse.error("��ѯʧ��: " + e.getMessage());
        }
    }
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        
        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(true, "�ɹ�", data);
        }
        
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}

14. �첽ˢ�»���ʵ�֣���ѡ��
package com.example.async;

import com.example.service.IAsyncProcessHandler;
import com.example.service.MetadataLoader;
import com.example.vo.AjaxMessageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * �첽ˢ��Ԫ���ݴ�����
 */
@Slf4j
@Component("metadataRefreshHandler")
public class MetadataRefreshHandler implements IAsyncProcessHandler {
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    @Override
    public AjaxMessageVo handlerProcess(Serializable context) throws ApplicationException {
        try {
            log.info("��ʼ�첽ˢ��Ԫ����");
            
            // ������ػ���
            metadataLoader.clearLocalCache();
            
            log.info("Ԫ����ˢ�����");
            return AjaxMessageVo.success();
            
        } catch (Exception e) {
            log.error("�첽ˢ��Ԫ����ʧ��", e);
            return AjaxMessageVo.error("ˢ��ʧ��: " + e.getMessage());
        }
    }
}

15. ��ʱ����ʵ�֣���ѡ��
package com.example.task;

import com.example.service.ITimerTask;
import com.example.service.MetadataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ԫ����ˢ�¶�ʱ����
 */
@Slf4j
@Component("metadataRefreshTask")
public class MetadataRefreshTask implements ITimerTask {
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        try {
            log.info("��ʱ���񣺿�ʼˢ��Ԫ����");
            
            // ������ػ���
            metadataLoader.clearLocalCache();
            
            log.info("��ʱ����Ԫ����ˢ�����");
            
        } catch (Exception e) {
            log.error("��ʱ����Ԫ����ˢ��ʧ��", e);
            throw new ApplicationException("Ԫ����ˢ��ʧ��", e);
        }
    }
}

16. �����ļ�
# application.yml
spring:
  application:
    name: metric-service

# Redis����
spring:
  redis:
    host: localhost
    port: 6379
    database: 0
    timeout: 3000ms

# �̳߳�����
thread-pool:
  core-size: 16
  max-size: 32
  queue-capacity: 1000

# ����Դ����
datasource:
  api-a:
    enabled: true
    page-size: 100
  api-b:
    enabled: true
    need-pagination: false

# Ԫ���ݻ�������
metadata:
  cache:
    local-ttl: 300 # ���ػ���TTL���룩
    redis-ttl: 3600 # Redis����TTL���룩

logging:
  level:
    com.example: INFO
    com.example.pipeline: DEBUG
    
�ܹ������ܽ�
1. ������

���д������API����Դ���в�ѯ
��ҳ���У�ÿ������Դ�Ķ�ҳ���ݲ��л�ȡ
���й��˺�ת����ʹ��Stream���д���
�̰߳�ȫ���ϣ�ʹ��ConcurrentHashMap��CopyOnWriteArrayList
���ػ��棺Ԫ���ݱ��ػ������Redis����

2. ����չ��

��������Դ��ʵ��IDataSource�ӿڼ���
������������ʵ��IDataFilter�ӿڣ��Զ������ֺ�Ӧ��
����ת������ʵ��IDataConverter�ӿ�
���ʽ��ƣ�ͨ��Spring�Զ�װ�䣬�����޸ĺ��Ĵ���

3. ��ά����

�����ķֲ㣺����Դ �� ���� �� ת�� �� �ۺ�
��һְ��ÿ�����ְ����ȷ
�ӿڳ�������������Ǿ���ʵ��
ͳһ���ã�ͨ�������༯�й�������

4. ���ģʽӦ��

ģ�巽��ģʽ��DataProcessPipeline���崦������
����ģʽ������Դ����������ת�������ǲ���
������ģʽ����������ʽִ��
����ģʽ��PipelineConfigFactory��̬��������
����ģʽ��PipelineOrchestrator�ṩͳһ���

5. ʹ����������

���ɹ�˾�̳߳أ�ʹ��ITaskExecutorService
֧���첽����ʵ��IAsyncProcessHandler
֧�ֶ�ʱ����ʵ��ITimerTask

���������ȫ�������������Ҿ���������������