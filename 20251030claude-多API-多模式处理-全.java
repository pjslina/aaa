��Ϊ����Java����ר�ң�����Ϊ�����һ�������ܡ�����չ�ԡ����ŵĽ�����������������ʹ��ģ�巽��ģʽ + ����ģʽ + ������ģʽ + ��������̡�
�����ܹ����
1. ��������ģ�Ͷ���
package com.example.domain;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * ���������ģ�ȫ�ֹ���
 */
@Data
public class RequestContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;
    private Map<String, Object> params;
    private Long userId;
    private String orgCode;
    // ������������Ϣ
}

/**
 * Ԫ���ݣ�ȫ�ֹ���ֻ����һ�Σ�
 */
@Data
public class Metadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Map<String, Object> metricMetadata;
    private Map<String, Object> domainMetadata;
    private Map<String, Object> orgMetadata;
    // ����Ԫ����
}

/**
 * ��ҳ�������
 */
@Data
public class PageRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer pageNum = 1;
    private Integer pageSize = 100;
    private Map<String, Object> queryParams;
}

/**
 * ��ҳ��Ӧ���
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
 * ���˺�ķ�������
 */
@Data
public class FilteredGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String metricCode;
    private String domainCode;
    private String orgCode;
    private Object rawData;
    // �����ֶ�
    
    /**
     * ��ȡ����Key
     */
    public String getGroupKey() {
        return metricCode + ":::" + domainCode + ":::" + orgCode;
    }
}

/**
 * ����������
 */
@Data
public class CalculatedResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String key;
    private Object value;
    private Map<String, Object> attributes;
}

2. ����Դ����ӿ�
package com.example.datasource;

import com.example.domain.PageRequest;
import com.example.domain.PageResponse;
import com.example.domain.RequestContext;
import com.example.domain.Metadata;

/**
 * ����Դ�ӿ�
 * ����B��B1��B2�Ȳ�ͬ����Դ
 */
public interface DataSource<T> {
    
    /**
     * ��ȡ����Դ����
     */
    String getSourceType();
    
    /**
     * �Ƿ���Ҫ��ҳ
     */
    default boolean needPagination() {
        return true;
    }
    
    /**
     * �����������
     */
    PageRequest buildRequest(RequestContext context, Metadata metadata, int pageNum);
    
    /**
     * ִ�в�ѯ����ҳ��
     */
    PageResponse<T> fetchData(PageRequest request);
    
    /**
     * ִ�в�ѯ��һ����ȫ��������ҳ��
     */
    default java.util.List<T> fetchAllData(RequestContext context, Metadata metadata) {
        PageRequest request = buildRequest(context, metadata, 1);
        PageResponse<T> response = fetchData(request);
        return response.getData();
    }
    
    /**
     * ��ȡ���ȼ������ڲ���ִ��ʱ������
     */
    default int getPriority() {
        return 0;
    }
}

3. ����������
package com.example.filter;

import com.example.domain.RequestContext;
import com.example.domain.Metadata;
import java.util.List;

/**
 * ǰ�ù������ӿ�
 */
public interface PreFilter<T, R> {
    
    /**
     * ����������
     */
    String getName();
    
    /**
     * ���ȼ�������ԽС���ȼ�Խ�ߣ�
     */
    default int getOrder() {
        return 0;
    }
    
    /**
     * �Ƿ�����
     */
    boolean isApplicable(String sourceType, RequestContext context);
    
    /**
     * ִ�й���
     */
    List<R> filter(List<T> rawData, RequestContext context, Metadata metadata);
}

/**
 * ���ô������ӿ�
 */
public interface PostProcessor<T, R> {
    
    /**
     * ����������
     */
    String getName();
    
    /**
     * �Ƿ�����
     */
    boolean isApplicable(String sourceType, RequestContext context);
    
    /**
     * ִ�д���
     */
    R process(T input, RequestContext context, Metadata metadata);
}

4. ���ݴ���ܵ���Pipeline��
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
 * ���ݴ���ܵ�����
 */
@Data
public class PipelineConfig<T, F, R> {
    
    /**
     * ����Դ
     */
    private DataSource<T> dataSource;
    
    /**
     * ǰ�ù�������
     */
    private List<PreFilter<T, F>> preFilters;
    
    /**
     * ���ô�����
     */
    private PostProcessor<F, R> postProcessor;
    
    /**
     * ����ϲ���
     */
    private ResultMerger<R> resultMerger;
}

/**
 * ���ݴ���ܵ�
 * ���𵥸�����Դ��������������
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
     * ִ�йܵ�����
     */
    public Map<String, R> execute() {
        log.info("��ʼִ�����ݹܵ�: {}", config.getDataSource().getSourceType());
        
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. ��ȡ����ԭʼ����
            List<T> allRawData = fetchAllData();
            
            // 2. ǰ�ù���
            List<F> filteredData = applyPreFilters(allRawData);
            
            // 3. ���ô���
            Map<String, R> resultMap = applyPostProcessor(filteredData);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("���ݹܵ�ִ�����: {}, ��ʱ: {}ms, �����: {}", 
                    config.getDataSource().getSourceType(), duration, resultMap.size());
            
            return resultMap;
            
        } catch (Exception e) {
            log.error("���ݹܵ�ִ��ʧ��: {}", config.getDataSource().getSourceType(), e);
            throw new RuntimeException("���ݹܵ�ִ��ʧ��", e);
        }
    }
    
    /**
     * ��ȡ�������ݣ�֧�ַ�ҳ�ͷǷ�ҳ��
     */
    private List<T> fetchAllData() {
        DataSource<T> dataSource = config.getDataSource();
        
        // ����Ҫ��ҳ��ֱ�ӻ�ȡȫ��
        if (!dataSource.needPagination()) {
            log.info("����Դ����Ҫ��ҳ: {}", dataSource.getSourceType());
            return dataSource.fetchAllData(context, metadata);
        }
        
        // ��Ҫ��ҳ
        return fetchDataWithPagination();
    }
    
    /**
     * ��ҳ��ȡ����
     */
    private List<T> fetchDataWithPagination() {
        DataSource<T> dataSource = config.getDataSource();
        
        // 1. ��ѯ��һҳ
        PageRequest firstPageRequest = dataSource.buildRequest(context, metadata, 1);
        PageResponse<T> firstPage = dataSource.fetchData(firstPageRequest);
        
        List<T> allData = new CopyOnWriteArrayList<>(firstPage.getData());
        
        int totalPages = firstPage.getTotalPages();
        log.info("����Դ: {}, ��ҳ��: {}, �ܼ�¼��: {}", 
                dataSource.getSourceType(), totalPages, firstPage.getTotal());
        
        if (totalPages <= 1) {
            return allData;
        }
        
        // 2. ���в�ѯʣ��ҳ
        List<CompletableFuture<List<T>>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            
            CompletableFuture<List<T>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    PageRequest request = dataSource.buildRequest(context, metadata, currentPage);
                    PageResponse<T> response = dataSource.fetchData(request);
                    log.debug("��ѯ��{}ҳ��ɣ�������: {}", currentPage, response.getData().size());
                    return response.getData();
                } catch (Exception e) {
                    log.error("��ѯ��{}ҳʧ��", currentPage, e);
                    return Collections.emptyList();
                }
            }, executorService);
            
            futures.add(future);
        }
        
        // 3. �ȴ������������
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 4. �ռ����н��
        for (CompletableFuture<List<T>> future : futures) {
            try {
                List<T> pageData = future.get();
                allData.addAll(pageData);
            } catch (Exception e) {
                log.error("��ȡ��ҳ����ʧ��", e);
            }
        }
        
        log.info("��ҳ��ѯ��ɣ�����ȡ {} ������", allData.size());
        return allData;
    }
    
    /**
     * Ӧ��ǰ�ù�����
     */
    private List<F> applyPreFilters(List<T> rawData) {
        if (config.getPreFilters() == null || config.getPreFilters().isEmpty()) {
            log.warn("û������ǰ�ù�����");
            return Collections.emptyList();
        }
        
        List<F> result = Collections.emptyList();
        String sourceType = config.getDataSource().getSourceType();
        
        // �����ȼ�����
        List<PreFilter<T, F>> sortedFilters = config.getPreFilters().stream()
                .filter(filter -> filter.isApplicable(sourceType, context))
                .sorted(Comparator.comparingInt(PreFilter::getOrder))
                .collect(Collectors.toList());
        
        // ����ִ�й�������������ģʽ��
        List currentData = rawData;
        for (PreFilter filter : sortedFilters) {
            log.debug("ִ��ǰ�ù�����: {}", filter.getName());
            currentData = filter.filter(currentData, context, metadata);
            log.debug("���˺�������: {}", currentData.size());
        }
        
        return (List<F>) currentData;
    }
    
    /**
     * Ӧ�ú��ô�����
     */
    private Map<String, R> applyPostProcessor(List<F> filteredData) {
        if (config.getPostProcessor() == null) {
            log.warn("û�����ú��ô�����");
            return Collections.emptyMap();
        }
        
        String sourceType = config.getDataSource().getSourceType();
        PostProcessor<F, R> processor = config.getPostProcessor();
        
        if (!processor.isApplicable(sourceType, context)) {
            log.warn("���ô�����������: {}", processor.getName());
            return Collections.emptyMap();
        }
        
        // ���д��������������
        Map<String, R> resultMap = new ConcurrentHashMap<>();
        
        filteredData.parallelStream().forEach(data -> {
            try {
                R result = processor.process(data, context, metadata);
                if (result instanceof CalculatedResult) {
                    CalculatedResult calcResult = (CalculatedResult) result;
                    resultMap.put(calcResult.getKey(), result);
                } else {
                    // ���Fʵ����getGroupKey����
                    if (data instanceof FilteredGroup) {
                        String key = ((FilteredGroup) data).getGroupKey();
                        resultMap.put(key, result);
                    }
                }
            } catch (Exception e) {
                log.error("���ô���ʧ��", e);
            }
        });
        
        log.info("���ô�����ɣ������: {}", resultMap.size());
        return resultMap;
    }
}

5. �ܵ�Э������Orchestrator��
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
 * �ܵ�Э����
 * ����Э���������Դ�Ĳ��д���ͽ���ϲ�
 */
@Slf4j
@Component
public class PipelineOrchestrator {
    
    /**
     * �̳߳�
     */
    private final ExecutorService executorService;
    
    /**
     * �ϲ��̳߳أ����ڲ��кϲ������
     */
    private final ExecutorService mergeExecutorService;
    
    @Autowired
    private MetadataLoader metadataLoader;
    
    public PipelineOrchestrator() {
        int processors = Runtime.getRuntime().availableProcessors();
        
        // ���ݴ����̳߳�
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
        
        // �ϲ��̳߳�
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
     * ִ�ж�ܵ�����
     * 
     * @param context ����������
     * @param pipelineConfigs �ܵ������б�
     * @return �ϲ���Ľ��
     */
    public <R> Map<String, List<R>> executeMultiPipeline(
            RequestContext context,
            List<PipelineConfig<?, ?, R>> pipelineConfigs) {
        
        log.info("��ʼִ�ж�ܵ������ܵ���: {}", pipelineConfigs.size());
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. ����Ԫ���ݣ�ֻ����һ�Σ�
            Metadata metadata = metadataLoader.loadMetadata(context);
            
            // 2. ����ִ�����йܵ�
            List<CompletableFuture<Map<String, R>>> futures = pipelineConfigs.stream()
                    .map(config -> CompletableFuture.supplyAsync(() -> {
                        DataProcessPipeline pipeline = new DataProcessPipeline(
                                config, context, metadata, executorService);
                        return pipeline.execute();
                    }, executorService))
                    .collect(Collectors.toList());
            
            // 3. �ȴ����йܵ����
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 4. �ռ����н��
            List<Map<String, R>> allResults = new ArrayList<>();
            for (CompletableFuture<Map<String, R>> future : futures) {
                try {
                    Map<String, R> result = future.get();
                    allResults.add(result);
                } catch (Exception e) {
                    log.error("��ȡ�ܵ����ʧ��", e);
                }
            }
            
            // 5. �ϲ����
            Map<String, List<R>> mergedResult = mergeResults(allResults);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("��ܵ�������ɣ��ܺ�ʱ: {}ms, �ϲ�������: {}", 
                    duration, mergedResult.size());
            
            return mergedResult;
            
        } catch (Exception e) {
            log.error("��ܵ�����ʧ��", e);
            throw new RuntimeException("��ܵ�����ʧ��", e);
        }
    }
    
    /**
     * �ϲ����Map���
     * ��ͬKey��Value�ϲ���List��
     */
    private <R> Map<String, List<R>> mergeResults(List<Map<String, R>> allResults) {
        log.info("��ʼ�ϲ���������ϲ�Map��: {}", allResults.size());
        
        Map<String, List<R>> mergedMap = new ConcurrentHashMap<>();
        
        // ���кϲ�
        allResults.parallelStream().forEach(resultMap -> {
            resultMap.forEach((key, value) -> {
                mergedMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(value);
            });
        });
        
        log.info("����ϲ���ɣ��ϲ���Key��: {}", mergedMap.size());
        
        // ͳ��ÿ��Key��Value����
        mergedMap.forEach((key, values) -> {
            if (values.size() > 1) {
                log.debug("Key: {} �ϲ��� {} ��Value", key, values.size());
            }
        });
        
        return mergedMap;
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("��ʼ�ر��̳߳�...");
        
        shutdownExecutor(executorService, "pipeline-executor");
        shutdownExecutor(mergeExecutorService, "merge-executor");
        
        log.info("�̳߳عر����");
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("{} δ����60������ɹرգ�ǿ�ƹر�", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("{} �ر�ʱ���ж�", name, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

6. Ԫ���ݼ�����
package com.example.orchestrator;

import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ԫ���ݼ�����
 * ������غͻ���Ԫ����
 */
@Slf4j
@Component
public class MetadataLoader {
    
    /**
     * Ԫ���ݻ���
     */
    private final Map<String, Metadata> metadataCache = new ConcurrentHashMap<>();
    
    /**
     * ����Ԫ���ݣ������棩
     */
    public Metadata loadMetadata(RequestContext context) {
        String cacheKey = buildCacheKey(context);
        
        return metadataCache.computeIfAbsent(cacheKey, k -> {
            log.info("����Ԫ����: {}", cacheKey);
            return doLoadMetadata(context);
        });
    }
    
    /**
     * ʵ�ʼ���Ԫ����
     */
    private Metadata doLoadMetadata(RequestContext context) {
        Metadata metadata = new Metadata();
        
        // TODO: �����ݿ�򻺴����Ԫ����
        metadata.setMetricMetadata(new HashMap<>());
        metadata.setDomainMetadata(new HashMap<>());
        metadata.setOrgMetadata(new HashMap<>());
        
        return metadata;
    }
    
    /**
     * ��������Key
     */
    private String buildCacheKey(RequestContext context) {
        // ���������Ĺ�������Key
        return "metadata:" + context.getUserId() + ":" + context.getOrgCode();
    }
    
    /**
     * �������
     */
    public void clearCache() {
        metadataCache.clear();
        log.info("Ԫ���ݻ��������");
    }
}

7. ����ʵ��ʾ��
package com.example.datasource.impl;

import com.example.datasource.DataSource;
import com.example.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ����ԴB��ʵ��
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
        
        // ������ѯ����
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("orgCode", context.getOrgCode());
        // ... ��������
        request.setQueryParams(params);
        
        return request;
    }
    
    @Override
    public PageResponse<RawDataB> fetchData(PageRequest request) {
        // TODO: ����ʵ�ʵķ����DAO
        log.info("��ѯ����ԴB��ҳ��: {}", request.getPageNum());
        
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
 * ����ԴB1��ʵ�֣�����Ҫ��ҳ��
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
        return false; // ����Ҫ��ҳ
    }
    
    @Override
    public PageRequest buildRequest(RequestContext context, Metadata metadata, int pageNum) {
        // B1����Ҫ��ҳ������null
        return null;
    }
    
    @Override
    public PageResponse<RawDataB1> fetchData(PageRequest request) {
        // B1��ʹ�ô˷���
        return null;
    }
    
    @Override
    public List<RawDataB1> fetchAllData(RequestContext context, Metadata metadata) {
        // TODO: һ���Ի�ȡȫ������
        log.info("��ѯ����ԴB1��ȫ����");
        return new ArrayList<>();
    }
    
    @Override
    public int getPriority() {
        return 2;
    }
}

8. ������ʵ��ʾ��
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
 * ҵ����������
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
        // ��������Դ������
        return true;
    }
    
    @Override
    public List<FilteredGroup> filter(List rawData, RequestContext context, Metadata metadata) {
        log.info("ִ��ҵ�������ˣ�ԭʼ������: {}", rawData.size());
        
        // TODO: ʵ�־���Ĺ����߼�
        List<FilteredGroup> filtered = rawData.stream()
                .filter(data -> applyBusinessRules(data, context, metadata))
                .map(data -> convertToFilteredGroup(data))
                .collect(Collectors.toList());
        
        log.info("������ɣ�ʣ��������: {}", filtered.size());
        return filtered;
    }
    
    private boolean applyBusinessRules(Object data, RequestContext context, Metadata metadata) {
        // TODO: ʵ��ҵ�����
        return true;
    }
    
    private FilteredGroup convertToFilteredGroup(Object data) {
        // TODO: ת���߼�
        return new FilteredGroup();
    }
}

/**
 * ���ݷ��������
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
        // SOURCE_B2����Ҫ����
        return !"SOURCE_B2".equals(sourceType);
    }
    
    @Override
    public List<FilteredGroup> filter(List<FilteredGroup> rawData, RequestContext context, Metadata metadata) {
        log.info("ִ�����ݷ��飬������: {}", rawData.size());
        
        // TODO: ʵ�ַ����߼�
        // ��metricCode+domainCode+orgCode����
        
        return rawData;
    }
}

9. ���ô�����ʵ��ʾ��
package com.example.filter.impl;

import com.example.domain.CalculatedResult;
import com.example.domain.FilteredGroup;
import com.example.domain.Metadata;
import com.example.domain.RequestContext;
import com.example.filter.PostProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ��׼���ô�����
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
        // �����߼�
        CalculatedResult result = new CalculatedResult();
        result.setKey(input.getGroupKey());
        
        // TODO: ����input + context + metadata����value
        Object calculatedValue = calculateValue(input, context, metadata);
        result.setValue(calculatedValue);
        
        return result;
    }
    
    private Object calculateValue(FilteredGroup input, RequestContext context, Metadata metadata) {
        // TODO: ʵ�ָ��ӵļ����߼�
        return new Object();
    }
}

10. ʹ��ʾ����Service�㣩
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
*ҵ�����ʵ��
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
*����ҵ������������B��B1��B2���У�
*/
public Map<String, List<CalculatedResult>> processBusinessRequest(RequestContext context) {
log.info("����ҵ������requestId: {}", context.getRequestId());
// 1. �����ܵ�����
List<PipelineConfig> pipelineConfigs = buildPipelineConfigs();
// 2. ִ�ж�ܵ�����
Map<String, List<CalculatedResult>> result = orchestrator.executeMultiPipeline(
context,
pipelineConfigs
);
log.info("ҵ����������ɣ����Key��: {}", result.size());
return result;
}

/*
*�����ܵ�����
*/
private List<PipelineConfig> buildPipelineConfigs() {
  List<PipelineConfig> configs = new ArrayList<>();
  // �ܵ�B����Ҫ��ҳ + ǰ�ù����� + ���ô�����
  PipelineConfig configB = new PipelineConfig();
  configB.setDataSource(dataSourceB);
  configB.setPreFilters(Arrays.asList(businessRuleFilter, groupingFilter));
  configB.setPostProcessor(standardPostProcessor);
  configs.add(configB);
// �ܵ�B1������Ҫ��ҳ + ǰ�ù����� + ���ô�����
PipelineConfig configB1 = new PipelineConfig();
configB1.setDataSource(dataSourceB1);
configB1.setPreFilters(Collections.singletonList(businessRuleFilter)); // ֻ��Ҫһ��������
configB1.setPostProcessor(standardPostProcessor);
configs.add(configB1);
// �ܵ�B2����Ҫ��ҳ + ��ǰ�ù����� + ���ô�����
PipelineConfig configB2 = new PipelineConfig();
configB2.setDataSource(dataSourceB2);
configB2.setPreFilters(Collections.emptyList()); // ����Ҫǰ�ù�����
configB2.setPostProcessor(standardPostProcessor);
configs.add(configB2);
return configs;
}
}

### 11. �߼����ԣ���̬�ܵ�����
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
 * �ܵ����ù���
 * ֧�ֶ�̬���ú���չ
 */
@Slf4j
@Component
public class PipelineConfigFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * ��������Ԫ���ݶ�̬�����ܵ�����
     */
    public List<PipelineConfig> buildFromMetadata(List<PipelineMetadata> metadataList) {
        List<PipelineConfig> configs = new ArrayList<>();
        
        for (PipelineMetadata metadata : metadataList) {
            try {
                PipelineConfig config = buildSingleConfig(metadata);
                configs.add(config);
            } catch (Exception e) {
                log.error("�����ܵ�����ʧ��: {}", metadata.getSourceType(), e);
            }
        }
        
        return configs;
    }
    
    /**
     * ���������ܵ�����
     */
    private PipelineConfig buildSingleConfig(PipelineMetadata metadata) {
        PipelineConfig config = new PipelineConfig();
        
        // 1. ��������Դ
        DataSource dataSource = getBean(metadata.getDataSourceBean(), DataSource.class);
        config.setDataSource(dataSource);
        
        // 2. ����ǰ�ù�����
        List<PreFilter> preFilters = metadata.getPreFilterBeans().stream()
                .map(beanName -> getBean(beanName, PreFilter.class))
                .collect(Collectors.toList());
        config.setPreFilters(preFilters);
        
        // 3. ���ú��ô�����
        PostProcessor postProcessor = getBean(metadata.getPostProcessorBean(), PostProcessor.class);
        config.setPostProcessor(postProcessor);
        
        return config;
    }
    
    /**
     * ��Spring������ȡBean
     */
    private <T> T getBean(String beanName, Class<T> clazz) {
        return applicationContext.getBean(beanName, clazz);
    }
    
    /**
     * �ܵ�Ԫ���ݣ����Դ������ļ������ݿ��ȡ��
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

### 12. ����ϲ����ӿڣ��߼����ԣ�
```java
package com.example.pipeline;

import java.util.List;

/**
 * ����ϲ����ӿ�
 * �����Զ���Value�ĺϲ��߼�
 */
@FunctionalInterface
public interface ResultMerger<R> {
    
    /**
     * �ϲ���ͬKey�Ķ��Value
     * 
     * @param values ���ϲ���Value�б�
     * @return �ϲ����Value
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
 * ��׼����ϲ���
 * �����CalculatedResult�ϲ���һ��
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
        
        log.debug("�ϲ� {} ��CalculatedResult", values.size());
        
        CalculatedResult merged = new CalculatedResult();
        merged.setKey(values.get(0).getKey());
        
        // �ϲ��߼�������ҵ�����ϲ�
        Map<String, Object> mergedAttributes = new HashMap<>();
        
        for (CalculatedResult result : values) {
            // TODO: ʵ�־���ĺϲ��߼�
            // ���磺��͡�ȡƽ����ȡ���ֵ��
            if (result.getAttributes() != null) {
                mergedAttributes.putAll(result.getAttributes());
            }
        }
        
        merged.setAttributes(mergedAttributes);
        merged.setValue(calculateMergedValue(values));
        
        return merged;
    }
    
    /**
     * ����ϲ����Value
     */
    private Object calculateMergedValue(List<CalculatedResult> values) {
        // TODO: ����ҵ�����ʵ��
        // ʾ����ȡ��һ���ǿ�ֵ
        return values.stream()
                .map(CalculatedResult::getValue)
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);
    }
}
```

### 13. ��ǿ��Э������֧�ֽ���ϲ���
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
 * ��ǿ�Ĺܵ�Э����
 * ֧���Զ������ϲ��߼�
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
     * ִ�ж�ܵ�����֧���Զ���ϲ���
     */
    public <R> Map<String, R> executePipelinesWithMerge(
            RequestContext context,
            List<PipelineConfig<?, ?, R>> pipelineConfigs,
            ResultMerger<R> globalMerger) {
        
        log.info("��ʼִ�ж�ܵ������Զ���ϲ������ܵ���: {}", pipelineConfigs.size());
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. ����Ԫ����
            Metadata metadata = metadataLoader.loadMetadata(context);
            
            // 2. ����ִ�����йܵ�
            List<CompletableFuture<Map<String, R>>> futures = pipelineConfigs.stream()
                    .map(config -> CompletableFuture.supplyAsync(() -> {
                        DataProcessPipeline<?, ?, R> pipeline = new DataProcessPipeline<>(
                                config, context, metadata, executorService);
                        return pipeline.execute();
                    }, executorService))
                    .collect(Collectors.toList());
            
            // 3. �ȴ��������
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 4. �ռ����
            List<Map<String, R>> allResults = futures.stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            log.error("��ȡ�ܵ����ʧ��", e);
                            return Collections.<String, R>emptyMap();
                        }
                    })
                    .collect(Collectors.toList());
            
            // 5. �ϲ������ʹ���Զ���ϲ�����
            Map<String, R> mergedResult = mergeWithCustomMerger(allResults, globalMerger);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("��ܵ�������ɣ��ܺ�ʱ: {}ms, �����: {}", duration, mergedResult.size());
            
            return mergedResult;
            
        } catch (Exception e) {
            log.error("��ܵ�����ʧ��", e);
            throw new RuntimeException("��ܵ�����ʧ��", e);
        }
    }
    
    /**
     * ʹ���Զ���ϲ����ϲ����
     */
    private <R> Map<String, R> mergeWithCustomMerger(
            List<Map<String, R>> allResults,
            ResultMerger<R> merger) {
        
        if (merger == null) {
            // �޺ϲ�����ʹ�ü򵥺ϲ���ȡ��һ����
            return simpleFirstWinMerge(allResults);
        }
        
        log.info("ʹ���Զ���ϲ����ϲ����");
        
        // 1. ��Key����
        Map<String, List<R>> groupedByKey = new ConcurrentHashMap<>();
        
        allResults.parallelStream().forEach(resultMap -> {
            resultMap.forEach((key, value) -> {
                groupedByKey.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                           .add(value);
            });
        });
        
        // 2. ʹ�úϲ����ϲ�ÿ��Key��Values
        Map<String, R> mergedMap = new ConcurrentHashMap<>();
        
        groupedByKey.entrySet().parallelStream().forEach(entry -> {
            String key = entry.getKey();
            List<R> values = entry.getValue();
            
            if (values.size() == 1) {
                mergedMap.put(key, values.get(0));
            } else {
                // ʹ�úϲ���
                R mergedValue = merger.merge(values);
                if (mergedValue != null) {
                    mergedMap.put(key, mergedValue);
                }
            }
        });
        
        return mergedMap;
    }
    
    /**
     * �򵥺ϲ�����ͬKeyȡ��һ��Value
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

### 14. Controller��ʹ��ʾ��
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
 * ҵ��ӿڿ�����
 */
@Slf4j
@RestController
@RequestMapping("/api/business")
@Api(tags = "ҵ����ӿ�")
public class BusinessController {
    
    @Autowired
    private BusinessService businessService;
    
    @PostMapping("/process")
    @ApiOperation("����ҵ������")
    public ApiResponse<Map<String, List<CalculatedResult>>> processRequest(
            @RequestBody BusinessRequest request) {
        
        try {
            // 1. ����������
            RequestContext context = buildContext(request);
            
            // 2. ִ��ҵ����
            Map<String, List<CalculatedResult>> result = 
                    businessService.processBusinessRequest(context);
            
            return ApiResponse.success(result);
            
        } catch (Exception e) {
            log.error("ҵ����ʧ��", e);
            return ApiResponse.error("����ʧ��: " + e.getMessage());
        }
    }
    
    /**
     * ��������������
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
            return new ApiResponse<>(true, "�ɹ�", data);
        }
        
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
```

### 15. �����ļ�ʾ��
```yaml
# application.yml
spring:
  application:
    name: business-service

# �̳߳�����
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

# ����Դ����
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

# Ԫ���ݻ�������
metadata:
  cache:
    enabled: true
    ttl: 3600

logging:
  level:
    com.example: INFO
    com.example.pipeline: DEBUG
```

## �ܹ��ܽ�

### ���ģʽӦ��

1. **ģ�巽��ģʽ**��`DataProcessPipeline`�����˹̶��Ĵ�������
2. **����ģʽ**��`DataSource`��`PreFilter`��`PostProcessor`���ǲ���
3. **������ģʽ**��`PreFilter`��ʽִ��
4. **����ģʽ**��`PipelineConfigFactory`��̬��������
5. **������ģʽ**��`PipelineConfig`�Ĺ���
6. **����ģʽ**��`PipelineOrchestrator`�ṩͳһ���

### SOLIDԭ������

1. **��һְ��**��ÿ����ְ������
2. **����ԭ��**������չ���ţ���������Դ/�������������޸Ĺر�
3. **�����滻**�����нӿ�ʵ�ֿɻ���
4. **�ӿڸ���**���ӿ�ϸ���ȣ���˾��ְ
5. **��������**���������󣬲���������ʵ��

### �����Ż�

1. **Context��Metadataֻ����һ��**
2. **��������Դ����ִ��**
3. **��ҳ��ѯ����**
4. **����ϲ�����**
5. **ʹ��`ConcurrentHashMap`��`CopyOnWriteArrayList`��֤�̰߳�ȫ**

### ��չ��

1. **��������Դ**��ֻ��ʵ��`DataSource`�ӿ�
2. **����������**��ֻ��ʵ��`PreFilter`�ӿ�
3. **����������**��ֻ��ʵ��`PostProcessor`�ӿ�
4. **�����ϲ��߼�**��ֻ��ʵ��`ResultMerger`�ӿ�

���������ȫ���������������ܸߡ�����չ���������ţ�