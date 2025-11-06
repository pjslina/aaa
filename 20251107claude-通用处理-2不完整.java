我将为您设计一个高性能、可扩展的指标数据聚合服务架构。这个设计将充分利用现有框架能力，并提供清晰的分层结构。
指标服务聚合服务架构设计
// ============================================================================
// 1. 核心上下文与元数据结构
// ============================================================================

/**
 * 全局执行上下文
 */
@Data
public class MetricExecutionContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // 请求参数
    private MeasureReqVO request;
    
    // 元数据缓存引用
    private Map<String, MetricMeta> metricMetaMap;
    private Map<String, MeasureMeta> measureMetaMap;
    private Map<String, DomainMeta> domainMetaMap;
    private Map<String, OrgMeta> orgMetaMap;
    
    // API执行结果缓存（用于依赖关系）
    private ConcurrentHashMap<String, Object> apiResultCache = new ConcurrentHashMap<>();
    
    // 线程安全的聚合结果容器
    private ConcurrentHashMap<String, ConcurrentHashMap<String, List<MeasureDataVO>>> aggregationMap = new ConcurrentHashMap<>();
    
    // 执行配置
    private MetricSceneConfig sceneConfig;
}

/**
 * 场景配置（基于sceneType）
 */
@Data
public class MetricSceneConfig {
    private String sceneType;
    private List<ApiExecutionConfig> apiConfigs;
    private List<String> filterChain; // 过滤器Bean名称列表
}

/**
 * API执行配置
 */
@Data
public class ApiExecutionConfig {
    private String apiName;
    private String apiHandlerBean; // Spring Bean名称
    private boolean pageable; // 是否分页
    private Integer pageSize;
    private List<String> dependencies; // 依赖的API名称
    private String conditionExpression; // SpEL表达式
    private int priority; // 优先级，用于排序
}

// ============================================================================
// 2. API调用层 - 统一API处理器接口
// ============================================================================

/**
 * API处理器接口（泛型适配器）
 */
public interface IApiHandler<T> {
    /**
     * 执行API调用
     * @param context 执行上下文
     * @param pageNum 页码（分页场景）
     * @param pageSize 每页大小
     * @return API返回结果
     */
    ApiResponse<T> execute(MetricExecutionContext context, Integer pageNum, Integer pageSize) throws ApplicationException;
    
    /**
     * 获取API标识
     */
    String getApiName();
    
    /**
     * 是否支持分页
     */
    boolean supportPagination();
}

/**
 * API响应包装
 */
@Data
public class ApiResponse<T> {
    private List<T> data;
    private Integer totalCount;
    private Integer currentPage;
    private Integer pageSize;
    private boolean hasMore;
}

/**
 * API调用编排器
 */
@Service
public class ApiOrchestrator {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private ItaskExecutorService taskExecutorService;
    
    @Autowired
    private ExpressionEvaluator expressionEvaluator;
    
    /**
     * 编排执行所有API调用
     */
    public void orchestrateApiCalls(MetricExecutionContext context) throws ApplicationException {
        MetricSceneConfig config = context.getSceneConfig();
        
        // 按优先级和依赖关系排序
        List<ApiExecutionConfig> sortedConfigs = sortByDependency(config.getApiConfigs());
        
        // 分组：无依赖的并行执行，有依赖的顺序执行
        Map<Boolean, List<ApiExecutionConfig>> grouped = sortedConfigs.stream()
            .collect(Collectors.partitioningBy(c -> c.getDependencies() == null || c.getDependencies().isEmpty()));
        
        // 第一批：并行执行无依赖的API
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ApiExecutionConfig apiConfig : grouped.get(true)) {
            if (shouldExecute(apiConfig, context)) {
                CompletableFuture<Void> future = executeApiAsync(apiConfig, context);
                futures.add(future);
            }
        }
        
        // 等待第一批完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 第二批：顺序执行有依赖的API
        for (ApiExecutionConfig apiConfig : grouped.get(false)) {
            if (shouldExecute(apiConfig, context)) {
                executeApiAsync(apiConfig, context).join();
            }
        }
    }
    
    /**
     * 异步执行单个API
     */
    private CompletableFuture<Void> executeApiAsync(ApiExecutionConfig apiConfig, MetricExecutionContext context) {
        return taskExecutorService.submitTask(() -> {
            try {
                IApiHandler<?> handler = applicationContext.getBean(apiConfig.getApiHandlerBean(), IApiHandler.class);
                
                if (handler.supportPagination() && apiConfig.isPageable()) {
                    // 分页场景：先同步第一页，再并行请求剩余页
                    executeWithPagination(handler, apiConfig, context);
                } else {
                    // 非分页场景：直接调用
                    ApiResponse<?> response = handler.execute(context, null, null);
                    processApiResponse(apiConfig.getApiName(), response, context);
                }
                return null;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, "API-" + apiConfig.getApiName());
    }
    
    /**
     * 分页执行
     */
    private void executeWithPagination(IApiHandler<?> handler, ApiExecutionConfig apiConfig, MetricExecutionContext context) throws ApplicationException {
        // 第一页同步获取
        ApiResponse<?> firstPage = handler.execute(context, 1, apiConfig.getPageSize());
        processApiResponse(apiConfig.getApiName(), firstPage, context);
        
        if (!firstPage.isHasMore()) {
            return;
        }
        
        // 计算总页数
        int totalPages = (int) Math.ceil((double) firstPage.getTotalCount() / apiConfig.getPageSize());
        
        // 并行获取剩余页
        List<CompletableFuture<Void>> pageFutures = new ArrayList<>();
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            CompletableFuture<Void> future = taskExecutorService.submitTask(() -> {
                try {
                    ApiResponse<?> pageResponse = handler.execute(context, currentPage, apiConfig.getPageSize());
                    processApiResponse(apiConfig.getApiName(), pageResponse, context);
                    return null;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, "API-" + apiConfig.getApiName() + "-Page-" + currentPage);
            pageFutures.add(future);
        }
        
        CompletableFuture.allOf(pageFutures.toArray(new CompletableFuture[0])).join();
    }
    
    /**
     * 处理API响应
     */
    private void processApiResponse(String apiName, ApiResponse<?> response, MetricExecutionContext context) {
        // 缓存结果供依赖使用
        context.getApiResultCache().put(apiName, response.getData());
    }
    
    /**
     * 检查是否应该执行（条件表达式）
     */
    private boolean shouldExecute(ApiExecutionConfig apiConfig, MetricExecutionContext context) {
        if (apiConfig.getConditionExpression() == null) {
            return true;
        }
        return expressionEvaluator.evaluate(apiConfig.getConditionExpression(), context);
    }
    
    /**
     * 按依赖关系拓扑排序
     */
    private List<ApiExecutionConfig> sortByDependency(List<ApiExecutionConfig> configs) {
        // 简单实现：按priority排序，实际应使用拓扑排序算法
        return configs.stream()
            .sorted(Comparator.comparingInt(ApiExecutionConfig::getPriority))
            .collect(Collectors.toList());
    }
}

// ============================================================================
// 3. 过滤层 - 可配置过滤器链
// ============================================================================

/**
 * 数据过滤器接口
 */
public interface IDataFilter<T> {
    /**
     * 过滤数据
     * @param data 原始数据
     * @param context 执行上下文
     * @return 过滤后的数据
     */
    List<T> filter(List<T> data, MetricExecutionContext context);
    
    /**
     * 获取过滤器顺序
     */
    int getOrder();
}

/**
 * 过滤器链执行器
 */
@Service
public class FilterChainExecutor {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * 执行过滤器链
     */
    public <T> List<T> executeFilterChain(List<T> data, MetricExecutionContext context) {
        List<String> filterBeanNames = context.getSceneConfig().getFilterChain();
        if (filterBeanNames == null || filterBeanNames.isEmpty()) {
            return data;
        }
        
        List<T> result = data;
        
        // 获取并排序过滤器
        List<IDataFilter<T>> filters = filterBeanNames.stream()
            .map(beanName -> (IDataFilter<T>) applicationContext.getBean(beanName))
            .sorted(Comparator.comparingInt(IDataFilter::getOrder))
            .collect(Collectors.toList());
        
        // 依次执行
        for (IDataFilter<T> filter : filters) {
            result = filter.filter(result, context);
            if (result == null || result.isEmpty()) {
                break;
            }
        }
        
        return result;
    }
}

// 示例过滤器：组织范围过滤
@Component("orgScopeFilter")
public class OrgScopeFilter<T> implements IDataFilter<T> {
    
    @Override
    public List<T> filter(List<T> data, MetricExecutionContext context) {
        // 根据组织层级和编码过滤
        Set<String> allowedOrgCodes = resolveOrgScope(context);
        
        return data.stream()
            .filter(item -> {
                String orgCode = extractOrgCode(item);
                return allowedOrgCodes.contains(orgCode);
            })
            .collect(Collectors.toList());
    }
    
    @Override
    public int getOrder() {
        return 1;
    }
    
    private Set<String> resolveOrgScope(MetricExecutionContext context) {
        // 实现组织层级展开逻辑
        return Collections.emptySet();
    }
    
    private String extractOrgCode(Object item) {
        // 通过反射或约定方法提取orgCode
        return "";
    }
}

// ============================================================================
// 4. 转换层 - 统一数据转换器
// ============================================================================

/**
 * 数据转换器接口
 */
public interface IDataConverter<S, T> {
    /**
     * 转换单条数据
     */
    T convert(S source, MetricExecutionContext context);
    
    /**
     * 批量转换
     */
    default List<T> convertBatch(List<S> sources, MetricExecutionContext context) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        return sources.stream()
            .map(s -> convert(s, context))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

/**
 * 转换为度量数据的转换器
 */
public interface IMeasureDataConverter<S> extends IDataConverter<S, MeasureDataVO> {
    /**
     * 提取业务Key（用于聚合）
     */
    String extractBusinessKey(S source, MetricExecutionContext context);
    
    /**
     * 提取周期ID
     */
    String extractPeriodId(S source, MetricExecutionContext context);
}

/**
 * 转换器注册中心
 */
@Service
public class ConverterRegistry {
    
    private final Map<String, IMeasureDataConverter<?>> converterMap = new ConcurrentHashMap<>();
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @PostConstruct
    public void init() {
        // 自动注册所有转换器
        Map<String, IMeasureDataConverter> beans = applicationContext.getBeansOfType(IMeasureDataConverter.class);
        beans.forEach((name, converter) -> converterMap.put(name, converter));
    }
    
    public <T> IMeasureDataConverter<T> getConverter(String converterName) {
        return (IMeasureDataConverter<T>) converterMap.get(converterName);
    }
}

// 示例转换器
@Component("standardMeasureConverter")
public class StandardMeasureConverter implements IMeasureDataConverter<Map<String, Object>> {
    
    @Override
    public MeasureDataVO convert(Map<String, Object> source, MetricExecutionContext context) {
        MeasureDataVO vo = new MeasureDataVO();
        
        // 从source提取数据
        vo.setMeasureCode((String) source.get("measureCode"));
        vo.setUnit((String) source.get("unit"));
        vo.setCurrency((String) source.get("currency"));
        
        // 计算originValue
        BigDecimal originValue = calculateOriginValue(source, context);
        vo.setOriginValue(originValue.toPlainString());
        
        // 计算fixedValue（四舍五入）
        MeasureMeta meta = context.getMeasureMetaMap().get(vo.getMeasureCode());
        int precision = meta != null ? meta.getPrecision() : 2;
        vo.setFixedValue(originValue.setScale(precision, RoundingMode.HALF_UP).toPlainString());
        
        return vo;
    }
    
    @Override
    public String extractBusinessKey(Map<String, Object> source, MetricExecutionContext context) {
        String metricCode = (String) source.get("metricCode");
        String orgCode = (String) source.get("orgCode");
        String domainCode = (String) source.get("domainCode");
        return String.format("%s:::%s:::%s", metricCode, orgCode, domainCode);
    }
    
    @Override
    public String extractPeriodId(Map<String, Object> source, MetricExecutionContext context) {
        return (String) source.getOrDefault("periodId", context.getRequest().getPeriodId());
    }
    
    private BigDecimal calculateOriginValue(Map<String, Object> source, MetricExecutionContext context) {
        // 实现具体计算逻辑
        return BigDecimal.ZERO;
    }
}

// ============================================================================
// 5. 聚合层 - 无锁线程安全聚合
// ============================================================================

/**
 * Key生成策略接口（扩展点）
 */
public interface IKeyGenerator {
    /**
     * 生成聚合Key
     */
    String generateKey(Object source, MetricExecutionContext context);
}

/**
 * 默认Key生成器：metricCode:::orgCode:::domainCode
 */
@Component("defaultKeyGenerator")
public class DefaultKeyGenerator implements IKeyGenerator {
    
    @Override
    public String generateKey(Object source, MetricExecutionContext context) {
        // 从source提取字段
        if (source instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) source;
            return String.format("%s:::%s:::%s",
                map.get("metricCode"),
                map.get("orgCode"),
                map.get("domainCode"));
        }
        throw new IllegalArgumentException("Unsupported source type");
    }
}

/**
 * 数据聚合器
 */
@Service
public class DataAggregator {
    
    @Autowired
    private ConverterRegistry converterRegistry;
    
    @Autowired
    private FilterChainExecutor filterChainExecutor;
    
    /**
     * 聚合单个API的数据
     */
    public <T> void aggregateApiData(String apiName, List<T> rawData, 
                                      String converterName, MetricExecutionContext context) {
        if (rawData == null || rawData.isEmpty()) {
            return;
        }
        
        // 1. 执行过滤器链
        List<T> filteredData = filterChainExecutor.executeFilterChain(rawData, context);
        
        // 2. 获取转换器
        IMeasureDataConverter<T> converter = converterRegistry.getConverter(converterName);
        
        // 3. 转换并聚合（使用ConcurrentHashMap保证线程安全）
        for (T item : filteredData) {
            String periodId = converter.extractPeriodId(item, context);
            String businessKey = converter.extractBusinessKey(item, context);
            MeasureDataVO measureData = converter.convert(item, context);
            
            if (measureData != null) {
                // 使用computeIfAbsent保证线程安全
                context.getAggregationMap()
                    .computeIfAbsent(periodId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(businessKey, k -> new CopyOnWriteArrayList<>())
                    .add(measureData);
            }
        }
    }
    
    /**
     * 构建最终响应
     */
    public List<OpMetricDataRespVO> buildResponse(MetricExecutionContext context) {
        return context.getAggregationMap().entrySet().stream()
            .map(entry -> {
                OpMetricDataRespVO vo = new OpMetricDataRespVO();
                vo.setPeriodId(entry.getKey());
                vo.setMeasureMap(new HashMap<>(entry.getValue()));
                return vo;
            })
            .collect(Collectors.toList());
    }
}

// ============================================================================
// 6. 主服务 - 统一编排
// ============================================================================

@Service
public class MetricDataService {
    
    @Autowired
    private MetricSceneConfigLoader sceneConfigLoader;
    
    @Autowired
    private MetadataCache metadataCache;
    
    @Autowired
    private ApiOrchestrator apiOrchestrator;
    
    @Autowired
    private DataAggregator dataAggregator;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * 获取度量数据（主入口）
     */
    public List<OpMetricDataRespVO> getMeasures(MeasureReqVO reqVO) throws ApplicationException {
        // 1. 构建执行上下文
        MetricExecutionContext context = buildContext(reqVO);
        
        // 2. 编排执行所有API调用
        apiOrchestrator.orchestrateApiCalls(context);
        
        // 3. 聚合所有API结果
        aggregateAllApiResults(context);
        
        // 4. 构建响应
        return dataAggregator.buildResponse(context);
    }
    
    /**
     * 构建执行上下文
     */
    private MetricExecutionContext buildContext(MeasureReqVO reqVO) {
        MetricExecutionContext context = new MetricExecutionContext();
        context.setRequest(reqVO);
        
        // 加载元数据
        context.setMetricMetaMap(metadataCache.getMetricMeta());
        context.setMeasureMetaMap(metadataCache.getMeasureMeta());
        context.setDomainMetaMap(metadataCache.getDomainMeta());
        context.setOrgMetaMap(metadataCache.getOrgMeta());
        
        // 加载场景配置
        MetricSceneConfig sceneConfig = sceneConfigLoader.loadConfig(reqVO.getSceneType());
        context.setSceneConfig(sceneConfig);
        
        return context;
    }
    
    /**
     * 聚合所有API结果
     */
    private void aggregateAllApiResults(MetricExecutionContext context) {
        context.getSceneConfig().getApiConfigs().forEach(apiConfig -> {
            Object apiResult = context.getApiResultCache().get(apiConfig.getApiName());
            if (apiResult instanceof List) {
                // 从配置中获取转换器名称（可扩展为配置）
                String converterName = resolveConverterName(apiConfig);
                dataAggregator.aggregateApiData(
                    apiConfig.getApiName(),
                    (List<?>) apiResult,
                    converterName,
                    context
                );
            }
        });
    }
    
    private String resolveConverterName(ApiExecutionConfig apiConfig) {
        // 可以从apiConfig的扩展属性中获取，这里简化为默认值
        return "standardMeasureConverter";
    }
}

// ============================================================================
// 7. 辅助组件
// ============================================================================

/**
 * 场景配置加载器（可从数据库或配置文件加载）
 */
@Service
public class MetricSceneConfigLoader {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    public MetricSceneConfig loadConfig(String sceneType) {
        // 从Redis缓存加载，未命中则从数据库加载
        String cacheKey = "metric:scene:config:" + sceneType;
        MetricSceneConfig config = (MetricSceneConfig) redisTemplate.opsForValue().get(cacheKey);
        
        if (config == null) {
            config = loadFromDatabase(sceneType);
            redisTemplate.opsForValue().set(cacheKey, config, 1, TimeUnit.HOURS);
        }
        
        return config;
    }
    
    private MetricSceneConfig loadFromDatabase(String sceneType) {
        // 从数据库加载配置
        return new MetricSceneConfig();
    }
}

/**
 * 元数据缓存
 */
@Service
public class MetadataCache {
    
    private final LoadingCache<String, Map<String, MetricMeta>> metricMetaCache;
    private final LoadingCache<String, Map<String, MeasureMeta>> measureMetaCache;
    
    public MetadataCache() {
        this.metricMetaCache = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build(new CacheLoader<String, Map<String, MetricMeta>>() {
                @Override
                public Map<String, MetricMeta> load(String key) {
                    return loadMetricMetaFromDb();
                }
            });
            
        this.measureMetaCache = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build(new CacheLoader<String, Map<String, MeasureMeta>>() {
                @Override
                public Map<String, MeasureMeta> load(String key) {
                    return loadMeasureMetaFromDb();
                }
            });
    }
    
    public Map<String, MetricMeta> getMetricMeta() {
        try {
            return metricMetaCache.get("ALL");
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
    
    public Map<String, MeasureMeta> getMeasureMeta() {
        try {
            return measureMetaCache.get("ALL");
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
    
    public Map<String, DomainMeta> getDomainMeta() {
        return Collections.emptyMap();
    }
    
    public Map<String, OrgMeta> getOrgMeta() {
        return Collections.emptyMap();
    }
    
    private Map<String, MetricMeta> loadMetricMetaFromDb() {
        return new HashMap<>();
    }
    
    private Map<String, MeasureMeta> loadMeasureMetaFromDb() {
        return new HashMap<>();
    }
    
    /**
     * 手动刷新缓存
     */
    public void refresh() {
        metricMetaCache.invalidateAll();
        measureMetaCache.invalidateAll();
    }
}

/**
 * SpEL表达式求值器
 */
@Service
public class ExpressionEvaluator {
    
    private final SpelExpressionParser parser = new SpelExpressionParser();
    
    public boolean evaluate(String expression, MetricExecutionContext context) {
        try {
            StandardEvaluationContext evalContext = new StandardEvaluationContext(context);
            Expression exp = parser.parseExpression(expression);
            Boolean result = exp.getValue(evalContext, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            // 表达式错误时默认不执行
            return false;
        }
    }
}

/**
 * 元数据定时刷新任务
 */
@Component("metadataRefreshTask")
public class MetadataRefreshTask implements ITimerTask {
    
    @Autowired
    private MetadataCache metadataCache;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        metadataCache.refresh();
    }
}

// ============================================================================
// 8. 元数据实体定义
// ============================================================================

@Data
class MetricMeta implements Serializable {
    private String metricCode;
    private String metricName;
    private List<String> measureCodes;
}

@Data
class MeasureMeta implements Serializable {
    private String measureCode;
    private String measureName;
    private String unit;
    private Integer precision;
}

@Data
class DomainMeta implements Serializable {
    private String domainCode;
    private String domainName;
}

@Data
class OrgMeta implements Serializable {
    private String orgCode;
    private String orgName;
    private String parentOrgCode;
    private Integer orgLevel;
}

现在让我创建一个配置示例和使用说明文档：
配置示例与使用说明：
// ============================================================================
// 配置示例：如何配置一个完整的业务场景
// ============================================================================

/**
 * 场景配置示例 - 财务指标聚合场景
 */
@Configuration
public class MetricSceneConfiguration {
    
    /**
     * 配置场景类型：FINANCIAL_METRIC
     */
    @Bean
    public MetricSceneConfig financialMetricSceneConfig() {
        MetricSceneConfig config = new MetricSceneConfig();
        config.setSceneType("FINANCIAL_METRIC");
        
        // API配置列表
        List<ApiExecutionConfig> apiConfigs = new ArrayList<>();
        
        // API 1: 基础财务数据（无依赖，优先级高）
        ApiExecutionConfig api1 = new ApiExecutionConfig();
        api1.setApiName("baseFinancialData");
        api1.setApiHandlerBean("baseFinancialApiHandler");
        api1.setPageable(true);
        api1.setPageSize(100);
        api1.setPriority(1);
        apiConfigs.add(api1);
        
        // API 2: 预算数据（无依赖）
        ApiExecutionConfig api2 = new ApiExecutionConfig();
        api2.setApiName("budgetData");
        api2.setApiHandlerBean("budgetApiHandler");
        api2.setPageable(false);
        api2.setPriority(1);
        // 条件：只有当请求包含预算指标时才调用
        api2.setConditionExpression("request.metricCodes.contains('BUDGET')");
        apiConfigs.add(api2);
        
        // API 3: 比率计算（依赖API1和API2）
        ApiExecutionConfig api3 = new ApiExecutionConfig();
        api3.setApiName("ratioCalculation");
        api3.setApiHandlerBean("ratioCalculationHandler");
        api3.setPageable(false);
        api3.setPriority(2);
        api3.setDependencies(Arrays.asList("baseFinancialData", "budgetData"));
        apiConfigs.add(api3);
        
        config.setApiConfigs(apiConfigs);
        
        // 过滤器链配置
        config.setFilterChain(Arrays.asList(
            "orgScopeFilter",      // 组织范围过滤
            "periodFilter",        // 会计期过滤
            "dataQualityFilter"    // 数据质量过滤
        ));
        
        return config;
    }
}

// ============================================================================
// API Handler 实现示例
// ============================================================================

/**
 * 基础财务数据API处理器
 */
@Component("baseFinancialApiHandler")
public class BaseFinancialApiHandler implements IApiHandler<FinancialDataDTO> {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Override
    public ApiResponse<FinancialDataDTO> execute(MetricExecutionContext context, 
                                                  Integer pageNum, Integer pageSize) throws ApplicationException {
        try {
            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("periodId", context.getRequest().getPeriodId());
            params.put("orgCodes", context.getRequest().getOrgCodes());
            params.put("pageNum", pageNum);
            params.put("pageSize", pageSize);
            
            // 调用下游API
            String url = "http://financial-service/api/data";
            ResponseEntity<FinancialApiResponse> response = restTemplate.postForEntity(
                url, params, FinancialApiResponse.class);
            
            // 转换为统一响应格式
            ApiResponse<FinancialDataDTO> apiResponse = new ApiResponse<>();
            apiResponse.setData(response.getBody().getData());
            apiResponse.setTotalCount(response.getBody().getTotal());
            apiResponse.setCurrentPage(pageNum);
            apiResponse.setPageSize(pageSize);
            apiResponse.setHasMore(pageNum * pageSize < response.getBody().getTotal());
            
            return apiResponse;
        } catch (Exception e) {
            throw new ApplicationException("调用财务数据API失败", e);
        }
    }
    
    @Override
    public String getApiName() {
        return "baseFinancialData";
    }
    
    @Override
    public boolean supportPagination() {
        return true;
    }
}

/**
 * 比率计算Handler（依赖其他API的结果）
 */
@Component("ratioCalculationHandler")
public class RatioCalculationHandler implements IApiHandler<RatioDataDTO> {
    
    @Override
    public ApiResponse<RatioDataDTO> execute(MetricExecutionContext context, 
                                             Integer pageNum, Integer pageSize) throws ApplicationException {
        // 从context获取依赖API的结果
        List<FinancialDataDTO> baseData = (List<FinancialDataDTO>) 
            context.getApiResultCache().get("baseFinancialData");
        List<BudgetDataDTO> budgetData = (List<BudgetDataDTO>) 
            context.getApiResultCache().get("budgetData");
        
        // 基于依赖数据计算比率
        List<RatioDataDTO> ratios = calculateRatios(baseData, budgetData);
        
        ApiResponse<RatioDataDTO> response = new ApiResponse<>();
        response.setData(ratios);
        response.setTotalCount(ratios.size());
        response.setHasMore(false);
        
        return response;
    }
    
    @Override
    public String getApiName() {
        return "ratioCalculation";
    }
    
    @Override
    public boolean supportPagination() {
        return false;
    }
    
    private List<RatioDataDTO> calculateRatios(List<FinancialDataDTO> baseData, 
                                                List<BudgetDataDTO> budgetData) {
        // 实现具体的比率计算逻辑
        return new ArrayList<>();
    }
}

// ============================================================================
// Filter 实现示例
// ============================================================================

/**
 * 会计期过滤器
 */
@Component("periodFilter")
public class PeriodFilter<T> implements IDataFilter<T> {
    
    @Override
    public List<T> filter(List<T> data, MetricExecutionContext context) {
        String targetPeriod = context.getRequest().getPeriodId();
        
        return data.stream()
            .filter(item -> {
                String itemPeriod = extractPeriod(item);
                return targetPeriod.equals(itemPeriod);
            })
            .collect(Collectors.toList());
    }
    
    @Override
    public int getOrder() {
        return 2;
    }
    
    private String extractPeriod(Object item) {
        try {
            Method method = item.getClass().getMethod("getPeriodId");
            return (String) method.invoke(item);
        } catch (Exception e) {
            return "";
        }
    }
}

/**
 * 数据质量过滤器
 */
@Component("dataQualityFilter")
public class DataQualityFilter<T> implements IDataFilter<T> {
    
    @Override
    public List<T> filter(List<T> data, MetricExecutionContext context) {
        return data.stream()
            .filter(this::isValidData)
            .collect(Collectors.toList());
    }
    
    @Override
    public int getOrder() {
        return 3;
    }
    
    private boolean isValidData(Object item) {
        // 检查数据质量：非空、合法范围等
        return item != null;
    }
}

// ============================================================================
// Converter 实现示例
// ============================================================================

/**
 * 财务数据转换器
 */
@Component("financialMeasureConverter")
public class FinancialMeasureConverter implements IMeasureDataConverter<FinancialDataDTO> {
    
    @Override
    public MeasureDataVO convert(FinancialDataDTO source, MetricExecutionContext context) {
        MeasureDataVO vo = new MeasureDataVO();
        
        // 基础字段映射
        vo.setMeasureCode(source.getMeasureCode());
        vo.setUnit(source.getUnit());
        vo.setCurrency(source.getCurrency());
        
        // 计算originValue
        BigDecimal amount = source.getAmount();
        BigDecimal rate = source.getExchangeRate();
        BigDecimal originValue = amount.multiply(rate);
        vo.setOriginValue(originValue.toPlainString());
        
        // 获取精度并计算fixedValue
        MeasureMeta meta = context.getMeasureMetaMap().get(source.getMeasureCode());
        int precision = meta != null ? meta.getPrecision() : 2;
        vo.setFixedValue(originValue.setScale(precision, RoundingMode.HALF_UP).toPlainString());
        
        return vo;
    }
    
    @Override
    public String extractBusinessKey(FinancialDataDTO source, MetricExecutionContext context) {
        return String.format("%s:::%s:::%s",
            source.getMetricCode(),
            source.getOrgCode(),
            source.getDomainCode());
    }
    
    @Override
    public String extractPeriodId(FinancialDataDTO source, MetricExecutionContext context) {
        return source.getPeriodId();
    }
}

/**
 * 自定义Key生成器示例（按产品线聚合）
 */
@Component("productLineKeyGenerator")
public class ProductLineKeyGenerator implements IKeyGenerator {
    
    @Override
    public String generateKey(Object source, MetricExecutionContext context) {
        if (source instanceof FinancialDataDTO) {
            FinancialDataDTO dto = (FinancialDataDTO) source;
            return String.format("%s:::%s:::%s:::%s",
                dto.getMetricCode(),
                dto.getOrgCode(),
                dto.getDomainCode(),
                dto.getProductLine()); // 增加产品线维度
        }
        throw new IllegalArgumentException("Unsupported source type");
    }
}

// ============================================================================
// 数据传输对象定义
// ============================================================================

@Data
class FinancialDataDTO implements Serializable {
    private String periodId;
    private String metricCode;
    private String measureCode;
    private String orgCode;
    private String domainCode;
    private String productLine;
    private BigDecimal amount;
    private BigDecimal exchangeRate;
    private String unit;
    private String currency;
}

@Data
class BudgetDataDTO implements Serializable {
    private String periodId;
    private String metricCode;
    private String orgCode;
    private BigDecimal budgetAmount;
}

@Data
class RatioDataDTO implements Serializable {
    private String periodId;
    private String metricCode;
    private String measureCode;
    private String orgCode;
    private BigDecimal ratioValue;
}

@Data
class FinancialApiResponse {
    private List<FinancialDataDTO> data;
    private Integer total;
}

// ============================================================================
// 使用示例
// ============================================================================

@RestController
@RequestMapping("/api/metric")
public class MetricDataController {
    
    @Autowired
    private MetricDataService metricDataService;
    
    /**
     * 查询指标数据
     */
    @PostMapping("/measures")
    public ResponseEntity<List<OpMetricDataRespVO>> getMeasures(@RequestBody MeasureReqVO reqVO) {
        try {
            List<OpMetricDataRespVO> result = metricDataService.getMeasures(reqVO);
            return ResponseEntity.ok(result);
        } catch (ApplicationException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 手动刷新元数据缓存
     */
    @PostMapping("/metadata/refresh")
    public ResponseEntity<Void> refreshMetadata() {
        // 可以通过异步任务触发刷新
        return ResponseEntity.ok().build();
    }
}

// ============================================================================
// 异步刷新元数据示例
// ============================================================================

/**
 * 异步刷新元数据处理器
 */
@Component("metadataRefreshHandler")
public class MetadataRefreshHandler implements IAsyncProcessHandler {
    
    @Autowired
    private MetadataCache metadataCache;
    
    @Autowired
    private MetricSceneConfigLoader configLoader;
    
    @Override
    public AjaxMessageVo handlerProcess(Serializable context) throws ApplicationException {
        try {
            // 刷新元数据缓存
            metadataCache.refresh();
            
            // 刷新场景配置缓存
            // configLoader.refreshAll();
            
            AjaxMessageVo vo = new AjaxMessageVo();
            vo.setSuccess(true);
            vo.setMessage("元数据刷新成功");
            return vo;
        } catch (Exception e) {
            AjaxMessageVo vo = new AjaxMessageVo();
            vo.setSuccess(false);
            vo.setMessage("元数据刷新失败：" + e.getMessage());
            return vo;
        }
    }
}

// ============================================================================
// 性能监控和日志
// ============================================================================

/**
 * 性能监控切面
 */
@Aspect
@Component
public class MetricPerformanceMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(MetricPerformanceMonitor.class);
    
    @Around("execution(* com.company.metric.service.MetricDataService.getMeasures(..))")
    public Object monitorPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        String methodName = joinPoint.getSignature().getName();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            logger.info("Method {} executed in {} ms", methodName, duration);
            
            // 可以发送到监控系统
            if (duration > 5000) {
                logger.warn("Method {} execution time exceeds threshold: {} ms", methodName, duration);
            }
            
            return result;
        } catch (Exception e) {
            logger.error("Method {} failed after {} ms", 
                methodName, System.currentTimeMillis() - startTime, e);
            throw e;
        }
    }
}

// ============================================================================
// 扩展点总结
// ============================================================================

/**
 * 架构扩展点说明：
 * 
 * 1. IApiHandler: 新增下游API时实现此接口
 *    - 支持泛型适配不同返回结构
 *    - 支持分页/非分页
 *    - 支持依赖其他API结果
 * 
 * 2. IDataFilter: 新增过滤逻辑时实现此接口
 *    - 通过Order控制执行顺序
 *    - 支持泛型处理不同数据类型
 * 
 * 3. IMeasureDataConverter: 新增数据转换逻辑时实现此接口
 *    - 负责将API返回数据转换为标准度量对象
 *    - 提供Key提取和周期提取方法
 * 
 * 4. IKeyGenerator: 自定义聚合Key生成策略
 *    - 支持不同业务场景的Key规则
 *    - 可以增加更多维度
 * 
 * 5. MetricSceneConfig: 场景配置
 *    - 通过配置组装不同的API、Filter、Converter
 *    - 支持条件表达式和依赖关系
 *    - 可以存储在数据库中实现动态配置
 * 
 * 性能优化要点：
 * 1. 使用ConcurrentHashMap和CopyOnWriteArrayList保证线程安全
 * 2. CompletableFuture实现真正的异步并行
 * 3. 分页场景：先同步第一页，再并行获取剩余页
 * 4. 无依赖API并行执行，有依赖API顺序执行
 * 5. 使用Guava LoadingCache实现本地缓存
 * 6. 过滤器链短路：如果某个过滤器返回空，立即终止
 * 
 * 线程安全保证：
 * 1. Context中的聚合容器使用ConcurrentHashMap
 * 2. 使用computeIfAbsent原子操作
 * 3. List使用CopyOnWriteArrayList（适合读多写少场景）
 * 4. 如果写多，可以改用synchronized或Lock
 */
 
 架构设计总结
我为您设计了一个高性能、可扩展、线程安全的指标数据聚合服务架构，具有以下特点：
? 核心架构特性
1. 四层清晰分层

API调用层：动态编排、支持依赖和条件表达式
过滤层：可配置的责任链模式
转换层：泛型适配器统一处理
聚合层：无锁并发聚合

2. 高性能设计

? 真正的异步并行：使用 CompletableFuture + 线程池
? 智能分页策略：先同步第一页，再并行获取剩余页
? 无依赖API并行：自动识别依赖关系，无依赖的API并行执行
? 无锁聚合：使用 ConcurrentHashMap.computeIfAbsent 原子操作
? 本地+Redis两级缓存：元数据使用 Guava LoadingCache

3. 线程安全保证
// 使用ConcurrentHashMap保证线程安全
ConcurrentHashMap<String, ConcurrentHashMap<String, List<MeasureDataVO>>> aggregationMap

// 使用原子操作
context.getAggregationMap()
    .computeIfAbsent(periodId, k -> new ConcurrentHashMap<>())
    .computeIfAbsent(businessKey, k -> new CopyOnWriteArrayList<>())
    .add(measureData);
    
4. 插件化扩展机制
扩展点接口用途API处理器IApiHandler<T>新增下游API过滤器IDataFilter<T>新增过滤逻辑转换器IMeasureDataConverter<T>数据转换规则Key生成器IKeyGenerator自定义聚合Key

5. 配置化能力

? API依赖关系配置
? 条件表达式（SpEL）
? 过滤器链动态组装
? 场景配置热加载

? 关键技术实现
1. API依赖编排

// 自动分组：无依赖并行，有依赖顺序执行
Map<Boolean, List<ApiExecutionConfig>> grouped = 
    sortedConfigs.stream()
        .collect(Collectors.partitioningBy(
            c -> c.getDependencies() == null || c.getDependencies().isEmpty()
        ));


2. 分页优化
// 先同步第一页获取总数
ApiResponse<?> firstPage = handler.execute(context, 1, pageSize);

// 并行获取剩余页
for (int page = 2; page <= totalPages; page++) {
    CompletableFuture<Void> future = taskExecutorService.submitTask(/*...*/);
}

3. 泛型适配器
public interface IApiHandler<T> {
    ApiResponse<T> execute(MetricExecutionContext context, 
                          Integer pageNum, Integer pageSize);
}

? 性能优势
特性传统方案本架构API调用串行并行（无依赖）分页处理串行获取所有页首页同步+剩余页并行数据聚合synchronized加锁ConcurrentHashMap无锁内存占用全量加载流式处理

? 扩展性示例
场景1：新增API

@Component("newApiHandler")
public class NewApiHandler implements IApiHandler<NewDTO> {
    // 实现execute方法即可
}

场景2：新增过滤器

@Component("customFilter")
public class CustomFilter implements IDataFilter<T> {
    // 指定Order和过滤逻辑
}

场景3：自定义Key规则
@Component("customKeyGenerator")
public class CustomKeyGenerator implements IKeyGenerator {
    // 实现自定义Key生成逻辑
}

? 使用建议

小数据量场景（<10万条）：使用 CopyOnWriteArrayList
大数据量场景（>10万条）：改用 Collections.synchronizedList 或细粒度锁
实时性要求高：减少过滤器数量，优化转换逻辑
批量场景：考虑使用批量API减少网络开销

这个架构已经具备了生产级别的健壮性和扩展性，您可以根据实际业务需求进行定制化开发。需要我进一步解释某个模块或提供更多实现细节吗?

我将为您提供更详细的实现细节和最佳实践指南。
高级实现细节与最佳实践
// ============================================================================
// 1. 高性能线程池配置与管理
// ============================================================================

/**
 * 线程池配置 - 针对不同场景优化
 */
@Configuration
public class ThreadPoolConfiguration {
    
    /**
     * API调用线程池 - IO密集型
     * 核心线程数 = CPU核心数 * 2
     * 最大线程数 = CPU核心数 * 4
     */
    @Bean("apiCallExecutor")
    public ThreadPoolTaskExecutor apiCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Runtime.getRuntime().availableProcessors();
        
        executor.setCorePoolSize(processors * 2);
        executor.setMaxPoolSize(processors * 4);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("api-call-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        return executor;
    }
    
    /**
     * 数据处理线程池 - CPU密集型
     * 核心线程数 = CPU核心数 + 1
     */
    @Bean("dataProcessExecutor")
    public ThreadPoolTaskExecutor dataProcessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Runtime.getRuntime().availableProcessors();
        
        executor.setCorePoolSize(processors + 1);
        executor.setMaxPoolSize(processors * 2);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("data-process-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.initialize();
        return executor;
    }
}

/**
 * 增强版TaskExecutorService实现
 */
@Service
public class EnhancedTaskExecutorService {
    
    @Autowired
    @Qualifier("apiCallExecutor")
    private ThreadPoolTaskExecutor apiCallExecutor;
    
    @Autowired
    @Qualifier("dataProcessExecutor")
    private ThreadPoolTaskExecutor dataProcessExecutor;
    
    /**
     * 提交API调用任务（带超时控制）
     */
    public <T> CompletableFuture<T> submitApiTask(Supplier<T> supplier, 
                                                   String taskInfo, 
                                                   long timeoutMs) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(supplier, apiCallExecutor);
        
        // 添加超时控制
        return future.completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                logger.error("API task [{}] failed: {}", taskInfo, ex.getMessage());
                return null;
            });
    }
    
    /**
     * 提交数据处理任务
     */
    public <T> CompletableFuture<T> submitProcessTask(Supplier<T> supplier, String taskInfo) {
        return CompletableFuture.supplyAsync(supplier, dataProcessExecutor)
            .exceptionally(ex -> {
                logger.error("Process task [{}] failed: {}", taskInfo, ex.getMessage());
                return null;
            });
    }
    
    /**
     * 批量提交任务并等待完成（带进度回调）
     */
    public <T> List<T> submitBatchTasks(List<Supplier<T>> tasks, 
                                        Consumer<Integer> progressCallback) {
        AtomicInteger completed = new AtomicInteger(0);
        
        List<CompletableFuture<T>> futures = tasks.stream()
            .map(task -> CompletableFuture.supplyAsync(task, apiCallExecutor)
                .whenComplete((result, ex) -> {
                    int count = completed.incrementAndGet();
                    if (progressCallback != null) {
                        progressCallback.accept(count);
                    }
                }))
            .collect(Collectors.toList());
        
        return futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
    }
}

// ============================================================================
// 2. 增强版API编排器 - 支持复杂依赖关系
// ============================================================================

/**
 * API依赖图管理器
 */
@Service
public class ApiDependencyGraphManager {
    
    /**
     * 拓扑排序 - 解决复杂依赖关系
     */
    public List<ApiExecutionConfig> topologicalSort(List<ApiExecutionConfig> configs) {
        // 构建依赖图
        Map<String, ApiExecutionConfig> configMap = configs.stream()
            .collect(Collectors.toMap(ApiExecutionConfig::getApiName, c -> c));
        
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        
        // 初始化
        for (ApiExecutionConfig config : configs) {
            inDegree.put(config.getApiName(), 0);
            graph.put(config.getApiName(), new ArrayList<>());
        }
        
        // 构建图和入度
        for (ApiExecutionConfig config : configs) {
            if (config.getDependencies() != null) {
                for (String dep : config.getDependencies()) {
                    graph.get(dep).add(config.getApiName());
                    inDegree.put(config.getApiName(), inDegree.get(config.getApiName()) + 1);
                }
            }
        }
        
        // 拓扑排序
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        List<ApiExecutionConfig> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(configMap.get(current));
            
            for (String next : graph.get(current)) {
                int degree = inDegree.get(next) - 1;
                inDegree.put(next, degree);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }
        
        // 检查循环依赖
        if (sorted.size() != configs.size()) {
            throw new IllegalStateException("Circular dependency detected in API configurations");
        }
        
        return sorted;
    }
    
    /**
     * 分析并行度 - 识别可以并行执行的API组
     */
    public List<List<ApiExecutionConfig>> analyzeParallelGroups(List<ApiExecutionConfig> sorted) {
        List<List<ApiExecutionConfig>> groups = new ArrayList<>();
        Map<String, Integer> apiLevels = new HashMap<>();
        
        // 计算每个API的层级
        for (ApiExecutionConfig config : sorted) {
            int maxDepLevel = 0;
            if (config.getDependencies() != null) {
                for (String dep : config.getDependencies()) {
                    maxDepLevel = Math.max(maxDepLevel, apiLevels.getOrDefault(dep, 0) + 1);
                }
            }
            apiLevels.put(config.getApiName(), maxDepLevel);
        }
        
        // 按层级分组
        int maxLevel = apiLevels.values().stream().max(Integer::compareTo).orElse(0);
        for (int level = 0; level <= maxLevel; level++) {
            final int currentLevel = level;
            List<ApiExecutionConfig> group = sorted.stream()
                .filter(c -> apiLevels.get(c.getApiName()) == currentLevel)
                .collect(Collectors.toList());
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        
        return groups;
    }
}

/**
 * 增强版API编排器 - 支持分层并行执行
 */
@Service
public class EnhancedApiOrchestrator {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private EnhancedTaskExecutorService taskExecutorService;
    
    @Autowired
    private ApiDependencyGraphManager dependencyManager;
    
    @Autowired
    private ExpressionEvaluator expressionEvaluator;
    
    private static final long API_TIMEOUT_MS = 30000; // 30秒超时
    
    /**
     * 增强版编排执行 - 支持分层并行
     */
    public void orchestrateApiCalls(MetricExecutionContext context) throws ApplicationException {
        MetricSceneConfig config = context.getSceneConfig();
        
        // 1. 拓扑排序
        List<ApiExecutionConfig> sortedConfigs = dependencyManager.topologicalSort(config.getApiConfigs());
        
        // 2. 分析并行组
        List<List<ApiExecutionConfig>> parallelGroups = dependencyManager.analyzeParallelGroups(sortedConfigs);
        
        logger.info("API execution plan: {} groups, total {} APIs", 
            parallelGroups.size(), sortedConfigs.size());
        
        // 3. 按组执行（组内并行，组间串行）
        for (int i = 0; i < parallelGroups.size(); i++) {
            List<ApiExecutionConfig> group = parallelGroups.get(i);
            logger.info("Executing group {}: {} APIs", i, group.size());
            
            executeParallelGroup(group, context);
        }
    }
    
    /**
     * 并行执行一组API
     */
    private void executeParallelGroup(List<ApiExecutionConfig> group, MetricExecutionContext context) {
        List<CompletableFuture<Void>> futures = group.stream()
            .filter(apiConfig -> shouldExecute(apiConfig, context))
            .map(apiConfig -> executeApiWithTimeout(apiConfig, context))
            .collect(Collectors.toList());
        
        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 检查失败的任务
        long failedCount = futures.stream()
            .filter(f -> f.isCompletedExceptionally())
            .count();
        
        if (failedCount > 0) {
            logger.warn("Group execution completed with {} failed APIs", failedCount);
        }
    }
    
    /**
     * 执行单个API（带超时和重试）
     */
    private CompletableFuture<Void> executeApiWithTimeout(ApiExecutionConfig apiConfig, 
                                                           MetricExecutionContext context) {
        return taskExecutorService.submitApiTask(() -> {
            try {
                IApiHandler<?> handler = applicationContext.getBean(
                    apiConfig.getApiHandlerBean(), IApiHandler.class);
                
                if (handler.supportPagination() && apiConfig.isPageable()) {
                    executeWithPaginationEnhanced(handler, apiConfig, context);
                } else {
                    ApiResponse<?> response = handler.execute(context, null, null);
                    processApiResponse(apiConfig.getApiName(), response, context);
                }
                return null;
            } catch (Exception e) {
                logger.error("API [{}] execution failed", apiConfig.getApiName(), e);
                // 记录失败但不中断整体流程
                context.getApiResultCache().put(apiConfig.getApiName() + "_error", e.getMessage());
                throw new CompletionException(e);
            }
        }, "API-" + apiConfig.getApiName(), API_TIMEOUT_MS);
    }
    
    /**
     * 增强版分页执行 - 支持动态并发控制
     */
    private void executeWithPaginationEnhanced(IApiHandler<?> handler, 
                                               ApiExecutionConfig apiConfig,
                                               MetricExecutionContext context) throws ApplicationException {
        // 第一页同步获取
        ApiResponse<?> firstPage = handler.execute(context, 1, apiConfig.getPageSize());
        processApiResponse(apiConfig.getApiName(), firstPage, context);
        
        if (!firstPage.isHasMore() || firstPage.getTotalCount() == null) {
            return;
        }
        
        int totalPages = (int) Math.ceil((double) firstPage.getTotalCount() / apiConfig.getPageSize());
        
        // 动态控制并发度（避免过多并发请求）
        int maxConcurrency = Math.min(10, totalPages - 1); // 最多10个并发
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            
            CompletableFuture<Void> future = taskExecutorService.submitApiTask(() -> {
                try {
                    ApiResponse<?> pageResponse = handler.execute(context, currentPage, apiConfig.getPageSize());
                    processApiResponse(apiConfig.getApiName(), pageResponse, context);
                    return null;
                } catch (Exception e) {
                    logger.error("Failed to fetch page {} for API [{}]", 
                        currentPage, apiConfig.getApiName(), e);
                    return null;
                }
            }, String.format("API-%s-Page-%d", apiConfig.getApiName(), currentPage), API_TIMEOUT_MS);
            
            futures.add(future);
            
            // 控制并发度：每达到maxConcurrency就等待一批完成
            if (futures.size() >= maxConcurrency) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                futures.clear();
            }
        }
        
        // 等待剩余任务
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }
    
    /**
     * 处理API响应（线程安全）
     */
    private synchronized void processApiResponse(String apiName, ApiResponse<?> response, 
                                                 MetricExecutionContext context) {
        if (response == null || response.getData() == null) {
            return;
        }
        
        // 合并数据到缓存
        context.getApiResultCache().compute(apiName, (key, existingData) -> {
            if (existingData == null) {
                return new ArrayList<>(response.getData());
            } else {
                List<Object> merged = new ArrayList<>((List<Object>) existingData);
                merged.addAll(response.getData());
                return merged;
            }
        });
    }
    
    /**
     * 条件表达式求值（支持复杂表达式）
     */
    private boolean shouldExecute(ApiExecutionConfig apiConfig, MetricExecutionContext context) {
        if (apiConfig.getConditionExpression() == null) {
            return true;
        }
        
        try {
            return expressionEvaluator.evaluate(apiConfig.getConditionExpression(), context);
        } catch (Exception e) {
            logger.warn("Failed to evaluate condition for API [{}]: {}", 
                apiConfig.getApiName(), e.getMessage());
            return false; // 表达式错误时默认不执行
        }
    }
}

// ============================================================================
// 3. 高性能过滤器链 - 支持并行过滤
// ============================================================================

/**
 * 过滤器元数据
 */
@Data
class FilterMetadata {
    private String name;
    private int order;
    private boolean parallelizable; // 是否可并行执行
    private double estimatedCost; // 预估执行成本（0-1）
}

/**
 * 增强版过滤器接口
 */
public interface IEnhancedDataFilter<T> extends IDataFilter<T> {
    /**
     * 是否可以并行执行
     */
    default boolean isParallelizable() {
        return true;
    }
    
    /**
     * 预估执行成本（用于优化执行顺序）
     */
    default double getEstimatedCost() {
        return 0.5;
    }
}

/**
 * 智能过滤器链执行器
 */
@Service
public class SmartFilterChainExecutor {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private EnhancedTaskExecutorService taskExecutorService;
    
    /**
     * 智能执行过滤器链 - 自动优化执行策略
     */
    public <T> List<T> executeFilterChain(List<T> data, MetricExecutionContext context) {
        List<String> filterBeanNames = context.getSceneConfig().getFilterChain();
        if (filterBeanNames == null || filterBeanNames.isEmpty() || data.isEmpty()) {
            return data;
        }
        
        // 获取过滤器实例
        List<IEnhancedDataFilter<T>> filters = filterBeanNames.stream()
            .map(beanName -> (IEnhancedDataFilter<T>) applicationContext.getBean(beanName))
            .sorted(Comparator.comparingInt(IDataFilter::getOrder))
            .collect(Collectors.toList());
        
        // 根据数据量选择执行策略
        if (data.size() < 1000) {
            // 小数据量：串行执行
            return executeSequential(data, filters, context);
        } else {
            // 大数据量：并行分片执行
            return executeParallel(data, filters, context);
        }
    }
    
    /**
     * 串行执行过滤器链
     */
    private <T> List<T> executeSequential(List<T> data, 
                                          List<IEnhancedDataFilter<T>> filters,
                                          MetricExecutionContext context) {
        List<T> result = data;
        
        for (IEnhancedDataFilter<T> filter : filters) {
            long startTime = System.currentTimeMillis();
            result = filter.filter(result, context);
            long duration = System.currentTimeMillis() - startTime;
            
            logger.debug("Filter [{}] executed in {} ms, result size: {}", 
                filter.getClass().getSimpleName(), duration, result.size());
            
            if (result.isEmpty()) {
                break; // 短路优化
            }
        }
        
        return result;
    }
    
    /**
     * 并行执行过滤器链 - 数据分片
     */
    private <T> List<T> executeParallel(List<T> data,
                                        List<IEnhancedDataFilter<T>> filters,
                                        MetricExecutionContext context) {
        int processors = Runtime.getRuntime().availableProcessors();
        int chunkSize = Math.max(100, data.size() / processors);
        
        // 分片
        List<List<T>> chunks = partitionList(data, chunkSize);
        
        // 并行处理每个分片
        List<CompletableFuture<List<T>>> futures = chunks.stream()
            .map(chunk -> taskExecutorService.submitProcessTask(
                () -> executeSequential(chunk, filters, context),
                "filter-chunk"
            ))
            .collect(Collectors.toList());
        
        // 合并结果
        return futures.stream()
            .map(CompletableFuture::join)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }
    
    /**
     * 列表分片工具方法
     */
    private <T> List<List<T>> partitionList(List<T> list, int chunkSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            partitions.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return partitions;
    }
}

// ============================================================================
// 4. 高性能数据聚合器 - 支持多种聚合策略
// ============================================================================

/**
 * 聚合策略枚举
 */
public enum AggregationStrategy {
    CONCURRENT_HASH_MAP,    // ConcurrentHashMap（默认）
    SEGMENT_LOCK,           // 分段锁
    LOCK_FREE               // 无锁（LongAdder）
}

/**
 * 高性能数据聚合器
 */
@Service
public class HighPerformanceDataAggregator {
    
    @Autowired
    private ConverterRegistry converterRegistry;
    
    @Autowired
    private SmartFilterChainExecutor filterChainExecutor;
    
    private static final int SEGMENT_SIZE = 16; // 分段锁的段数
    
    /**
     * 聚合单个API的数据（支持选择策略）
     */
    public <T> void aggregateApiData(String apiName, 
                                      List<T> rawData,
                                      String converterName,
                                      MetricExecutionContext context,
                                      AggregationStrategy strategy) {
        if (rawData == null || rawData.isEmpty()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // 1. 执行过滤器链
        List<T> filteredData = filterChainExecutor.executeFilterChain(rawData, context);
        
        if (filteredData.isEmpty()) {
            logger.info("API [{}]: All data filtered out", apiName);
            return;
        }
        
        // 2. 获取转换器
        IMeasureDataConverter<T> converter = converterRegistry.getConverter(converterName);
        
        // 3. 根据策略选择聚合方法
        switch (strategy) {
            case CONCURRENT_HASH_MAP:
                aggregateWithConcurrentHashMap(filteredData, converter, context);
                break;
            case SEGMENT_LOCK:
                aggregateWithSegmentLock(filteredData, converter, context);
                break;
            case LOCK_FREE:
                aggregateWithLockFree(filteredData, converter, context);
                break;
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("API [{}] aggregated {} records in {} ms", 
            apiName, filteredData.size(), duration);
    }
    
    /**
     * 方案1：ConcurrentHashMap（推荐 - 平衡性能和复杂度）
     */
    private <T> void aggregateWithConcurrentHashMap(List<T> data,
                                                     IMeasureDataConverter<T> converter,
                                                     MetricExecutionContext context) {
        for (T item : data) {
            String periodId = converter.extractPeriodId(item, context);
            String businessKey = converter.extractBusinessKey(item, context);
            MeasureDataVO measureData = converter.convert(item, context);
            
            if (measureData != null) {
                context.getAggregationMap()
                    .computeIfAbsent(periodId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(businessKey, k -> new CopyOnWriteArrayList<>())
                    .add(measureData);
            }
        }
    }
    
    /**
     * 方案2：分段锁（高并发写场景）
     */
    private <T> void aggregateWithSegmentLock(List<T> data,
                                              IMeasureDataConverter<T> converter,
                                              MetricExecutionContext context) {
        // 创建分段锁数组
        ReentrantLock[] locks = new ReentrantLock[SEGMENT_SIZE];
        for (int i = 0; i < SEGMENT_SIZE; i++) {
            locks[i] = new ReentrantLock();
        }
        
        for (T item : data) {
            String periodId = converter.extractPeriodId(item, context);
            String businessKey = converter.extractBusinessKey(item, context);
            MeasureDataVO measureData = converter.convert(item, context);
            
            if (measureData != null) {
                // 根据businessKey计算锁索引
                int lockIndex = Math.abs(businessKey.hashCode()) % SEGMENT_SIZE;
                ReentrantLock lock = locks[lockIndex];
                
                lock.lock();
                try {
                    context.getAggregationMap()
                        .computeIfAbsent(periodId, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(businessKey, k -> new ArrayList<>())
                        .add(measureData);
                } finally {
                    lock.unlock();
                }
            }
        }
    }
    
    /**
     * 方案3：完全无锁（仅适用于累加场景）
     * 注意：此方案仅展示概念，实际需要根据业务调整
     */
    private <T> void aggregateWithLockFree(List<T> data,
                                           IMeasureDataConverter<T> converter,
                                           MetricExecutionContext context) {
        // 使用ThreadLocal收集，最后合并
        Map<String, Map<String, List<MeasureDataVO>>> localMap = new HashMap<>();
        
        for (T item : data) {
            String periodId = converter.extractPeriodId(item, context);
            String businessKey = converter.extractBusinessKey(item, context);
            MeasureDataVO measureData = converter.convert(item, context);
            
            if (measureData != null) {
                localMap.computeIfAbsent(periodId, k -> new HashMap<>())
                    .computeIfAbsent(businessKey, k -> new ArrayList<>())
                    .add(measureData);
            }
        }
        
        // 一次性合并到全局Map
        for (Map.Entry<String, Map<String, List<MeasureDataVO>>> periodEntry : localMap.entrySet()) {
            for (Map.Entry<String, List<MeasureDataVO>> keyEntry : periodEntry.getValue().entrySet()) {
                context.getAggregationMap()
                    .computeIfAbsent(periodEntry.getKey(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(keyEntry.getKey(), k -> new CopyOnWriteArrayList<>())
                    .addAll(keyEntry.getValue());
            }
        }
    }
    
    /**
     * 批量并行聚合多个API结果
     */
    public void aggregateBatchApis(Map<String, List<?>> apiResults,
                                   Map<String, String> converterMapping,
                                   MetricExecutionContext context) {
        List<CompletableFuture<Void>> futures = apiResults.entrySet().stream()
            .map(entry -> CompletableFuture.runAsync(() -> {
                String apiName = entry.getKey();
                List<?> data = entry.getValue();
                String converterName = converterMapping.get(apiName);
                
                aggregateApiData(apiName, data, converterName, context, 
                    AggregationStrategy.CONCURRENT_HASH_MAP);
            }))
            .collect(Collectors.toList());
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}

// ============================================================================
// 5. 元数据缓存优化 - 多级缓存策略
// ============================================================================

/**
 * 多级缓存元数据管理器
 */
@Service
public class MultiLevelMetadataCache {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private MetadataRepository metadataRepository;
    
    // L1缓存：本地Guava缓存（1分钟过期）
    private final LoadingCache<String, Map<String, MetricMeta>> l1MetricCache;
    
    // L2缓存：Redis（1小时过期）
    private static final String REDIS_KEY_PREFIX = "metadata:";
    private static final Duration REDIS_TTL = Duration.ofHours(1);
    
    public MultiLevelMetadataCache() {
        this.l1MetricCache = CacheBuilder.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .recordStats()
            .build(new CacheLoader<String, Map<String, MetricMeta>>() {
                @Override
                public Map<String, MetricMeta> load(String key) throws Exception {
                    return loadFromL2OrDatabase(key);
                }
            });
    }
    
    /**
     * 获取指标元数据
     */
    public Map<String, MetricMeta> getMetricMeta() {
        try {
            return l1MetricCache.get("ALL");
        } catch (Exception e) {
            logger.error("Failed to load metric metadata", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * 从L2缓存或数据库加载
     */
    private Map<String, MetricMeta> loadFromL2OrDatabase(String key) {
        // 尝试从Redis加载
        String redisKey = REDIS_KEY_PREFIX + "metric:" + key;
        Map<String, MetricMeta> data = (Map<String, MetricMeta>) 
            redisTemplate.opsForValue().get(redisKey);
        
        if (data != null) {
            logger.debug("Loaded metric metadata from Redis");
            return data;
        }
        
        // 从数据库加载
        logger.info("Loading metric metadata from database");
        data = metadataRepository.loadMetricMeta();
        
        // 写入Redis
        redisTemplate.opsForValue().set(redisKey, data, REDIS_TTL);
        