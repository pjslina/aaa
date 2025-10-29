我们面临的挑战：

原有单个接口B变为多个接口（B、B1、B2等），需要并行调用，然后合并结果。

不同接口可能有分页和非分页的区别，且前置和后置过滤器可能不同。

合并结果时，如果key相同，需要合并value（合并策略可能不同）。

设计思路：

使用任务模式（Task）来封装每个接口的调用逻辑（包括分页处理、过滤等）。

使用工厂模式来创建不同的任务实例。

使用组合模式来合并多个任务的结果。

使用策略模式来定义不同的合并策略（如覆盖、属性合并等）。

使用模板方法模式来抽象分页和非分页任务的流程。

步骤：

定义任务接口，包含执行方法。

实现具体任务（分页任务和非分页任务）。

定义合并策略接口，实现多种合并策略。

定义一个聚合服务，用于并行执行多个任务，并合并结果。

配置每个任务对应的过滤器和合并策略。

详细代码：

首先，定义任务接口：

以下是完整的生产级别代码实现：

1. 核心模型和枚举
// Context.java - 上下文对象
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Context {
    private String requestId;
    private Map<String, Object> parameters;
    private Long userId;
    private String tenantId;
    private Instant requestTime;
    
    // 业务参数获取方法
    public <T> T getParameter(String key, Class<T> clazz) {
        Object value = parameters.get(key);
        return clazz.isInstance(value) ? clazz.cast(value) : null;
    }
    
    public String getParameterAsString(String key) {
        return getParameter(key, String.class);
    }
    
    public Integer getParameterAsInteger(String key) {
        return getParameter(key, Integer.class);
    }
}

// Metadata.java - 元数据对象（支持动态更新）
@Component
public class Metadata {
    private final AtomicReference<Map<String, Object>> metadataRef = 
        new AtomicReference<>(new ConcurrentHashMap<>());
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    private final String METADATA_KEY = "system:metadata";
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metadata-refresh");
            t.setDaemon(true);
            return t;
        });
    
    @PostConstruct
    public void init() {
        // 初始化加载
        refreshMetadata();
        // 每分钟刷新一次
        scheduler.scheduleAtFixedRate(this::refreshMetadata, 1, 1, TimeUnit.MINUTES);
    }
    
    public void refreshMetadata() {
        try {
            Map<Object, Object> redisData = redisTemplate.opsForHash().entries(METADATA_KEY);
            Map<String, Object> newMetadata = new ConcurrentHashMap<>();
            redisData.forEach((k, v) -> newMetadata.put(k.toString(), v));
            metadataRef.set(newMetadata);
        } catch (Exception e) {
            // 记录日志但不中断服务
            System.err.println("Refresh metadata failed: " + e.getMessage());
        }
    }
    
    public <T> T get(String key, Class<T> clazz) {
        Object value = metadataRef.get().get(key);
        return clazz.isInstance(value) ? clazz.cast(value) : null;
    }
    
    public Object get(String key) {
        return metadataRef.get().get(key);
    }
    
    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
    }
}

2. 过滤器相关接口和实现

// Filter.java - 过滤器标记接口
public interface Filter {}

// PreFilter.java - 前置过滤器接口
public interface PreFilter extends Filter {
    List<F> filter(List<F> rawData, Context context, Metadata metadata);
}

// PostFilter.java - 后置过滤器接口
public interface PostFilter extends Filter {
    G process(F item, Context context, Metadata metadata);
}

// CompositePreFilter.java - 组合前置过滤器
@Component
public class CompositePreFilter implements PreFilter {
    private final List<PreFilter> filters;
    
    @Autowired
    public CompositePreFilter(List<PreFilter> filters) {
        this.filters = filters.stream()
            .sorted(Comparator.comparingInt(this::getOrder))
            .collect(Collectors.toList());
    }
    
    private int getOrder(PreFilter filter) {
        Order order = filter.getClass().getAnnotation(Order.class);
        return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
    }
    
    @Override
    public List<F> filter(List<F> rawData, Context context, Metadata metadata) {
        List<F> result = new ArrayList<>(rawData);
        for (PreFilter filter : filters) {
            long startTime = System.currentTimeMillis();
            result = filter.filter(result, context, metadata);
            long duration = System.currentTimeMillis() - startTime;
            // 记录过滤器执行时间
            System.out.println(filter.getClass().getSimpleName() + " executed in " + duration + "ms");
        }
        return result;
    }
}

// ValidationPreFilter.java - 验证过滤器示例
@Component
@Order(1)
public class ValidationPreFilter implements PreFilter {
    @Override
    public List<F> filter(List<F> rawData, Context context, Metadata metadata) {
        return rawData.stream()
            .filter(item -> isValid(item, context))
            .collect(Collectors.toList());
    }
    
    private boolean isValid(F item, Context context) {
        // 实现验证逻辑
        return item != null && 
               item.getMetricCode() != null && 
               !item.getMetricCode().trim().isEmpty();
    }
}

// BusinessPreFilter.java - 业务过滤器示例
@Component
@Order(2)
public class BusinessPreFilter implements PreFilter {
    @Override
    public List<F> filter(List<F> rawData, Context context, Metadata metadata) {
        return rawData.stream()
            .filter(item -> meetsBusinessRequirements(item, context, metadata))
            .collect(Collectors.toList());
    }
    
    private boolean meetsBusinessRequirements(F item, Context context, Metadata metadata) {
        // 实现业务逻辑
        return true;
    }
}

3. 数据模型
// F.java - 原始数据对象
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class F {
    private String metricCode;
    private String domainCode;
    private String orgCode;
    private Map<String, Object> attributes;
    private Instant dataTime;
    
    public String getCompositeKey() {
        return metricCode + ":::" + domainCode + ":::" + orgCode;
    }
}

// G.java - 处理后的数据对象（使用建造者模式）
@Data
@Builder(builderClassName = "GBuilder", builderMethodName = "newBuilder")
public class G {
    private final String compositeKey;
    private final String metricCode;
    private final String domainCode;
    private final String orgCode;
    private final Map<String, Object> calculatedData;
    private final Instant processedTime;
    private final Map<String, Object> metadata;
    
    // 线程安全的建造者
    public static class GBuilder {
        private String compositeKey;
        private String metricCode;
        private String domainCode;
        private String orgCode;
        private Map<String, Object> calculatedData = new ConcurrentHashMap<>();
        private Instant processedTime = Instant.now();
        private Map<String, Object> metadata = new ConcurrentHashMap<>();
        
        public GBuilder compositeKey(String metricCode, String domainCode, String orgCode) {
            this.metricCode = metricCode;
            this.domainCode = domainCode;
            this.orgCode = orgCode;
            this.compositeKey = metricCode + ":::" + domainCode + ":::" + orgCode;
            return this;
        }
        
        public GBuilder addCalculatedData(String key, Object value) {
            this.calculatedData.put(key, value);
            return this;
        }
        
        public GBuilder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public G build() {
            return new G(compositeKey, metricCode, domainCode, orgCode, 
                        Collections.unmodifiableMap(calculatedData), 
                        processedTime, Collections.unmodifiableMap(metadata));
        }
    }
}

4. API响应模型
// PageResponse.java - 分页响应
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> data;
    private int currentPage;
    private int pageSize;
    private int totalPages;
    private long totalElements;
    private boolean hasNext;
}

// ApiResponse.java - 统一API响应
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
    private Long timestamp;
    
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .code("SUCCESS")
            .message("操作成功")
            .data(data)
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    public static ApiResponse<Object> error(String code, String message) {
        return ApiResponse.builder()
            .success(false)
            .code(code)
            .message(message)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}

5. 任务执行框架
// TaskType.java - 任务类型枚举
public enum TaskType {
    B(true, true, "B_API_TASK"),
    B1(true, true, "B1_API_TASK"), 
    B2(false, false, "B2_API_TASK");
    
    private final boolean supportsPaging;
    private final boolean requiresPreFilter;
    private final String taskName;
    
    TaskType(boolean supportsPaging, boolean requiresPreFilter, String taskName) {
        this.supportsPaging = supportsPaging;
        this.requiresPreFilter = requiresPreFilter;
        this.taskName = taskName;
    }
    
    // getters...
}

// DataFetchTask.java - 任务接口
public interface DataFetchTask {
    String getTaskName();
    TaskType getTaskType();
    Map<String, G> execute(Context context, Metadata metadata);
    boolean supportsPaging();
    boolean requiresPreFilter();
}

// AbstractDataFetchTask.java - 抽象任务基类
public abstract class AbstractDataFetchTask implements DataFetchTask {
    protected final ExecutorService executorService;
    protected final PreFilter preFilter;
    protected final PostFilter postFilter;
    protected final TaskType taskType;
    
    protected AbstractDataFetchTask(ExecutorService executorService, 
                                 PreFilter preFilter, 
                                 PostFilter postFilter,
                                 TaskType taskType) {
        this.executorService = executorService;
        this.preFilter = preFilter;
        this.postFilter = postFilter;
        this.taskType = taskType;
    }
    
    @Override
    public String getTaskName() {
        return taskType.getTaskName();
    }
    
    @Override
    public TaskType getTaskType() {
        return taskType;
    }
    
    @Override
    public boolean supportsPaging() {
        return taskType.supportsPaging();
    }
    
    @Override
    public boolean requiresPreFilter() {
        return taskType.requiresPreFilter();
    }
    
    protected Map<String, G> processData(List<F> data, Context context, Metadata metadata) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyMap();
        }
        
        List<F> filteredData = requiresPreFilter() ? 
            preFilter.filter(data, context, metadata) : data;
            
        return filteredData.parallelStream()
            .map(item -> postFilter.process(item, context, metadata))
            .collect(Collectors.toConcurrentMap(
                G::getCompositeKey,
                Function.identity(),
                (existing, replacement) -> replacement // 冲突处理策略
            ));
    }
    
    protected Map<String, G> mergeResults(Map<String, G> map1, Map<String, G> map2) {
        Map<String, G> result = new ConcurrentHashMap<>(map1);
        result.putAll(map2);
        return result;
    }
}

// AbstractPagedTask.java - 分页任务抽象类
public abstract class AbstractPagedTask extends AbstractDataFetchTask {
    
    protected AbstractPagedTask(ExecutorService executorService, 
                              PreFilter preFilter, 
                              PostFilter postFilter,
                              TaskType taskType) {
        super(executorService, preFilter, postFilter, taskType);
    }
    
    @Override
    public Map<String, G> execute(Context context, Metadata metadata) {
        try {
            // 获取第一页数据
            Object firstPageRequest = buildFirstPageRequest(context, metadata);
            PageResponse<F> firstPage = fetchPage(firstPageRequest, context);
            
            Map<String, G> result = processData(firstPage.getData(), context, metadata);
            
            // 如果有多页，并行获取剩余页
            if (firstPage.getTotalPages() > 1) {
                List<CompletableFuture<Map<String, G>>> futures = createPageFutures(
                    context, metadata, firstPage.getTotalPages());
                
                // 等待所有任务完成并合并结果
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
                );
                
                Map<String, G> finalResult = allFutures.thenApply(v -> 
                    futures.stream()
                        .map(CompletableFuture::join)
                        .reduce(result, this::mergeResults)
                ).join();
                
                return finalResult;
            }
            
            return result;
        } catch (Exception e) {
            System.err.println("Task " + getTaskName() + " execution failed: " + e.getMessage());
            return Collections.emptyMap();
        }
    }
    
    private List<CompletableFuture<Map<String, G>>> createPageFutures(
            Context context, Metadata metadata, int totalPages) {
        List<CompletableFuture<Map<String, G>>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            CompletableFuture<Map<String, G>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    Object pageRequest = buildPageRequest(context, metadata, currentPage);
                    PageResponse<F> pageResponse = fetchPage(pageRequest, context);
                    return processData(pageResponse.getData(), context, metadata);
                } catch (Exception e) {
                    System.err.println("Page " + currentPage + " fetch failed: " + e.getMessage());
                    return Collections.<String, G>emptyMap();
                }
            }, executorService);
            
            futures.add(future);
        }
        
        return futures;
    }
    
    protected abstract Object buildFirstPageRequest(Context context, Metadata metadata);
    protected abstract Object buildPageRequest(Context context, Metadata metadata, int page);
    protected abstract PageResponse<F> fetchPage(Object request, Context context);
}

// NonPagedTask.java - 非分页任务抽象类
public abstract class NonPagedTask extends AbstractDataFetchTask {
    
    protected NonPagedTask(ExecutorService executorService, 
                          PreFilter preFilter, 
                          PostFilter postFilter,
                          TaskType taskType) {
        super(executorService, preFilter, postFilter, taskType);
    }
    
    @Override
    public Map<String, G> execute(Context context, Metadata metadata) {
        try {
            Object request = buildRequest(context, metadata);
            List<F> data = fetchData(request, context);
            return processData(data, context, metadata);
        } catch (Exception e) {
            System.err.println("Non-paged task " + getTaskName() + " execution failed: " + e.getMessage());
            return Collections.emptyMap();
        }
    }
    
    protected abstract Object buildRequest(Context context, Metadata metadata);
    protected abstract List<F> fetchData(Object request, Context context);
}

6. 具体任务实现
// TaskB.java - B接口任务实现
@Component
public class TaskB extends AbstractPagedTask {
    private final ApiClientB apiClientB;
    
    @Autowired
    public TaskB(@Qualifier("taskExecutor") ExecutorService executorService,
                @Qualifier("bPreFilter") PreFilter preFilter,
                @Qualifier("bPostFilter") PostFilter postFilter,
                ApiClientB apiClientB) {
        super(executorService, preFilter, postFilter, TaskType.B);
        this.apiClientB = apiClientB;
    }
    
    @Override
    protected Object buildFirstPageRequest(Context context, Metadata metadata) {
        return RequestB.builder()
            .param1(context.getParameterAsString("param1"))
            .param2(context.getParameterAsInteger("param2"))
            .page(1)
            .pageSize(metadata.get("defaultPageSize", Integer.class))
            .tenantId(context.getTenantId())
            .build();
    }
    
    @Override
    protected Object buildPageRequest(Context context, Metadata metadata, int page) {
        return RequestB.builder()
            .param1(context.getParameterAsString("param1"))
            .param2(context.getParameterAsInteger("param2"))
            .page(page)
            .pageSize(metadata.get("defaultPageSize", Integer.class))
            .tenantId(context.getTenantId())
            .build();
    }
    
    @Override
    protected PageResponse<F> fetchPage(Object request, Context context) {
        try {
            return apiClientB.callApi((RequestB) request);
        } catch (Exception e) {
            throw new RuntimeException("API B call failed for request: " + request, e);
        }
    }
}

// TaskB2.java - B2接口任务实现
@Component
public class TaskB2 extends NonPagedTask {
    private final ApiClientB2 apiClientB2;
    
    @Autowired
    public TaskB2(@Qualifier("taskExecutor") ExecutorService executorService,
                 @Qualifier("b2PostFilter") PostFilter postFilter,
                 ApiClientB2 apiClientB2) {
        super(executorService, null, postFilter, TaskType.B2);
        this.apiClientB2 = apiClientB2;
    }
    
    @Override
    protected Object buildRequest(Context context, Metadata metadata) {
        return RequestB2.builder()
            .domain(context.getParameterAsString("domain"))
            .category(metadata.get("b2Category", String.class))
            .tenantId(context.getTenantId())
            .build();
    }
    
    @Override
    protected List<F> fetchData(Object request, Context context) {
        try {
            return apiClientB2.callApi((RequestB2) request);
        } catch (Exception e) {
            throw new RuntimeException("API B2 call failed for request: " + request, e);
        }
    }
}

7. 结果合并策略
// ResultMergeStrategy.java - 合并策略接口
public interface ResultMergeStrategy {
    String getStrategyName();
    Map<String, G> merge(Map<String, G> map1, Map<String, G> map2);
}

// OverrideMergeStrategy.java - 覆盖合并策略
@Component
public class OverrideMergeStrategy implements ResultMergeStrategy {
    @Override
    public String getStrategyName() {
        return "override";
    }
    
    @Override
    public Map<String, G> merge(Map<String, G> map1, Map<String, G> map2) {
        Map<String, G> result = new ConcurrentHashMap<>(map1);
        result.putAll(map2);
        return result;
    }
}

// CompositeMergeStrategy.java - 复合合并策略
@Component
public class CompositeMergeStrategy implements ResultMergeStrategy {
    @Override
    public String getStrategyName() {
        return "composite";
    }
    
    @Override
    public Map<String, G> merge(Map<String, G> map1, Map<String, G> map2) {
        Map<String, G> result = new ConcurrentHashMap<>(map1);
        
        map2.forEach((key, newValue) -> {
            if (result.containsKey(key)) {
                G existingValue = result.get(key);
                G mergedValue = mergeGObjects(existingValue, newValue);
                result.put(key, mergedValue);
            } else {
                result.put(key, newValue);
            }
        });
        
        return result;
    }
    
    private G mergeGObjects(G existing, G newValue) {
        // 实现复杂的合并逻辑
        Map<String, Object> mergedData = new ConcurrentHashMap<>();
        if (existing.getCalculatedData() != null) {
            mergedData.putAll(existing.getCalculatedData());
        }
        if (newValue.getCalculatedData() != null) {
            mergedData.putAll(newValue.getCalculatedData());
        }
        
        return G.newBuilder()
            .compositeKey(existing.getMetricCode(), existing.getDomainCode(), existing.getOrgCode())
            .calculatedData(mergedData)
            .build();
    }
}

// ResultMerger.java - 结果合并器
@Component
public class ResultMerger {
    private final Map<String, ResultMergeStrategy> strategies;
    
    @Autowired
    public ResultMerger(List<ResultMergeStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toMap(ResultMergeStrategy::getStrategyName, Function.identity()));
    }
    
    public Map<String, G> merge(List<Map<String, G>> results, String strategyName) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyMap();
        }
        
        ResultMergeStrategy strategy = strategies.getOrDefault(strategyName, strategies.get("override"));
        
        return results.stream()
            .reduce(new ConcurrentHashMap<>(), strategy::merge);
    }
    
    public Map<String, G> merge(Map<String, G> result1, Map<String, G> result2, String strategyName) {
        return merge(Arrays.asList(result1, result2), strategyName);
    }
}

8. 任务工厂和配置
// TaskConfig.java - 任务配置
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskConfig {
    private TaskType taskType;
    private String mergeStrategy;
    private boolean enabled;
    private int timeoutSeconds;
    
    public static TaskConfig defaultConfig(TaskType taskType) {
        return TaskConfig.builder()
            .taskType(taskType)
            .mergeStrategy("override")
            .enabled(true)
            .timeoutSeconds(30)
            .build();
    }
}

// TaskFactory.java - 任务工厂
@Component
public class TaskFactory {
    private final Map<TaskType, DataFetchTask> taskMap;
    
    @Autowired
    public TaskFactory(List<DataFetchTask> tasks) {
        this.taskMap = tasks.stream()
            .collect(Collectors.toMap(DataFetchTask::getTaskType, Function.identity()));
    }
    
    public DataFetchTask createTask(TaskType taskType) {
        DataFetchTask task = taskMap.get(taskType);
        if (task == null) {
            throw new IllegalArgumentException("Unsupported task type: " + taskType);
        }
        return task;
    }
    
    public List<DataFetchTask> createTasks(List<TaskType> taskTypes) {
        return taskTypes.stream()
            .map(this::createTask)
            .collect(Collectors.toList());
    }
}

9. 核心聚合服务
// DataAggregationService.java - 核心聚合服务
@Service
@Slf4j
public class DataAggregationService {
    private final TaskFactory taskFactory;
    private final ResultMerger resultMerger;
    private final ExecutorService taskExecutor;
    
    @Autowired
    public DataAggregationService(TaskFactory taskFactory, 
                                 ResultMerger resultMerger,
                                 @Qualifier("taskExecutor") ExecutorService taskExecutor) {
        this.taskFactory = taskFactory;
        this.resultMerger = resultMerger;
        this.taskExecutor = taskExecutor;
    }
    
    public Map<String, G> aggregateData(Context context, Metadata metadata, 
                                       List<TaskConfig> taskConfigs) {
        return aggregateData(context, metadata, taskConfigs, "override");
    }
    
    public Map<String, G> aggregateData(Context context, Metadata metadata,
                                       List<TaskConfig> taskConfigs, String finalMergeStrategy) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 过滤启用的任务配置
            List<TaskConfig> enabledConfigs = taskConfigs.stream()
                .filter(TaskConfig::isEnabled)
                .collect(Collectors.toList());
            
            // 并行执行所有任务
            List<CompletableFuture<TaskResult>> futures = enabledConfigs.stream()
                .map(config -> executeTaskWithTimeout(config, context, metadata))
                .collect(Collectors.toList());
            
            // 等待所有任务完成
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            List<TaskResult> taskResults = allFutures.thenApply(v -> 
                futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList())
            ).join();
            
            // 按合并策略分组合并
            Map<String, List<Map<String, G>>> resultsByStrategy = taskResults.stream()
                .collect(Collectors.groupingBy(
                    TaskResult::getMergeStrategy,
                    Collectors.mapping(TaskResult::getResult, Collectors.toList())
                ));
            
            // 按策略合并结果
            Map<String, G> finalResult = new ConcurrentHashMap<>();
            for (Map.Entry<String, List<Map<String, G>>> entry : resultsByStrategy.entrySet()) {
                Map<String, G> strategyResult = resultMerger.merge(entry.getValue(), entry.getKey());
                finalResult = resultMerger.merge(finalResult, strategyResult, finalMergeStrategy);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Data aggregation completed in {}ms for {} tasks", duration, taskResults.size());
            
            return finalResult;
            
        } catch (Exception e) {
            log.error("Data aggregation failed", e);
            throw new RuntimeException("Data aggregation failed", e);
        }
    }
    
    private CompletableFuture<TaskResult> executeTaskWithTimeout(TaskConfig config, 
                                                                Context context, 
                                                                Metadata metadata) {
        return CompletableFuture.supplyAsync(() -> {
            long taskStartTime = System.currentTimeMillis();
            try {
                DataFetchTask task = taskFactory.createTask(config.getTaskType());
                Map<String, G> result = task.execute(context, metadata);
                long taskDuration = System.currentTimeMillis() - taskStartTime;
                
                log.info("Task {} completed in {}ms", config.getTaskType(), taskDuration);
                
                return TaskResult.builder()
                    .taskType(config.getTaskType())
                    .result(result)
                    .mergeStrategy(config.getMergeStrategy())
                    .success(true)
                    .duration(taskDuration)
                    .build();
                    
            } catch (Exception e) {
                log.error("Task {} execution failed", config.getTaskType(), e);
                return TaskResult.builder()
                    .taskType(config.getTaskType())
                    .result(Collections.emptyMap())
                    .mergeStrategy(config.getMergeStrategy())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
            }
        }, taskExecutor).orTimeout(config.getTimeoutSeconds(), TimeUnit.SECONDS);
    }
}

// TaskResult.java - 任务执行结果
@Data
@Builder
class TaskResult {
    private TaskType taskType;
    private Map<String, G> result;
    private String mergeStrategy;
    private boolean success;
    private String errorMessage;
    private long duration;
}

10. 配置类
// ExecutorConfig.java - 线程池配置
@Configuration
@EnableAsync
public class ExecutorConfig {
    
    @Value("${task.executor.corePoolSize:10}")
    private int corePoolSize;
    
    @Value("${task.executor.maxPoolSize:50}")
    private int maxPoolSize;
    
    @Value("${task.executor.queueCapacity:100}")
    private int queueCapacity;
    
    @Bean(name = "taskExecutor")
    public ExecutorService taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("data-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }
}

// FilterConfig.java - 过滤器配置
@Configuration
public class FilterConfig {
    
    @Bean
    @Qualifier("bPreFilter")
    public PreFilter bPreFilter() {
        return new CompositePreFilter(Arrays.asList(
            new ValidationPreFilter(),
            new BusinessPreFilter()
        ));
    }
    
    @Bean
    @Qualifier("bPostFilter")
    public PostFilter bPostFilter() {
        return (item, context, metadata) -> {
            // 实现B接口的后置处理逻辑
            return G.newBuilder()
                .compositeKey(item.getMetricCode(), item.getDomainCode(), item.getOrgCode())
                .addCalculatedData("value", calculateValue(item, context, metadata))
                .addMetadata("source", "B")
                .build();
        };
    }
    
    @Bean
    @Qualifier("b2PostFilter")
    public PostFilter b2PostFilter() {
        return (item, context, metadata) -> {
            // 实现B2接口的后置处理逻辑
            return G.newBuilder()
                .compositeKey(item.getMetricCode(), item.getDomainCode(), item.getOrgCode())
                .addCalculatedData("b2Value", calculateB2Value(item, context, metadata))
                .addMetadata("source", "B2")
                .build();
        };
    }
    
    private Object calculateValue(F item, Context context, Metadata metadata) {
        // 实现计算逻辑
        return item.getAttributes().get("value");
    }
    
    private Object calculateB2Value(F item, Context context, Metadata metadata) {
        // 实现B2计算逻辑
        return item.getAttributes().get("b2Value");
    }
}

11. 控制器
// DataAggregationController.java - REST控制器
@RestController
@RequestMapping("/api/data")
@Slf4j
public class DataAggregationController {
    
    @Autowired
    private DataAggregationService dataAggregationService;
    
    @Autowired
    private Metadata metadata;
    
    @PostMapping("/aggregate")
    public ApiResponse<Map<String, G>> aggregateData(@RequestBody AggregateRequest request) {
        try {
            Context context = buildContext(request);
            List<TaskConfig> taskConfigs = buildTaskConfigs(request.getTaskTypes());
            
            Map<String, G> result = dataAggregationService.aggregateData(
                context, metadata, taskConfigs, request.getMergeStrategy());
                
            return ApiResponse.success(result);
            
        } catch (Exception e) {
            log.error("Aggregate data failed", e);
            return ApiResponse.error("AGGREGATION_FAILED", e.getMessage());
        }
    }
    
    private Context buildContext(AggregateRequest request) {
        return Context.builder()
            .requestId(UUID.randomUUID().toString())
            .parameters(request.getParameters())
            .userId(request.getUserId())
            .tenantId(request.getTenantId())
            .requestTime(Instant.now())
            .build();
    }
    
    private List<TaskConfig> buildTaskConfigs(List<String> taskTypes) {
        return taskTypes.stream()
            .map(type -> {
                try {
                    TaskType taskType = TaskType.valueOf(type.toUpperCase());
                    return TaskConfig.defaultConfig(taskType);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid task type: {}", type);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

// AggregateRequest.java - 聚合请求DTO
@Data
public class AggregateRequest {
    private List<String> taskTypes;
    private Map<String, Object> parameters;
    private Long userId;
    private String tenantId;
    private String mergeStrategy = "override";
}

12. 应用配置

# application.yml
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    database: 0
    timeout: 2000ms

task:
  executor:
    corePoolSize: 10
    maxPoolSize: 50
    queueCapacity: 100

logging:
  level:
    com.yourpackage: INFO
    
这个完整的生产级别实现提供了：

高性能：并行执行所有任务和分页请求

可扩展性：易于添加新的任务类型和合并策略

容错性：单个任务失败不影响其他任务

可配置性：支持动态配置和策略选择

监控性：完整的日志和性能监控

线程安全：使用并发集合和安全的建造者模式

可以根据具体业务需求进一步调整和优化。