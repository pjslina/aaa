下面是基于你项目已有框架能力的完整生产级实现。
一、领域模型与元数据定义
// ==================== 入参 DTO ====================

@Data
public class MetricQueryRequest implements Serializable {
    private String periodType;
    private List<String> periodList;
    private List<Long> metricIdList;
    /** 非必填，缺省取全量 */
    private List<String> measureCodes;
    /** 非必填，缺省取全量 */
    private List<String> domainCodeList;
    private String needHEMetric;
    private String tabCode;
    private List<String> orgCodes;
    private String orgLevel;
    private String userOrgCode;
    private String userOrgLevel;
}

// ==================== 出参 VO ====================

@Data
@Builder
public class MetricDataVO {
    private String periodId;
    private Long metricId;
    private String metricCode;
    private String domainCode;
    private String domainName;
    private String orgCode;
    private String orgLevel;
    private Integer rankDirection;
    private String warning;
    /** 动态度量值：key=measureCode, value=度量值（"-"或实际值） */
    private Map<String, Object> measures;
}

// ==================== 元数据 ====================

@Data
public class MetricMeta {
    private Long metricId;
    private String metricCode;
    /** 正负向：1正向/-1负向 */
    private Integer rankDirection;
    private String periodType;
    private String metricType;
    private boolean isHEMetric;
    /** 预警判断所依赖的度量：YTD 或 PTD */
    private String warningMeasureCode;
    /** key=orgLevel, value=该层级下的domainCode列表 */
    private Map<String, List<String>> domainCodesByOrgLevel;
}

@Data
public class MeasureMeta {
    private String measureCode;
    private String nameZh;
    private String nameEn;
    private boolean isAmount;
    private String unitZh;
    private String unitEn;
    /** EDITABLE=用户编辑(按年), API=下游API(按月), DERIVED=衍生计算 */
    private MeasureCalcType calcType;
    private Integer decimalScale;
}

@Data
public class DomainMeta {
    private String domainCode;
    private String domainName;
}

public enum MeasureCalcType {
    EDITABLE, API, DERIVED
}

// ==================== 内部数据载体 ====================

/**
 * 用户编辑度量DO（表A）
 */
@Data
public class EditableMeasureDO {
    private String year;
    private String orgCode;
    private String orgLevel;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private Object measureValue;
}

/**
 * 下游API度量数据
 */
@Data
public class ApiMeasureData {
    private String periodId;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private Object measureValue;
}

/**
 * 用户度量配置DO（表B）
 */
@Data
public class UserMeasureConfigDO {
    private String measureCode;
    private Integer displayOrder;
    private boolean visible;
}

/**
 * 三路并发数据汇总容器
 */
@Data
@Builder
public class MetricRawData {
    /**
     * 用户编辑度量Map
     * key = year_orgCode_metricCode_domainCode_measureCode
     */
    private Map<String, Object> editableDataMap;
    /**
     * 下游API度量Map
     * key = periodId_orgCode_metricCode_domainCode_measureCode
     */
    private Map<String, Object> apiDataMap;
    /**
     * 用户配置的可见度量集合（有序）
     */
    private List<String> userVisibleMeasureCodes;
}

/**
 * 单条VO的构建上下文，封装构建所需的全部信息
 */
@Data
@Builder
public class VoBuildContext {
    private String periodId;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    private MetricMeta metricMeta;
    private MetricRawData rawData;
    private List<String> targetMeasureCodes;
}

二、元数据缓存服务
/**
 * 元数据缓存 - 启动时加载，运行期只读
 */
@Component
public class MetaDataCache {

    /** key = metricId */
    private Map<Long, MetricMeta> metricById = new ConcurrentHashMap<>();
    /** key = metricCode */
    private Map<String, MetricMeta> metricByCode = new ConcurrentHashMap<>();
    /** key = measureCode */
    private Map<String, MeasureMeta> measureByCode = new ConcurrentHashMap<>();
    /** key = domainCode */
    private Map<String, DomainMeta> domainByCode = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 从DB或配置中心加载，此处省略具体实现
    }

    public MetricMeta getMetricById(Long metricId) {
        return metricById.get(metricId);
    }

    public MetricMeta getMetricByCode(String metricCode) {
        return metricByCode.get(metricCode);
    }

    public MeasureMeta getMeasureByCode(String measureCode) {
        return measureByCode.get(measureCode);
    }

    public DomainMeta getDomainByCode(String domainCode) {
        return domainByCode.get(domainCode);
    }

    public Collection<String> getAllMeasureCodes() {
        return measureByCode.keySet();
    }

    public Collection<String> getAllDomainCodes() {
        return domainByCode.keySet();
    }
}
三、数据获取层（策略模式）
// ==================== 数据获取策略接口 ====================

/**
 * 数据源获取策略
 * 新增数据源只需新增实现类，无需改动并发编排逻辑
 */
public interface MetricDataFetchStrategy {
    /**
     * 获取数据并返回统一格式的键值Map
     * key规则由各实现类自行定义，需与聚合器对应
     */
    Map<String, Object> fetch(MetricQueryRequest request);

    DataSourceType getSourceType();
}

public enum DataSourceType {
    EDITABLE_DB, EXTERNAL_API, USER_CONFIG
}

// ==================== 用户编辑度量（表A）====================

@Component
@Slf4j
public class EditableMeasureFetchStrategy implements MetricDataFetchStrategy {

    @Autowired
    private EditableMeasureRepository editableMeasureRepository;

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.EDITABLE_DB;
    }

    @Override
    public Map<String, Object> fetch(MetricQueryRequest request) {
        // 提取年份（去重）
        List<String> years = request.getPeriodList().stream()
                .map(p -> p.substring(0, 4))
                .distinct()
                .collect(Collectors.toList());

        List<EditableMeasureDO> records = editableMeasureRepository
                .queryByYearsAndOrgs(years, request.getOrgCodes(), request.getOrgLevel());

        // key = year_orgCode_metricCode_domainCode_measureCode
        return records.stream().collect(Collectors.toMap(
                r -> buildEditableKey(r.getYear(), r.getOrgCode(),
                        r.getMetricCode(), r.getDomainCode(), r.getMeasureCode()),
                EditableMeasureDO::getMeasureValue,
                (v1, v2) -> v1
        ));
    }

    public static String buildEditableKey(String year, String orgCode,
            String metricCode, String domainCode, String measureCode) {
        return String.join("|", year, orgCode, metricCode, domainCode, measureCode);
    }
}

// ==================== 下游API度量 ====================

@Component
@Slf4j
public class ExternalApiMeasureFetchStrategy implements MetricDataFetchStrategy {

    @Autowired
    private ExternalMetricApiAdapter externalMetricApiAdapter;

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.EXTERNAL_API;
    }

    @Override
    public Map<String, Object> fetch(MetricQueryRequest request) {
        List<ApiMeasureData> dataList = externalMetricApiAdapter.queryMeasures(request);

        // key = periodId_orgCode_metricCode_domainCode_measureCode
        return dataList.stream().collect(Collectors.toMap(
                d -> buildApiKey(d.getPeriodId(), d.getOrgCode(),
                        d.getMetricCode(), d.getDomainCode(), d.getMeasureCode()),
                ApiMeasureData::getMeasureValue,
                (v1, v2) -> v1
        ));
    }

    public static String buildApiKey(String periodId, String orgCode,
            String metricCode, String domainCode, String measureCode) {
        return String.join("|", periodId, orgCode, metricCode, domainCode, measureCode);
    }
}

// ==================== 用户度量配置（表B）====================

@Component
@Slf4j
public class UserConfigFetchStrategy implements MetricDataFetchStrategy {

    @Autowired
    private UserMeasureConfigRepository userMeasureConfigRepository;

    /** 特殊key，标记用户可见度量列表 */
    public static final String USER_VISIBLE_MEASURES_KEY = "__USER_VISIBLE_MEASURES__";

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.USER_CONFIG;
    }

    @Override
    public Map<String, Object> fetch(MetricQueryRequest request) {
        List<UserMeasureConfigDO> configs = userMeasureConfigRepository
                .queryVisibleMeasures(request.getUserOrgCode(), request.getUserOrgLevel());

        // 按displayOrder排序后序列化为有序列表，存入特殊key
        String visibleCodes = configs.stream()
                .filter(UserMeasureConfigDO::isVisible)
                .sorted(Comparator.comparingInt(UserMeasureConfigDO::getDisplayOrder))
                .map(UserMeasureConfigDO::getMeasureCode)
                .collect(Collectors.joining(","));

        Map<String, Object> result = new HashMap<>(2);
        result.put(USER_VISIBLE_MEASURES_KEY, visibleCodes);
        return result;
    }
}

// ==================== 并发编排器 ====================

/**
 * 并发数据获取编排器
 * 基于 ItaskExecutorService 并行执行所有数据源，汇总为 MetricRawData
 */
@Component
@Slf4j
public class ConcurrentDataFetchOrchestrator {

    @Autowired
    private ItaskExecutorService taskExecutorService;

    @Autowired
    private List<MetricDataFetchStrategy> fetchStrategies;

    /**
     * 并发执行所有数据源策略，超时容错，单路失败不影响整体
     */
    public MetricRawData fetchAll(MetricQueryRequest request) {
        // 为每个策略提交CompletableFuture任务
        Map<DataSourceType, CompletableFuture<Map<String, Object>>> futureMap =
                new EnumMap<>(DataSourceType.class);

        for (MetricDataFetchStrategy strategy : fetchStrategies) {
            CompletableFuture<Map<String, Object>> future = taskExecutorService.submitTask(
                    () -> safeFetch(strategy, request),
                    "MetricFetch-" + strategy.getSourceType().name()
            );
            futureMap.put(strategy.getSourceType(), future);
        }

        // 等待全部完成（10秒超时）
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futureMap.values().toArray(new CompletableFuture[0]));
        try {
            allOf.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[ConcurrentDataFetchOrchestrator] 数据获取超时，使用已完成部分继续");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("数据获取被中断", e);
        } catch (ExecutionException e) {
            log.error("[ConcurrentDataFetchOrchestrator] 数据获取异常", e);
        }

        return buildRawData(futureMap);
    }

    private Map<String, Object> safeeFetch(MetricDataFetchStrategy strategy,
                                            MetricQueryRequest request) {
        try {
            return strategy.fetch(request);
        } catch (Exception e) {
            log.error("[DataFetch] 数据源获取失败: {}", strategy.getSourceType(), e);
            return Collections.emptyMap();
        }
    }

    private MetricRawData buildRawData(
            Map<DataSourceType, CompletableFuture<Map<String, Object>>> futureMap) {

        Map<String, Object> editableMap = extractSafely(
                futureMap.get(DataSourceType.EDITABLE_DB));
        Map<String, Object> apiMap = extractSafely(
                futureMap.get(DataSourceType.EXTERNAL_API));
        Map<String, Object> configMap = extractSafely(
                futureMap.get(DataSourceType.USER_CONFIG));

        // 解析用户可见度量列表
        String visibleCodesStr = (String) configMap.get(
                UserConfigFetchStrategy.USER_VISIBLE_MEASURES_KEY);
        List<String> userVisibleMeasureCodes = StringUtils.hasText(visibleCodesStr)
                ? Arrays.asList(visibleCodesStr.split(","))
                : Collections.emptyList();

        return MetricRawData.builder()
                .editableDataMap(editableMap)
                .apiDataMap(apiMap)
                .userVisibleMeasureCodes(userVisibleMeasureCodes)
                .build();
    }

    private Map<String, Object> extractSafely(CompletableFuture<Map<String, Object>> future) {
        if (future == null) return Collections.emptyMap();
        try {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                Map<String, Object> result = future.get();
                return result != null ? result : Collections.emptyMap();
            }
        } catch (Exception e) {
            log.warn("[DataFetch] 提取Future结果异常", e);
        }
        return Collections.emptyMap();
    }
}

四、度量值赋值策略（策略模式）
// ==================== 度量赋值策略接口 ====================

/**
 * 度量值填充策略
 * 对应三种赋值规则：直接赋值 / 优先级覆盖 / 计算衍生
 */
public interface MeasureValueFillStrategy {
    boolean supports(String measureCode);
    Object resolve(MeasureResolveParam param);
}

/**
 * 度量解析入参封装（超3个参数需封装）
 */
@Data
@Builder
public class MeasureResolveParam {
    private String periodId;
    private String orgCode;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private MetricRawData rawData;
    /** 当前VO已填充的度量（供衍生计算使用） */
    private Map<String, Object> resolvedMeasures;
}

// ==================== 直接赋值策略（API来源） ====================

@Component
public class ApiDirectFillStrategy implements MeasureValueFillStrategy {

    /** 直接取API数据的度量集合 */
    private static final Set<String> SUPPORTED = new HashSet<>(
            Arrays.asList("Budget_Y", "YTD", "PTD", "Target_Y"));

    @Override
    public boolean supports(String measureCode) {
        return SUPPORTED.contains(measureCode);
    }

    @Override
    public Object resolve(MeasureResolveParam param) {
        String key = ExternalApiMeasureFetchStrategy.buildApiKey(
                param.getPeriodId(), param.getOrgCode(),
                param.getMetricCode(), param.getDomainCode(), param.getMeasureCode());
        return param.getRawData().getApiDataMap().get(key);
    }
}

// ==================== 直接赋值策略（用户编辑来源） ====================

@Component
public class EditableDirectFillStrategy implements MeasureValueFillStrategy {

    private static final Set<String> SUPPORTED = new HashSet<>(
            Arrays.asList("Forecast_Y"));

    @Override
    public boolean supports(String measureCode) {
        return SUPPORTED.contains(measureCode);
    }

    @Override
    public Object resolve(MeasureResolveParam param) {
        String year = param.getPeriodId().substring(0, 4);
        String key = EditableMeasureFetchStrategy.buildEditableKey(
                year, param.getOrgCode(),
                param.getMetricCode(), param.getDomainCode(), param.getMeasureCode());
        return param.getRawData().getEditableDataMap().get(key);
    }
}

// ==================== 优先级覆盖策略（baseline） ====================

@Component
public class BaselinePriorityFillStrategy implements MeasureValueFillStrategy {

    private static final Set<String> SUPPORTED = new HashSet<>(
            Arrays.asList("baseline"));

    @Override
    public boolean supports(String measureCode) {
        return SUPPORTED.contains(measureCode);
    }

    @Override
    public Object resolve(MeasureResolveParam param) {
        String year = param.getPeriodId().substring(0, 4);
        // 优先取用户编辑的baseline值
        String editableKey = EditableMeasureFetchStrategy.buildEditableKey(
                year, param.getOrgCode(),
                param.getMetricCode(), param.getDomainCode(), "baseline");
        Object editableValue = param.getRawData().getEditableDataMap().get(editableKey);
        if (editableValue != null) {
            return editableValue;
        }
        // 降级取API的Budget_Y
        String apiKey = ExternalApiMeasureFetchStrategy.buildApiKey(
                param.getPeriodId(), param.getOrgCode(),
                param.getMetricCode(), param.getDomainCode(), "Budget_Y");
        return param.getRawData().getApiDataMap().get(apiKey);
    }
}

// ==================== 衍生计算策略（grapBaseline） ====================

@Component
public class GrapBaselineDerivedFillStrategy implements MeasureValueFillStrategy {

    private static final Set<String> SUPPORTED = new HashSet<>(
            Arrays.asList("grapBaseline"));

    @Override
    public boolean supports(String measureCode) {
        return SUPPORTED.contains(measureCode);
    }

    @Override
    public Object resolve(MeasureResolveParam param) {
        // grapBaseline = YTD - baseline，依赖已解析的度量值
        Object ytdVal = param.getResolvedMeasures().get("YTD");
        Object baselineVal = param.getResolvedMeasures().get("baseline");
        if (ytdVal == null || baselineVal == null) {
            return null;
        }
        try {
            BigDecimal ytd = new BigDecimal(ytdVal.toString());
            BigDecimal baseline = new BigDecimal(baselineVal.toString());
            return ytd.subtract(baseline);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

// ==================== 策略工厂 ====================

/**
 * 度量填充策略工厂
 * Spring自动注入所有MeasureValueFillStrategy实现，无需手动注册
 */
@Component
public class MeasureFillStrategyFactory {

    @Autowired
    private List<MeasureValueFillStrategy> strategies;

    /**
     * 根据measureCode找到匹配的策略
     * 找不到时返回空值策略（返回null，由调用方保持默认"-"）
     */
    public MeasureValueFillStrategy getStrategy(String measureCode) {
        return strategies.stream()
                .filter(s -> s.supports(measureCode))
                .findFirst()
                .orElse(param -> null); // 无匹配策略，返回null保持默认值
    }
}

五、预警规则引擎
/**
 * 预警规则引擎（策略模式：根据rankDirection选择比较方向）
 */
@Component
public class WarningRuleEngine {

    private static final String RED = "red";
    private static final String YELLOW = "yellow";
    private static final String GREEN = "green";

    /**
     * 计算预警状态
     */
    public String evaluate(WarningEvaluateParam param) {
        Object actualObj = param.getResolvedMeasures().get(param.getWarningMeasureCode());
        Object yellowObj = param.getYellowBaseline();
        Object redObj = param.getRedBaseline();

        if (actualObj == null || yellowObj == null || redObj == null) {
            return GREEN;
        }

        BigDecimal actual = parseSafely(actualObj);
        BigDecimal yellow = parseSafely(yellowObj);
        BigDecimal red = parseSafely(redObj);
        if (actual == null || yellow == null || red == null) {
            return GREEN;
        }

        // 正向指标：值越大越好；负向指标：值越小越好
        boolean positiveDirection = param.getRankDirection() != null
                && param.getRankDirection() > 0;
        return positiveDirection
                ? evaluatePositive(actual, yellow, red)
                : evaluateNegative(actual, yellow, red);
    }

    /** 正向：实际值低于基线则告警 */
    private String evaluatePositive(BigDecimal actual, BigDecimal yellow, BigDecimal red) {
        if (actual.compareTo(red) < 0) return RED;
        if (actual.compareTo(yellow) < 0) return YELLOW;
        return GREEN;
    }

    /** 负向：实际值高于基线则告警 */
    private String evaluateNegative(BigDecimal actual, BigDecimal yellow, BigDecimal red) {
        if (actual.compareTo(red) > 0) return RED;
        if (actual.compareTo(yellow) > 0) return YELLOW;
        return GREEN;
    }

    private BigDecimal parseSafely(Object val) {
        try {
            return val != null ? new BigDecimal(val.toString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

/**
 * 预警计算入参封装
 */
@Data
@Builder
public class WarningEvaluateParam {
    private String warningMeasureCode;
    private Integer rankDirection;
    private Object yellowBaseline;
    private Object redBaseline;
    private Map<String, Object> resolvedMeasures;
}

六、数据聚合器（核心，无多层嵌套）
/**
 * 指标数据聚合器
 * 核心思路：
 *  1. 用Stream flatMap替代4层for循环展开笛卡尔积
 *  2. 每个维度组合委托 SingleVoBuilder 独立处理
 *  3. 所有数据查找通过O(1) Map索引完成
 */
@Component
@Slf4j
public class MetricDataAggregator {

    @Autowired
    private MetaDataCache metaDataCache;

    @Autowired
    private MeasureFillStrategyFactory strategyFactory;

    @Autowired
    private WarningRuleEngine warningRuleEngine;

    /**
     * 聚合入口
     */
    public List<MetricDataVO> aggregate(MetricQueryRequest request, MetricRawData rawData) {
        // 解析本次查询的有效维度
        List<MetricMeta> metricMetas = resolveEffectiveMetrics(request);
        List<String> targetMeasureCodes = resolveTargetMeasures(request, rawData);

        // 用Stream flatMap展开四维笛卡尔积，替代4层嵌套循环
        return request.getPeriodList().stream()
                .flatMap(periodId -> request.getOrgCodes().stream()
                        .flatMap(orgCode -> metricMetas.stream()
                                .flatMap(metricMeta -> buildVOsForMetric(
                                        periodId, orgCode, metricMeta,
                                        request, rawData, targetMeasureCodes).stream())))
                .collect(Collectors.toList());
    }

    /**
     * 为指定 period+org+metric 构建所有domain维度的VO
     * （最内层只负责1个metric的所有domain，职责单一）
     */
    private List<MetricDataVO> buildVOsForMetric(
            String periodId, String orgCode, MetricMeta metricMeta,
            MetricQueryRequest request, MetricRawData rawData, List<String> targetMeasureCodes) {

        // 取该指标在当前orgLevel下的有效领域列表
        List<String> effectiveDomains = filterEffectiveDomains(metricMeta, request);

        return effectiveDomains.stream()
                .map(domainCode -> {
                    VoBuildContext ctx = VoBuildContext.builder()
                            .periodId(periodId)
                            .orgCode(orgCode)
                            .metricCode(metricMeta.getMetricCode())
                            .domainCode(domainCode)
                            .metricMeta(metricMeta)
                            .rawData(rawData)
                            .targetMeasureCodes(targetMeasureCodes)
                            .build();
                    return buildSingleVO(ctx);
                })
                .collect(Collectors.toList());
    }

    /**
     * 构建单条VO（四维唯一确定一条记录）
     */
    private MetricDataVO buildSingleVO(VoBuildContext ctx) {
        // Step1: 初始化所有度量为默认值"-"
        Map<String, Object> measures = initMeasures(ctx.getTargetMeasureCodes());

        // Step2: 填充度量值（通过策略工厂，无条件分支堆叠）
        fillMeasureValues(ctx, measures);

        // Step3: 计算预警状态
        String warning = evaluateWarning(ctx, measures);

        // Step4: 组装VO
        DomainMeta domainMeta = metaDataCache.getDomainByCode(ctx.getDomainCode());
        return MetricDataVO.builder()
                .periodId(ctx.getPeriodId())
                .metricId(ctx.getMetricMeta().getMetricId())
                .metricCode(ctx.getMetricCode())
                .domainCode(ctx.getDomainCode())
                .domainName(domainMeta != null ? domainMeta.getDomainName() : "")
                .orgCode(ctx.getOrgCode())
                .orgLevel(ctx.getMetricMeta().getMetricCode()) // orgLevel来自request
                .rankDirection(ctx.getMetricMeta().getRankDirection())
                .warning(warning)
                .measures(measures)
                .build();
    }

    /**
     * 初始化所有目标度量为默认值"-"
     */
    private Map<String, Object> initMeasures(List<String> targetMeasureCodes) {
        Map<String, Object> measures = new LinkedHashMap<>();
        targetMeasureCodes.forEach(code -> measures.put(code, "-"));
        return measures;
    }

    /**
     * 填充所有度量值：遍历目标度量，每个度量委托策略工厂解析
     * 单层循环，无嵌套
     */
    private void fillMeasureValues(VoBuildContext ctx, Map<String, Object> measures) {
        for (String measureCode : ctx.getTargetMeasureCodes()) {
            MeasureResolveParam resolveParam = MeasureResolveParam.builder()
                    .periodId(ctx.getPeriodId())
                    .orgCode(ctx.getOrgCode())
                    .metricCode(ctx.getMetricCode())
                    .domainCode(ctx.getDomainCode())
                    .measureCode(measureCode)
                    .rawData(ctx.getRawData())
                    .resolvedMeasures(measures) // 传入已解析Map，供衍生计算使用
                    .build();

            Object value = strategyFactory.getStrategy(measureCode).resolve(resolveParam);
            if (value != null) {
                measures.put(measureCode, value);
            }
        }
    }

    /**
     * 计算预警状态
     */
    private String evaluateWarning(VoBuildContext ctx, Map<String, Object> measures) {
        MetricMeta metricMeta = ctx.getMetricMeta();
        String warningMeasureCode = metricMeta.getWarningMeasureCode();

        // 取预警基线值（来自用户编辑数据）
        String year = ctx.getPeriodId().substring(0, 4);
        String yellowKey = EditableMeasureFetchStrategy.buildEditableKey(
                year, ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), "yellowBaseline");
        String redKey = EditableMeasureFetchStrategy.buildEditableKey(
                year, ctx.getOrgCode(), ctx.getMetricCode(), ctx.getDomainCode(), "redBaseline");

        WarningEvaluateParam evaluateParam = WarningEvaluateParam.builder()
                .warningMeasureCode(warningMeasureCode)
                .rankDirection(metricMeta.getRankDirection())
                .yellowBaseline(ctx.getRawData().getEditableDataMap().get(yellowKey))
                .redBaseline(ctx.getRawData().getEditableDataMap().get(redKey))
                .resolvedMeasures(measures)
                .build();

        return warningRuleEngine.evaluate(evaluateParam);
    }

    /**
     * 解析有效指标列表（根据入参metricIdList + 元数据过滤）
     */
    private List<MetricMeta> resolveEffectiveMetrics(MetricQueryRequest request) {
        return request.getMetricIdList().stream()
                .map(metaDataCache::getMetricById)
                .filter(Objects::nonNull)
                .filter(m -> !"Y".equals(request.getNeedHEMetric()) || m.isHEMetric())
                .collect(Collectors.toList());
    }

    /**
     * 解析用户可见的目标度量列表（2.3的结果，若为空则取入参或全量）
     */
    private List<String> resolveTargetMeasures(MetricQueryRequest request, MetricRawData rawData) {
        List<String> userConfigured = rawData.getUserVisibleMeasureCodes();
        if (!userConfigured.isEmpty()) {
            return userConfigured;
        }
        if (request.getMeasureCodes() != null && !request.getMeasureCodes().isEmpty()) {
            return request.getMeasureCodes();
        }
        return new ArrayList<>(metaDataCache.getAllMeasureCodes());
    }

    /**
     * 过滤有效领域：取指标在当前orgLevel配置的领域，与入参取交集
     */
    private List<String> filterEffectiveDomains(MetricMeta metricMeta, MetricQueryRequest request) {
        List<String> metaDomains = Optional.ofNullable(
                metricMeta.getDomainCodesByOrgLevel().get(request.getOrgLevel()))
                .orElse(Collections.emptyList());

        if (request.getDomainCodeList() == null || request.getDomainCodeList().isEmpty()) {
            return metaDomains;
        }
        Set<String> requestSet = new HashSet<>(request.getDomainCodeList());
        return metaDomains.stream().filter(requestSet::contains).collect(Collectors.toList());
    }
}

七、Service层（核心编排）
/**
 * 入参校验与查询条件预处理
 */
@Component
public class MetricQueryParamValidator {

    public void validate(MetricQueryRequest request) {
        Assert.notEmpty(request.getPeriodList(), "periodList不能为空");
        Assert.notEmpty(request.getMetricIdList(), "metricIdList不能为空");
        Assert.notEmpty(request.getOrgCodes(), "orgCodes不能为空");
        Assert.hasText(request.getOrgLevel(), "orgLevel不能为空");
        Assert.hasText(request.getUserOrgCode(), "userOrgCode不能为空");
    }
}

/**
 * 指标查询 Service
 */
@Service
@Slf4j
public class MetricQueryService {

    @Autowired
    private MetricQueryParamValidator paramValidator;

    @Autowired
    private ConcurrentDataFetchOrchestrator fetchOrchestrator;

    @Autowired
    private MetricDataAggregator aggregator;

    /**
     * 查询指标度量值主入口
     */
    public List<MetricDataVO> queryMetricData(MetricQueryRequest request) {
        // Step1: 参数校验
        paramValidator.validate(request);

        log.info("[MetricQueryService] 开始查询 periods={} orgs={} metrics={}",
                request.getPeriodList(), request.getOrgCodes(), request.getMetricIdList());

        // Step2: 并发获取三路数据
        MetricRawData rawData = fetchOrchestrator.fetchAll(request);

        // Step3: 聚合数据，生成VO列表
        List<MetricDataVO> result = aggregator.aggregate(request, rawData);

        log.info("[MetricQueryService] 查询完成，返回{}条数据", result.size());
        return result;
    }
}

八、Repository/Adapter接口定义
// ==================== 表A Repository ====================

public interface EditableMeasureRepository {
    /**
     * 按年份+组织查询用户编辑的度量数据
     */
    List<EditableMeasureDO> queryByYearsAndOrgs(
            List<String> years, List<String> orgCodes, String orgLevel);
}

// ==================== 表B Repository ====================

public interface UserMeasureConfigRepository {
    /**
     * 查询用户组织配置的可见度量列表
     */
    List<UserMeasureConfigDO> queryVisibleMeasures(String userOrgCode, String userOrgLevel);
}

// ==================== 外部API Adapter ====================

public interface ExternalMetricApiAdapter {
    /**
     * 调用下游API获取度量数据
     */
    List<ApiMeasureData> queryMeasures(MetricQueryRequest request);
}

// ==================== 外部API Adapter实现（示意） ====================

@Component
@Slf4j
public class ExternalMetricApiAdapterImpl implements ExternalMetricApiAdapter {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${external.metric.api.url}")
    private String apiUrl;

    @Override
    public List<ApiMeasureData> queryMeasures(MetricQueryRequest request) {
        // 构建下游API请求，实际根据第三方API协议实现
        try {
            // 此处为示意，实际按下游API协议封装
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("[ExternalMetricApiAdapter] 调用下游API失败", e);
            return Collections.emptyList();
        }
    }
}

九、Controller层
@RestController
@RequestMapping("/api/v1/metrics")
@Slf4j
public class MetricQueryController {

    @Autowired
    private MetricQueryService metricQueryService;

    @PostMapping("/queryMetricData")
    public ResponseEntity<List<MetricDataVO>> queryMetricData(
            @RequestBody @Validated MetricQueryRequest request) {
        List<MetricDataVO> result = metricQueryService.queryMetricData(request);
        return ResponseEntity.ok(result);
    }
}
```

---

## 十、设计总结
```
整体数据流：
Request
  → MetricQueryParamValidator（校验）
  → ConcurrentDataFetchOrchestrator（ItaskExecutorService并发三路）
      ├── EditableMeasureFetchStrategy   → editableDataMap
      ├── ExternalApiMeasureFetchStrategy → apiDataMap
      └── UserConfigFetchStrategy        → userVisibleMeasureCodes
  → MetricDataAggregator（Stream flatMap展开四维）
      └── buildSingleVO
            ├── initMeasures（初始化"-"）
            ├── fillMeasureValues → MeasureFillStrategyFactory路由
            │     ├── ApiDirectFillStrategy
            │     ├── EditableDirectFillStrategy
            │     ├── BaselinePriorityFillStrategy
            │     └── GrapBaselineDerivedFillStrategy
            └── WarningRuleEngine（正/负向预警判断）
            
扩展场景                操作                                                            是否修改核心代码
新增数据源（如表C）     新增 MetricDataFetchStrategy 实现类 + @Component                x 不需要
新增度量赋值规则        新增 MeasureValueFillStrategy 实现类 + @Component               x 不需要
新增衍生计算度量        新增 MeasureValueFillStrategy 实现类                            x 不需要
修改预警比较逻辑        只改 WarningRuleEngine                                          只改一处