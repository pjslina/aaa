�������ٵ���ս��

ԭ�е����ӿ�B��Ϊ����ӿڣ�B��B1��B2�ȣ�����Ҫ���е��ã�Ȼ��ϲ������

��ͬ�ӿڿ����з�ҳ�ͷǷ�ҳ��������ǰ�úͺ��ù��������ܲ�ͬ��

�ϲ����ʱ�����key��ͬ����Ҫ�ϲ�value���ϲ����Կ��ܲ�ͬ����

���˼·��

ʹ������ģʽ��Task������װÿ���ӿڵĵ����߼���������ҳ�������˵ȣ���

ʹ�ù���ģʽ��������ͬ������ʵ����

ʹ�����ģʽ���ϲ��������Ľ����

ʹ�ò���ģʽ�����岻ͬ�ĺϲ����ԣ��縲�ǡ����Ժϲ��ȣ���

ʹ��ģ�巽��ģʽ�������ҳ�ͷǷ�ҳ��������̡�

���裺

��������ӿڣ�����ִ�з�����

ʵ�־������񣨷�ҳ����ͷǷ�ҳ���񣩡�

����ϲ����Խӿڣ�ʵ�ֶ��ֺϲ����ԡ�

����һ���ۺϷ������ڲ���ִ�ж�����񣬲��ϲ������

����ÿ�������Ӧ�Ĺ������ͺϲ����ԡ�

��ϸ���룺

���ȣ���������ӿڣ�

�����������������������ʵ�֣�

1. ����ģ�ͺ�ö��
// Context.java - �����Ķ���
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
    
    // ҵ�������ȡ����
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

// Metadata.java - Ԫ���ݶ���֧�ֶ�̬���£�
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
        // ��ʼ������
        refreshMetadata();
        // ÿ����ˢ��һ��
        scheduler.scheduleAtFixedRate(this::refreshMetadata, 1, 1, TimeUnit.MINUTES);
    }
    
    public void refreshMetadata() {
        try {
            Map<Object, Object> redisData = redisTemplate.opsForHash().entries(METADATA_KEY);
            Map<String, Object> newMetadata = new ConcurrentHashMap<>();
            redisData.forEach((k, v) -> newMetadata.put(k.toString(), v));
            metadataRef.set(newMetadata);
        } catch (Exception e) {
            // ��¼��־�����жϷ���
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

2. ��������ؽӿں�ʵ��

// Filter.java - ��������ǽӿ�
public interface Filter {}

// PreFilter.java - ǰ�ù������ӿ�
public interface PreFilter extends Filter {
    List<F> filter(List<F> rawData, Context context, Metadata metadata);
}

// PostFilter.java - ���ù������ӿ�
public interface PostFilter extends Filter {
    G process(F item, Context context, Metadata metadata);
}

// CompositePreFilter.java - ���ǰ�ù�����
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
            // ��¼������ִ��ʱ��
            System.out.println(filter.getClass().getSimpleName() + " executed in " + duration + "ms");
        }
        return result;
    }
}

// ValidationPreFilter.java - ��֤������ʾ��
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
        // ʵ����֤�߼�
        return item != null && 
               item.getMetricCode() != null && 
               !item.getMetricCode().trim().isEmpty();
    }
}

// BusinessPreFilter.java - ҵ�������ʾ��
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
        // ʵ��ҵ���߼�
        return true;
    }
}

3. ����ģ��
// F.java - ԭʼ���ݶ���
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

// G.java - ���������ݶ���ʹ�ý�����ģʽ��
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
    
    // �̰߳�ȫ�Ľ�����
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

4. API��Ӧģ��
// PageResponse.java - ��ҳ��Ӧ
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

// ApiResponse.java - ͳһAPI��Ӧ
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
            .message("�����ɹ�")
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

5. ����ִ�п��
// TaskType.java - ��������ö��
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

// DataFetchTask.java - ����ӿ�
public interface DataFetchTask {
    String getTaskName();
    TaskType getTaskType();
    Map<String, G> execute(Context context, Metadata metadata);
    boolean supportsPaging();
    boolean requiresPreFilter();
}

// AbstractDataFetchTask.java - �����������
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
                (existing, replacement) -> replacement // ��ͻ�������
            ));
    }
    
    protected Map<String, G> mergeResults(Map<String, G> map1, Map<String, G> map2) {
        Map<String, G> result = new ConcurrentHashMap<>(map1);
        result.putAll(map2);
        return result;
    }
}

// AbstractPagedTask.java - ��ҳ���������
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
            // ��ȡ��һҳ����
            Object firstPageRequest = buildFirstPageRequest(context, metadata);
            PageResponse<F> firstPage = fetchPage(firstPageRequest, context);
            
            Map<String, G> result = processData(firstPage.getData(), context, metadata);
            
            // ����ж�ҳ�����л�ȡʣ��ҳ
            if (firstPage.getTotalPages() > 1) {
                List<CompletableFuture<Map<String, G>>> futures = createPageFutures(
                    context, metadata, firstPage.getTotalPages());
                
                // �ȴ�����������ɲ��ϲ����
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

// NonPagedTask.java - �Ƿ�ҳ���������
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

6. ��������ʵ��
// TaskB.java - B�ӿ�����ʵ��
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

// TaskB2.java - B2�ӿ�����ʵ��
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

7. ����ϲ�����
// ResultMergeStrategy.java - �ϲ����Խӿ�
public interface ResultMergeStrategy {
    String getStrategyName();
    Map<String, G> merge(Map<String, G> map1, Map<String, G> map2);
}

// OverrideMergeStrategy.java - ���Ǻϲ�����
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

// CompositeMergeStrategy.java - ���Ϻϲ�����
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
        // ʵ�ָ��ӵĺϲ��߼�
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

// ResultMerger.java - ����ϲ���
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

8. ���񹤳�������
// TaskConfig.java - ��������
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

// TaskFactory.java - ���񹤳�
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

9. ���ľۺϷ���
// DataAggregationService.java - ���ľۺϷ���
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
            // �������õ���������
            List<TaskConfig> enabledConfigs = taskConfigs.stream()
                .filter(TaskConfig::isEnabled)
                .collect(Collectors.toList());
            
            // ����ִ����������
            List<CompletableFuture<TaskResult>> futures = enabledConfigs.stream()
                .map(config -> executeTaskWithTimeout(config, context, metadata))
                .collect(Collectors.toList());
            
            // �ȴ������������
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            List<TaskResult> taskResults = allFutures.thenApply(v -> 
                futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList())
            ).join();
            
            // ���ϲ����Է���ϲ�
            Map<String, List<Map<String, G>>> resultsByStrategy = taskResults.stream()
                .collect(Collectors.groupingBy(
                    TaskResult::getMergeStrategy,
                    Collectors.mapping(TaskResult::getResult, Collectors.toList())
                ));
            
            // �����Ժϲ����
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

// TaskResult.java - ����ִ�н��
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

10. ������
// ExecutorConfig.java - �̳߳�����
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

// FilterConfig.java - ����������
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
            // ʵ��B�ӿڵĺ��ô����߼�
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
            // ʵ��B2�ӿڵĺ��ô����߼�
            return G.newBuilder()
                .compositeKey(item.getMetricCode(), item.getDomainCode(), item.getOrgCode())
                .addCalculatedData("b2Value", calculateB2Value(item, context, metadata))
                .addMetadata("source", "B2")
                .build();
        };
    }
    
    private Object calculateValue(F item, Context context, Metadata metadata) {
        // ʵ�ּ����߼�
        return item.getAttributes().get("value");
    }
    
    private Object calculateB2Value(F item, Context context, Metadata metadata) {
        // ʵ��B2�����߼�
        return item.getAttributes().get("b2Value");
    }
}

11. ������
// DataAggregationController.java - REST������
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

// AggregateRequest.java - �ۺ�����DTO
@Data
public class AggregateRequest {
    private List<String> taskTypes;
    private Map<String, Object> parameters;
    private Long userId;
    private String tenantId;
    private String mergeStrategy = "override";
}

12. Ӧ������

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
    
�����������������ʵ���ṩ�ˣ�

�����ܣ�����ִ����������ͷ�ҳ����

����չ�ԣ���������µ��������ͺͺϲ�����

�ݴ��ԣ���������ʧ�ܲ�Ӱ����������

�������ԣ�֧�ֶ�̬���úͲ���ѡ��

����ԣ���������־�����ܼ��

�̰߳�ȫ��ʹ�ò������ϺͰ�ȫ�Ľ�����ģʽ

���Ը��ݾ���ҵ�������һ���������Ż���