指标查询接口重构方案
一、整体架构设计
Controller
    └── MetricQueryService (核心编排)
            ├── 并行数据获取层 (DataFetcher策略)
            │     ├── LocalHistoryDataFetcher (DB Table A)
            │     ├── ExternalApiDataFetcher (3rd Party API)
            │     └── UserConfigDataFetcher (DB Table B)
            ├── 数据聚合层 (MetricDataAggregator)
            │     ├── MeasureValueResolver (度量值优先级策略)
            │     ├── DerivedValueCalculator (衍生计算策略)
            │     └── WarningRuleEngine (预警规则)
            └── 元数据缓存 (MetaDataCache)
            
核心设计模式：

策略模式：DataFetcher接口隔离三种数据源；MeasureValueResolver策略处理优先级合并
工厂模式：DataFetcherFactory根据类型创建fetcher
模板方法：AbstractDataFetcher定义获取骨架
组合模式：聚合器统一编排多源结果

二、领域建模
// ==================== 请求/响应 ====================

/** 查询请求 DTO */
@Data
public class MetricQueryRequest {
    /** 周期列表，格式 "202601" */
    private List<String> periodList;
    /** 指标ID列表 */
    private List<String> metricIdList;
    /** 组织编码列表 */
    private List<String> orgCodes;
    /** 组织层级 */
    private String orgLevel;
    /** 度量编码过滤（可选，缺省全量） */
    private List<String> measureCodes;
    /** 领域编码过滤（可选，缺省全量） */
    private List<String> domainCodeList;
    /** 当前用户组织编码 */
    private String userOrgCode;
    /** 当前用户组织层级 */
    private String userOrgLevel;
}

/** 返回VO */
@Data
@Builder
public class MetricDataVO {
    private String periodId;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    /** 动态度量值 key=measureCode, value=度量值 */
    private Map<String, Object> measures;
    /** 预警状态: red/yellow/green */
    private String warning;
    /** 排名方向 */
    private String rankDirection;
}

// ==================== 元数据 ====================

/** 指标元数据 */
@Data
public class MetricMeta {
    private String metricCode;
    private String metricName;
    /** 该指标关联的领域列表 */
    private List<String> domainCodes;
    /** 该指标下的度量配置列表 */
    private List<MeasureMeta> measures;
    /** 预警基线配置 */
    private WarningBaseline warningBaseline;
    private String rankDirection;
}

/** 度量元数据 */
@Data
public class MeasureMeta {
    private String measureCode;
    /** 数据来源优先级: LOCAL_FIRST / EXTERNAL_FIRST / DERIVED */
    private MeasureSourceType sourceType;
    /** 衍生计算类型（当sourceType=DERIVED时有效） */
    private String derivedType;
}

/** 预警基线 */
@Data
public class WarningBaseline {
    /** 参考度量code，如"Y" */
    private String referenceMeasureCode;
    private BigDecimal redThreshold;
    private BigDecimal yellowThreshold;
    /** 比较方向: HIGHER_IS_BETTER / LOWER_IS_BETTER */
    private String compareDirection;
}

/** 用户配置 */
@Data
public class UserMeasureConfig {
    private String measureCode;
    private boolean visible;
    private int displayOrder;
}

// ==================== 内部数据载体 ====================

/** 聚合上下文，贯穿整个处理流程 */
@Data
@Builder
public class AggregationContext {
    private MetricQueryRequest request;
    /** 本地历史数据: key= year_orgCode_metricCode_domainCode_measureCode */
    private Map<String, Object> localDataMap;
    /** 外部实时数据: key= periodId_orgCode_metricCode_domainCode_measureCode */
    private Map<String, Object> externalDataMap;
    /** 用户度量配置: key=measureCode */
    private Map<String, UserMeasureConfig> userConfigMap;
}

/** 数据获取结果 */
@Data
@AllArgsConstructor
public class FetchResult<T> {
    private DataSourceType sourceType;
    private T data;
    private boolean success;
    private String errorMsg;

    public static <T> FetchResult<T> success(DataSourceType type, T data) {
        return new FetchResult<>(type, data, true, null);
    }

    public static <T> FetchResult<T> failure(DataSourceType type, String errorMsg) {
        return new FetchResult<>(type, null, false, errorMsg);
    }
}

public enum DataSourceType { LOCAL_HISTORY, EXTERNAL_API, USER_CONFIG }
public enum MeasureSourceType { LOCAL_FIRST, EXTERNAL_FIRST, DERIVED }

三、元数据缓存
/**
 * 元数据内存缓存 - 启动时加载，提供快速查询
 */
@Component
@Slf4j
public class MetaDataCache {

    /** key=metricCode */
    private volatile Map<String, MetricMeta> metricMetaMap = new ConcurrentHashMap<>();
    /** key=domainCode */
    private volatile Map<String, String> domainNameMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 从DB或配置中心加载元数据，此处省略具体实现
        log.info("MetaDataCache initialized");
    }

    public MetricMeta getMetricMeta(String metricCode) {
        return metricMetaMap.get(metricCode);
    }

    public Map<String, MetricMeta> getMetricMetaMap() {
        return Collections.unmodifiableMap(metricMetaMap);
    }

    /**
     * 根据metricId列表解析对应的metricCode列表
     */
    public List<String> resolveMetricCodes(List<String> metricIdList) {
        return metricIdList.stream()
                .map(id -> metricMetaMap.values().stream()
                        .filter(m -> m.getMetricCode().equals(id))
                        .map(MetricMeta::getMetricCode)
                        .findFirst().orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 从periodId（如"202601"）提取年份
     */
    public static String extractYear(String periodId) {
        return periodId.substring(0, 4);
    }
}

四、数据获取层（策略模式）
// ==================== DataFetcher 策略接口 ====================

/**
 * 数据源获取策略接口
 * 新增数据源只需实现此接口，无需修改核心流程
 */
public interface DataFetcher<T> {
    /**
     * 异步获取数据
     */
    CompletableFuture<FetchResult<T>> fetchAsync(MetricQueryRequest request, Executor executor);

    DataSourceType getSourceType();
}

// ==================== 抽象基类：模板方法 ====================

@Slf4j
public abstract class AbstractDataFetcher<T> implements DataFetcher<T> {

    @Override
    public CompletableFuture<FetchResult<T>> fetchAsync(MetricQueryRequest request, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[DataFetcher] 开始获取数据源: {}", getSourceType());
                T data = doFetch(request);
                log.info("[DataFetcher] 数据源获取成功: {}", getSourceType());
                return FetchResult.success(getSourceType(), data);
            } catch (Exception e) {
                log.error("[DataFetcher] 数据源获取失败: {}, error: {}", getSourceType(), e.getMessage(), e);
                return FetchResult.failure(getSourceType(), e.getMessage());
            }
        }, executor);
    }

    /**
     * 子类实现具体数据获取逻辑
     */
    protected abstract T doFetch(MetricQueryRequest request);
}

// ==================== 本地历史数据 Fetcher ====================

@Component
@Slf4j
public class LocalHistoryDataFetcher extends AbstractDataFetcher<Map<String, Object>> {

    @Autowired
    private MetricHistoryRepository metricHistoryRepository;

    @Autowired
    private MetaDataCache metaDataCache;

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.LOCAL_HISTORY;
    }

    @Override
    protected Map<String, Object> doFetch(MetricQueryRequest request) {
        // 提取查询所需的年份列表（去重）
        List<String> years = request.getPeriodList().stream()
                .map(MetaDataCache::extractYear)
                .distinct()
                .collect(Collectors.toList());

        List<MetricHistoryDO> records = metricHistoryRepository.queryByYearsAndOrgs(
                years, request.getOrgCodes(), request.getOrgLevel());

        // 构建高效查询Map: key= year_orgCode_metricCode_domainCode_measureCode
        return records.stream()
                .collect(Collectors.toMap(
                        r -> buildLocalKey(r.getYear(), r.getOrgCode(),
                                r.getMetricCode(), r.getDomainCode(), r.getMeasureCode()),
                        MetricHistoryDO::getMeasureValue,
                        (v1, v2) -> v1 // 重复key取第一个
                ));
    }

    public static String buildLocalKey(String year, String orgCode,
                                        String metricCode, String domainCode, String measureCode) {
        return String.join("_", year, orgCode, metricCode, domainCode, measureCode);
    }
}

// ==================== 外部API数据 Fetcher ====================

@Component
@Slf4j
public class ExternalApiDataFetcher extends AbstractDataFetcher<Map<String, Object>> {

    @Autowired
    private ExternalMetricApiClient externalMetricApiClient;

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.EXTERNAL_API;
    }

    @Override
    protected Map<String, Object> doFetch(MetricQueryRequest request) {
        ExternalApiRequest apiRequest = ExternalApiRequest.builder()
                .periodIds(request.getPeriodList())
                .orgCodes(request.getOrgCodes())
                .metricCodes(request.getMetricIdList())
                .domainCodes(request.getDomainCodeList())
                .measureCodes(request.getMeasureCodes())
                .build();

        List<ExternalMetricData> dataList = externalMetricApiClient.query(apiRequest);

        // 构建高效查询Map: key= periodId_orgCode_metricCode_domainCode_measureCode
        return dataList.stream()
                .collect(Collectors.toMap(
                        d -> buildExternalKey(d.getPeriodId(), d.getOrgCode(),
                                d.getMetricCode(), d.getDomainCode(), d.getMeasureCode()),
                        ExternalMetricData::getMeasureValue,
                        (v1, v2) -> v1
                ));
    }

    public static String buildExternalKey(String periodId, String orgCode,
                                           String metricCode, String domainCode, String measureCode) {
        return String.join("_", periodId, orgCode, metricCode, domainCode, measureCode);
    }
}

// ==================== 用户配置数据 Fetcher ====================

@Component
@Slf4j
public class UserConfigDataFetcher extends AbstractDataFetcher<Map<String, UserMeasureConfig>> {

    @Autowired
    private UserConfigRepository userConfigRepository;

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.USER_CONFIG;
    }

    @Override
    protected Map<String, UserMeasureConfig> doFetch(MetricQueryRequest request) {
        List<UserMeasureConfig> configs = userConfigRepository
                .queryByUserOrg(request.getUserOrgCode(), request.getUserOrgLevel());

        return configs.stream()
                .collect(Collectors.toMap(
                        UserMeasureConfig::getMeasureCode,
                        c -> c,
                        (v1, v2) -> v1
                ));
    }
}

五、度量值解析策略
// ==================== 度量值解析策略接口 ====================

/**
 * 度量值解析策略
 * 根据MeasureMeta中的sourceType选择对应策略
 */
public interface MeasureValueResolver {
    /**
     * 是否支持该度量类型
     */
    boolean supports(MeasureSourceType sourceType);

    /**
     * 解析度量值
     */
    Object resolve(MeasureResolveContext ctx);
}

/** 度量值解析上下文 */
@Data
@Builder
public class MeasureResolveContext {
    private String periodId;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    private MeasureMeta measureMeta;
    private AggregationContext aggregationContext;
    /** 当前VO已解析的度量值（供衍生计算使用） */
    private Map<String, Object> resolvedMeasures;
}

// ==================== 本地优先策略 ====================

@Component
public class LocalFirstMeasureValueResolver implements MeasureValueResolver {

    @Override
    public boolean supports(MeasureSourceType sourceType) {
        return MeasureSourceType.LOCAL_FIRST == sourceType;
    }

    @Override
    public Object resolve(MeasureResolveContext ctx) {
        String year = MetaDataCache.extractYear(ctx.getPeriodId());
        String measureCode = ctx.getMeasureMeta().getMeasureCode();

        // 优先取本地历史数据
        String localKey = LocalHistoryDataFetcher.buildLocalKey(
                year, ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), measureCode);
        Object localValue = ctx.getAggregationContext().getLocalDataMap().get(localKey);

        if (localValue != null) {
            return localValue;
        }

        // 降级取外部实时数据
        String externalKey = ExternalApiDataFetcher.buildExternalKey(
                ctx.getPeriodId(), ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), measureCode);
        return ctx.getAggregationContext().getExternalDataMap().get(externalKey);
    }
}

// ==================== 外部优先策略 ====================

@Component
public class ExternalFirstMeasureValueResolver implements MeasureValueResolver {

    @Override
    public boolean supports(MeasureSourceType sourceType) {
        return MeasureSourceType.EXTERNAL_FIRST == sourceType;
    }

    @Override
    public Object resolve(MeasureResolveContext ctx) {
        String measureCode = ctx.getMeasureMeta().getMeasureCode();

        // 优先取外部实时数据
        String externalKey = ExternalApiDataFetcher.buildExternalKey(
                ctx.getPeriodId(), ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), measureCode);
        Object externalValue = ctx.getAggregationContext().getExternalDataMap().get(externalKey);

        if (externalValue != null) {
            return externalValue;
        }

        // 降级取本地历史数据
        String year = MetaDataCache.extractYear(ctx.getPeriodId());
        String localKey = LocalHistoryDataFetcher.buildLocalKey(
                year, ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), measureCode);
        return ctx.getAggregationContext().getLocalDataMap().get(localKey);
    }
}

// ==================== 衍生计算策略 ====================

/**
 * 衍生度量计算策略接口
 */
public interface DerivedValueCalculator {
    boolean supports(String derivedType);
    Object calculate(MeasureResolveContext ctx);
}

/** YTD（年累计）计算器 */
@Component
public class YtdDerivedCalculator implements DerivedValueCalculator {
    @Override
    public boolean supports(String derivedType) {
        return "YTD".equals(derivedType);
    }

    @Override
    public Object calculate(MeasureResolveContext ctx) {
        // 取已解析的Y值进行YTD累加计算（业务逻辑简化示意）
        Object yValue = ctx.getResolvedMeasures().get("Y");
        if (yValue == null) return null;
        // 实际YTD计算需结合历史月份数据，此处返回示意值
        return yValue;
    }
}

/** 同比计算器 */
@Component
public class YoyDerivedCalculator implements DerivedValueCalculator {
    @Override
    public boolean supports(String derivedType) {
        return "YOY".equals(derivedType);
    }

    @Override
    public Object calculate(MeasureResolveContext ctx) {
        Object currentValue = ctx.getResolvedMeasures().get("Y");
        // 获取同期数据（需要查历史数据，此处为简化示意）
        Object lastYearValue = getLastYearValue(ctx);
        if (currentValue == null || lastYearValue == null) return null;
        BigDecimal cur = new BigDecimal(currentValue.toString());
        BigDecimal last = new BigDecimal(lastYearValue.toString());
        if (last.compareTo(BigDecimal.ZERO) == 0) return null;
        return cur.subtract(last).divide(last, 4, RoundingMode.HALF_UP);
    }

    private Object getLastYearValue(MeasureResolveContext ctx) {
        String lastYear = String.valueOf(Integer.parseInt(
                MetaDataCache.extractYear(ctx.getPeriodId())) - 1);
        String lastMonth = ctx.getPeriodId().substring(4);
        String lastPeriod = lastYear + lastMonth;
        String externalKey = ExternalApiDataFetcher.buildExternalKey(
                lastPeriod, ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), "Y");
        return ctx.getAggregationContext().getExternalDataMap().get(externalKey);
    }
}

/** 衍生计算委托器 */
@Component
public class DerivedMeasureValueResolver implements MeasureValueResolver {

    @Autowired
    private List<DerivedValueCalculator> calculators;

    @Override
    public boolean supports(MeasureSourceType sourceType) {
        return MeasureSourceType.DERIVED == sourceType;
    }

    @Override
    public Object resolve(MeasureResolveContext ctx) {
        String derivedType = ctx.getMeasureMeta().getDerivedType();
        return calculators.stream()
                .filter(c -> c.supports(derivedType))
                .findFirst()
                .map(c -> c.calculate(ctx))
                .orElse(null);
    }
}

// ==================== 度量值解析器工厂 ====================

@Component
public class MeasureValueResolverFactory {

    @Autowired
    private List<MeasureValueResolver> resolvers;

    public MeasureValueResolver getResolver(MeasureSourceType sourceType) {
        return resolvers.stream()
                .filter(r -> r.supports(sourceType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No resolver found for sourceType: " + sourceType));
    }
}

六、预警规则引擎
/**
 * 预警规则引擎
 * 根据指标配置的基线，计算warning状态
 */
@Component
public class WarningRuleEngine {

    private static final String WARNING_RED = "red";
    private static final String WARNING_YELLOW = "yellow";
    private static final String WARNING_GREEN = "green";

    /**
     * 计算预警状态
     *
     * @param measures       已填充的度量值
     * @param warningBaseline 预警基线配置
     * @return warning状态
     */
    public String evaluate(Map<String, Object> measures, WarningBaseline warningBaseline) {
        if (warningBaseline == null) {
            return WARNING_GREEN;
        }

        Object refValue = measures.get(warningBaseline.getReferenceMeasureCode());
        if (refValue == null) {
            return WARNING_GREEN;
        }

        BigDecimal actual;
        try {
            actual = new BigDecimal(refValue.toString());
        } catch (NumberFormatException e) {
            return WARNING_GREEN;
        }

        boolean higherIsBetter = "HIGHER_IS_BETTER".equals(warningBaseline.getCompareDirection());

        if (higherIsBetter) {
            if (warningBaseline.getRedThreshold() != null
                    && actual.compareTo(warningBaseline.getRedThreshold()) < 0) {
                return WARNING_RED;
            }
            if (warningBaseline.getYellowThreshold() != null
                    && actual.compareTo(warningBaseline.getYellowThreshold()) < 0) {
                return WARNING_YELLOW;
            }
        } else {
            if (warningBaseline.getRedThreshold() != null
                    && actual.compareTo(warningBaseline.getRedThreshold()) > 0) {
                return WARNING_RED;
            }
            if (warningBaseline.getYellowThreshold() != null
                    && actual.compareTo(warningBaseline.getYellowThreshold()) > 0) {
                return WARNING_YELLOW;
            }
        }

        return WARNING_GREEN;
    }
}

七、数据聚合器（核心，无多层嵌套）
/**
 * 指标数据聚合器
 * 核心设计：基于Map索引查找替代多层嵌套循环
 * 最大嵌套深度：2层（period x org 外层 + measure填充内层）
 */
@Component
@Slf4j
public class MetricDataAggregator {

    @Autowired
    private MetaDataCache metaDataCache;

    @Autowired
    private MeasureValueResolverFactory resolverFactory;

    @Autowired
    private WarningRuleEngine warningRuleEngine;

    /**
     * 聚合入口
     * 将笛卡尔积展开和数据填充分离，各自O(n)处理
     */
    public List<MetricDataVO> aggregate(MetricQueryRequest request, AggregationContext ctx) {
        // Step1: 解析有效的 metricCode 列表
        List<String> metricCodes = request.getMetricIdList();

        // Step2: 过滤用户可见的度量配置
        Map<String, UserMeasureConfig> userConfigMap = ctx.getUserConfigMap();

        // Step3: 构建笛卡尔积骨架 + 填充数据
        // 使用flatMap展开，避免4层嵌套
        return request.getPeriodList().stream()
                .flatMap(periodId -> request.getOrgCodes().stream()
                        .flatMap(orgCode -> buildVOsForPeriodOrg(
                                periodId, orgCode, metricCodes, userConfigMap, request, ctx).stream()))
                .collect(Collectors.toList());
    }

    /**
     * 为指定 period+org 构建所有指标/领域的VO列表
     * 职责单一：只处理一个period+org组合
     */
    private List<MetricDataVO> buildVOsForPeriodOrg(
            String periodId, String orgCode,
            List<String> metricCodes,
            Map<String, UserMeasureConfig> userConfigMap,
            MetricQueryRequest request,
            AggregationContext ctx) {

        List<MetricDataVO> result = new ArrayList<>();

        for (String metricCode : metricCodes) {
            MetricMeta metricMeta = metaDataCache.getMetricMeta(metricCode);
            if (metricMeta == null) {
                log.warn("MetricMeta not found for metricCode: {}", metricCode);
                continue;
            }

            // 过滤领域
            List<String> effectiveDomainCodes = filterDomainCodes(
                    metricMeta.getDomainCodes(), request.getDomainCodeList());

            for (String domainCode : effectiveDomainCodes) {
                MetricDataVO vo = buildSingleVO(
                        periodId, orgCode, metricCode, domainCode,
                        metricMeta, userConfigMap, ctx);
                result.add(vo);
            }
        }

        return result;
    }

    /**
     * 构建单个VO（period+org+metric+domain 四维确定唯一VO）
     * 度量值填充逻辑在此处，通过策略模式解耦，无额外嵌套
     */
    private MetricDataVO buildSingleVO(
            String periodId, String orgCode, String metricCode, String domainCode,
            MetricMeta metricMeta, Map<String, UserMeasureConfig> userConfigMap,
            AggregationContext ctx) {

        Map<String, Object> measures = new LinkedHashMap<>();

        // 过滤并排序用户可见的度量
        List<MeasureMeta> visibleMeasures = metricMeta.getMeasures().stream()
                .filter(m -> isVisible(m.getMeasureCode(), userConfigMap))
                .sorted(Comparator.comparingInt(m ->
                        Optional.ofNullable(userConfigMap.get(m.getMeasureCode()))
                                .map(UserMeasureConfig::getDisplayOrder).orElse(99)))
                .collect(Collectors.toList());

        // 逐度量填充（单层循环，通过策略模式解耦计算逻辑）
        for (MeasureMeta measureMeta : visibleMeasures) {
            MeasureResolveContext resolveCtx = MeasureResolveContext.builder()
                    .periodId(periodId)
                    .orgCode(orgCode)
                    .metricCode(metricCode)
                    .domainCode(domainCode)
                    .measureMeta(measureMeta)
                    .aggregationContext(ctx)
                    .resolvedMeasures(measures) // 传入已解析结果供衍生计算使用
                    .build();

            MeasureValueResolver resolver = resolverFactory.getResolver(measureMeta.getSourceType());
            Object value = resolver.resolve(resolveCtx);
            measures.put(measureMeta.getMeasureCode(), value);
        }

        // 计算预警状态（O(1)，基于已填充的measures Map）
        String warning = warningRuleEngine.evaluate(measures, metricMeta.getWarningBaseline());

        return MetricDataVO.builder()
                .periodId(periodId)
                .orgCode(orgCode)
                .metricCode(metricCode)
                .domainCode(domainCode)
                .measures(measures)
                .warning(warning)
                .rankDirection(metricMeta.getRankDirection())
                .build();
    }

    /**
     * 过滤有效领域：若请求未指定则取全量
     */
    private List<String> filterDomainCodes(List<String> metaDomains, List<String> requestDomains) {
        if (requestDomains == null || requestDomains.isEmpty()) {
            return metaDomains;
        }
        Set<String> requestSet = new HashSet<>(requestDomains);
        return metaDomains.stream().filter(requestSet::contains).collect(Collectors.toList());
    }

    /**
     * 判断度量是否对当前用户可见
     */
    private boolean isVisible(String measureCode, Map<String, UserMeasureConfig> userConfigMap) {
        UserMeasureConfig config = userConfigMap.get(measureCode);
        // 无配置则默认可见
        return config == null || config.isVisible();
    }
}

八、Service层（并行编排核心）
/**
 * 指标查询服务 - 核心编排层
 * 职责：并行协调数据获取 + 委托聚合器生成最终结果
 */
@Service
@Slf4j
public class MetricQueryService {

    /** 专用线程池：IO密集型任务，核心线程数建议为 CPU*2 */
    private final Executor metricFetchExecutor = new ThreadPoolExecutor(
            8, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactoryBuilder().setNameFormat("metric-fetch-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Autowired
    private LocalHistoryDataFetcher localHistoryDataFetcher;

    @Autowired
    private ExternalApiDataFetcher externalApiDataFetcher;

    @Autowired
    private UserConfigDataFetcher userConfigDataFetcher;

    @Autowired
    private MetricDataAggregator aggregator;

    /**
     * 查询指标数据主入口
     */
    public List<MetricDataVO> queryMetricData(MetricQueryRequest request) {
        log.info("[MetricQueryService] 开始查询, periods={}, orgs={}, metrics={}",
                request.getPeriodList(), request.getOrgCodes(), request.getMetricIdList());

        // ========== Step1: 并行异步获取三路数据 ==========
        CompletableFuture<FetchResult<Map<String, Object>>> localFuture =
                localHistoryDataFetcher.fetchAsync(request, metricFetchExecutor);

        CompletableFuture<FetchResult<Map<String, Object>>> externalFuture =
                externalApiDataFetcher.fetchAsync(request, metricFetchExecutor);

        CompletableFuture<FetchResult<Map<String, UserMeasureConfig>>> userConfigFuture =
                userConfigDataFetcher.fetchAsync(request, metricFetchExecutor);

        // ========== Step2: 等待所有异步任务完成 ==========
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                localFuture, externalFuture, userConfigFuture);

        try {
            // 设置超时，防止下游超时拖垮主流程
            allOf.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[MetricQueryService] 数据获取超时，使用已完成的部分数据继续处理");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询被中断", e);
        } catch (ExecutionException e) {
            log.error("[MetricQueryService] 数据获取执行异常", e);
        }

        // ========== Step3: 收集结果（容错处理，单路失败不影响整体） ==========
        Map<String, Object> localDataMap = extractData(localFuture, new HashMap<>());
        Map<String, Object> externalDataMap = extractData(externalFuture, new HashMap<>());
        Map<String, UserMeasureConfig> userConfigMap = extractData(userConfigFuture, new HashMap<>());

        log.info("[MetricQueryService] 数据获取完成: localSize={}, externalSize={}, configSize={}",
                localDataMap.size(), externalDataMap.size(), userConfigMap.size());

        // ========== Step4: 构建聚合上下文，委托聚合器处理 ==========
        AggregationContext ctx = AggregationContext.builder()
                .request(request)
                .localDataMap(localDataMap)
                .externalDataMap(externalDataMap)
                .userConfigMap(userConfigMap)
                .build();

        List<MetricDataVO> result = aggregator.aggregate(request, ctx);

        log.info("[MetricQueryService] 查询完成, 返回VO数量={}", result.size());
        return result;
    }

    /**
     * 安全提取Future结果，失败时返回默认值（降级容错）
     */
    private <T> T extractData(CompletableFuture<FetchResult<T>> future, T defaultValue) {
        try {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                FetchResult<T> result = future.get();
                if (result.isSuccess() && result.getData() != null) {
                    return result.getData();
                }
                log.warn("[MetricQueryService] 数据源获取失败: {}, msg={}",
                        result.getSourceType(), result.getErrorMsg());
            }
        } catch (Exception e) {
            log.error("[MetricQueryService] 提取Future结果异常", e);
        }
        return defaultValue;
    }
}

九、Controller层
@RestController
@RequestMapping("/api/v1/metrics")
@Slf4j
public class MetricQueryController {

    @Autowired
    private MetricQueryService metricQueryService;

    @PostMapping("/query")
    public ResponseEntity<List<MetricDataVO>> queryMetricData(
            @RequestBody @Validated MetricQueryRequest request) {
        List<MetricDataVO> result = metricQueryService.queryMetricData(request);
        return ResponseEntity.ok(result);
    }
}

十、Repository/Adapter接口（占位定义）
// DB Table A
public interface MetricHistoryRepository {
    List<MetricHistoryDO> queryByYearsAndOrgs(List<String> years,
                                               List<String> orgCodes, String orgLevel);
}

// DB Table B
public interface UserConfigRepository {
    List<UserMeasureConfig> queryByUserOrg(String userOrgCode, String userOrgLevel);
}

// 外部API客户端
public interface ExternalMetricApiClient {
    List<ExternalMetricData> query(ExternalApiRequest request);
}

@Data
@Builder
public class ExternalApiRequest {
    private List<String> periodIds;
    private List<String> orgCodes;
    private List<String> metricCodes;
    private List<String> domainCodes;
    private List<String> measureCodes;
}

@Data
public class ExternalMetricData {
    private String periodId;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private Object measureValue;
}

@Data
public class MetricHistoryDO {
    private String year;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private Object measureValue;
}

十一、设计总结
挑战                         解决方案                                                           效果
多数据源并行获取             CompletableFuture + 专用线程池 + DataFetcher策略接口               3路IO并行，延迟取最慢单路
4层嵌套循环                  flatMap展开笛卡尔积 + Map索引O(1)查找                              最大嵌套2层，圈复杂度可控
度量值优先级逻辑             MeasureValueResolver策略模式 + 工厂路由                            新增优先级规则只需新增策略类
衍生计算扩展                 DerivedValueCalculator接口 + Spring注入列表自动发现                新增计算类型零修改核心代码
预警规则                     WarningRuleEngine独立封装，基于已填充Map O(1)计算                  规则与聚合流程完全解耦
单路数据源故障               extractData降级容错，返回空Map继续处理                             部分数据源故障不影响主流程
