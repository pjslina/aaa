作为资深Java技术专家，我来为您设计一个高性能、高扩展性、优雅的解决方案。这个方案将使用模板方法模式 + 策略模式 + 责任链模式 + 并行流编程。
完整架构设计
1. 核心领域模型定义
package com.example.domain;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * 请求上下文（全局共享）
 */
@Data
public class RequestContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;
    private Map<String, Object> params;
    private Long userId;
    private String orgCode;
    // 其他上下文信息
}

/**
 * 元数据（全局共享，只加载一次）
 */
@Data
public class Metadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Map<String, Object> metricMetadata;
    private Map<String, Object> domainMetadata;
    private Map<String, Object> orgMetadata;
    // 其他元数据
}

/**
 * 分页请求参数
 */
@Data
public class PageRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer pageNum = 1;
    private Integer pageSize = 100;
    private Map<String, Object> queryParams;
}

/**
 * 分页响应结果
 */
@Data
public class PageResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long total;
    private Integer pageNum;
    private Integer pageSize;
    private java.util.List<T> data;
    
    public int getTotalPages() {
        if (total == null || pageSize == null || pageSize == 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }
}

/**
 * 过滤后的分组数据
 */
@Data
public class FilteredGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String metricCode;
    private String domainCode;
    private String orgCode;
    private Object rawData;
    // 其他字段
    
    /**
     * 获取分组Key
     */
    public String getGroupKey() {
        return metricCode + ":::" + domainCode + ":::" + orgCode;
    }
}

/**
 * 计算结果对象
 */
@Data
public class CalculatedResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String key;
    private Object value;
    private Map<String, Object> attributes;
}

2. 数据源抽象接口
package com.example.datasource;

import com.example.domain.PageRequest;
import com.example.domain.PageResponse;
import com.example.domain.RequestContext;
import com.example.domain.Metadata;

/**
 * 数据源接口
 * 抽象B、B1、B2等不同数据源
 */
public interface DataSource<T> {
    
    /**
     * 获取数据源类型
     */
    String getSourceType();
    
    /**
     * 是否需要分页
     */
    default boolean needPagination() {
        return true;
    }
    
    /**
     * 构建请求参数
     */
    PageRequest buildRequest(RequestContext context, Metadata metadata, int pageNum);
    
    /**
     * 执行查询（分页）
     */
    PageResponse<T> fetchData(PageRequest request);
    
    /**
     * 执行查询（一次性全量，不分页）
     */
    default java.util.List<T> fetchAllData(RequestContext context, Metadata metadata) {
        PageRequest request = buildRequest(context, metadata, 1);
        PageResponse<T> response = fetchData(request);
        return response.getData();
    }
    
    /**
     * 获取优先级（用于并行执行时的排序）
     */
    default int getPriority() {
        return 0;
    }
}

3. 过滤器抽象
package com.example.filter;

import com.example.domain.RequestContext;
import com.example.domain.Metadata;
import java.util.List;

/**
 * 前置过滤器接口
 */
public interface PreFilter<T, R> {
    
    /**
     * 过滤器名称
     */
    String getName();
    
    /**
     * 优先级（数字越小优先级越高）
     */
    default int getOrder() {
        return 0;
    }
    
    /**
     * 是否适用
     */
    boolean isApplicable(String sourceType, RequestContext context);
    
    /**
     * 执行过滤
     */
    List<R> filter(List<T> rawData, RequestContext context, Metadata metadata);
}

/**
 * 后置处理器接口
 */
public interface PostProcessor<T, R> {
    
    /**
     * 处理器名称
     */
    String getName();
    
    /**
     * 是否适用
     */
    boolean isApplicable(String sourceType, RequestContext context);
    
    /**
     * 执行处理
     */
    R process(T input, RequestContext context, Metadata metadata);
}

4. 数据处理管道（Pipeline）
package com.example.pipeline;

import com.example.datasource.DataSource;
import com.example.domain.*;
import com.example.filter.PreFilter;
import com.example.filter.PostProcessor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 数据处理管道配置
 */
@Data
public class PipelineConfig<T, F, R> {
    
    /**
     * 数据源
     */
    private DataSource<T> dataSource;
    
    /**
     * 前置过滤器链
     */
    private List<PreFilter<T, F>> preFilters;
    
    /**
     * 后置处理器
     */
    private PostProcessor<F, R> postProcessor;
    
    /**
     * 结果合并器
     */
    private ResultMerger<R> resultMerger;
}

/**
 * 数据处理管道
 * 负责单个数据源的完整处理流程
 */
@Slf4j
public class DataProcessPipeline<T, F, R> {
    
    private final PipelineConfig<T, F, R> config;
    private final RequestContext context;
    private final Metadata metadata;
    private final ExecutorService executorService;
    
    public DataProcessPipeline(PipelineConfig<T, F, R> config,
                               RequestContext context,
                               Metadata metadata,
                               ExecutorService executorService) {
        this.config = config;
        this.context = context;
        this.metadata = metadata;
        this.executorService = executorService;
    }
    
    /**
     * 执行管道处理
     */
    public Map<String, R> execute() {
        log.info("开始执行数据管道: {}", config.getDataSource().getSourceType());
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 获取所有原始数据
            List<T> allRawData = fetchAllData();
            
            // 2. 前置过滤
            List<F> filteredData = applyPreFilters(allRawData);
            
            // 3. 后置处理
            Map<String, R> resultMap = applyPostProcessor(filteredData);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("数据管道执行完成: {}, 耗时: {}ms, 结果数: {}", 
                    config.getDataSource().getSourceType(), duration, resultMap.size());
            
            return resultMap;
            
        } catch (Exception e) {
            log.error("数据管道执行失败: {}", config.getDataSource().getSourceType(), e);
            throw new RuntimeException("数据管道执行失败", e);
        }
    }
    
    /**
     * 获取所有数据（支持分页和非分页）
     */
    private List<T> fetchAllData() {
        DataSource<T> dataSource = config.getDataSource();
        
        // 不需要分页，直接获取全部
        if (!dataSource.needPagination()) {
            log.info("数据源不需要分页: {}", dataSource.getSourceType());
            return dataSource.fetchAllData(context, metadata);
        }
        
        // 需要分页
        return fetchDataWithPagination();
    }
    
    /**
     * 分页获取数据
     */
    private List<T> fetchDataWithPagination() {
        DataSource<T> dataSource = config.getDataSource();
        
        // 1. 查询第一页
        PageRequest firstPageRequest = dataSource.buildRequest(context, metadata, 1);
        PageResponse<T> firstPage = dataSource.fetchData(firstPageRequest);
        
        List<T> allData = new CopyOnWriteArrayList<>(firstPage.getData());
        
        int totalPages = firstPage.getTotalPages();
        log.info("数据源: {}, 总页数: {}, 总记录数: {}", 
                dataSource.getSourceType(), totalPages, firstPage.getTotal());
        
        if (totalPages <= 1) {
            return allData;
        }
        
        // 2. 并行查询剩余页
        List<CompletableFuture<List<T>>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            
            CompletableFuture<List<T>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    PageRequest request = dataSource.buildRequest(context, metadata, currentPage);
                    PageResponse<T> response = dataSource.fetchData(request);
                    log.debug("查询第{}页完成，数据量: {}", currentPage, response.getData().size());
                    return response.getData();
                } catch (Exception e) {
                    log.error("查询第{}页失败", currentPage, e);
                    return Collections.emptyList();
                }
            }, executorService);
            
            futures.add(future);
        }
        
        // 3. 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 4. 收集所有结果
        for (CompletableFuture<List<T>> future : futures) {
            try {
                List<T> pageData = future.get();
                allData.addAll(pageData);
            } catch (Exception e) {
                log.error("获取分页数据失败", e);
            }
        }
        
        log.info("分页查询完成，共获取 {} 条数据", allData.size());
        return allData;
    }
    
    /**
     * 应用前置过滤器
     */
    private List<F> applyPreFilters(List<T> rawData) {
        if (config.getPreFilters() == null || config.getPreFilters().isEmpty()) {
            log.warn("没有配置前置过滤器");
            return Collections.emptyList();
        }
        
        List<F> result = Collections.emptyList();
        String sourceType = config.getDataSource().getSourceType();
        
        // 按优先级排序
        List<PreFilter<T, F>> sortedFilters = config.getPreFilters().stream()
                .filter(filter -> filter.isApplicable(sourceType, context))
                .sorted(Comparator.comparingInt(PreFilter::getOrder))
                .collect(Collectors.toList());
        
        // 依次执行过滤器（责任链模式）
        List currentData = rawData;
        for (PreFilter filter : sortedFilters) {
            log.debug("执行前置过滤器: {}", filter.getName());
            currentData = filter.filter(currentData, context, metadata);
            log.debug("过滤后数据量: {}", currentData.size());
        }
        
        return (List<F>) currentData;
    }
    
    /**
     * 应用后置处理器
     */
    private Map<String, R> applyPostProcessor(List<F> filteredData) {
        if (config.getPostProcessor() == null) {
            log.warn("没有配置后置处理器");
            return Collections.emptyMap();
        }
        
        String sourceType = config.getDataSource().getSourceType();
        PostProcessor<F, R> processor = config.getPostProcessor();
        
        if (!processor.isApplicable(sourceType, context)) {
            log.warn("后置处理器不适用: {}", processor.getName());
            return Collections.emptyMap();
        }
        
        // 并行处理（如果数据量大）
        Map<String, R> resultMap = new ConcurrentHashMap<>();
        
        filteredData.parallelStream().forEach(data -> {
            try {
                R result = processor.process(data, context, metadata);
                if (result instanceof CalculatedResult) {
                    CalculatedResult calcResult = (CalculatedResult) result;
                    resultMap.put(calcResult.getKey(), result);
                } else {
                    // 如果F实现了getGroupKey方法
                    if (data instanceof FilteredGroup) {
                        String key = ((FilteredGroup) data).getGroupKey();
                        resultMap.put(key, result);
                    }
                }
            } catch (Exception e) {
                log.error("后置处理失败", e);
            }
        });
        
        log.info("后置处理完成，结果数: {}", resultMap.size());
        return resultMap;
    }
}

5. 管道协调器（Orchestrator）
package com.example.orchestrator;

import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import com.example.pipeline.DataProcessPipeline;
import com.example.pipeline.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 管道协调器
 * 负责协调多个数据源的并行处理和结果合并
 */
@Slf4j
@Component
public class PipelineOrchestrator {
    
    /**
     * 线程池
     */
    private final ExecutorService executorService;
    
    /**
     * 合并线程池（用于并行合并结果）
     */
    private final ExecutorService mergeExecutorService;
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    public PipelineOrchestrator() {
        int processors = Runtime.getRuntime().availableProcessors();
        
        // 数据处理线程池
        this.executorService = new ThreadPoolExecutor(
                processors * 2,
                processors * 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "pipeline-worker-" + count++);
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 合并线程池
        this.mergeExecutorService = new ThreadPoolExecutor(
                processors,
                processors * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "merge-worker-" + count++);
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
    
    /**
     * 执行多管道处理
     * 
     * @param context 请求上下文
     * @param pipelineConfigs 管道配置列表
     * @return 合并后的结果
     */
    public <R> Map<String, List<R>> executeMultiPipeline(
            RequestContext context,
            List<PipelineConfig<?, ?, R>> pipelineConfigs) {
        
        log.info("开始执行多管道处理，管道数: {}", pipelineConfigs.size());
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 加载元数据（只加载一次）
            Metadata metadata = metadataLoader.loadMetadata(context);
            
            // 2. 并行执行所有管道
            List<CompletableFuture<Map<String, R>>> futures = pipelineConfigs.stream()
                    .map(config -> CompletableFuture.supplyAsync(() -> {
                        DataProcessPipeline pipeline = new DataProcessPipeline(
                                config, context, metadata, executorService);
                        return pipeline.execute();
                    }, executorService))
                    .collect(Collectors.toList());
            
            // 3. 等待所有管道完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 4. 收集所有结果
            List<Map<String, R>> allResults = new ArrayList<>();
            for (CompletableFuture<Map<String, R>> future : futures) {
                try {
                    Map<String, R> result = future.get();
                    allResults.add(result);
                } catch (Exception e) {
                    log.error("获取管道结果失败", e);
                }
            }
            
            // 5. 合并结果
            Map<String, List<R>> mergedResult = mergeResults(allResults);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("多管道处理完成，总耗时: {}ms, 合并后结果数: {}", 
                    duration, mergedResult.size());
            
            return mergedResult;
            
        } catch (Exception e) {
            log.error("多管道处理失败", e);
            throw new RuntimeException("多管道处理失败", e);
        }
    }
    
    /**
     * 合并多个Map结果
     * 相同Key的Value合并到List中
     */
    private <R> Map<String, List<R>> mergeResults(List<Map<String, R>> allResults) {
        log.info("开始合并结果，待合并Map数: {}", allResults.size());
        
        Map<String, List<R>> mergedMap = new ConcurrentHashMap<>();
        
        // 并行合并
        allResults.parallelStream().forEach(resultMap -> {
            resultMap.forEach((key, value) -> {
                mergedMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(value);
            });
        });
        
        log.info("结果合并完成，合并后Key数: {}", mergedMap.size());
        
        // 统计每个Key的Value数量
        mergedMap.forEach((key, values) -> {
            if (values.size() > 1) {
                log.debug("Key: {} 合并了 {} 个Value", key, values.size());
            }
        });
        
        return mergedMap;
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("开始关闭线程池...");
        
        shutdownExecutor(executorService, "pipeline-executor");
        shutdownExecutor(mergeExecutorService, "merge-executor");
        
        log.info("线程池关闭完成");
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("{} 未能在60秒内完成关闭，强制关闭", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("{} 关闭时被中断", name, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

6. 元数据加载器
package com.example.orchestrator;

import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据加载器
 * 负责加载和缓存元数据
 */
@Slf4j
@Component
public class MetadataLoader {
    
    /**
     * 元数据缓存
     */
    private final Map<String, Metadata> metadataCache = new ConcurrentHashMap<>();
    
    /**
     * 加载元数据（带缓存）
     */
    public Metadata loadMetadata(RequestContext context) {
        String cacheKey = buildCacheKey(context);
        
        return metadataCache.computeIfAbsent(cacheKey, k -> {
            log.info("加载元数据: {}", cacheKey);
            return doLoadMetadata(context);
        });
    }
    
    /**
     * 实际加载元数据
     */
    private Metadata doLoadMetadata(RequestContext context) {
        Metadata metadata = new Metadata();
        
        // TODO: 从数据库或缓存加载元数据
        metadata.setMetricMetadata(new HashMap<>());
        metadata.setDomainMetadata(new HashMap<>());
        metadata.setOrgMetadata(new HashMap<>());
        
        return metadata;
    }
    
    /**
     * 构建缓存Key
     */
    private String buildCacheKey(RequestContext context) {
        // 根据上下文构建缓存Key
        return "metadata:" + context.getUserId() + ":" + context.getOrgCode();
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        metadataCache.clear();
        log.info("元数据缓存已清除");
    }
}

7. 具体实现示例
package com.example.datasource.impl;

import com.example.datasource.DataSource;
import com.example.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源B的实现
 */
@Slf4j
@Component
public class DataSourceB implements DataSource<RawDataB> {
    
    @Override
    public String getSourceType() {
        return "SOURCE_B";
    }
    
    @Override
    public boolean needPagination() {
        return true;
    }
    
    @Override
    public PageRequest buildRequest(RequestContext context, Metadata metadata, int pageNum) {
        PageRequest request = new PageRequest();
        request.setPageNum(pageNum);
        request.setPageSize(100);
        
        // 构建查询参数
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("orgCode", context.getOrgCode());
        // ... 其他参数
        request.setQueryParams(params);
        
        return request;
    }
    
    @Override
    public PageResponse<RawDataB> fetchData(PageRequest request) {
        // TODO: 调用实际的服务或DAO
        log.info("查询数据源B，页码: {}", request.getPageNum());
        
        PageResponse<RawDataB> response = new PageResponse<>();
        response.setTotal(1000L);
        response.setPageNum(request.getPageNum());
        response.setPageSize(request.getPageSize());
        response.setData(new ArrayList<>());
        
        return response;
    }
    
    @Override
    public int getPriority() {
        return 1;
    }
}

/**
 * 数据源B1的实现（不需要分页）
 */
@Slf4j
@Component
public class DataSourceB1 implements DataSource<RawDataB1> {
    
    @Override
    public String getSourceType() {
        return "SOURCE_B1";
    }
    
    @Override
    public boolean needPagination() {
        return false; // 不需要分页
    }
    
    @Override
    public PageRequest buildRequest(RequestContext context, Metadata metadata, int pageNum) {
        // B1不需要分页，返回null
        return null;
    }
    
    @Override
    public PageResponse<RawDataB1> fetchData(PageRequest request) {
        // B1不使用此方法
        return null;
    }
    
    @Override
    public List<RawDataB1> fetchAllData(RequestContext context, Metadata metadata) {
        // TODO: 一次性获取全部数据
        log.info("查询数据源B1（全量）");
        return new ArrayList<>();
    }
    
    @Override
    public int getPriority() {
        return 2;
    }
}

8. 过滤器实现示例
package com.example.filter.impl;

import com.example.domain.FilteredGroup;
import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import com.example.filter.PreFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 业务规则过滤器
 */
@Slf4j
@Component
public class BusinessRulePreFilter implements PreFilter<Object, FilteredGroup> {
    
    @Override
    public String getName() {
        return "BusinessRuleFilter";
    }
    
    @Override
    public int getOrder() {
        return 1;
    }
    
    @Override
    public boolean isApplicable(String sourceType, RequestContext context) {
        // 所有数据源都适用
        return true;
    }
    
    @Override
    public List<FilteredGroup> filter(List rawData, RequestContext context, Metadata metadata) {
        log.info("执行业务规则过滤，原始数据量: {}", rawData.size());
        
        // TODO: 实现具体的过滤逻辑
        List<FilteredGroup> filtered = rawData.stream()
                .filter(data -> applyBusinessRules(data, context, metadata))
                .map(data -> convertToFilteredGroup(data))
                .collect(Collectors.toList());
        
        log.info("过滤完成，剩余数据量: {}", filtered.size());
        return filtered;
    }
    
    private boolean applyBusinessRules(Object data, RequestContext context, Metadata metadata) {
        // TODO: 实现业务规则
        return true;
    }
    
    private FilteredGroup convertToFilteredGroup(Object data) {
        // TODO: 转换逻辑
        return new FilteredGroup();
    }
}

/**
 * 数据分组过滤器
 */
@Slf4j
@Component
public class GroupingPreFilter implements PreFilter<FilteredGroup, FilteredGroup> {
    
    @Override
    public String getName() {
        return "GroupingFilter";
    }
    
    @Override
    public int getOrder() {
        return 2;
    }
    
    @Override
    public boolean isApplicable(String sourceType, RequestContext context) {
        // SOURCE_B2不需要分组
        return !"SOURCE_B2".equals(sourceType);
    }
    
    @Override
    public List<FilteredGroup> filter(List<FilteredGroup> rawData, RequestContext context, Metadata metadata) {
        log.info("执行数据分组，数据量: {}", rawData.size());
        
        // TODO: 实现分组逻辑
        // 按metricCode+domainCode+orgCode分组
        
        return rawData;
    }
}

9. 后置处理器实现示例
package com.example.filter.impl;

import com.example.domain.CalculatedResult;
import com.example.domain.FilteredGroup;
import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import com.example.filter.PostProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 标准后置处理器
 */
@Slf4j
@Component
public class StandardPostProcessor implements PostProcessor<FilteredGroup, CalculatedResult> {
    
    @Override
    public String getName() {
        return "StandardPostProcessor";
    }
    
    @Override
    public boolean isApplicable(String sourceType, RequestContext context) {
        return true;
    }
    
    @Override
    public CalculatedResult process(FilteredGroup input, RequestContext context, Metadata metadata) {
        // 计算逻辑
        CalculatedResult result = new CalculatedResult();
        result.setKey(input.getGroupKey());
        
        // TODO: 根据input + context + metadata计算value
        Object calculatedValue = calculateValue(input, context, metadata);
        result.setValue(calculatedValue);
        
        return result;
    }
    
    private Object calculateValue(FilteredGroup input, RequestContext context, Metadata metadata) {
        // TODO: 实现复杂的计算逻辑
        return new Object();
    }
}

10. 使用示例（Service层）
package com.example.service;

import com.example.datasource.DataSource;
import com.example.datasource.impl.DataSourceB;
import com.example.datasource.impl.DataSourceB1;
import com.example.datasource.impl.DataSourceB2;
import com.example.domain.CalculatedResult;
import com.example.domain.RequestContext;
import com.example.filter.PostProcessor;
import com.example.filter.PreFilter;
import com.example.filter.impl.BusinessRulePreFilter;
import com.example.filter.impl.GroupingPreFilter;
import com.example.filter.impl.StandardPostProcessor;
import com.example.orchestrator.PipelineOrchestrator;
import com.example.pipeline.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/*
*业务服务实现
*/
@Slf4j
@Service
public class BusinessService {
@Autowired
private PipelineOrchestrator orchestrator;
@Autowired
private DataSourceB dataSourceB;
@Autowired
private DataSourceB1 dataSourceB1;
@Autowired
private DataSourceB2 dataSourceB2;
@Autowired
private BusinessRulePreFilter businessRuleFilter;
@Autowired
private GroupingPreFilter groupingFilter;
@Autowired
private StandardPostProcessor standardPostProcessor;
/*
*处理业务请求（新需求：B、B1、B2并行）
*/
public Map<String, List<CalculatedResult>> processBusinessRequest(RequestContext context) {
log.info("处理业务请求，requestId: {}", context.getRequestId());
// 1. 构建管道配置
List<PipelineConfig> pipelineConfigs = buildPipelineConfigs();
// 2. 执行多管道处理
Map<String, List<CalculatedResult>> result = orchestrator.executeMultiPipeline(
context,
pipelineConfigs
);
log.info("业务请求处理完成，结果Key数: {}", result.size());
return result;
}

/*
*构建管道配置
*/
private List<PipelineConfig> buildPipelineConfigs() {
  List<PipelineConfig> configs = new ArrayList<>();
  // 管道B：需要分页 + 前置过滤器 + 后置处理器
  PipelineConfig configB = new PipelineConfig();
  configB.setDataSource(dataSourceB);
  configB.setPreFilters(Arrays.asList(businessRuleFilter, groupingFilter));
  configB.setPostProcessor(standardPostProcessor);
  configs.add(configB);
// 管道B1：不需要分页 + 前置过滤器 + 后置处理器
PipelineConfig configB1 = new PipelineConfig();
configB1.setDataSource(dataSourceB1);
configB1.setPreFilters(Collections.singletonList(businessRuleFilter)); // 只需要一个过滤器
configB1.setPostProcessor(standardPostProcessor);
configs.add(configB1);
// 管道B2：需要分页 + 无前置过滤器 + 后置处理器
PipelineConfig configB2 = new PipelineConfig();
configB2.setDataSource(dataSourceB2);
configB2.setPreFilters(Collections.emptyList()); // 不需要前置过滤器
configB2.setPostProcessor(standardPostProcessor);
configs.add(configB2);
return configs;
}
}

### 11. 高级特性：动态管道配置
```java
package com.example.config;

import com.example.datasource.DataSource;
import com.example.filter.PostProcessor;
import com.example.filter.PreFilter;
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
 * 支持动态配置和扩展
 */
@Slf4j
@Component
public class PipelineConfigFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * 根据配置元数据动态构建管道配置
     */
    public List<PipelineConfig> buildFromMetadata(List<PipelineMetadata> metadataList) {
        List<PipelineConfig> configs = new ArrayList<>();
        
        for (PipelineMetadata metadata : metadataList) {
            try {
                PipelineConfig config = buildSingleConfig(metadata);
                configs.add(config);
            } catch (Exception e) {
                log.error("构建管道配置失败: {}", metadata.getSourceType(), e);
            }
        }
        
        return configs;
    }
    
    /**
     * 构建单个管道配置
     */
    private PipelineConfig buildSingleConfig(PipelineMetadata metadata) {
        PipelineConfig config = new PipelineConfig();
        
        // 1. 设置数据源
        DataSource dataSource = getBean(metadata.getDataSourceBean(), DataSource.class);
        config.setDataSource(dataSource);
        
        // 2. 设置前置过滤器
        List<PreFilter> preFilters = metadata.getPreFilterBeans().stream()
                .map(beanName -> getBean(beanName, PreFilter.class))
                .collect(Collectors.toList());
        config.setPreFilters(preFilters);
        
        // 3. 设置后置处理器
        PostProcessor postProcessor = getBean(metadata.getPostProcessorBean(), PostProcessor.class);
        config.setPostProcessor(postProcessor);
        
        return config;
    }
    
    /**
     * 从Spring容器获取Bean
     */
    private <T> T getBean(String beanName, Class<T> clazz) {
        return applicationContext.getBean(beanName, clazz);
    }
    
    /**
     * 管道元数据（可以从配置文件或数据库读取）
     */
    @lombok.Data
    public static class PipelineMetadata {
        private String sourceType;
        private String dataSourceBean;
        private List<String> preFilterBeans;
        private String postProcessorBean;
    }
}
```

### 12. 结果合并器接口（高级特性）
```java
package com.example.pipeline;

import java.util.List;

/**
 * 结果合并器接口
 * 用于自定义Value的合并逻辑
 */
@FunctionalInterface
public interface ResultMerger<R> {
    
    /**
     * 合并相同Key的多个Value
     * 
     * @param values 待合并的Value列表
     * @return 合并后的Value
     */
    R merge(List<R> values);
}
```
```java
package com.example.pipeline.merger;

import com.example.domain.CalculatedResult;
import com.example.pipeline.ResultMerger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 标准结果合并器
 * 将多个CalculatedResult合并成一个
 */
@Slf4j
@Component
public class StandardResultMerger implements ResultMerger<CalculatedResult> {
    
    @Override
    public CalculatedResult merge(List<CalculatedResult> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        
        if (values.size() == 1) {
            return values.get(0);
        }
        
        log.debug("合并 {} 个CalculatedResult", values.size());
        
        CalculatedResult merged = new CalculatedResult();
        merged.setKey(values.get(0).getKey());
        
        // 合并逻辑：根据业务规则合并
        Map<String, Object> mergedAttributes = new HashMap<>();
        
        for (CalculatedResult result : values) {
            // TODO: 实现具体的合并逻辑
            // 例如：求和、取平均、取最大值等
            if (result.getAttributes() != null) {
                mergedAttributes.putAll(result.getAttributes());
            }
        }
        
        merged.setAttributes(mergedAttributes);
        merged.setValue(calculateMergedValue(values));
        
        return merged;
    }
    
    /**
     * 计算合并后的Value
     */
    private Object calculateMergedValue(List<CalculatedResult> values) {
        // TODO: 根据业务规则实现
        // 示例：取第一个非空值
        return values.stream()
                .map(CalculatedResult::getValue)
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);
    }
}
```

### 13. 增强的协调器（支持结果合并）
```java
package com.example.orchestrator;

import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import com.example.pipeline.DataProcessPipeline;
import com.example.pipeline.PipelineConfig;
import com.example.pipeline.ResultMerger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 增强的管道协调器
 * 支持自定义结果合并逻辑
 */
@Slf4j
@Component
public class EnhancedPipelineOrchestrator {
    
    private final ExecutorService executorService;
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    public EnhancedPipelineOrchestrator() {
        int processors = Runtime.getRuntime().availableProcessors();
        this.executorService = new ThreadPoolExecutor(
                processors * 2,
                processors * 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> new Thread(r, "enhanced-pipeline-" + UUID.randomUUID()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
    
    /**
     * 执行多管道处理（支持自定义合并）
     */
    public <R> Map<String, R> executePipelinesWithMerge(
            RequestContext context,
            List<PipelineConfig<?, ?, R>> pipelineConfigs,
            ResultMerger<R> globalMerger) {
        
        log.info("开始执行多管道处理（自定义合并），管道数: {}", pipelineConfigs.size());
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 加载元数据
            Metadata metadata = metadataLoader.loadMetadata(context);
            
            // 2. 并行执行所有管道
            List<CompletableFuture<Map<String, R>>> futures = pipelineConfigs.stream()
                    .map(config -> CompletableFuture.supplyAsync(() -> {
                        DataProcessPipeline<?, ?, R> pipeline = new DataProcessPipeline<>(
                                config, context, metadata, executorService);
                        return pipeline.execute();
                    }, executorService))
                    .collect(Collectors.toList());
            
            // 3. 等待所有完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 4. 收集结果
            List<Map<String, R>> allResults = futures.stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            log.error("获取管道结果失败", e);
                            return Collections.<String, R>emptyMap();
                        }
                    })
                    .collect(Collectors.toList());
            
            // 5. 合并结果（使用自定义合并器）
            Map<String, R> mergedResult = mergeWithCustomMerger(allResults, globalMerger);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("多管道处理完成，总耗时: {}ms, 结果数: {}", duration, mergedResult.size());
            
            return mergedResult;
            
        } catch (Exception e) {
            log.error("多管道处理失败", e);
            throw new RuntimeException("多管道处理失败", e);
        }
    }
    
    /**
     * 使用自定义合并器合并结果
     */
    private <R> Map<String, R> mergeWithCustomMerger(
            List<Map<String, R>> allResults,
            ResultMerger<R> merger) {
        
        if (merger == null) {
            // 无合并器，使用简单合并（取第一个）
            return simpleFirstWinMerge(allResults);
        }
        
        log.info("使用自定义合并器合并结果");
        
        // 1. 按Key分组
        Map<String, List<R>> groupedByKey = new ConcurrentHashMap<>();
        
        allResults.parallelStream().forEach(resultMap -> {
            resultMap.forEach((key, value) -> {
                groupedByKey.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                           .add(value);
            });
        });
        
        // 2. 使用合并器合并每个Key的Values
        Map<String, R> mergedMap = new ConcurrentHashMap<>();
        
        groupedByKey.entrySet().parallelStream().forEach(entry -> {
            String key = entry.getKey();
            List<R> values = entry.getValue();
            
            if (values.size() == 1) {
                mergedMap.put(key, values.get(0));
            } else {
                // 使用合并器
                R mergedValue = merger.merge(values);
                if (mergedValue != null) {
                    mergedMap.put(key, mergedValue);
                }
            }
        });
        
        return mergedMap;
    }
    
    /**
     * 简单合并：相同Key取第一个Value
     */
    private <R> Map<String, R> simpleFirstWinMerge(List<Map<String, R>> allResults) {
        Map<String, R> result = new ConcurrentHashMap<>();
        
        allResults.forEach(map -> {
            map.forEach((key, value) -> {
                result.putIfAbsent(key, value);
            });
        });
        
        return result;
    }
    
    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

### 14. Controller层使用示例
```java
package com.example.controller;

import com.example.domain.CalculatedResult;
import com.example.domain.RequestContext;
import com.example.service.BusinessService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务接口控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/business")
@Api(tags = "业务处理接口")
public class BusinessController {
    
    @Autowired
    private BusinessService businessService;
    
    @PostMapping("/process")
    @ApiOperation("处理业务请求")
    public ApiResponse<Map<String, List<CalculatedResult>>> processRequest(
            @RequestBody BusinessRequest request) {
        
        try {
            // 1. 构建上下文
            RequestContext context = buildContext(request);
            
            // 2. 执行业务处理
            Map<String, List<CalculatedResult>> result = 
                    businessService.processBusinessRequest(context);
            
            return ApiResponse.success(result);
            
        } catch (Exception e) {
            log.error("业务处理失败", e);
            return ApiResponse.error("处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 构建请求上下文
     */
    private RequestContext buildContext(BusinessRequest request) {
        RequestContext context = new RequestContext();
        context.setRequestId(UUID.randomUUID().toString());
        context.setParams(request.getParams());
        context.setUserId(request.getUserId());
        context.setOrgCode(request.getOrgCode());
        return context;
    }
    
    @lombok.Data
    public static class BusinessRequest {
        private Long userId;
        private String orgCode;
        private Map<String, Object> params;
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
```

### 15. 配置文件示例
```yaml
# application.yml
spring:
  application:
    name: business-service

# 线程池配置
thread-pool:
  pipeline:
    core-size: 16
    max-size: 32
    queue-capacity: 1000
    keep-alive-seconds: 60
    
  merge:
    core-size: 8
    max-size: 16
    queue-capacity: 500

# 数据源配置
datasource:
  b:
    enabled: true
    page-size: 100
  b1:
    enabled: true
    need-pagination: false
  b2:
    enabled: true
    page-size: 200

# 元数据缓存配置
metadata:
  cache:
    enabled: true
    ttl: 3600

logging:
  level:
    com.example: INFO
    com.example.pipeline: DEBUG
```

## 架构总结

### 设计模式应用

1. **模板方法模式**：`DataProcessPipeline`定义了固定的处理流程
2. **策略模式**：`DataSource`、`PreFilter`、`PostProcessor`都是策略
3. **责任链模式**：`PreFilter`链式执行
4. **工厂模式**：`PipelineConfigFactory`动态创建配置
5. **建造者模式**：`PipelineConfig`的构建
6. **门面模式**：`PipelineOrchestrator`提供统一入口

### SOLID原则体现

1. **单一职责**：每个类职责清晰
2. **开闭原则**：对扩展开放（新增数据源/过滤器），对修改关闭
3. **里氏替换**：所有接口实现可互换
4. **接口隔离**：接口细粒度，各司其职
5. **依赖倒置**：依赖抽象，不依赖具体实现

### 性能优化

1. **Context和Metadata只加载一次**
2. **所有数据源并行执行**
3. **分页查询并行**
4. **结果合并并行**
5. **使用`ConcurrentHashMap`和`CopyOnWriteArrayList`保证线程安全**

### 扩展性

1. **新增数据源**：只需实现`DataSource`接口
2. **新增过滤器**：只需实现`PreFilter`接口
3. **新增处理器**：只需实现`PostProcessor`接口
4. **新增合并逻辑**：只需实现`ResultMerger`接口

这个方案完全满足您的需求，性能高、易扩展、代码优雅！