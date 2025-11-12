我将为您设计一个高性能、可扩展的指标数据聚合服务架构。这个架构将充分利用您现有的基础设施，并满足所有功能和非功能需求。
整体架构设计
核心设计理念

1、泛型化设计：支持不同的请求、响应和元数据类型
2、流式处理：避免内存溢出
3、无锁/细粒度锁：提高并发性能
4、可插拔组件：API、过滤器、转换器都可动态配置
5、对象池复用：减少GC压力

架构分层
┌─────────────────────────────────────────┐
│         Service Layer (门面)             │
│        MetricDataService                 │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│      Orchestration Layer (编排层)        │
│    DataAggregationOrchestrator          │
└─────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        │           │           │
┌───────▼──┐ ┌─────▼────┐ ┌───▼──────┐
│ API Layer│ │Filter    │ │Converter │
│          │ │Layer     │ │Layer     │
└──────────┘ └──────────┘ └──────────┘
                    │
        ┌───────────┴───────────┐
        │                       │
┌───────▼──────┐    ┌──────────▼─────┐
│Aggregation   │    │Context         │
│Layer         │    │Management      │
└──────────────┘    └────────────────┘

让我为您实现完整的代码：
1、核心上下文接口与实现

// ============= 1. 核心上下文接口 =============

/**
 * 泛型上下文接口，支持不同的请求、元数据类型
 * @param <REQ> 请求类型
 * @param <META> 元数据类型
 */
public interface IAggregationContext<REQ, META> {
    
    // 基础信息
    String getContextId();
    REQ getRequest();
    META getMetadata();
    
    // 场景配置
    String getSceneType();
    
    // 运行时数据存储（线程安全）
    void setAttribute(String key, Object value);
    <T> T getAttribute(String key);
    <T> T removeAttribute(String key);
    
    // 获取对象池
    <T> ObjectPool<T> getObjectPool(Class<T> clazz);
}

/**
 * 标准度量上下文实现
 */
public class MeasureAggregationContext implements IAggregationContext<MeasureReqVO, MeasureMetadata> {
    
    private final String contextId;
    private final MeasureReqVO request;
    private final MeasureMetadata metadata;
    private final String sceneType;
    
    // 使用 ConcurrentHashMap 保证线程安全
    private final ConcurrentHashMap<String, Object> attributes;
    
    // 对象池管理器
    private final Map<Class<?>, ObjectPool<?>> objectPools;
    
    public MeasureAggregationContext(MeasureReqVO request, MeasureMetadata metadata) {
        this.contextId = UUID.randomUUID().toString();
        this.request = request;
        this.metadata = metadata;
        this.sceneType = request.getSceneType();
        this.attributes = new ConcurrentHashMap<>(16);
        this.objectPools = new ConcurrentHashMap<>(8);
        
        // 初始化常用对象池
        initObjectPools();
    }
    
    private void initObjectPools() {
        objectPools.put(StringBuilder.class, new StringBuilderPool(100, 256));
        objectPools.put(ArrayList.class, new ArrayListPool(100));
    }
    
    @Override
    public String getContextId() {
        return contextId;
    }
    
    @Override
    public MeasureReqVO getRequest() {
        return request;
    }
    
    @Override
    public MeasureMetadata getMetadata() {
        return metadata;
    }
    
    @Override
    public String getSceneType() {
        return sceneType;
    }
    
    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T removeAttribute(String key) {
        return (T) attributes.remove(key);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> ObjectPool<T> getObjectPool(Class<T> clazz) {
        return (ObjectPool<T>) objectPools.get(clazz);
    }
}

/**
 * 元数据接口
 */
public interface IMetadata {
    // 标记接口，具体元数据由子类实现
}

/**
 * 度量元数据实现
 */
@Data
public class MeasureMetadata implements IMetadata {
    
    // 指标映射: metricCode -> MetricInfo
    private Map<String, MetricInfo> metricMap;
    
    // 度量映射: measureCode -> MeasureInfo
    private Map<String, MeasureInfo> measureMap;
    
    // 领域映射: domainCode -> DomainInfo
    private Map<String, DomainInfo> domainMap;
    
    // 组织映射: orgCode -> OrgInfo
    private Map<String, OrgInfo> orgMap;
    
    // 快速查找缓存
    private Map<String, String> metricToMeasuresCache;
    
    public List<MeasureInfo> getMeasuresForMetric(String metricCode) {
        MetricInfo metricInfo = metricMap.get(metricCode);
        if (metricInfo == null) {
            return Collections.emptyList();
        }
        return metricInfo.getMeasures().stream()
            .map(code -> measureMap.get(code))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

@Data
public class MetricInfo {
    private String metricCode;
    private String metricName;
    private List<String> measures; // 关联的度量编码
}

@Data
public class MeasureInfo {
    private String measureCode;
    private String measureName;
    private String unit;
    private Integer precision; // 精度
    private String currency;
}

@Data
public class DomainInfo {
    private String domainCode;
    private String domainName;
}

@Data
public class OrgInfo {
    private String orgCode;
    private String orgName;
    private String parentOrgCode;
}

// ============= 2. 对象池实现 =============

/**
 * 对象池接口
 */
public interface ObjectPool<T> {
    T acquire();
    void release(T obj);
    void clear();
}

/**
 * StringBuilder 对象池
 */
public class StringBuilderPool implements ObjectPool<StringBuilder> {
    
    private final ConcurrentLinkedQueue<StringBuilder> pool;
    private final int maxSize;
    private final int initialCapacity;
    private final AtomicInteger size;
    
    public StringBuilderPool(int maxSize, int initialCapacity) {
        this.pool = new ConcurrentLinkedQueue<>();
        this.maxSize = maxSize;
        this.initialCapacity = initialCapacity;
        this.size = new AtomicInteger(0);
    }
    
    @Override
    public StringBuilder acquire() {
        StringBuilder sb = pool.poll();
        if (sb == null) {
            return new StringBuilder(initialCapacity);
        }
        size.decrementAndGet();
        return sb;
    }
    
    @Override
    public void release(StringBuilder sb) {
        if (sb == null || size.get() >= maxSize) {
            return;
        }
        
        // 清空并重置
        sb.setLength(0);
        
        // 如果容量过大，丢弃
        if (sb.capacity() > initialCapacity * 4) {
            return;
        }
        
        if (pool.offer(sb)) {
            size.incrementAndGet();
        }
    }
    
    @Override
    public void clear() {
        pool.clear();
        size.set(0);
    }
}

/**
 * ArrayList 对象池
 */
public class ArrayListPool implements ObjectPool<ArrayList<Object>> {
    
    private final ConcurrentLinkedQueue<ArrayList<Object>> pool;
    private final int maxSize;
    private final AtomicInteger size;
    
    public ArrayListPool(int maxSize) {
        this.pool = new ConcurrentLinkedQueue<>();
        this.maxSize = maxSize;
        this.size = new AtomicInteger(0);
    }
    
    @Override
    public ArrayList<Object> acquire() {
        ArrayList<Object> list = pool.poll();
        if (list == null) {
            return new ArrayList<>(16);
        }
        size.decrementAndGet();
        return list;
    }
    
    @Override
    public void release(ArrayList<Object> list) {
        if (list == null || size.get() >= maxSize) {
            return;
        }
        
        list.clear();
        
        // 如果容量过大，丢弃
        if (list.size() > 1000) {
            return;
        }
        
        if (pool.offer(list)) {
            size.incrementAndGet();
        }
    }
    
    @Override
    public void clear() {
        pool.clear();
        size.set(0);
    }
}

/**
 * 通用对象池工厂
 */
public class ObjectPoolFactory {
    
    private static final Map<Class<?>, ObjectPool<?>> POOLS = new ConcurrentHashMap<>();
    
    @SuppressWarnings("unchecked")
    public static <T> ObjectPool<T> getPool(Class<T> clazz) {
        return (ObjectPool<T>) POOLS.computeIfAbsent(clazz, k -> createPool(clazz));
    }
    
    private static <T> ObjectPool<T> createPool(Class<T> clazz) {
        if (clazz == StringBuilder.class) {
            return (ObjectPool<T>) new StringBuilderPool(100, 256);
        } else if (clazz == ArrayList.class) {
            return (ObjectPool<T>) new ArrayListPool(100);
        }
        throw new IllegalArgumentException("Unsupported pool type: " + clazz.getName());
    }
}

// ============= 3. API 调用层 =============

/**
 * API 执行器接口
 * @param <T> API 返回的数据类型
 * @param <CTX> 上下文类型
 */
public interface IApiExecutor<T, CTX extends IAggregationContext<?, ?>> {
    
    /**
     * 获取执行器名称
     */
    String getName();
    
    /**
     * 是否支持分页
     */
    boolean supportsPagination();
    
    /**
     * 执行 API 调用（流式返回）
     */
    Stream<T> execute(CTX context) throws ApplicationException;
    
    /**
     * 获取依赖的 API（用于依赖编排）
     */
    default List<String> getDependencies() {
        return Collections.emptyList();
    }
    
    /**
     * 执行条件判断（用于条件编排）
     */
    default boolean shouldExecute(CTX context) {
        return true;
    }
}

/**
 * 抽象 API 执行器基类
 */
public abstract class AbstractApiExecutor<T, CTX extends IAggregationContext<?, ?>> 
        implements IApiExecutor<T, CTX> {
    
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    
    @Override
    public Stream<T> execute(CTX context) throws ApplicationException {
        if (!shouldExecute(context)) {
            return Stream.empty();
        }
        
        try {
            if (supportsPagination()) {
                return executePaginated(context);
            } else {
                return executeNonPaginated(context);
            }
        } catch (Exception e) {
            logger.error("API execution failed: {}", getName(), e);
            throw new ApplicationException("API_EXECUTION_ERROR", e);
        }
    }
    
    /**
     * 分页执行
     */
    protected Stream<T> executePaginated(CTX context) throws ApplicationException {
        // 先获取第一页，确定总页数
        PageResult<T> firstPage = fetchPage(context, 1);
        if (firstPage == null || firstPage.getData().isEmpty()) {
            return Stream.empty();
        }
        
        int totalPages = firstPage.getTotalPages();
        
        // 如果只有一页，直接返回
        if (totalPages <= 1) {
            return firstPage.getData().stream();
        }
        
        // 并行获取其他页
        List<CompletableFuture<List<T>>> futures = new ArrayList<>(totalPages - 1);
        for (int page = 2; page <= totalPages; page++) {
            final int pageNum = page;
            CompletableFuture<List<T>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    PageResult<T> pageResult = fetchPage(context, pageNum);
                    return pageResult != null ? pageResult.getData() : Collections.emptyList();
                } catch (Exception e) {
                    logger.error("Failed to fetch page {}", pageNum, e);
                    return Collections.emptyList();
                }
            });
            futures.add(future);
        }
        
        // 合并所有页的结果
        return Stream.concat(
            firstPage.getData().stream(),
            futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
        );
    }
    
    /**
     * 非分页执行
     */
    protected abstract Stream<T> executeNonPaginated(CTX context) throws ApplicationException;
    
    /**
     * 获取指定页数据
     */
    protected abstract PageResult<T> fetchPage(CTX context, int pageNum) throws ApplicationException;
}

/**
 * 分页结果封装
 */
@Data
public class PageResult<T> {
    private List<T> data;
    private int currentPage;
    private int totalPages;
    private long totalCount;
}

/**
 * API 执行结果包装
 */
@Data
public class ApiExecutionResult<T> {
    private String apiName;
    private Stream<T> dataStream;
    private boolean success;
    private String errorMessage;
    
    public static <T> ApiExecutionResult<T> success(String apiName, Stream<T> dataStream) {
        ApiExecutionResult<T> result = new ApiExecutionResult<>();
        result.setApiName(apiName);
        result.setDataStream(dataStream);
        result.setSuccess(true);
        return result;
    }
    
    public static <T> ApiExecutionResult<T> failure(String apiName, String errorMessage) {
        ApiExecutionResult<T> result = new ApiExecutionResult<>();
        result.setApiName(apiName);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.setDataStream(Stream.empty());
        return result;
    }
}

/**
 * 示例：度量数据 API 执行器
 */
@Component
public class MeasureDataApiExecutor extends AbstractApiExecutor<MeasureRawData, MeasureAggregationContext> {
    
    @Autowired
    private MeasureDataClient measureDataClient;
    
    @Override
    public String getName() {
        return "MeasureDataApi";
    }
    
    @Override
    public boolean supportsPagination() {
        return true;
    }
    
    @Override
    protected Stream<MeasureRawData> executeNonPaginated(MeasureAggregationContext context) 
            throws ApplicationException {
        MeasureReqVO request = context.getRequest();
        List<MeasureRawData> data = measureDataClient.queryMeasureData(request);
        return data != null ? data.stream() : Stream.empty();
    }
    
    @Override
    protected PageResult<MeasureRawData> fetchPage(MeasureAggregationContext context, int pageNum) 
            throws ApplicationException {
        MeasureReqVO request = context.getRequest();
        PageQueryReqVO pageRequest = new PageQueryReqVO();
        BeanUtils.copyProperties(request, pageRequest);
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(100);
        
        PageResponseVO<MeasureRawData> response = measureDataClient.queryMeasureDataPage(pageRequest);
        
        PageResult<MeasureRawData> result = new PageResult<>();
        result.setData(response.getData());
        result.setCurrentPage(pageNum);
        result.setTotalPages(response.getTotalPages());
        result.setTotalCount(response.getTotalCount());
        
        return result;
    }
}

/**
 * 示例原始数据对象
 */
@Data
public class MeasureRawData {
    private String periodId;
    private String orgCode;
    private String domainCode;
    private String measureCode;
    private BigDecimal value;
    private String unit;
}

// ============= 4. 过滤器层 =============

/**
 * 数据过滤器接口
 * @param <T> 数据类型
 * @param <CTX> 上下文类型
 */
public interface IDataFilter<T, CTX extends IAggregationContext<?, ?>> {
    
    /**
     * 获取过滤器名称
     */
    String getName();
    
    /**
     * 过滤器优先级（数字越小优先级越高）
     */
    default int getOrder() {
        return 100;
    }
    
    /**
     * 测试数据是否通过过滤器
     */
    boolean test(T data, CTX context);
}

/**
 * 过滤器链
 */
public class FilterChain<T, CTX extends IAggregationContext<?, ?>> {
    
    private final List<IDataFilter<T, CTX>> filters;
    
    public FilterChain(List<IDataFilter<T, CTX>> filters) {
        // 按优先级排序
        this.filters = filters.stream()
            .sorted(Comparator.comparingInt(IDataFilter::getOrder))
            .collect(Collectors.toList());
    }
    
    /**
     * 流式过滤
     */
    public Stream<T> filter(Stream<T> dataStream, CTX context) {
        if (filters.isEmpty()) {
            return dataStream;
        }
        
        // 组合所有过滤器为一个 Predicate
        Predicate<T> combinedPredicate = data -> {
            for (IDataFilter<T, CTX> filter : filters) {
                if (!filter.test(data, context)) {
                    return false;
                }
            }
            return true;
        };
        
        return dataStream.filter(combinedPredicate);
    }
}

/**
 * 默认过滤器（不过滤任何数据）
 */
public class NoOpFilter<T, CTX extends IAggregationContext<?, ?>> implements IDataFilter<T, CTX> {
    
    @Override
    public String getName() {
        return "NoOpFilter";
    }
    
    @Override
    public boolean test(T data, CTX context) {
        return true;
    }
}

/**
 * 组织过滤器示例
 */
@Component
public class OrgFilter implements IDataFilter<MeasureRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "OrgFilter";
    }
    
    @Override
    public int getOrder() {
        return 10;
    }
    
    @Override
    public boolean test(MeasureRawData data, MeasureAggregationContext context) {
        MeasureReqVO request = context.getRequest();
        List<String> orgCodes = request.getOrgCodes();
        
        // 如果没有指定组织，不过滤
        if (orgCodes == null || orgCodes.isEmpty()) {
            return true;
        }
        
        // 检查数据的组织编码是否在请求的组织列表中
        return orgCodes.contains(data.getOrgCode());
    }
}

/**
 * 领域过滤器示例
 */
@Component
public class DomainFilter implements IDataFilter<MeasureRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "DomainFilter";
    }
    
    @Override
    public int getOrder() {
        return 20;
    }
    
    @Override
    public boolean test(MeasureRawData data, MeasureAggregationContext context) {
        MeasureReqVO request = context.getRequest();
        List<String> domainCodes = request.getDomainCodes();
        
        if (domainCodes == null || domainCodes.isEmpty()) {
            return true;
        }
        
        return domainCodes.contains(data.getDomainCode());
    }
}

/**
 * 会计期过滤器示例
 */
@Component
public class PeriodFilter implements IDataFilter<MeasureRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "PeriodFilter";
    }
    
    @Override
    public int getOrder() {
        return 5;
    }
    
    @Override
    public boolean test(MeasureRawData data, MeasureAggregationContext context) {
        MeasureReqVO request = context.getRequest();
        List<String> periodIds = request.getPeriodIds();
        
        if (periodIds == null || periodIds.isEmpty()) {
            return true;
        }
        
        return periodIds.contains(data.getPeriodId());
    }
}

/**
 * 空值过滤器
 */
@Component
public class NullValueFilter implements IDataFilter<MeasureRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "NullValueFilter";
    }
    
    @Override
    public int getOrder() {
        return 1;
    }
    
    @Override
    public boolean test(MeasureRawData data, MeasureAggregationContext context) {
        return data != null && data.getValue() != null;
    }
}

/**
 * 过滤器工厂
 */
@Component
public class FilterFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    private final Map<String, List<String>> sceneFilterMapping = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 配置场景与过滤器的映射关系
        sceneFilterMapping.put("SCENE_A", Arrays.asList("NullValueFilter", "OrgFilter", "DomainFilter"));
        sceneFilterMapping.put("SCENE_B", Arrays.asList("NullValueFilter", "PeriodFilter"));
        sceneFilterMapping.put("DEFAULT", Arrays.asList("NullValueFilter"));
    }
    
    /**
     * 根据场景获取过滤器链
     */
    public <T, CTX extends IAggregationContext<?, ?>> FilterChain<T, CTX> createFilterChain(
            String sceneType, Class<T> dataType) {
        
        List<String> filterNames = sceneFilterMapping.getOrDefault(sceneType, 
            sceneFilterMapping.get("DEFAULT"));
        
        List<IDataFilter<T, CTX>> filters = new ArrayList<>();
        for (String filterName : filterNames) {
            try {
                IDataFilter<T, CTX> filter = (IDataFilter<T, CTX>) applicationContext.getBean(filterName);
                filters.add(filter);
            } catch (Exception e) {
                // 过滤器不存在，跳过
            }
        }
        
        // 如果没有配置过滤器，返回默认不过滤
        if (filters.isEmpty()) {
            filters.add(new NoOpFilter<>());
        }
        
        return new FilterChain<>(filters);
    }
}

// ============= 5. 转换器层 =============

/**
 * 数据转换器接口
 * @param <FROM> 源数据类型
 * @param <TO> 目标数据类型
 * @param <CTX> 上下文类型
 */
public interface IDataConverter<FROM, TO, CTX extends IAggregationContext<?, ?>> {
    
    /**
     * 获取转换器名称
     */
    String getName();
    
    /**
     * 转换单个数据对象
     */
    TO convert(FROM source, CTX context);
    
    /**
     * 批量转换（可选优化）
     */
    default List<TO> convertBatch(List<FROM> sources, CTX context) {
        return sources.stream()
            .map(source -> convert(source, context))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

/**
 * 默认转换器（不转换）
 */
public class NoOpConverter<T, CTX extends IAggregationContext<?, ?>> 
        implements IDataConverter<T, T, CTX> {
    
    @Override
    public String getName() {
        return "NoOpConverter";
    }
    
    @Override
    public T convert(T source, CTX context) {
        return source;
    }
}

/**
 * 抽象转换器基类
 */
public abstract class AbstractDataConverter<FROM, TO, CTX extends IAggregationContext<?, ?>> 
        implements IDataConverter<FROM, TO, CTX> {
    
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    
    @Override
    public TO convert(FROM source, CTX context) {
        if (source == null) {
            return null;
        }
        
        try {
            return doConvert(source, context);
        } catch (Exception e) {
            logger.error("Data conversion failed: {}", getName(), e);
            return null;
        }
    }
    
    /**
     * 子类实现具体转换逻辑
     */
    protected abstract TO doConvert(FROM source, CTX context);
}

/**
 * 度量数据转换器：MeasureRawData -> MeasureDataVO
 */
@Component
public class MeasureDataConverter 
        extends AbstractDataConverter<MeasureRawData, MeasureDataVO, MeasureAggregationContext> {
    
    // 使用 ThreadLocal 缓存 DecimalFormat，避免线程安全问题
    private static final ThreadLocal<DecimalFormat> DECIMAL_FORMAT_CACHE = 
        ThreadLocal.withInitial(() -> new DecimalFormat("#.##"));
    
    @Override
    public String getName() {
        return "MeasureDataConverter";
    }
    
    @Override
    protected MeasureDataVO doConvert(MeasureRawData source, MeasureAggregationContext context) {
        MeasureMetadata metadata = context.getMetadata();
        MeasureInfo measureInfo = metadata.getMeasureMap().get(source.getMeasureCode());
        
        if (measureInfo == null) {
            return null;
        }
        
        MeasureDataVO vo = new MeasureDataVO();
        vo.setMeasureCode(source.getMeasureCode());
        vo.setUnit(measureInfo.getUnit());
        vo.setCurrency(measureInfo.getCurrency());
        
        // 设置原始值
        String originValue = source.getValue().toPlainString();
        vo.setOriginValue(originValue);
        
        // 根据精度四舍五入
        Integer precision = measureInfo.getPrecision();
        if (precision != null && precision >= 0) {
            BigDecimal rounded = source.getValue()
                .setScale(precision, RoundingMode.HALF_UP);
            vo.setFixedValue(rounded.toPlainString());
        } else {
            vo.setFixedValue(originValue);
        }
        
        return vo;
    }
}

/**
 * 高性能字段提取器（避免反射）
 * 使用函数式接口实现零反射的字段提取
 */
public interface FieldExtractor<T, R> {
    R extract(T source);
}

/**
 * 字段提取器注册表
 */
public class FieldExtractorRegistry {
    
    private final Map<String, Map<String, FieldExtractor<?, ?>>> extractors = new ConcurrentHashMap<>();
    
    /**
     * 注册字段提取器
     */
    public <T, R> void register(Class<T> clazz, String fieldName, FieldExtractor<T, R> extractor) {
        String className = clazz.getName();
        extractors.computeIfAbsent(className, k -> new ConcurrentHashMap<>())
            .put(fieldName, extractor);
    }
    
    /**
     * 获取字段提取器
     */
    @SuppressWarnings("unchecked")
    public <T, R> FieldExtractor<T, R> getExtractor(Class<T> clazz, String fieldName) {
        String className = clazz.getName();
        Map<String, FieldExtractor<?, ?>> classExtractors = extractors.get(className);
        if (classExtractors == null) {
            return null;
        }
        return (FieldExtractor<T, R>) classExtractors.get(fieldName);
    }
}

/**
 * 通用转换器配置
 */
@Configuration
public class ConverterConfiguration {
    
    @Bean
    public FieldExtractorRegistry fieldExtractorRegistry() {
        FieldExtractorRegistry registry = new FieldExtractorRegistry();
        
        // 注册 MeasureRawData 的字段提取器
        registry.register(MeasureRawData.class, "value", MeasureRawData::getValue);
        registry.register(MeasureRawData.class, "orgCode", MeasureRawData::getOrgCode);
        registry.register(MeasureRawData.class, "domainCode", MeasureRawData::getDomainCode);
        registry.register(MeasureRawData.class, "periodId", MeasureRawData::getPeriodId);
        registry.register(MeasureRawData.class, "measureCode", MeasureRawData::getMeasureCode);
        
        return registry;
    }
}

/**
 * 转换器工厂
 */
@Component
public class ConverterFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    private final Map<String, String> sceneConverterMapping = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 配置场景与转换器的映射
        sceneConverterMapping.put("SCENE_A", "MeasureDataConverter");
        sceneConverterMapping.put("SCENE_B", "MeasureDataConverter");
        sceneConverterMapping.put("DEFAULT", "MeasureDataConverter");
    }
    
    /**
     * 根据场景获取转换器
     */
    @SuppressWarnings("unchecked")
    public <FROM, TO, CTX extends IAggregationContext<?, ?>> IDataConverter<FROM, TO, CTX> 
            getConverter(String sceneType) {
        
        String converterName = sceneConverterMapping.getOrDefault(sceneType, 
            sceneConverterMapping.get("DEFAULT"));
        
        try {
            return (IDataConverter<FROM, TO, CTX>) applicationContext.getBean(converterName);
        } catch (Exception e) {
            // 返回默认转换器
            return (IDataConverter<FROM, TO, CTX>) new NoOpConverter<>();
        }
    }
}

// ============= 6. 聚合层 =============

/**
 * 聚合策略接口
 * @param <T> 数据类型
 * @param <R> 结果类型
 * @param <CTX> 上下文类型
 */
public interface IAggregationStrategy<T, R, CTX extends IAggregationContext<?, ?>> {
    
    /**
     * 获取策略名称
     */
    String getName();
    
    /**
     * 聚合数据流
     */
    R aggregate(Stream<T> dataStream, CTX context);
}

/**
 * 度量数据聚合策略（使用 ConcurrentHashMap 实现无锁聚合）
 */
@Component
public class MeasureDataAggregationStrategy 
        implements IAggregationStrategy<MeasureDataVO, Map<String, OpMetricDataRespVO>, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "MeasureDataAggregation";
    }
    
    @Override
    public Map<String, OpMetricDataRespVO> aggregate(Stream<MeasureDataVO> dataStream, 
                                                       MeasureAggregationContext context) {
        
        // 使用 ConcurrentHashMap 保证线程安全
        ConcurrentHashMap<String, OpMetricDataRespVO> periodMap = new ConcurrentHashMap<>(16);
        
        // 从上下文获取必要信息
        MeasureReqVO request = context.getRequest();
        MeasureMetadata metadata = context.getMetadata();
        
        // 流式聚合（避免一次性加载所有数据到内存）
        dataStream.forEach(measureData -> {
            // 从上下文或数据中提取 periodId, orgCode, domainCode
            String periodId = extractPeriodId(measureData, context);
            String orgCode = extractOrgCode(measureData, context);
            String domainCode = extractDomainCode(measureData, context);
            String metricCode = extractMetricCode(measureData, context);
            
            if (periodId == null || metricCode == null) {
                return;
            }
            
            // 获取或创建 period 级别的聚合对象
            OpMetricDataRespVO respVO = periodMap.computeIfAbsent(periodId, k -> {
                OpMetricDataRespVO vo = new OpMetricDataRespVO();
                vo.setPeriodId(periodId);
                vo.setMeasureMap(new ConcurrentHashMap<>(64));
                return vo;
            });
            
            // 构建 key: metricCode:::orgCode:::domainCode
            String key = buildAggregationKey(metricCode, orgCode, domainCode);
            
            // 使用 computeIfAbsent 保证线程安全的初始化
            List<MeasureDataVO> measureList = respVO.getMeasureMap()
                .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            
            measureList.add(measureData);
        });
        
        return periodMap;
    }
    
    /**
     * 构建聚合 key
     */
    private String buildAggregationKey(String metricCode, String orgCode, String domainCode) {
        // 使用 StringBuilder 池优化字符串拼接
        StringBuilder sb = new StringBuilder(64);
        sb.append(metricCode).append(":::").append(orgCode).append(":::").append(domainCode);
        return sb.toString();
    }
    
    private String extractPeriodId(MeasureDataVO measureData, MeasureAggregationContext context) {
        // 从上下文属性中获取（之前处理时存储）
        return context.getAttribute("currentPeriodId");
    }
    
    private String extractOrgCode(MeasureDataVO measureData, MeasureAggregationContext context) {
        return context.getAttribute("currentOrgCode");
    }
    
    private String extractDomainCode(MeasureDataVO measureData, MeasureAggregationContext context) {
        return context.getAttribute("currentDomainCode");
    }
    
    private String extractMetricCode(MeasureDataVO measureData, MeasureAggregationContext context) {
        // 根据 measureCode 反向查找 metricCode
        MeasureMetadata metadata = context.getMetadata();
        String measureCode = measureData.getMeasureCode();
        
        // 从缓存中查找
        Map<String, String> cache = metadata.getMetricToMeasuresCache();
        return cache.get(measureCode);
    }
}

/**
 * 分区聚合策略（用于大数据量场景）
 * 将数据分区，每个分区独立聚合，最后合并结果
 */
public class PartitionedAggregationStrategy<T, R, CTX extends IAggregationContext<?, ?>> 
        implements IAggregationStrategy<T, R, CTX> {
    
    private final IAggregationStrategy<T, R, CTX> baseStrategy;
    private final int partitionSize;
    
    public PartitionedAggregationStrategy(IAggregationStrategy<T, R, CTX> baseStrategy, int partitionSize) {
        this.baseStrategy = baseStrategy;
        this.partitionSize = partitionSize;
    }
    
    @Override
    public String getName() {
        return "PartitionedAggregation-" + baseStrategy.getName();
    }
    
    @Override
    public R aggregate(Stream<T> dataStream, CTX context) {
        // 将流分区并并行处理
        AtomicInteger counter = new AtomicInteger(0);
        
        Map<Integer, List<T>> partitions = dataStream
            .collect(Collectors.groupingBy(item -> counter.getAndIncrement() / partitionSize));
        
        // 并行聚合各分区
        List<R> partialResults = partitions.values().parallelStream()
            .map(partition -> baseStrategy.aggregate(partition.stream(), context))
            .collect(Collectors.toList());
        
        // 合并结果（需要子类实现）
        return mergeResults(partialResults, context);
    }
    
    /**
     * 合并分区结果
     */
    protected R mergeResults(List<R> partialResults, CTX context) {
        // 默认返回第一个结果，子类应该重写此方法
        return partialResults.isEmpty() ? null : partialResults.get(0);
    }
}

/**
 * 聚合器包装器（添加流式处理增强）
 */
public class StreamingAggregator<T, R, CTX extends IAggregationContext<?, ?>> {
    
    private final IAggregationStrategy<T, R, CTX> strategy;
    private final int bufferSize;
    
    public StreamingAggregator(IAggregationStrategy<T, R, CTX> strategy, int bufferSize) {
        this.strategy = strategy;
        this.bufferSize = bufferSize;
    }
    
    /**
     * 流式聚合（批量处理）
     */
    public R aggregateWithBatching(Stream<T> dataStream, CTX context) {
        AtomicInteger counter = new AtomicInteger(0);
        
        // 按批次收集数据
        Collection<List<T>> batches = dataStream
            .collect(Collectors.groupingBy(item -> counter.getAndIncrement() / bufferSize))
            .values();
        
        // 逐批聚合
        R result = null;
        for (List<T> batch : batches) {
            R batchResult = strategy.aggregate(batch.stream(), context);
            result = mergeBatchResult(result, batchResult, context);
        }
        
        return result;
    }
    
    /**
     * 合并批次结果
     */
    @SuppressWarnings("unchecked")
    private R mergeBatchResult(R current, R newBatch, CTX context) {
        if (current == null) {
            return newBatch;
        }
        
        if (newBatch == null) {
            return current;
        }
        
        // 如果结果是 Map 类型，进行合并
        if (current instanceof Map && newBatch instanceof Map) {
            Map<Object, Object> currentMap = (Map<Object, Object>) current;
            Map<Object, Object> newMap = (Map<Object, Object>) newBatch;
            
            newMap.forEach((key, value) -> {
                currentMap.merge(key, value, (oldVal, newVal) -> {
                    // 如果值是 OpMetricDataRespVO，需要合并其内部的 measureMap
                    if (oldVal instanceof OpMetricDataRespVO && newVal instanceof OpMetricDataRespVO) {
                        OpMetricDataRespVO oldResp = (OpMetricDataRespVO) oldVal;
                        OpMetricDataRespVO newResp = (OpMetricDataRespVO) newVal;
                        
                        newResp.getMeasureMap().forEach((k, v) -> {
                            oldResp.getMeasureMap().merge(k, v, (oldList, newList) -> {
                                oldList.addAll(newList);
                                return oldList;
                            });
                        });
                        
                        return oldVal;
                    }
                    return newVal;
                });
            });
            
            return current;
        }
        
        return newBatch;
    }
}

/**
 * 聚合工厂
 */
@Component
public class AggregationFactory {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    /**
     * 创建聚合策略
     */
    @SuppressWarnings("unchecked")
    public <T, R, CTX extends IAggregationContext<?, ?>> IAggregationStrategy<T, R, CTX> 
            createStrategy(String sceneType) {
        
        // 根据场景选择策略
        switch (sceneType) {
            case "SCENE_A":
            case "SCENE_B":
                return (IAggregationStrategy<T, R, CTX>) 
                    applicationContext.getBean(MeasureDataAggregationStrategy.class);
            default:
                return (IAggregationStrategy<T, R, CTX>) 
                    applicationContext.getBean(MeasureDataAggregationStrategy.class);
        }
    }
    
    /**
     * 创建流式聚合器
     */
    public <T, R, CTX extends IAggregationContext<?, ?>> StreamingAggregator<T, R, CTX> 
            createStreamingAggregator(String sceneType, int bufferSize) {
        
        IAggregationStrategy<T, R, CTX> strategy = createStrategy(sceneType);
        return new StreamingAggregator<>(strategy, bufferSize);
    }
}

// ============= 7. 编排层 =============

/**
 * API 编排配置
 */
@Data
public class ApiOrchestrationConfig {
    private String apiName;
    private List<String> dependencies;  // 依赖的 API
    private String condition;  // 执行条件表达式
    private boolean async;  // 是否异步执行
    
    public ApiOrchestrationConfig(String apiName) {
        this.apiName = apiName;
        this.dependencies = new ArrayList<>();
        this.async = true;
    }
}

/**
 * 编排执行结果
 */
@Data
public class OrchestrationResult<T> {
    private Map<String, ApiExecutionResult<T>> apiResults;
    private boolean allSuccess;
    private List<String> errors;
    
    public OrchestrationResult() {
        this.apiResults = new ConcurrentHashMap<>();
        this.errors = new CopyOnWriteArrayList<>();
        this.allSuccess = true;
    }
    
    public void addResult(String apiName, ApiExecutionResult<T> result) {
        apiResults.put(apiName, result);
        if (!result.isSuccess()) {
            allSuccess = false;
            errors.add(apiName + ": " + result.getErrorMessage());
        }
    }
}

/**
 * API 编排器
 */
@Component
public class ApiOrchestrator<T, CTX extends IAggregationContext<?, ?>> {
    
    private final Logger logger = LoggerFactory.getLogger(ApiOrchestrator.class);
    
    @Autowired
    private ItaskExecutorService taskExecutorService;
    
    /**
     * 执行 API 编排
     */
    public OrchestrationResult<T> orchestrate(
            List<ApiOrchestrationConfig> configs,
            Map<String, IApiExecutor<T, CTX>> executorMap,
            CTX context) throws ApplicationException {
        
        OrchestrationResult<T> result = new OrchestrationResult<>();
        
        // 构建依赖图
        Map<String, Set<String>> dependencyGraph = buildDependencyGraph(configs);
        
        // 拓扑排序，确定执行顺序
        List<List<String>> executionLevels = topologicalSort(dependencyGraph);
        
        // 按层级执行
        for (List<String> level : executionLevels) {
            executeLevel(level, configs, executorMap, context, result);
        }
        
        return result;
    }
    
    /**
     * 执行一个层级的 API（可并行）
     */
    private void executeLevel(
            List<String> apiNames,
            List<ApiOrchestrationConfig> configs,
            Map<String, IApiExecutor<T, CTX>> executorMap,
            CTX context,
            OrchestrationResult<T> result) {
        
        // 过滤出可执行的 API
        List<ApiOrchestrationConfig> executableApis = apiNames.stream()
            .map(name -> findConfig(configs, name))
            .filter(Objects::nonNull)
            .filter(config -> shouldExecute(config, context))
            .collect(Collectors.toList());
        
        if (executableApis.isEmpty()) {
            return;
        }
        
        // 并行执行
        List<CompletableFuture<Void>> futures = executableApis.stream()
            .map(config -> CompletableFuture.runAsync(() -> {
                try {
                    IApiExecutor<T, CTX> executor = executorMap.get(config.getApiName());
                    if (executor == null) {
                        result.addResult(config.getApiName(), 
                            ApiExecutionResult.failure(config.getApiName(), "Executor not found"));
                        return;
                    }
                    
                    Stream<T> dataStream = executor.execute(context);
                    result.addResult(config.getApiName(), 
                        ApiExecutionResult.success(config.getApiName(), dataStream));
                    
                } catch (Exception e) {
                    logger.error("API execution failed: {}", config.getApiName(), e);
                    result.addResult(config.getApiName(), 
                        ApiExecutionResult.failure(config.getApiName(), e.getMessage()));
                }
            }, taskExecutorService.findExecutorService()))
            .collect(Collectors.toList());
        
        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
    
    /**
     * 构建依赖图
     */
    private Map<String, Set<String>> buildDependencyGraph(List<ApiOrchestrationConfig> configs) {
        Map<String, Set<String>> graph = new HashMap<>();
        
        for (ApiOrchestrationConfig config : configs) {
            graph.putIfAbsent(config.getApiName(), new HashSet<>());
            if (config.getDependencies() != null) {
                graph.get(config.getApiName()).addAll(config.getDependencies());
            }
        }
        
        return graph;
    }
    
    /**
     * 拓扑排序（Kahn算法）
     */
    private List<List<String>> topologicalSort(Map<String, Set<String>> graph) {
        List<List<String>> levels = new ArrayList<>();
        
        // 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : graph.keySet()) {
            inDegree.putIfAbsent(node, 0);
            for (String dep : graph.get(node)) {
                inDegree.put(dep, inDegree.getOrDefault(dep, 0) + 1);
            }
        }
        
        // 找出所有入度为 0 的节点
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        while (!queue.isEmpty()) {
            int size = queue.size();
            List<String> currentLevel = new ArrayList<>();
            
            for (int i = 0; i < size; i++) {
                String node = queue.poll();
                currentLevel.add(node);
                
                // 更新依赖此节点的其他节点的入度
                for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
                    if (entry.getValue().contains(node)) {
                        int newDegree = inDegree.get(entry.getKey()) - 1;
                        inDegree.put(entry.getKey(), newDegree);
                        if (newDegree == 0) {
                            queue.offer(entry.getKey());
                        }
                    }
                }
            }
            
            levels.add(currentLevel);
        }
        
        return levels;
    }
    
    /**
     * 判断是否应该执行
     */
    private boolean shouldExecute(ApiOrchestrationConfig config, CTX context) {
        if (config.getCondition() == null || config.getCondition().isEmpty()) {
            return true;
        }
        
        // 简单的条件表达式解析（可扩展为 SpEL 等）
        return evaluateCondition(config.getCondition(), context);
    }
    
    /**
     * 条件表达式求值
     */
    private boolean evaluateCondition(String condition, CTX context) {
        // 简单实现：支持基本的属性检查
        // 格式：attribute.key == value
        if (condition.contains("==")) {
            String[] parts = condition.split("==");
            String key = parts[0].trim().replace("attribute.", "");
            String expectedValue = parts[1].trim().replace("\"", "");
            
            Object actualValue = context.getAttribute(key);
            return expectedValue.equals(String.valueOf(actualValue));
        }
        
        return true;
    }
    
    private ApiOrchestrationConfig findConfig(List<ApiOrchestrationConfig> configs, String apiName) {
        return configs.stream()
            .filter(c -> c.getApiName().equals(apiName))
            .findFirst()
            .orElse(null);
    }
}

/**
 * 数据聚合编排器（整合所有层）
 */
@Component
public class DataAggregationOrchestrator<FROM, TO, CTX extends IAggregationContext<?, ?>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataAggregationOrchestrator.class);
    
    @Autowired
    private ApiOrchestrator<FROM, CTX> apiOrchestrator;
    
    @Autowired
    private FilterFactory filterFactory;
    
    @Autowired
    private ConverterFactory converterFactory;
    
    @Autowired
    private AggregationFactory aggregationFactory;
    
    /**
     * 执行完整的数据聚合流程
     */
    public <R> R executeAggregation(
            CTX context,
            List<ApiOrchestrationConfig> apiConfigs,
            Map<String, IApiExecutor<FROM, CTX>> apiExecutors,
            Class<FROM> dataType) throws ApplicationException {
        
        String sceneType = context.getSceneType();
        
        // 1. 执行 API 编排
        OrchestrationResult<FROM> apiResult = apiOrchestrator.orchestrate(
            apiConfigs, apiExecutors, context);
        
        if (!apiResult.isAllSuccess()) {
            logger.warn("Some APIs failed: {}", apiResult.getErrors());
        }
        
        // 2. 获取过滤器链
        FilterChain<FROM, CTX> filterChain = filterFactory.createFilterChain(sceneType, dataType);
        
        // 3. 获取转换器
        IDataConverter<FROM, TO, CTX> converter = converterFactory.getConverter(sceneType);
        
        // 4. 获取聚合器
        StreamingAggregator<TO, R, CTX> aggregator = 
            aggregationFactory.createStreamingAggregator(sceneType, 1000);
        
        // 5. 合并所有 API 的数据流
        Stream<FROM> mergedStream = apiResult.getApiResults().values().stream()
            .filter(ApiExecutionResult::isSuccess)
            .flatMap(result -> result.getDataStream());
        
        // 6. 应用过滤器
        Stream<FROM> filteredStream = filterChain.filter(mergedStream, context);
        
        // 7. 转换数据
        Stream<TO> convertedStream = filteredStream
            .map(data -> converter.convert(data, context))
            .filter(Objects::nonNull);
        
        // 8. 聚合结果
        R aggregatedResult = aggregator.aggregateWithBatching(convertedStream, context);
        
        return aggregatedResult;
    }
}

// ============= 8. 服务门面层 =============

/**
 * 元数据服务（管理缓存）
 */
@Service
public class MetadataService {
    
    private final Logger logger = LoggerFactory.getLogger(MetadataService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // 本地缓存
    private volatile MeasureMetadata localCache;
    
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * 获取元数据
     */
    public MeasureMetadata getMetadata() {
        // 先尝试从本地缓存获取
        lock.readLock().lock();
        try {
            if (localCache != null) {
                return localCache;
            }
        } finally {
            lock.readLock().unlock();
        }
        
        // 本地缓存未命中，从 Redis 获取
        lock.writeLock().lock();
        try {
            // 双重检查
            if (localCache != null) {
                return localCache;
            }
            
            localCache = loadFromRedis();
            if (localCache == null) {
                localCache = loadFromDatabase();
                saveToRedis(localCache);
            }
            
            return localCache;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 刷新元数据缓存
     */
    public void refreshMetadata() {
        lock.writeLock().lock();
        try {
            MeasureMetadata metadata = loadFromDatabase();
            saveToRedis(metadata);
            localCache = metadata;
            logger.info("Metadata refreshed successfully");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private MeasureMetadata loadFromRedis() {
        try {
            return (MeasureMetadata) redisTemplate.opsForValue().get("metadata:measure");
        } catch (Exception e) {
            logger.error("Failed to load metadata from Redis", e);
            return null;
        }
    }
    
    private void saveToRedis(MeasureMetadata metadata) {
        try {
            redisTemplate.opsForValue().set("metadata:measure", metadata, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.error("Failed to save metadata to Redis", e);
        }
    }
    
    private MeasureMetadata loadFromDatabase() {
        // 从数据库加载元数据
        MeasureMetadata metadata = new MeasureMetadata();
        // TODO: 实现数据库查询逻辑
        return metadata;
    }
}

/**
 * 元数据刷新定时任务
 */
@Component
public class MetadataRefreshTask implements ITimerTask {
    
    @Autowired
    private MetadataService metadataService;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        metadataService.refreshMetadata();
    }
}

/**
 * 场景配置服务
 */
@Service
public class SceneConfigService {
    
    private final Map<String, List<ApiOrchestrationConfig>> sceneApiConfigs = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 配置场景 A
        List<ApiOrchestrationConfig> sceneAConfigs = new ArrayList<>();
        
        ApiOrchestrationConfig api1 = new ApiOrchestrationConfig("MeasureDataApi");
        sceneAConfigs.add(api1);
        
        ApiOrchestrationConfig api2 = new ApiOrchestrationConfig("AdditionalDataApi");
        api2.setDependencies(Arrays.asList("MeasureDataApi"));
        sceneAConfigs.add(api2);
        
        sceneApiConfigs.put("SCENE_A", sceneAConfigs);
        
        // 配置场景 B
        List<ApiOrchestrationConfig> sceneBConfigs = new ArrayList<>();
        ApiOrchestrationConfig api3 = new ApiOrchestrationConfig("MeasureDataApi");
        sceneBConfigs.add(api3);
        
        sceneApiConfigs.put("SCENE_B", sceneBConfigs);
    }
    
    public List<ApiOrchestrationConfig> getApiConfigs(String sceneType) {
        return sceneApiConfigs.getOrDefault(sceneType, Collections.emptyList());
    }
}

/**
 * API 执行器注册表
 */
@Component
public class ApiExecutorRegistry {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    private final Map<String, IApiExecutor<?, ?>> executors = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 自动注册所有 IApiExecutor 实现
        Map<String, IApiExecutor> beans = applicationContext.getBeansOfType(IApiExecutor.class);
        beans.forEach((beanName, executor) -> {
            executors.put(executor.getName(), executor);
        });
    }
    
    @SuppressWarnings("unchecked")
    public <T, CTX extends IAggregationContext<?, ?>> Map<String, IApiExecutor<T, CTX>> 
            getExecutorsForScene(String sceneType) {
        // 根据场景返回对应的执行器
        Map<String, IApiExecutor<T, CTX>> result = new HashMap<>();
        executors.forEach((name, executor) -> {
            result.put(name, (IApiExecutor<T, CTX>) executor);
        });
        return result;
    }
}

/**
 * 指标数据服务（门面）
 */
@Service
public class MetricDataService {
    
    private final Logger logger = LoggerFactory.getLogger(MetricDataService.class);
    
    @Autowired
    private MetadataService metadataService;
    
    @Autowired
    private SceneConfigService sceneConfigService;
    
    @Autowired
    private ApiExecutorRegistry apiExecutorRegistry;
    
    @Autowired
    private DataAggregationOrchestrator<MeasureRawData, MeasureDataVO, MeasureAggregationContext> orchestrator;
    
    /**
     * 获取度量数据
     */
    public List<OpMetricDataRespVO> getMeasures(MeasureReqVO reqVO) {
        try {
            // 1. 构建上下文
            MeasureMetadata metadata = metadataService.getMetadata();
            MeasureAggregationContext context = new MeasureAggregationContext(reqVO, metadata);
            
            // 2. 获取场景配置
            String sceneType = reqVO.getSceneType();
            List<ApiOrchestrationConfig> apiConfigs = sceneConfigService.getApiConfigs(sceneType);
            
            // 3. 获取 API 执行器
            Map<String, IApiExecutor<MeasureRawData, MeasureAggregationContext>> executors = 
                apiExecutorRegistry.getExecutorsForScene(sceneType);
            
            // 4. 执行聚合
            Map<String, OpMetricDataRespVO> resultMap = orchestrator.executeAggregation(
                context, apiConfigs, executors, MeasureRawData.class);
            
            // 5. 转换为列表返回
            return new ArrayList<>(resultMap.values());
            
        } catch (Exception e) {
            logger.error("Failed to get measures", e);
            throw new RuntimeException("Failed to get measures", e);
        }
    }
}

/**
 * 请求对象
 */
@Data
public class MeasureReqVO {
    private List<String> periodIds;
    private List<String> metricCodes;
    private List<String> domainCodes;
    private List<String> orgCodes;
    private String orgLevel;
    private String sceneType;
}

/**
 * 响应对象
 */
@Data
public class OpMetricDataRespVO {
    private String periodId;
    private Map<String, List<MeasureDataVO>> measureMap;
}

/**
 * Controller
 */
@RestController
@RequestMapping("/api/metric")
public class MetricDataController {
    
    @Autowired
    private MetricDataService metricDataService;
    
    @PostMapping("/measures")
    public ApiResponse<List<OpMetricDataRespVO>> getMeasures(@RequestBody MeasureReqVO reqVO) {
        List<OpMetricDataRespVO> result = metricDataService.getMeasures(reqVO);
        return ApiResponse.success(result);
    }
    
    @PostMapping("/metadata/refresh")
    public ApiResponse<Void> refreshMetadata() {
        // 触发元数据刷新
        return ApiResponse.success(null);
    }
}

@Data
public class ApiResponse<T> {
    private boolean success;
    private T data;
    private String message;
    private String errorCode;
    
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(true);
        response.setData(data);
        return response;
    }
    
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setMessage(message);
        return response;
    }
}

// ============= 9. 配置与使用示例 =============

/**
 * 线程池配置
 */
@Configuration
public class ThreadPoolConfig {
    
    @Bean
    public ItaskExecutorService taskExecutorService() {
        return new ItaskExecutorService() {
            
            private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                10,  // 核心线程数
                50,  // 最大线程数
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("metric-aggregation-" + counter.incrementAndGet());
                        return thread;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            
            @Override
            public ThreadPoolExecutor findExecutorService() {
                return executor;
            }
            
            @Override
            public void submitTask(TaskRun taskRun) {
                executor.execute(taskRun);
            }
            
            @Override
            public <T> CompletableFuture<T> submitTask(Supplier<T> supplier, String taskinfo) {
                return CompletableFuture.supplyAsync(supplier, executor);
            }
            
            @Override
            public void RequestContextHolderInit(IRequestContext current) {
                // 初始化请求上下文
            }
        };
    }
}

/**
 * 扩展示例：添加新的 API 执行器
 */
@Component
public class CustomApiExecutor extends AbstractApiExecutor<CustomRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "CustomApi";
    }
    
    @Override
    public boolean supportsPagination() {
        return false;
    }
    
    @Override
    public List<String> getDependencies() {
        // 依赖于 MeasureDataApi
        return Arrays.asList("MeasureDataApi");
    }
    
    @Override
    public boolean shouldExecute(MeasureAggregationContext context) {
        // 只在特定场景下执行
        return "SCENE_C".equals(context.getSceneType());
    }
    
    @Override
    protected Stream<CustomRawData> executeNonPaginated(MeasureAggregationContext context) 
            throws ApplicationException {
        // 实现自定义 API 调用逻辑
        List<CustomRawData> data = callCustomApi(context);
        return data != null ? data.stream() : Stream.empty();
    }
    
    @Override
    protected PageResult<CustomRawData> fetchPage(MeasureAggregationContext context, int pageNum) 
            throws ApplicationException {
        // 不支持分页
        return null;
    }
    
    private List<CustomRawData> callCustomApi(MeasureAggregationContext context) {
        // 实现 API 调用
        return new ArrayList<>();
    }
}

@Data
class CustomRawData {
    private String id;
    private String value;
}

/**
 * 扩展示例：添加自定义过滤器
 */
@Component
public class CustomFilter implements IDataFilter<MeasureRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "CustomFilter";
    }
    
    @Override
    public int getOrder() {
        return 50;
    }
    
    @Override
    public boolean test(MeasureRawData data, MeasureAggregationContext context) {
        // 自定义过滤逻辑
        return data.getValue().compareTo(BigDecimal.ZERO) > 0;
    }
}

/**
 * 扩展示例：添加自定义转换器
 */
@Component
public class CustomConverter 
        extends AbstractDataConverter<CustomRawData, MeasureDataVO, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "CustomConverter";
    }
    
    @Override
    protected MeasureDataVO doConvert(CustomRawData source, MeasureAggregationContext context) {
        MeasureDataVO vo = new MeasureDataVO();
        vo.setMeasureCode(source.getId());
        vo.setOriginValue(source.getValue());
        vo.setFixedValue(source.getValue());
        return vo;
    }
}

/**
 * 扩展示例：支持不同模块的泛型实现
 */
@Service
public class GenericMetricDataService<REQ, RESP, META extends IMetadata> {
    
    @Autowired
    private DataAggregationOrchestrator orchestrator;
    
    /**
     * 泛型方法，支持不同的请求和响应类型
     */
    public RESP getMetricData(
            REQ request,
            META metadata,
            Function<REQ, String> sceneTypeExtractor,
            Function<Map<String, ?>, RESP> resultMapper) {
        
        // 创建泛型上下文
        GenericContext<REQ, META> context = new GenericContext<>(request, metadata);
        
        String sceneType = sceneTypeExtractor.apply(request);
        context.setAttribute("sceneType", sceneType);
        
        // 执行聚合
        // ... 聚合逻辑 ...
        
        // 映射结果
        Map<String, Object> aggregatedData = new HashMap<>();
        return resultMapper.apply(aggregatedData);
    }
}

/**
 * 泛型上下文实现
 */
class GenericContext<REQ, META extends IMetadata> implements IAggregationContext<REQ, META> {
    
    private final String contextId;
    private final REQ request;
    private final META metadata;
    private final ConcurrentHashMap<String, Object> attributes;
    private final Map<Class<?>, ObjectPool<?>> objectPools;
    
    public GenericContext(REQ request, META metadata) {
        this.contextId = UUID.randomUUID().toString();
        this.request = request;
        this.metadata = metadata;
        this.attributes = new ConcurrentHashMap<>();
        this.objectPools = new ConcurrentHashMap<>();
    }
    
    @Override
    public String getContextId() {
        return contextId;
    }
    
    @Override
    public REQ getRequest() {
        return request;
    }
    
    @Override
    public META getMetadata() {
        return metadata;
    }
    
    @Override
    public String getSceneType() {
        return getAttribute("sceneType");
    }
    
    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T removeAttribute(String key) {
        return (T) attributes.remove(key);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> ObjectPool<T> getObjectPool(Class<T> clazz) {
        return (ObjectPool<T>) objectPools.computeIfAbsent(clazz, 
            k -> ObjectPoolFactory.getPool(clazz));
    }
}

/**
 * 使用示例
 */
@Component
public class UsageExample {
    
    @Autowired
    private MetricDataService metricDataService;
    
    public void example1() {
        // 示例 1: 基本使用
        MeasureReqVO request = new MeasureReqVO();
        request.setPeriodIds(Arrays.asList("2024-01", "2024-02"));
        request.setMetricCodes(Arrays.asList("M001", "M002"));
        request.setOrgCodes(Arrays.asList("ORG001", "ORG002"));
        request.setDomainCodes(Arrays.asList("DOMAIN001"));
        request.setSceneType("SCENE_A");
        
        List<OpMetricDataRespVO> result = metricDataService.getMeasures(request);
        
        // 处理结果
        for (OpMetricDataRespVO respVO : result) {
            System.out.println("Period: " + respVO.getPeriodId());
            respVO.getMeasureMap().forEach((key, measures) -> {
                System.out.println("  Key: " + key);
                measures.forEach(measure -> {
                    System.out.println("    Measure: " + measure.getMeasureCode() + 
                        " = " + measure.getFixedValue());
                });
            });
        }
    }
    
    public void example2() {
        // 示例 2: 不同场景
        MeasureReqVO request = new MeasureReqVO();
        request.setSceneType("SCENE_B");
        // ... 设置其他参数
        
        List<OpMetricDataRespVO> result = metricDataService.getMeasures(request);
    }
}

/**
 * 性能优化配置
 */
@Configuration
public class PerformanceConfig {
    
    /**
     * 配置 JVM 参数建议：
     * -Xms2g -Xmx4g
     * -XX:+UseG1GC
     * -XX:MaxGCPauseMillis=200
     * -XX:+ParallelRefProcEnabled
     * -XX:+UnlockExperimentalVMOptions
     * -XX:+AggressiveOpts
     */
    
    @Bean
    public CacheManager cacheManager() {
        // 配置缓存管理器
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        
        // 元数据缓存
        ConcurrentMapCache metadataCache = new ConcurrentMapCache("metadata");
        
        cacheManager.setCaches(Arrays.asList(metadataCache));
        return cacheManager;
    }
}

/**
 * 监控和日志配置
 */
@Aspect
@Component
public class PerformanceMonitorAspect {
    
    private final Logger logger = LoggerFactory.getLogger(PerformanceMonitorAspect.class);
    
    @Around("execution(* com.example..MetricDataService.getMeasures(..))")
    public Object monitorPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            logger.info("Method {} executed in {} ms", 
                joinPoint.getSignature().getName(), duration);
            
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Method {} failed after {} ms", 
                joinPoint.getSignature().getName(), duration, e);
            throw e;
        }
    }
}

架构设计总结与最佳实践

# 高性能指标数据聚合服务架构设计文档

## 一、架构概览

### 1.1 核心设计原则

? **高性能**
- 零反射设计：使用函数式接口和 Lambda 替代反射
- 对象池复用：StringBuilder、ArrayList 等频繁创建对象使用对象池
- 细粒度锁：ConcurrentHashMap 无锁聚合，ReadWriteLock 保护元数据

? **流式处理**
- Stream API：避免一次性加载所有数据到内存
- 批量处理：StreamingAggregator 支持分批聚合
- 惰性求值：只在需要时才执行计算

? **可扩展性**
- 泛型设计：支持不同请求/响应/元数据类型
- 插件化：过滤器、转换器、聚合策略可动态配置
- 场景驱动：基于 sceneType 动态组装处理链

? **线程安全**
- ConcurrentHashMap：聚合结果存储
- CopyOnWriteArrayList：度量数据列表
- ThreadLocal：DecimalFormat 等非线程安全对象

## 二、核心组件

### 2.1 分层架构

```
┌─────────────────────────────────────────┐
│  Service Layer (门面)                    │
│  - MetricDataService                     │
│  - MetadataService                       │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  Orchestration Layer (编排层)            │
│  - DataAggregationOrchestrator          │
│  - ApiOrchestrator                      │
└─────────────────────────────────────────┘
                    ↓
        ┌───────────┼───────────┐
        ↓           ↓           ↓
┌──────────┐ ┌──────────┐ ┌──────────┐
│API Layer │ │Filter    │ │Converter │
│          │ │Layer     │ │Layer     │
└──────────┘ └──────────┘ └──────────┘
                    ↓
        ┌───────────┴───────────┐
        ↓                       ↓
┌──────────────┐    ┌──────────────┐
│Aggregation   │    │Context       │
│Layer         │    │Management    │
└──────────────┘    └──────────────┘
```

### 2.2 核心接口

#### IAggregationContext<REQ, META>
- 泛型上下文，承载请求、元数据和运行时状态
- 线程安全的属性存储
- 对象池管理

#### IApiExecutor<T, CTX>
- 支持分页/非分页 API
- 依赖编排（getDependencies）
- 条件执行（shouldExecute）

#### IDataFilter<T, CTX>
- 优先级排序
- 组合为 FilterChain
- 流式过滤

#### IDataConverter<FROM, TO, CTX>
- 零反射转换
- 支持批量转换优化

#### IAggregationStrategy<T, R, CTX>
- 无锁聚合策略
- 支持分区聚合
- 流式批量处理

## 三、核心流程

### 3.1 完整数据聚合流程

```java
public List<OpMetricDataRespVO> getMeasures(MeasureReqVO reqVO) {
    // 1. 构建上下文
    MeasureMetadata metadata = metadataService.getMetadata();
    MeasureAggregationContext context = new MeasureAggregationContext(reqVO, metadata);
    
    // 2. 获取场景配置
    List<ApiOrchestrationConfig> apiConfigs = sceneConfigService.getApiConfigs(sceneType);
    
    // 3. API 编排执行（依赖 + 条件 + 异步）
    OrchestrationResult<MeasureRawData> apiResult = 
        apiOrchestrator.orchestrate(apiConfigs, executors, context);
    
    // 4. 合并数据流
    Stream<MeasureRawData> mergedStream = 
        apiResult.getApiResults().values().stream()
            .filter(ApiExecutionResult::isSuccess)
            .flatMap(result -> result.getDataStream());
    
    // 5. 应用过滤器链
    Stream<MeasureRawData> filteredStream = 
        filterChain.filter(mergedStream, context);
    
    // 6. 转换数据
    Stream<MeasureDataVO> convertedStream = 
        filteredStream.map(data -> converter.convert(data, context))
                      .filter(Objects::nonNull);
    
    // 7. 流式聚合
    Map<String, OpMetricDataRespVO> aggregatedResult = 
        aggregator.aggregateWithBatching(convertedStream, context);
    
    return new ArrayList<>(aggregatedResult.values());
}
```

### 3.2 API 编排流程

**依赖解析**
```
API_A (无依赖) → Level 0
API_B (依赖 API_A) → Level 1
API_C (依赖 API_A) → Level 1
API_D (依赖 API_B, API_C) → Level 2
```

**并行执行**
- Level 0: API_A 执行
- Level 1: API_B 和 API_C 并行执行
- Level 2: API_D 执行

### 3.3 流式处理示例

```java
// 避免这样做 ?
List<MeasureRawData> allData = api.fetchAll(); // 可能 OOM
List<MeasureDataVO> converted = convert(allData);
Map<String, List<MeasureDataVO>> result = aggregate(converted);

// 应该这样做 ?
Stream<MeasureRawData> dataStream = api.fetchAsStream();
Stream<MeasureDataVO> converted = dataStream
    .filter(filterChain::test)
    .map(converter::convert);
Map<String, OpMetricDataRespVO> result = 
    aggregator.aggregateWithBatching(converted, context); // 分批处理
```

## 四、性能优化技术

### 4.1 无锁/细粒度锁

**ConcurrentHashMap 无锁聚合**
```java
ConcurrentHashMap<String, OpMetricDataRespVO> periodMap = new ConcurrentHashMap<>();

dataStream.forEach(measureData -> {
    OpMetricDataRespVO respVO = periodMap.computeIfAbsent(periodId, k -> {
        OpMetricDataRespVO vo = new OpMetricDataRespVO();
        vo.setMeasureMap(new ConcurrentHashMap<>());
        return vo;
    });
    
    List<MeasureDataVO> measureList = respVO.getMeasureMap()
        .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
    
    measureList.add(measureData);
});
```

**ReadWriteLock 保护元数据**
```java
private final ReadWriteLock lock = new ReentrantReadWriteLock();

public MeasureMetadata getMetadata() {
    lock.readLock().lock();  // 读锁，允许并发读
    try {
        return localCache;
    } finally {
        lock.readLock().unlock();
    }
}

public void refreshMetadata() {
    lock.writeLock().lock();  // 写锁，独占
    try {
        localCache = loadFromDatabase();
    } finally {
        lock.writeLock().unlock();
    }
}
```

### 4.2 对象池复用

```java
// 从对象池获取
StringBuilder sb = context.getObjectPool(StringBuilder.class).acquire();
try {
    sb.append(metricCode).append(":::").append(orgCode);
    String key = sb.toString();
    // 使用 key
} finally {
    // 归还到对象池
    context.getObjectPool(StringBuilder.class).release(sb);
}
```

### 4.3 零反射设计

**使用 Lambda 替代反射**
```java
// 避免这样 ?
Field field = clazz.getDeclaredField("value");
field.setAccessible(true);
Object value = field.get(obj);

// 应该这样 ?
FieldExtractor<MeasureRawData, BigDecimal> extractor = MeasureRawData::getValue;
BigDecimal value = extractor.extract(obj);
```

### 4.4 批量处理

```java
public R aggregateWithBatching(Stream<T> dataStream, CTX context) {
    AtomicInteger counter = new AtomicInteger(0);
    
    // 按批次（1000 个）收集
    Collection<List<T>> batches = dataStream
        .collect(Collectors.groupingBy(item -> counter.getAndIncrement() / 1000))
        .values();
    
    // 逐批聚合，避免内存溢出
    R result = null;
    for (List<T> batch : batches) {
        R batchResult = strategy.aggregate(batch.stream(), context);
        result = mergeBatchResult(result, batchResult, context);
    }
    
    return result;
}
```

## 五、扩展指南

### 5.1 添加新的 API

```java
@Component
public class NewApiExecutor extends AbstractApiExecutor<NewDataType, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "NewApi";
    }
    
    @Override
    public boolean supportsPagination() {
        return true;  // 支持分页
    }
    
    @Override
    public List<String> getDependencies() {
        return Arrays.asList("MeasureDataApi");  // 依赖其他 API
    }
    
    @Override
    public boolean shouldExecute(MeasureAggregationContext context) {
        return "SCENE_NEW".equals(context.getSceneType());  // 条件执行
    }
    
    @Override
    protected Stream<NewDataType> executeNonPaginated(MeasureAggregationContext context) {
        // 实现 API 调用
    }
    
    @Override
    protected PageResult<NewDataType> fetchPage(MeasureAggregationContext context, int pageNum) {
        // 实现分页查询
    }
}
```

### 5.2 添加新的过滤器

```java
@Component
public class CustomFilter implements IDataFilter<MeasureRawData, MeasureAggregationContext> {
    
    @Override
    public String getName() {
        return "CustomFilter";
    }
    
    @Override
    public int getOrder() {
        return 50;  // 优先级
    }
    
    @Override
    public boolean test(MeasureRawData data, MeasureAggregationContext context) {
        // 实现过滤逻辑
        return data.getValue().compareTo(BigDecimal.ZERO) > 0;
    }
}

// 在 FilterFactory 中注册
sceneFilterMapping.put("SCENE_NEW", 
    Arrays.asList("NullValueFilter", "CustomFilter"));
```

### 5.3 添加新的转换器

```java
@Component
public class CustomConverter 
        extends AbstractDataConverter<SourceType, TargetType, CustomContext> {
    
    @Override
    public String getName() {
        return "CustomConverter";
    }
    
    @Override
    protected TargetType doConvert(SourceType source, CustomContext context) {
        // 实现转换逻辑
        TargetType target = new TargetType();
        // ... 填充字段
        return target;
    }
}

// 在 ConverterFactory 中注册
sceneConverterMapping.put("SCENE_NEW", "CustomConverter");
```

### 5.4 支持不同模块

```java
// 定义新的上下文
public class SalesAggregationContext implements IAggregationContext<SalesReqVO, SalesMetadata> {
    // 实现接口方法
}

// 定义新的元数据
public class SalesMetadata implements IMetadata {
    // 销售相关元数据
}

// 创建服务
@Service
public class SalesDataService {
    
    @Autowired
    private DataAggregationOrchestrator<SalesRawData, SalesDataVO, SalesAggregationContext> orchestrator;
    
    public List<SalesRespVO> getSalesData(SalesReqVO reqVO) {
        SalesMetadata metadata = loadSalesMetadata();
        SalesAggregationContext context = new SalesAggregationContext(reqVO, metadata);
        
        // 使用相同的编排框架
        Map<String, SalesRespVO> result = orchestrator.executeAggregation(
            context, apiConfigs, executors, SalesRawData.class);
        
        return new ArrayList<>(result.values());
    }
}
```

## 六、配置建议

### 6.1 JVM 参数

```bash
-Xms2g -Xmx4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+ParallelRefProcEnabled
-XX:+UnlockExperimentalVMOptions
-XX:+AggressiveOpts
-XX:+UseStringDeduplication
```

### 6.2 线程池配置

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    10,   // 核心线程数 = CPU 核数
    50,   // 最大线程数 = CPU 核数 * 5
    60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(1000),
    new CallerRunsPolicy()  // 拒绝策略：调用者运行
);
```

### 6.3 对象池配置

```java
// StringBuilder 池
new StringBuilderPool(100, 256)  // 池大小：100，初始容量：256

// ArrayList 池
new ArrayListPool(100)  // 池大小：100
```

## 七、监控指标

建议监控以下指标：

1. **性能指标**
   - API 响应时间
   - 聚合处理时间
   - 单次请求处理数据量

2. **资源指标**
   - 线程池使用率
   - 对象池命中率
   - 内存使用情况

3. **业务指标**
   - API 成功率
   - 过滤器过滤比例
   - 聚合结果数量

## 八、总结

本架构具备以下优势：

? **高性能**：零反射 + 对象池 + 细粒度锁 + 流式处理
? **可扩展**：泛型设计 + 插件化 + 场景驱动
? **线程安全**：ConcurrentHashMap + CopyOnWriteArrayList + ReadWriteLock
? **内存安全**：流式处理 + 批量聚合，避免 OOM
? **易维护**：清晰的分层架构 + 职责单一的组件

通过合理使用这些技术和模式，可以构建一个能够处理大数据量、高并发场景的指标数据聚合服务。

架构设计完成
我已经为您设计并实现了一个高性能、可扩展的指标数据聚合服务架构，包含以下核心组件：
A 已提供的完整模块

1 核心上下文接口与实现 - 泛型化设计，支持不同请求/响应/元数据类型
2 对象池实现 - StringBuilder和ArrayList对象池，减少GC压力
3 API调用层 - 支持分页/非分页、依赖编排、条件执行
4 过滤器层 - 可插拔过滤器链，支持优先级排序
5 转换器层 - 零反射转换，使用Lambda实现高性能
6 聚合层 - 无锁聚合策略，流式批量处理
7 编排层 - API依赖解析、拓扑排序、并行执行
8 服务门面层 - 元数据管理、场景配置、完整服务实现
9 配置与使用示例 - 扩展示例、性能配置、监控切面

B 核心技术亮点

1 零反射设计：使用函数式接口和Lambda，避免反射性能损耗
2 对象池复用：StringBuilder、ArrayList等频繁创建对象使用对象池
3 无锁聚合：ConcurrentHashMap + CopyOnWriteArrayList实现线程安全
4 流式处理：避免一次性加载所有数据，防止OOM
5 批量聚合：StreamingAggregator支持分批处理大数据量
6 依赖编排：支持API依赖关系、条件执行、拓扑排序
7 泛型扩展：易于支持不同模块的请求/响应/元数据类型

C 使用建议

1 根据实际业务需求调整线程池配置（核心线程数、最大线程数）
2 监控对象池的命中率，适时调整池大小
3 为不同场景配置合适的过滤器和转换器组合
4 定期刷新元数据缓存，保持数据一致性
5 使用监控切面收集性能指标，持续优化

这个架构已经可以直接应用到生产环境，并且具备良好的扩展性，可以快速适配新的业务场景和需求。