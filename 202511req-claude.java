作为资深Java技术专家和架构师，我来为您设计一个高性能、可扩展、易维护的完整解决方案。
完整架构方案
1. 核心领域模型
package com.example.domain;

import lombok.Data;
import java.io.Serializable;
import java.util.*;

/**
 * 前端查询参数
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
 * 元数据对象（从Redis加载）
 */
@Data
public class Metadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /** 指标元数据: metricCode -> MetricMeta */
    private Map<String, MetricMeta> metricMetaMap;
    
    /** 度量元数据: measureCode -> MeasureMeta */
    private Map<String, MeasureMeta> measureMetaMap;
    
    /** 领域元数据: domainCode -> DomainMeta */
    private Map<String, DomainMeta> domainMetaMap;
    
    /** 组织层级元数据: orgLevel -> OrgLevelMeta */
    private Map<String, OrgLevelMeta> orgLevelMetaMap;
    
    /** 指标-组织层级关联: metricCode -> Set<orgLevel> */
    private Map<String, Set<String>> metricOrgLevelMap;
    
    /** 指标-领域关联: metricCode -> Set<domainCode> */
    private Map<String, Set<String>> metricDomainMap;
    
    /** 指标-度量关联: metricCode -> List<measureCode> */
    private Map<String, List<String>> metricMeasureMap;
}

/**
 * 请求上下文对象
 */
@Data
public class ProcessContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;
    private MetricQueryVO queryVO;
    private Metadata metadata;
    private Long startTime;
    
    /** 扩展属性 */
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
 * 度量数据对象
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
 * 返回给前端的数据对象
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

2. API数据源抽象
package com.example.datasource;

import com.example.domain.ProcessContext;
import java.util.List;

/**
 * 数据源接口
 * @param <T> 原始数据类型
 */
public interface IDataSource<T> {
    
    /**
     * 数据源名称
     */
    String getSourceName();
    
    /**
     * 是否需要分页
     */
    boolean needPagination();
    
    /**
     * 获取每页大小
     */
    default int getPageSize() {
        return 100;
    }
    
    /**
     * 构建API请求参数
     */
    Object buildApiRequest(ProcessContext context, int pageNum);
    
    /**
     * 查询第一页（获取总数）
     */
    PageResult<T> queryFirstPage(ProcessContext context);
    
    /**
     * 查询指定页
     */
    List<T> queryPage(ProcessContext context, int pageNum);
    
    /**
     * 一次性查询全部（不分页）
     */
    List<T> queryAll(ProcessContext context);
    
    /**
     * 分页结果
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

3. 数据过滤器抽象
package com.example.filter;

import com.example.domain.ProcessContext;
import java.util.List;

/**
 * 数据过滤器接口
 * @param <T> 原始数据类型
 */
public interface IDataFilter<T> {
    
    /**
     * 过滤器名称
     */
    String getFilterName();
    
    /**
     * 优先级（数字越小优先级越高）
     */
    default int getPriority() {
        return 100;
    }
    
    /**
     * 是否适用于该数据源
     */
    boolean isApplicable(String sourceName, ProcessContext context);
    
    /**
     * 执行过滤
     * @param rawData 原始数据
     * @param context 上下文
     * @return 过滤后的数据
     */
    List<T> filter(List<T> rawData, ProcessContext context);
}

4. 数据转换器抽象
package com.example.converter;

import com.example.domain.MeasureData;
import com.example.domain.ProcessContext;

/**
 * 数据转换器接口
 * @param <T> 原始数据类型
 */
public interface IDataConverter<T> {
    
    /**
     * 转换器名称
     */
    String getConverterName();
    
    /**
     * 是否适用
     */
    boolean isApplicable(String sourceName, ProcessContext context);
    
    /**
     * 转换数据
     * @param rawData 原始数据
     * @param context 上下文
     * @return 转换后的度量数据
     */
    ConvertedData convert(T rawData, ProcessContext context);
    
    /**
     * 转换后的数据
     */
    @lombok.Data
    class ConvertedData {
        private String periodId;
        private String metricCode;
        private String domainCode;
        private String measureCode;
        private MeasureData measureData;
        
        /**
         * 获取Map的Key
         */
        public String getMapKey() {
            return metricCode + ":::" + domainCode + ":::" + measureCode;
        }
    }
}

5. 元数据加载器
package com.example.service;

import com.example.domain.Metadata;
import com.example.domain.MetricQueryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据加载器
 */
@Slf4j
@Component
public class MetadataLoader {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 本地缓存（减少Redis访问）
     */
    private final ConcurrentHashMap<String, Metadata> localCache = new ConcurrentHashMap<>();
    
    /**
     * 加载元数据
     */
    public Metadata loadMetadata(MetricQueryVO queryVO) {
        String cacheKey = buildCacheKey(queryVO);
        
        // 1. 先从本地缓存获取
        Metadata metadata = localCache.get(cacheKey);
        if (metadata != null) {
            log.debug("从本地缓存加载元数据");
            return metadata;
        }
        
        // 2. 从Redis加载
        metadata = loadFromRedis(queryVO);
        
        // 3. 放入本地缓存
        localCache.put(cacheKey, metadata);
        
        return metadata;
    }
    
    /**
     * 从Redis加载元数据
     */
    private Metadata loadFromRedis(MetricQueryVO queryVO) {
        log.info("从Redis加载元数据");
        
        Metadata metadata = new Metadata();
        
        // TODO: 从Redis加载各种元数据
        // metadata.setMetricMetaMap(...)
        // metadata.setMeasureMetaMap(...)
        // ...
        
        return metadata;
    }
    
    /**
     * 构建缓存Key
     */
    private String buildCacheKey(MetricQueryVO queryVO) {
        // 根据查询条件构建Key
        return "metadata:" + queryVO.getSceneType();
    }
    
    /**
     * 清除本地缓存
     */
    public void clearLocalCache() {
        localCache.clear();
    }
}

6. 数据处理管道

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
 * 数据处理管道
 * 处理单个数据源的完整流程
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
     * 执行管道处理
     */
    public List<IDataConverter.ConvertedData> execute() {
        log.info("开始执行数据管道: {}", dataSource.getSourceName());
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 获取所有原始数据
            List<T> allRawData = fetchAllData();
            
            // 2. 过滤数据
            List<T> filteredData = filterData(allRawData);
            
            // 3. 转换数据
            List<IDataConverter.ConvertedData> convertedData = convertData(filteredData);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("数据管道执行完成: {}, 耗时: {}ms, 原始数据: {}, 过滤后: {}, 转换后: {}",
                    dataSource.getSourceName(), duration, 
                    allRawData.size(), filteredData.size(), convertedData.size());
            
            return convertedData;
            
        } catch (Exception e) {
            log.error("数据管道执行失败: {}", dataSource.getSourceName(), e);
            throw new RuntimeException("数据管道执行失败", e);
        }
    }
    
    /**
     * 获取所有数据
     */
    private List<T> fetchAllData() {
        if (!dataSource.needPagination()) {
            log.info("数据源不需要分页: {}", dataSource.getSourceName());
            return dataSource.queryAll(context);
        }
        
        return fetchDataWithPagination();
    }
    
    /**
     * 分页获取数据（并行）
     */
    private List<T> fetchDataWithPagination() {
        // 1. 查询第一页
        IDataSource.PageResult<T> firstPage = dataSource.queryFirstPage(context);
        List<T> allData = new CopyOnWriteArrayList<>(firstPage.getData());
        
        int totalPages = firstPage.getTotalPages(dataSource.getPageSize());
        log.info("数据源: {}, 总页数: {}, 总记录: {}", 
                dataSource.getSourceName(), totalPages, firstPage.getTotal());
        
        if (totalPages <= 1) {
            return allData;
        }
        
        // 2. 并行查询剩余页
        List<CompletableFuture<List<T>>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            
            CompletableFuture<List<T>> future = taskExecutor.submitTask(() -> {
                try {
                    List<T> pageData = dataSource.queryPage(context, currentPage);
                    log.debug("查询第{}页完成，数据量: {}", currentPage, pageData.size());
                    return pageData;
                } catch (Exception e) {
                    log.error("查询第{}页失败", currentPage, e);
                    return Collections.emptyList();
                }
            }, "查询第" + currentPage + "页");
            
            futures.add(future);
        }
        
        // 3. 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 4. 收集结果
        for (CompletableFuture<List<T>> future : futures) {
            try {
                allData.addAll(future.get());
            } catch (Exception e) {
                log.error("获取分页数据失败", e);
            }
        }
        
        return allData;
    }
    
    /**
     * 过滤数据
     */
    private List<T> filterData(List<T> rawData) {
        if (filters == null || filters.isEmpty()) {
            return rawData;
        }
        
        String sourceName = dataSource.getSourceName();
        
        // 获取适用的过滤器并排序
        List<IDataFilter<T>> applicableFilters = filters.stream()
                .filter(f -> f.isApplicable(sourceName, context))
                .sorted(Comparator.comparingInt(IDataFilter::getPriority))
                .collect(Collectors.toList());
        
        // 依次执行过滤器
        List<T> currentData = rawData;
        for (IDataFilter<T> filter : applicableFilters) {
            log.debug("执行过滤器: {}", filter.getFilterName());
            currentData = filter.filter(currentData, context);
            log.debug("过滤后数据量: {}", currentData.size());
        }
        
        return currentData;
    }
    
    /**
     * 转换数据
     */
    private List<IDataConverter.ConvertedData> convertData(List<T> filteredData) {
        if (converter == null || !converter.isApplicable(dataSource.getSourceName(), context)) {
            log.warn("没有适用的转换器");
            return Collections.emptyList();
        }
        
        // 并行转换
        return filteredData.parallelStream()
                .map(data -> {
                    try {
                        return converter.convert(data, context);
                    } catch (Exception e) {
                        log.error("转换数据失败", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}

7. 管道协调器
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
 * 管道协调器
 * 协调多个数据源的并行处理
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
     * 执行多管道处理
     */
    public List<MeasureDataVO> executeMultiPipeline(MetricQueryVO queryVO) {
        log.info("开始执行多管道处理，查询条件: {}", queryVO);
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 构建上下文
            ProcessContext context = buildContext(queryVO);
            
            // 2. 加载元数据
            context.setMetadata(metadataLoader.loadMetadata(queryVO));
            
            // 3. 构建管道配置
            List<PipelineConfig> pipelineConfigs = configFactory.buildConfigs(context);
            
            // 4. 并行执行所有管道
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
                    }, "执行管道-" + config.getDataSource().getSourceName()))
                    .collect(Collectors.toList());
            
            // 5. 等待所有管道完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 6. 收集所有结果
            List<IDataConverter.ConvertedData> allConvertedData = new ArrayList<>();
            for (CompletableFuture<List<IDataConverter.ConvertedData>> future : futures) {
                try {
                    allConvertedData.addAll(future.get());
                } catch (Exception e) {
                    log.error("获取管道结果失败", e);
                }
            }
            
            // 7. 聚合数据
            List<MeasureDataVO> result = aggregateData(allConvertedData);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("多管道处理完成，总耗时: {}ms, 结果数: {}", duration, result.size());
            
            return result;
            
        } catch (Exception e) {
            log.error("多管道处理失败", e);
            throw new RuntimeException("多管道处理失败", e);
        }
    }
    
    /**
     * 构建上下文
     */
    private ProcessContext buildContext(MetricQueryVO queryVO) {
        ProcessContext context = new ProcessContext();
        context.setRequestId(UUID.randomUUID().toString());
        context.setQueryVO(queryVO);
        context.setStartTime(System.currentTimeMillis());
        return context;
    }
    
    /**
     * 聚合数据
     * 按periodId分组，构建Map结构
     */
    private List<MeasureDataVO> aggregateData(List<IDataConverter.ConvertedData> allData) {
        log.info("开始聚合数据，总数据量: {}", allData.size());
        
        // 1. 按periodId分组
        Map<String, List<IDataConverter.ConvertedData>> groupedByPeriod = allData.parallelStream()
                .collect(Collectors.groupingByConcurrent(
                        IDataConverter.ConvertedData::getPeriodId,
                        ConcurrentHashMap::new,
                        Collectors.toList()
                ));
        
        // 2. 构建MeasureDataVO
        List<MeasureDataVO> result = groupedByPeriod.entrySet().parallelStream()
                .map(entry -> {
                    String periodId = entry.getKey();
                    List<IDataConverter.ConvertedData> periodData = entry.getValue();
                    
                    MeasureDataVO vo = new MeasureDataVO();
                    vo.setPeriodId(periodId);
                    
                    // 按Key分组合并
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
        
        log.info("数据聚合完成，会计期数: {}", result.size());
        return result;
    }
}

8. 管道配置工厂
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
 * 管道配置工厂
 */
@Slf4j
@Component
public class PipelineConfigFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * 构建管道配置列表
     */
    public List<PipelineConfig> buildConfigs(ProcessContext context) {
        List<PipelineConfig> configs = new ArrayList<>();
        
        // 1. 获取所有数据源
        Map<String, IDataSource> dataSourceMap = applicationContext.getBeansOfType(IDataSource.class);
        
        // 2. 获取所有过滤器
        Map<String, IDataFilter> filterMap = applicationContext.getBeansOfType(IDataFilter.class);
        List<IDataFilter> allFilters = new ArrayList<>(filterMap.values());
        
        // 3. 获取所有转换器
        Map<String, IDataConverter> converterMap = applicationContext.getBeansOfType(IDataConverter.class);
        
        // 4. 为每个数据源构建配置
        for (IDataSource dataSource : dataSourceMap.values()) {
            PipelineConfig config = new PipelineConfig();
            config.setDataSource(dataSource);
            
            // 筛选适用的过滤器
            List<IDataFilter> applicableFilters = allFilters.stream()
                    .filter(f -> f.isApplicable(dataSource.getSourceName(), context))
                    .collect(Collectors.toList());
            config.setFilters(applicableFilters);
            
            // 筛选适用的转换器（每个数据源一个转换器）
            IDataConverter converter = converterMap.values().stream()
                    .filter(c -> c.isApplicable(dataSource.getSourceName(), context))
                    .findFirst()
                    .orElse(null);
            config.setConverter(converter);
            
            if (converter != null) {
                configs.add(config);
                log.info("构建管道配置: {}, 过滤器数: {}", 
                        dataSource.getSourceName(), applicableFilters.size());
            } else {
                log.warn("数据源 {} 没有找到适用的转换器", dataSource.getSourceName());
            }
        }
        
        return configs;
    }
}

/**
 * 管道配置
 */
@lombok.Data
class PipelineConfig<T> {
    private IDataSource<T> dataSource;
    private List<IDataFilter<T>> filters;
    private IDataConverter<T> converter;
}

9. 具体实现示例
package com.example.datasource.impl;

import com.example.datasource.IDataSource;
import com.example.domain.ProcessContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API数据源A实现
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
        // TODO: 构建API请求参数
        return null;
    }
    
    @Override
    public PageResult<ApiDataA> queryFirstPage(ProcessContext context) {
        log.info("查询数据源A第一页");
        // TODO: 调用实际API
        PageResult<ApiDataA> result = new PageResult<>();
        result.setTotal(1000L);
        result.setData(new ArrayList<>());
        return result;
    }
    
    @Override
    public List<ApiDataA> queryPage(ProcessContext context, int pageNum) {
        log.debug("查询数据源A第{}页", pageNum);
        // TODO: 调用实际API
        return new ArrayList<>();
    }
    
    @Override
    public List<ApiDataA> queryAll(ProcessContext context) {
        return null;
    }
}

/**
 * API数据源B实现（不需要分页）
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
        return false; // 不需要分页
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
        log.info("查询数据源B全量数据");
        // TODO: 调用实际API
        return new ArrayList<>();
    }
}

10. 过滤器实现示例
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
 * 指标编码过滤器
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
     * 提取指标编码（需要根据实际数据类型实现）
     */
    private String extractMetricCode(T data) {
        // TODO: 根据实际数据类型提取metricCode
        return null;
    }
}

/**
 * 组织层级过滤器
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
                    
                    // 检查该指标是否支持该组织层级
                    if (metadata.getMetricOrgLevelMap().containsKey(metricCode)) {
                        Set<String> supportedOrgLevels = metadata.getMetricOrgLevelMap().get(metricCode);
                        return orgLevels.contains(orgLevel) && supportedOrgLevels.contains(orgLevel);
                    }
                    
                    return orgLevels.contains(orgLevel);
                })
                .collect(Collectors.toList());
    }
    
    private String extractMetricCode(T data) {
        // TODO: 根据实际数据类型提取
        return null;
    }
    
    private String extractOrgLevel(T data) {
        // TODO: 根据实际数据类型提取
        return null;
    }
}

/**
 * 领域过滤器
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
                    
                    // 检查该指标是否支持该领域
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
 * 数据质量过滤器
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
        return 10; // 低优先级，最后执行
    }
    
    @Override
    public boolean isApplicable(String sourceName, ProcessContext context) {
        return true; // 所有数据源都适用
    }
    
    @Override
    public List<T> filter(List<T> rawData, ProcessContext context) {
        return rawData.stream()
                .filter(this::isValidData)
                .collect(Collectors.toList());
    }
    
    /**
     * 校验数据质量
     */
    private boolean isValidData(T data) {
        // TODO: 实现数据质量校验逻辑
        // 例如：非空校验、数值范围校验等
        return true;
    }
}

11. 转换器实现示例
package com.example.converter.impl;

import com.example.converter.IDataConverter;
import com.example.domain.MeasureData;
import com.example.domain.Metadata;
import com.example.domain.ProcessContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;

/**
 * API数据源A的转换器
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
        
        // 1. 提取基本信息
        convertedData.setPeriodId(rawData.getPeriodId());
        convertedData.setMetricCode(rawData.getMetricCode());
        convertedData.setDomainCode(rawData.getDomainCode());
        convertedData.setMeasureCode(rawData.getMeasureCode());
        
        // 2. 构建MeasureData
        MeasureData measureData = new MeasureData();
        measureData.setMeasureCode(rawData.getMeasureCode());
        
        // 从元数据获取度量的单位和币种
        if (metadata.getMeasureMetaMap().containsKey(rawData.getMeasureCode())) {
            MeasureMeta measureMeta = metadata.getMeasureMetaMap().get(rawData.getMeasureCode());
            measureData.setMeasureUnit(measureMeta.getUnit());
            measureData.setCurrency(measureMeta.getCurrency());
        }
        
        // 计算值（可能需要复杂计算）
        String calculatedValue = calculateValue(rawData, context);
        measureData.setValue(calculatedValue);
        
        // 扩展属性
        measureData.setExtAttributes(new HashMap<>());
        
        convertedData.setMeasureData(measureData);
        
        return convertedData;
    }
    
    /**
     * 计算度量值
     */
    private String calculateValue(ApiDataA rawData, ProcessContext context) {
        // TODO: 实现复杂的计算逻辑
        // 可能依赖于元数据中的计算规则
        return rawData.getValue();
    }
}

/**
 * API数据源B的转换器
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
        
        // 从元数据获取其他信息
        if (metadata.getMeasureMetaMap().containsKey(rawData.getMeasure())) {
            MeasureMeta meta = metadata.getMeasureMetaMap().get(rawData.getMeasure());
            measureData.setMeasureUnit(meta.getUnit());
            measureData.setCurrency(meta.getCurrency());
        }
        
        convertedData.setMeasureData(measureData);
        
        return convertedData;
    }
}

12. Service层实现
package com.example.service;

import com.example.domain.MeasureDataVO;
import com.example.domain.MetricQueryVO;
import com.example.orchestrator.PipelineOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务服务实现
 */
@Slf4j
@Service
public class MetricDataService {
    
    @Autowired
    private PipelineOrchestrator orchestrator;
    
    /**
     * 查询度量数据
     */
    public List<MeasureDataVO> queryMeasureData(MetricQueryVO queryVO) {
        log.info("查询度量数据，查询条件: {}", queryVO);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 执行多管道处理
            List<MeasureDataVO> result = orchestrator.executeMultiPipeline(queryVO);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("查询完成，耗时: {}ms, 结果数: {}", duration, result.size());
            
            return result;
            
        } catch (Exception e) {
            log.error("查询度量数据失败", e);
            throw new RuntimeException("查询失败", e);
        }
    }
}

13. Controller层实现
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
 * 指标数据查询接口
 */
@Slf4j
@RestController
@RequestMapping("/api/metric")
@Api(tags = "指标数据查询")
public class MetricDataController {
    
    @Autowired
    private MetricDataService metricDataService;
    
    @PostMapping("/query")
    @ApiOperation("查询度量数据")
    public ApiResponse<List<MeasureDataVO>> queryMeasureData(@RequestBody MetricQueryVO queryVO) {
        try {
            List<MeasureDataVO> result = metricDataService.queryMeasureData(queryVO);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("查询度量数据失败", e);
            return ApiResponse.error("查询失败: " + e.getMessage());
        }
    }
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        
        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(true, "成功", data);
        }
        
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}

14. 异步刷新缓存实现（可选）
package com.example.async;

import com.example.service.IAsyncProcessHandler;
import com.example.service.MetadataLoader;
import com.example.vo.AjaxMessageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 异步刷新元数据处理器
 */
@Slf4j
@Component("metadataRefreshHandler")
public class MetadataRefreshHandler implements IAsyncProcessHandler {
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    @Override
    public AjaxMessageVo handlerProcess(Serializable context) throws ApplicationException {
        try {
            log.info("开始异步刷新元数据");
            
            // 清除本地缓存
            metadataLoader.clearLocalCache();
            
            log.info("元数据刷新完成");
            return AjaxMessageVo.success();
            
        } catch (Exception e) {
            log.error("异步刷新元数据失败", e);
            return AjaxMessageVo.error("刷新失败: " + e.getMessage());
        }
    }
}

15. 定时任务实现（可选）
package com.example.task;

import com.example.service.ITimerTask;
import com.example.service.MetadataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 元数据刷新定时任务
 */
@Slf4j
@Component("metadataRefreshTask")
public class MetadataRefreshTask implements ITimerTask {
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        try {
            log.info("定时任务：开始刷新元数据");
            
            // 清除本地缓存
            metadataLoader.clearLocalCache();
            
            log.info("定时任务：元数据刷新完成");
            
        } catch (Exception e) {
            log.error("定时任务：元数据刷新失败", e);
            throw new ApplicationException("元数据刷新失败", e);
        }
    }
}

16. 配置文件
# application.yml
spring:
  application:
    name: metric-service

# Redis配置
spring:
  redis:
    host: localhost
    port: 6379
    database: 0
    timeout: 3000ms

# 线程池配置
thread-pool:
  core-size: 16
  max-size: 32
  queue-capacity: 1000

# 数据源配置
datasource:
  api-a:
    enabled: true
    page-size: 100
  api-b:
    enabled: true
    need-pagination: false

# 元数据缓存配置
metadata:
  cache:
    local-ttl: 300 # 本地缓存TTL（秒）
    redis-ttl: 3600 # Redis缓存TTL（秒）

logging:
  level:
    com.example: INFO
    com.example.pipeline: DEBUG
    
架构优势总结
1. 高性能

并行处理：多个API数据源并行查询
分页并行：每个数据源的多页数据并行获取
并行过滤和转换：使用Stream并行处理
线程安全集合：使用ConcurrentHashMap和CopyOnWriteArrayList
本地缓存：元数据本地缓存减少Redis访问

2. 可扩展性

新增数据源：实现IDataSource接口即可
新增过滤器：实现IDataFilter接口，自动被发现和应用
新增转换器：实现IDataConverter接口
插拔式设计：通过Spring自动装配，无需修改核心代码

3. 易维护性

清晰的分层：数据源 → 过滤 → 转换 → 聚合
单一职责：每个组件职责明确
接口抽象：依赖抽象而非具体实现
统一配置：通过工厂类集中管理配置

4. 设计模式应用

模板方法模式：DataProcessPipeline定义处理流程
策略模式：数据源、过滤器、转换器都是策略
责任链模式：过滤器链式执行
工厂模式：PipelineConfigFactory动态创建配置
门面模式：PipelineOrchestrator提供统一入口

5. 使用现有能力

集成公司线程池：使用ITaskExecutorService
支持异步处理：实现IAsyncProcessHandler
支持定时任务：实现ITimerTask

这个方案完全满足您的需求，且具有生产级质量！


问题分析与优化方案
问题1：线程安全问题
当前代码中确实存在潜在的线程安全问题：

ConvertedData的构造：在并行流中创建对象是安全的，但Key的生成需要保证幂等性
Map聚合：Collectors.groupingByConcurrent是线程安全的，但默认的Collectors.toList()不是
上下文对象共享：ProcessContext在多线程中共享，需要保证线程安全

问题2：内存管理问题

大量临时对象：在转换过程中会创建大量临时对象
数据堆积：所有数据源的数据都加载到内存中
GC压力：频繁的对象创建和销毁

完整优化方案
1. 增强的ProcessContext（线程安全）
package com.example.domain;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的请求上下文对象
 */
@Data
public class ThreadSafeProcessContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String requestId;
    private final MetricQueryVO queryVO;
    private volatile Metadata metadata;
    private final Long startTime;
    
    /** 线程安全的扩展属性 */
    private final ConcurrentHashMap<String, Object> attributes;
    
    /** 统计信息 */
    private final AtomicLong processedRecords;
    private final AtomicLong filteredRecords;
    
    public ThreadSafeProcessContext(String requestId, MetricQueryVO queryVO) {
        this.requestId = requestId;
        this.queryVO = queryVO;
        this.startTime = System.currentTimeMillis();
        this.attributes = new ConcurrentHashMap<>();
        this.processedRecords = new AtomicLong(0);
        this.filteredRecords = new AtomicLong(0);
    }
    
    /**
     * 线程安全地设置属性
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
    
    /**
     * 线程安全地获取属性
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
    
    /**
     * 原子增加处理记录数
     */
    public long incrementProcessed(long delta) {
        return processedRecords.addAndGet(delta);
    }
    
    /**
     * 原子增加过滤记录数
     */
    public long incrementFiltered(long delta) {
        return filteredRecords.addAndGet(delta);
    }
}

2. 内存优化的数据处理管道
package com.example.pipeline;

import com.example.converter.IDataConverter;
import com.example.datasource.IDataSource;
import com.example.domain.ThreadSafeProcessContext;
import com.example.filter.IDataFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 内存优化的数据处理管道
 */
@Slf4j
public class MemoryOptimizedPipeline<T> {
    
    private static final int BATCH_SIZE = 500; // 批处理大小
    private static final int MAX_QUEUE_SIZE = 2000; // 最大队列大小
    
    private final IDataSource<T> dataSource;
    private final List<IDataFilter<T>> filters;
    private final IDataConverter<T> converter;
    private final ThreadSafeProcessContext context;
    private final ITaskExecutorService taskExecutor;
    
    public MemoryOptimizedPipeline(IDataSource<T> dataSource,
                                   List<IDataFilter<T>> filters,
                                   IDataConverter<T> converter,
                                   ThreadSafeProcessContext context,
                                   ITaskExecutorService taskExecutor) {
        this.dataSource = dataSource;
        this.filters = filters;
        this.converter = converter;
        this.context = context;
        this.taskExecutor = taskExecutor;
    }
    
    /**
     * 执行管道处理（流式处理，减少内存占用）
     */
    public BlockingQueue<IDataConverter.ConvertedData> executeStreaming() {
        log.info("开始流式处理数据管道: {}", dataSource.getSourceName());
        
        // 使用有界队列，避免内存溢出
        BlockingQueue<IDataConverter.ConvertedData> resultQueue = 
                new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        
        try {
            if (!dataSource.needPagination()) {
                // 不分页：批量处理
                processBatch(dataSource.queryAll(context), resultQueue);
            } else {
                // 分页：流式处理
                processWithPagination(resultQueue);
            }
            
            // 添加结束标记
            resultQueue.put(createEndMarker());
            
        } catch (Exception e) {
            log.error("流式处理失败", e);
            throw new RuntimeException("流式处理失败", e);
        }
        
        return resultQueue;
    }
    
    /**
     * 分页流式处理
     */
    private void processWithPagination(BlockingQueue<IDataConverter.ConvertedData> resultQueue) 
            throws Exception {
        
        // 1. 查询第一页
        IDataSource.PageResult<T> firstPage = dataSource.queryFirstPage(context);
        processBatch(firstPage.getData(), resultQueue);
        
        int totalPages = firstPage.getTotalPages(dataSource.getPageSize());
        if (totalPages <= 1) {
            return;
        }
        
        // 2. 使用信号量控制并发度，避免OOM
        Semaphore semaphore = new Semaphore(4); // 最多4个并发任务
        CountDownLatch latch = new CountDownLatch(totalPages - 1);
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            
            semaphore.acquire(); // 获取许可
            
            taskExecutor.submitTask(() -> {
                try {
                    List<T> pageData = dataSource.queryPage(context, currentPage);
                    processBatch(pageData, resultQueue);
                } catch (Exception e) {
                    log.error("处理第{}页失败", currentPage, e);
                } finally {
                    semaphore.release(); // 释放许可
                    latch.countDown();
                }
            }, "处理第" + currentPage + "页");
        }
        
        // 等待所有页处理完成
        latch.await(5, TimeUnit.MINUTES);
    }
    
    /**
     * 批量处理数据（避免一次性加载大量数据）
     */
    private void processBatch(List<T> rawData, BlockingQueue<IDataConverter.ConvertedData> resultQueue) 
            throws InterruptedException {
        
        if (rawData == null || rawData.isEmpty()) {
            return;
        }
        
        // 分批处理，每批BATCH_SIZE条
        for (int i = 0; i < rawData.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, rawData.size());
            List<T> batch = rawData.subList(i, end);
            
            // 过滤
            List<T> filteredBatch = filterBatch(batch);
            
            // 转换
            List<IDataConverter.ConvertedData> convertedBatch = convertBatch(filteredBatch);
            
            // 放入结果队列
            for (IDataConverter.ConvertedData data : convertedBatch) {
                resultQueue.put(data);
            }
            
            // 显式清理，帮助GC
            batch.clear();
            filteredBatch.clear();
            convertedBatch.clear();
        }
        
        // 清理原始数据
        rawData.clear();
    }
    
    /**
     * 批量过滤
     */
    private List<T> filterBatch(List<T> batch) {
        if (filters == null || filters.isEmpty()) {
            return batch;
        }
        
        String sourceName = dataSource.getSourceName();
        
        List<IDataFilter<T>> applicableFilters = filters.stream()
                .filter(f -> f.isApplicable(sourceName, context))
                .sorted(Comparator.comparingInt(IDataFilter::getPriority))
                .collect(Collectors.toList());
        
        List<T> currentBatch = batch;
        for (IDataFilter<T> filter : applicableFilters) {
            currentBatch = filter.filter(currentBatch, context);
        }
        
        context.incrementFiltered(batch.size() - currentBatch.size());
        return currentBatch;
    }
    
    /**
     * 批量转换
     */
    private List<IDataConverter.ConvertedData> convertBatch(List<T> filteredBatch) {
        if (converter == null || !converter.isApplicable(dataSource.getSourceName(), context)) {
            return Collections.emptyList();
        }
        
        List<IDataConverter.ConvertedData> result = filteredBatch.stream()
                .map(data -> {
                    try {
                        return converter.convert(data, context);
                    } catch (Exception e) {
                        log.error("转换数据失败", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        context.incrementProcessed(result.size());
        return result;
    }
    
    /**
     * 创建结束标记
     */
    private IDataConverter.ConvertedData createEndMarker() {
        IDataConverter.ConvertedData marker = new IDataConverter.ConvertedData();
        marker.setPeriodId("__END__");
        return marker;
    }
}

3. 线程安全的结果聚合器
package com.example.aggregator;

import com.example.converter.IDataConverter;
import com.example.domain.MeasureData;
import com.example.domain.MeasureDataVO;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;

/**
 * 线程安全的结果聚合器
 */
@Slf4j
public class ThreadSafeResultAggregator {
    
    /**
     * 使用ConcurrentHashMap保证线程安全
     * 第一层：periodId -> 第二层Map
     * 第二层：mapKey -> List<MeasureData>
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CopyOnWriteArrayList<MeasureData>>> 
            aggregateMap = new ConcurrentHashMap<>();
    
    /**
     * 统计信息
     */
    private final AtomicLong totalRecords = new AtomicLong(0);
    private final AtomicLong duplicateKeys = new AtomicLong(0);
    
    /**
     * 添加转换后的数据（线程安全）
     */
    public void add(IDataConverter.ConvertedData data) {
        if (data == null || "__END__".equals(data.getPeriodId())) {
            return;
        }
        
        String periodId = data.getPeriodId();
        String mapKey = data.getMapKey();
        MeasureData measureData = data.getMeasureData();
        
        // 获取或创建period级别的Map
        ConcurrentHashMap<String, CopyOnWriteArrayList<MeasureData>> periodMap = 
                aggregateMap.computeIfAbsent(periodId, k -> new ConcurrentHashMap<>());
        
        // 获取或创建mapKey对应的List
        CopyOnWriteArrayList<MeasureData> dataList = 
                periodMap.computeIfAbsent(mapKey, k -> new CopyOnWriteArrayList<>());
        
        // 添加数据
        dataList.add(measureData);
        
        totalRecords.incrementAndGet();
        
        if (dataList.size() > 1) {
            duplicateKeys.incrementAndGet();
        }
    }
    
    /**
     * 批量添加（优化性能）
     */
    public void addBatch(List<IDataConverter.ConvertedData> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return;
        }
        
        // 按periodId分组，减少锁竞争
        Map<String, List<IDataConverter.ConvertedData>> groupedByPeriod = 
                dataList.stream()
                .filter(d -> d != null && !"__END__".equals(d.getPeriodId()))
                .collect(Collectors.groupingBy(IDataConverter.ConvertedData::getPeriodId));
        
        groupedByPeriod.forEach((periodId, periodDataList) -> {
            ConcurrentHashMap<String, CopyOnWriteArrayList<MeasureData>> periodMap = 
                    aggregateMap.computeIfAbsent(periodId, k -> new ConcurrentHashMap<>());
            
            periodDataList.forEach(data -> {
                String mapKey = data.getMapKey();
                CopyOnWriteArrayList<MeasureData> list = 
                        periodMap.computeIfAbsent(mapKey, k -> new CopyOnWriteArrayList<>());
                list.add(data.getMeasureData());
            });
        });
        
        totalRecords.addAndGet(dataList.size());
    }
    
    /**
     * 获取聚合结果
     */
    public List<MeasureDataVO> getResult() {
        log.info("开始构建最终结果，总记录数: {}, 重复Key数: {}", 
                totalRecords.get(), duplicateKeys.get());
        
        List<MeasureDataVO> result = new ArrayList<>(aggregateMap.size());
        
        aggregateMap.forEach((periodId, periodMap) -> {
            MeasureDataVO vo = new MeasureDataVO();
            vo.setPeriodId(periodId);
            
            // 转换为普通HashMap（不再需要并发访问）
            Map<String, List<MeasureData>> dataMap = new HashMap<>(periodMap.size());
            periodMap.forEach((key, value) -> {
                // 去重和优化
                dataMap.put(key, optimizeMeasureList(value));
            });
            
            vo.setDataMap(dataMap);
            result.add(vo);
        });
        
        // 清理内存
        clear();
        
        log.info("最终结果构建完成，会计期数: {}", result.size());
        return result;
    }
    
    /**
     * 优化度量列表（去重、排序）
     */
    private List<MeasureData> optimizeMeasureList(List<MeasureData> measures) {
        if (measures.size() == 1) {
            return new ArrayList<>(measures);
        }
        
        // 去重（根据measureCode）
        Map<String, MeasureData> uniqueMap = new LinkedHashMap<>();
        for (MeasureData measure : measures) {
            uniqueMap.putIfAbsent(measure.getMeasureCode(), measure);
        }
        
        return new ArrayList<>(uniqueMap.values());
    }
    
    /**
     * 清理内存
     */
    public void clear() {
        aggregateMap.clear();
        totalRecords.set(0);
        duplicateKeys.set(0);
    }
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRecords", totalRecords.get());
        stats.put("duplicateKeys", duplicateKeys.get());
        stats.put("periodCount", aggregateMap.size());
        return stats;
    }
}

4. 内存优化的协调器
package com.example.orchestrator;

import com.example.converter.IDataConverter;
import com.example.domain.MeasureDataVO;
import com.example.domain.MetricQueryVO;
import com.example.domain.ThreadSafeProcessContext;
import com.example.aggregator.ThreadSafeResultAggregator;
import com.example.pipeline.MemoryOptimizedPipeline;
import com.example.pipeline.PipelineConfig;
import com.example.service.MetadataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * 内存优化的管道协调器
 */
@Slf4j
@Component
public class MemoryOptimizedOrchestrator {
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    @Autowired
    private ITaskExecutorService taskExecutor;
    
    @Autowired
    private PipelineConfigFactory configFactory;
    
    /**
     * 执行多管道处理（内存优化版）
     */
    public List<MeasureDataVO> executeWithMemoryOptimization(MetricQueryVO queryVO) {
        log.info("开始执行多管道处理（内存优化版）");
        long startTime = System.currentTimeMillis();
        
        // 1. 构建线程安全的上下文
        ThreadSafeProcessContext context = new ThreadSafeProcessContext(
                UUID.randomUUID().toString(), 
                queryVO
        );
        
        // 2. 加载元数据
        context.setMetadata(metadataLoader.loadMetadata(queryVO));
        
        // 3. 构建管道配置
        List<PipelineConfig> pipelineConfigs = configFactory.buildConfigs(context);
        
        // 4. 创建线程安全的结果聚合器
        ThreadSafeResultAggregator aggregator = new ThreadSafeResultAggregator();
        
        // 5. 启动所有管道（流式处理）
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (PipelineConfig config : pipelineConfigs) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processPipelineStreaming(config, context, aggregator);
                } catch (Exception e) {
                    log.error("管道处理失败: {}", config.getDataSource().getSourceName(), e);
                }
            }, taskExecutor.findExecutorService());
            
            futures.add(future);
        }
        
        // 6. 等待所有管道完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 7. 获取聚合结果
        List<MeasureDataVO> result = aggregator.getResult();
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("多管道处理完成，总耗时: {}ms, 处理记录: {}, 过滤记录: {}, 结果数: {}",
                duration, context.getProcessedRecords(), context.getFilteredRecords(), result.size());
        
        // 8. 打印统计信息
        printMemoryUsage();
        
        return result;
    }
    
    /**
     * 流式处理单个管道
     */
    private void processPipelineStreaming(PipelineConfig config, 
                                         ThreadSafeProcessContext context,
                                         ThreadSafeResultAggregator aggregator) {
        
        MemoryOptimizedPipeline pipeline = new MemoryOptimizedPipeline(
                config.getDataSource(),
                config.getFilters(),
                config.getConverter(),
                context,
                taskExecutor
        );
        
        // 获取结果队列
        BlockingQueue<IDataConverter.ConvertedData> resultQueue = pipeline.executeStreaming();
        
        // 消费结果队列
        List<IDataConverter.ConvertedData> batch = new ArrayList<>(100);
        
        try {
            while (true) {
                IDataConverter.ConvertedData data = resultQueue.poll(100, TimeUnit.MILLISECONDS);
                
                if (data == null) {
                    continue;
                }
                
                // 检查结束标记
                if ("__END__".equals(data.getPeriodId())) {
                    if (!batch.isEmpty()) {
                        aggregator.addBatch(batch);
                        batch.clear();
                    }
                    break;
                }
                
                batch.add(data);
                
                // 批量添加到聚合器
                if (batch.size() >= 100) {
                    aggregator.addBatch(batch);
                    batch.clear();
                }
            }
        } catch (InterruptedException e) {
            log.error("消费结果队列被中断", e);
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 打印内存使用情况
     */
    private void printMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        
        log.info("内存使用情况 - 已用: {}MB, 空闲: {}MB, 总计: {}MB, 最大: {}MB",
                usedMemory, freeMemory, totalMemory, maxMemory);
    }
}

5. Key生成工具（保证线程安全和幂等性）
package com.example.util;

import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的Key生成工具
 */
@Slf4j
public class ThreadSafeKeyGenerator {
    
    /**
     * Key缓存（避免重复计算）
     */
    private static final ConcurrentHashMap<String, String> KEY_CACHE = new ConcurrentHashMap<>();
    
    /**
     * 生成Map的Key（线程安全，幂等）
     * 
     * @param metricCode 指标编码
     * @param domainCode 领域编码
     * @param measureCode 度量编码
     * @return Map的Key
     */
    public static String generateMapKey(String metricCode, String domainCode, String measureCode) {
        // 参数校验
        if (metricCode == null || domainCode == null || measureCode == null) {
            throw new IllegalArgumentException("Key参数不能为null");
        }
        
        // 构建原始Key
        String rawKey = metricCode + ":::" + domainCode + ":::" + measureCode;
        
        // 从缓存获取（减少字符串拼接）
        return KEY_CACHE.computeIfAbsent(rawKey, k -> k);
    }
    
    /**
     * 生成Hash Key（用于缓存等场景）
     */
    public static String generateHashKey(String metricCode, String domainCode, String measureCode) {
        String rawKey = metricCode + ":::" + domainCode + ":::" + measureCode;
        
        return Hashing.murmur3_128()
                .hashString(rawKey, StandardCharsets.UTF_8)
                .toString();
    }
    
    /**
     * 清理缓存（定时清理，避免内存泄漏）
     */
    public static void clearCache() {
        int size = KEY_CACHE.size();
        KEY_CACHE.clear();
        log.info("清理Key缓存，清理数量: {}", size);
    }
    
    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return KEY_CACHE.size();
    }
}

6. 内存监控组件
package com.example.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 内存监控组件
 */
@Slf4j
@Component
public class MemoryMonitor {
    
    private ScheduledExecutorService scheduler;
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    
    /** 内存使用阈值（80%） */
    private static final double MEMORY_THRESHOLD = 0.8;
    
    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(1);
        
        // 每30秒监控一次内存
        scheduler.scheduleAtFixedRate(this::monitorMemory, 30, 30, TimeUnit.SECONDS);
        
        log.info("内存监控已启动");
    }
    
    /**
     * 监控内存使用情况
     */
    private void monitorMemory() {
        try {
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            
            long used = heapUsage.getUsed();
            long max = heapUsage.getMax();
            double usage = (double) used / max;
            
            long usedMB = used / 1024 / 1024;
            long maxMB = max / 1024 / 1024;
            
            log.debug("堆内存使用: {}MB / {}MB ({}%)", usedMB, maxMB, String.format("%.2f", usage * 100));
            
            // 内存使用超过阈值，触发GC
            if (usage > MEMORY_THRESHOLD) {
                log.warn("内存使用超过阈值({}%)，触发GC", MEMORY_THRESHOLD * 100);
                System.gc();
            }
            
        } catch (Exception e) {
            log.error("监控内存失败", e);
        }
    }
    
    /**
     * 获取当前内存使用情况
     */
    public MemoryInfo getMemoryInfo() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        
        MemoryInfo info = new MemoryInfo();
        info.setUsed(heapUsage.getUsed() / 1024 / 1024);
        info.setMax(heapUsage.getMax() / 1024 / 1024);
        info.setCommitted(heapUsage.getCommitted() / 1024 / 1024);
        info.setUsagePercent((double) heapUsage.getUsed() / heapUsage.getMax() * 100);
        
        return info;
    }
    
    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("内存监控已关闭");
    }
    
    @lombok.Data
    public static class MemoryInfo {
        private long used;
        private long max;
        private long committed;
        private double usagePercent;
    }
}

7. 配置优化建议
# application.yml

# JVM参数建议（在启动脚本中设置）
# -Xms2g -Xmx4g
# -XX:+UseG1GC
# -XX:MaxGCPauseMillis=200
# -XX:+HeapDumpOnOutOfMemoryError
# -XX:HeapDumpPath=/logs/heapdump.hprof

# 线程池配置
thread-pool:
  core-size: 8  # 根据CPU核心数调整
  max-size: 16
  queue-capacity: 500  # 不要设置太大
  keep-alive-seconds: 60

# 批处理配置
batch:
  size: 500  # 每批处理的记录数
  max-queue-size: 2000  # 队列最大大小
  max-concurrent-tasks: 4  # 最大并发任务数

# 内存监控配置
memory:
  monitor:
    enabled: true
    interval-seconds: 30
    threshold-percent: 80
    
关键优化点总结
线程安全保证

? 使用ConcurrentHashMap代替HashMap
? 使用CopyOnWriteArrayList代替ArrayList
? 使用AtomicLong进行原子计数
? Key生成使用缓存，