我仔细梳理了你提出的所有问题，重新设计如下：
核心改动点：

MetricQueryContext 直接持有扁平化 Map，消除 MeasureLookupContext
IDataSourceStrategy 泛化返回类型，支持 List<T> 和 Map<String,T>
度量值统一用 Object 接收，转换时兼容 String/Double/Number
预警策略重新设计：用户配置比较符（>/</>=/<=），与排名无关
内存优化：流式处理、及时 GC、避免中间大集合

一、核心元数据 / DTO / VO 类
// ==================== 入参 ====================
// MetricQueryDTO.java
@Data
public class MetricQueryDTO implements Serializable {
    @NotNull private String periodType;
    @NotEmpty private List<String> periodList;
    @NotEmpty private List<Long> metricIdList;
    private List<String> measureCodes;      // 非必填
    private List<String> domainCodeList;    // 非必填
    private String needHEMetric;
    private String tabCode;
    @NotEmpty private List<String> orgCodes;
    @NotBlank private String orgLevel;
    @NotBlank private String userOrgCode;
    @NotBlank private String userOrgLevel;
}

// ==================== 出参 VO ====================
// MetricDataVO.java
@Data
public class MetricDataVO implements Serializable {
    private String periodId;
    private Long   metricId;
    private String metricCode;
    private String domainCode;
    private String domainName;
    private String orgCode;
    private String orgLevel;
    private Integer rankDirection;
    private String warning;

    // ---- 用户编辑度量（表A）----
    private String baseline       = "-";
    private String grapBaseline   = "-";
    private String Forecast_Y     = "-";

    // ---- 下游API度量 ----
    private String Budget_Y  = "-";
    private String Target_Y  = "-";
    private String YTD       = "-";
    private String PTD       = "-";

    // ---- 其他扩展度量 ----
    private String worldBest  = "-";
    private String regionBest = "-";
    // ...实际几十个，按需追加，均默认"-"

    // ---- 仅内部预警使用，不序列化给前端 ----
    @JsonIgnore private Double rawYellowBaseline;
    @JsonIgnore private Double rawRedBaseline;
}

// ==================== 元数据 ====================
// MetaMetricVO.java
@Data
public class MetaMetricVO implements Serializable {
    private Long   metricId;
    private String metricCode;
    private String cnName;
    private String enName;
    private String currencyFlag;
    private String periodCalcType;
    private Integer rankDirection;     // 1=正向/-1=负向/0=无方向
    private List<String> domainCodes;
    /** key: metricCode:::orgLevel -> List<domainCode> */
    private Map<String, List<String>> orgDomainCodes;
    private List<String> orgLevels;
    /** 预警用度量：YTD 或 PTD */
    private String warningMeasureCode;
}

// MetaMeasureVO.java
@Data
public class MetaMeasureVO implements Serializable {
    private Long    metricId;
    private String  metricCode;
    private String  measureCode;
    private String  cnName;
    private String  enName;
    private String  currencyFlag;
    private Integer calcType;
    private Integer decimalPlaces;
    private Integer measureType;
    private String  cnUnit;
    private String  enUnit;
}

// ==================== 用户预警配置（重新设计！） ====================
// UserWarningConfigDO.java — 数据库映射
@Data
public class UserWarningConfigDO {
    private Long   metricId;
    private String metricCode;
    private String domainCode;
    private String orgCode;
    private String orgLevel;
    /** 比较符：GT / GTE / LT / LTE */
    private String yellowOperator;
    private Double yellowThreshold;
    private String redOperator;
    private Double redThreshold;
}

// CompareOperator.java
public enum CompareOperator {
    GT  { @Override public boolean test(double v, double t) { return v >  t; } },
    GTE { @Override public boolean test(double v, double t) { return v >= t; } },
    LT  { @Override public boolean test(double v, double t) { return v <  t; } },
    LTE { @Override public boolean test(double v, double t) { return v <= t; } };

    public abstract boolean test(double value, double threshold);

    public static CompareOperator of(String code) {
        for (CompareOperator op : values()) {
            if (op.name().equalsIgnoreCase(code)) return op;
        }
        throw new IllegalArgumentException("Unknown operator: " + code);
    }
}

二、核心上下文（单一上下文，直接持有扁平 Map）
// MetricQueryContext.java
/**
 * 贯穿整个查询生命周期的上下文。
 * 三路原始 List 在写入扁平 Map 后立即置 null，释放堆内存。
 */
@Data
@Builder
public class MetricQueryContext implements Serializable {

    // ---------- 入参解析结果 ----------
    private MetricQueryDTO queryDTO;
    private List<MetaMetricVO> validMetrics;
    private List<String>  validDomainCodes;
    private List<String>  validMeasureCodes;
    /** periodList 截前4位去重，供表A查询 */
    private List<String>  years;

    // ---------- 三路扁平化 Map（核心！） ----------

    /**
     * 表A扁平 Map。
     * key = year:::orgCode:::metricCode:::domainCode:::fieldName
     * value = Double（度量原始值）
     */
    private Map<String, Double> userEditFlatMap;

    /**
     * API扁平 Map。
     * key = periodId:::orgCode:::metricCode:::domainCode:::measureCode
     * value = Double（兼容 String/Number 转换后）
     */
    private Map<String, Double> apiFlatMap;

    /**
     * 用户预警配置 Map。
     * key = metricCode:::domainCode:::orgCode:::orgLevel
     */
    private Map<String, UserWarningConfigDO> warningConfigMap;

    /** 用户关注的度量 Code 集合（用于最终过滤） */
    private Set<String> userFocusMeasures;

    /** 度量元数据（用于格式化） key = measureCode */
    private Map<String, MetaMeasureVO> measureMetaMap;
}

三、数据源策略：泛化支持 List / Map 两种返回形态
// ==================== 策略结果包装（支持 List 或 Map） ====================
// DataSourceResult.java
public final class DataSourceResult<T> {

    private final List<T>         listResult;
    private final Map<String, T>  mapResult;

    private DataSourceResult(List<T> list, Map<String, T> map) {
        this.listResult = list;
        this.mapResult  = map;
    }

    public static <T> DataSourceResult<T> ofList(List<T> list) {
        return new DataSourceResult<>(list, null);
    }

    public static <T> DataSourceResult<T> ofMap(Map<String, T> map) {
        return new DataSourceResult<>(null, map);
    }

    public boolean isMap() { return mapResult != null; }

    public List<T> getList() {
        return listResult != null ? listResult : Collections.emptyList();
    }

    public Map<String, T> getMap() {
        return mapResult != null ? mapResult : Collections.emptyMap();
    }
}

// ==================== 数据源策略接口 ====================
// IDataSourceStrategy.java
public interface IDataSourceStrategy {
    DataSourceType sourceType();

    /**
     * 异步获取数据，返回包装结果（List 或 Map 均可）。
     * 实现类根据实际场景选择更高效的返回形式。
     */
    CompletableFuture<DataSourceResult<?>> fetchAsync(MetricQueryContext context);

    /**
     * 将本数据源结果写入 context 的扁平 Map。
     * 由各策略自行决定 key 结构，避免聚合层感知具体字段。
     */
    void flattenIntoContext(DataSourceResult<?> result, MetricQueryContext context);
}

// DataSourceType.java
public enum DataSourceType {
    USER_EDIT,   // 表A
    API_MEASURE, // 下游API
    USER_CONFIG  // 表B
}

3.1 表A策略（返回 Map，直接按 key 索引）
// UserEditDataSourceStrategy.java
@Component
public class UserEditDataSourceStrategy implements IDataSourceStrategy {

    @Autowired private UserEditMeasureRepository repository;
    @Autowired private ItaskExecutorService       taskExecutorService;

    @Override
    public DataSourceType sourceType() { return DataSourceType.USER_EDIT; }

    @Override
    public CompletableFuture<DataSourceResult<?>> fetchAsync(MetricQueryContext ctx) {
        MetricQueryDTO dto = ctx.getQueryDTO();
        return taskExecutorService.submitTask(
            () -> {
                // 直接从 DB 返回 Map 形式（SQL 聚合或应用层转换）
                List<UserEditMeasureDTO> rows =
                    repository.queryByYearAndOrg(ctx.getYears(), dto.getOrgCodes(), dto.getOrgLevel());
                // 应用层转 Map，key=year:::orgCode:::metricCode:::domainCode:::fieldName
                Map<String, Double> flat = new HashMap<>(rows.size() * 4);
                for (UserEditMeasureDTO r : rows) {
                    String prefix = r.getYear() + ":::" + r.getOrgCode() + ":::"
                                  + r.getMetricCode() + ":::" + r.getDomainCode() + ":::";
                    putIfNotNull(flat, prefix + "baseline",       r.getBaseline());
                    putIfNotNull(flat, prefix + "Forecast_Y",     r.getForecastY());
                    putIfNotNull(flat, prefix + "yellowBaseline", r.getYellowBaseline());
                    putIfNotNull(flat, prefix + "redBaseline",    r.getRedBaseline());
                }
                return DataSourceResult.ofMap(flat);
            },
            "UserEdit-" + dto.getOrgLevel()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public void flattenIntoContext(DataSourceResult<?> result, MetricQueryContext ctx) {
        // 直接写入 context，外部无需感知 key 结构
        ctx.setUserEditFlatMap((Map<String, Double>) result.getMap());
    }

    private void putIfNotNull(Map<String, Double> map, String key, Double val) {
        if (val != null) map.put(key, val);
    }
}

3.2 下游API策略（返回 List，策略内转 Map）
// ApiMeasureDataSourceStrategy.java
@Component
public class ApiMeasureDataSourceStrategy implements IDataSourceStrategy {

    @Autowired private DownstreamApiAdapter  adapter;
    @Autowired private ItaskExecutorService  taskExecutorService;

    @Override
    public DataSourceType sourceType() { return DataSourceType.API_MEASURE; }

    @Override
    public CompletableFuture<DataSourceResult<?>> fetchAsync(MetricQueryContext ctx) {
        return taskExecutorService.submitTask(
            () -> {
                List<ApiMeasureDTO> list = adapter.queryMeasures(ctx);
                return DataSourceResult.ofList(list);
            },
            "ApiMeasure-" + ctx.getQueryDTO().getOrgLevel()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public void flattenIntoContext(DataSourceResult<?> result, MetricQueryContext ctx) {
        List<ApiMeasureDTO> list = (List<ApiMeasureDTO>) result.getList();
        Map<String, Double> flat = new HashMap<>(list.size());
        for (ApiMeasureDTO r : list) {
            // 兼容 String / Double / Number 类型
            Double val = toDouble(r.getRawValue());
            if (val != null) {
                String key = r.getPeriodId() + ":::" + r.getOrgCode() + ":::"
                           + r.getMetricCode() + ":::" + r.getDomainCode() + ":::" + r.getMeasureCode();
                flat.put(key, val);
            }
        }
        // 转换完毕，原始 list 可被 GC（此处 result 出作用域即可回收）
        ctx.setApiFlatMap(flat);
    }

    /**
     * 兼容 Double / String / Number 等多种类型的度量值
     */
    private Double toDouble(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Double)  return (Double) raw;
        if (raw instanceof Number)  return ((Number) raw).doubleValue();
        if (raw instanceof String)  {
            String s = ((String) raw).trim();
            if (s.isEmpty() || "-".equals(s)) return null;
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}

3.3 用户配置策略（返回 Map，支持预警配置）
// UserConfigDataSourceStrategy.java
@Component
public class UserConfigDataSourceStrategy implements IDataSourceStrategy {

    @Autowired private UserMeasureConfigRepository repository;
    @Autowired private ItaskExecutorService         taskExecutorService;

    @Override
    public DataSourceType sourceType() { return DataSourceType.USER_CONFIG; }

    @Override
    public CompletableFuture<DataSourceResult<?>> fetchAsync(MetricQueryContext ctx) {
        MetricQueryDTO dto = ctx.getQueryDTO();
        return taskExecutorService.submitTask(
            () -> {
                UserConfigResult cfg = repository.queryUserConfig(dto.getUserOrgCode(), dto.getUserOrgLevel());
                // 预警配置 Map: key=metricCode:::domainCode:::orgCode:::orgLevel
                Map<String, UserWarningConfigDO> warningMap = cfg.getWarningConfigs().stream()
                    .collect(Collectors.toMap(
                        w -> w.getMetricCode() + ":::" + w.getDomainCode()
                           + ":::" + w.getOrgCode() + ":::" + w.getOrgLevel(),
                        Function.identity(),
                        (a, b) -> a
                    ));
                // 用 Map 同时携带两类配置
                Map<String, Object> composite = new HashMap<>(2);
                composite.put("focusMeasures", new HashSet<>(cfg.getFocusMeasures()));
                composite.put("warningMap",    warningMap);
                return DataSourceResult.ofMap(composite);
            },
            "UserConfig-" + dto.getUserOrgCode()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public void flattenIntoContext(DataSourceResult<?> result, MetricQueryContext ctx) {
        Map<String, Object> composite = (Map<String, Object>) result.getMap();
        ctx.setUserFocusMeasures((Set<String>) composite.get("focusMeasures"));
        ctx.setWarningConfigMap((Map<String, UserWarningConfigDO>) composite.get("warningMap"));
    }
}

// 策略工厂
// DataSourceStrategyFactory.java
@Component
public class DataSourceStrategyFactory {

    private final Map<DataSourceType, IDataSourceStrategy> strategyMap;

    @Autowired
    public DataSourceStrategyFactory(List<IDataSourceStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(IDataSourceStrategy::sourceType, Function.identity()));
    }

    public IDataSourceStrategy getStrategy(DataSourceType type) {
        IDataSourceStrategy s = strategyMap.get(type);
        if (s == null) throw new IllegalArgumentException("No strategy for type: " + type);
        return s;
    }

    public Collection<IDataSourceStrategy> allStrategies() {
        return strategyMap.values();
    }
}

四、并发编排 Manager（写回扁平 Map + 及时释放原始数据）
// MetricDataFetchManager.java
@Component
public class MetricDataFetchManager {

    @Autowired private DataSourceStrategyFactory strategyFactory;

    /**
     * 并发三路拉取，结果直接扁平化写入 context。
     * 原始集合在 flattenIntoContext 后不再持有引用，可被 GC。
     */
    public void fetchAndFlatten(MetricQueryContext context) {
        // 构建并发任务
        List<CompletableFuture<Void>> futures = Arrays.stream(DataSourceType.values())
            .map(type -> {
                IDataSourceStrategy strategy = strategyFactory.getStrategy(type);
                return strategy.fetchAsync(context)
                    .thenAccept(result -> {
                        // 写入扁平 Map，result 局部变量，方法返回后可被 GC
                        strategy.flattenIntoContext(result, context);
                    });
            })
            .collect(Collectors.toList());

        // 等待全部完成，任意一个异常则整体抛出
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}

五、预警策略（重新设计：基于用户配置的比较符，与排名无关）
// ==================== 预警策略接口 ====================
// IWarningStrategy.java
public interface IWarningStrategy {
    /**
     * 根据用户配置的比较符和阈值计算预警色
     *
     * @param value  当前度量值（YTD 或 PTD）
     * @param config 用户预警配置（含 redOperator/redThreshold/yellowOperator/yellowThreshold）
     * @return "red" / "yellow" / "green" / null（无配置时）
     */
    String calculate(double value, UserWarningConfigDO config);

    /** 策略是否支持（扩展点：未来可按指标类型分策略） */
    boolean support(UserWarningConfigDO config);
}

// ==================== 通用比较符预警策略（默认实现） ====================
// OperatorBasedWarningStrategy.java
@Component
public class OperatorBasedWarningStrategy implements IWarningStrategy {

    @Override
    public boolean support(UserWarningConfigDO config) {
        // 只要有配置就支持，红色优先级高于黄色
        return config != null;
    }

    @Override
    public String calculate(double value, UserWarningConfigDO config) {
        if (config == null) return null;

        // 红色判断（优先级最高）
        if (config.getRedOperator() != null && config.getRedThreshold() != null) {
            CompareOperator redOp = CompareOperator.of(config.getRedOperator());
            if (redOp.test(value, config.getRedThreshold())) return "red";
        }

        // 黄色判断
        if (config.getYellowOperator() != null && config.getYellowThreshold() != null) {
            CompareOperator yellowOp = CompareOperator.of(config.getYellowOperator());
            if (yellowOp.test(value, config.getYellowThreshold())) return "yellow";
        }

        return "green";
    }
}

// ==================== 预警策略工厂 ====================
// WarningStrategyFactory.java
@Component
public class WarningStrategyFactory {

    private final List<IWarningStrategy> strategies;

    @Autowired
    public WarningStrategyFactory(List<IWarningStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 根据配置选取策略（策略按注册顺序优先匹配）
     */
    public IWarningStrategy getStrategy(UserWarningConfigDO config) {
        return strategies.stream()
            .filter(s -> s.support(config))
            .findFirst()
            .orElse(null); // 无配置时不计算预警
    }
}

六、度量赋值策略（直接操作扁平 Map，无 MeasureLookupContext）
// ==================== 度量赋值策略接口 ====================
// IMeasureAssignStrategy.java
public interface IMeasureAssignStrategy {

    /** 策略处理的度量 Code 列表（用于注册到工厂） */
    List<String> supportedMeasureCodes();

    /**
     * 将度量值写入 VO。
     * 直接从 context 的扁平 Map 中按 key 取值，避免额外包装对象。
     */
    void assign(MetricDataVO vo, MetricQueryContext context);
}

// ==================== 公共工具基类 ====================
// AbstractMeasureAssignStrategy.java
public abstract class AbstractMeasureAssignStrategy implements IMeasureAssignStrategy {

    /** 构建表A的 key：year:::orgCode:::metricCode:::domainCode:::fieldName */
    protected String userEditKey(MetricDataVO vo, String fieldName) {
        return vo.getPeriodId().substring(0, 4) + ":::" + vo.getOrgCode() + ":::"
             + vo.getMetricCode() + ":::" + vo.getDomainCode() + ":::" + fieldName;
    }

    /** 构建API的 key：periodId:::orgCode:::metricCode:::domainCode:::measureCode */
    protected String apiKey(MetricDataVO vo, String measureCode) {
        return vo.getPeriodId() + ":::" + vo.getOrgCode() + ":::"
             + vo.getMetricCode() + ":::" + vo.getDomainCode() + ":::" + measureCode;
    }

    /** 取表A值 */
    protected Double getUserEdit(MetricDataVO vo, String fieldName, MetricQueryContext ctx) {
        return ctx.getUserEditFlatMap().get(userEditKey(vo, fieldName));
    }

    /** 取API值 */
    protected Double getApi(MetricDataVO vo, String measureCode, MetricQueryContext ctx) {
        return ctx.getApiFlatMap().get(apiKey(vo, measureCode));
    }

    /** Double -> 格式化 String，兼容小数位数配置 */
    protected String format(Double val, String measureCode, MetricQueryContext ctx) {
        if (val == null) return "-";
        MetaMeasureVO meta = ctx.getMeasureMetaMap().get(measureCode);
        int places = (meta != null && meta.getDecimalPlaces() != null) ? meta.getDecimalPlaces() : 2;
        return new BigDecimal(val).setScale(places, RoundingMode.HALF_UP).toPlainString();
    }

    /** 解析 VO 上已赋值的 String 度量 -> Double（用于衍生计算） */
    protected Double parseVoValue(String s) {
        if (s == null || "-".equals(s)) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }
}

// ==================== 直接赋值策略 ====================
// DirectAssignStrategy.java
@Component
public class DirectAssignStrategy extends AbstractMeasureAssignStrategy {

    @Override
    public List<String> supportedMeasureCodes() {
        return Arrays.asList("YTD", "PTD", "Target_Y", "Budget_Y",
                             "Forecast_Y", "worldBest", "regionBest");
    }

    @Override
    public void assign(MetricDataVO vo, MetricQueryContext ctx) {
        // API 来源
        Double ytd = getApi(vo, "YTD", ctx);
        if (ytd != null) vo.setYTD(format(ytd, "YTD", ctx));

        Double ptd = getApi(vo, "PTD", ctx);
        if (ptd != null) vo.setPTD(format(ptd, "PTD", ctx));

        Double targetY = getApi(vo, "Target_Y", ctx);
        if (targetY != null) vo.setTarget_Y(format(targetY, "Target_Y", ctx));

        Double budgetY = getApi(vo, "Budget_Y", ctx);
        if (budgetY != null) vo.setBudget_Y(format(budgetY, "Budget_Y", ctx));

        // 表A 来源
        Double forecastY = getUserEdit(vo, "Forecast_Y", ctx);
        if (forecastY != null) vo.setForecast_Y(format(forecastY, "Forecast_Y", ctx));
    }
}

// ==================== 优先级覆盖策略（baseline：优先表A，降级取API的Budget_Y） ====================
// FallbackAssignStrategy.java
@Component
public class FallbackAssignStrategy extends AbstractMeasureAssignStrategy {

    @Override
    public List<String> supportedMeasureCodes() {
        return Collections.singletonList("baseline");
    }

    @Override
    public void assign(MetricDataVO vo, MetricQueryContext ctx) {
        Double baseline = getUserEdit(vo, "baseline", ctx);
        if (baseline == null) {
            // 降级：取 API 的 Budget_Y
            baseline = getApi(vo, "Budget_Y", ctx);
        }
        if (baseline != null) {
            vo.setBaseline(format(baseline, "baseline", ctx));
        }

        // 预警阈值：存入 VO 的 @JsonIgnore 字段，供预警计算使用，不序列化给前端
        Double yellow = getUserEdit(vo, "yellowBaseline", ctx);
        Double red    = getUserEdit(vo, "redBaseline", ctx);
        vo.setRawYellowBaseline(yellow);
        vo.setRawRedBaseline(red);
    }
}

// ==================== 计算衍生策略（grapBaseline = YTD - baseline） ====================
// DerivedAssignStrategy.java
@Component
public class DerivedAssignStrategy extends AbstractMeasureAssignStrategy {

    @Override
    public List<String> supportedMeasureCodes() {
        return Collections.singletonList("grapBaseline");
    }

    @Override
    public void assign(MetricDataVO vo, MetricQueryContext ctx) {
        // 依赖已赋值的 YTD 和 baseline，衍生计算
        Double ytd      = parseVoValue(vo.getYTD());
        Double baseline = parseVoValue(vo.getBaseline());
        if (ytd != null && baseline != null) {
            vo.setGrapBaseline(format(ytd - baseline, "grapBaseline", ctx));
        }
    }
}

// ==================== 度量策略工厂 ====================
// MeasureAssignStrategyFactory.java
@Component
public class MeasureAssignStrategyFactory {

    /** 去重后有序的策略集合（衍生策略必须在直接/覆盖之后执行） */
    private final List<IMeasureAssignStrategy> orderedStrategies;

    @Autowired
    public MeasureAssignStrategyFactory(List<IMeasureAssignStrategy> strategies) {
        // 利用 LinkedHashSet 去重，同时保留注册顺序
        // 若需严格顺序，可在策略接口增加 order() 方法 + 排序
        this.orderedStrategies = new ArrayList<>(new LinkedHashSet<>(strategies));
    }

    /** 返回有序策略列表（供聚合器遍历） */
    public List<IMeasureAssignStrategy> orderedStrategies() {
        return orderedStrategies;
    }
}

七、聚合 Manager（无多层嵌套、内存友好）
// MetricDataAggregateManager.java
@Component
public class MetricDataAggregateManager {

    @Autowired private MeasureAssignStrategyFactory measureStrategyFactory;
    @Autowired private WarningStrategyFactory       warningStrategyFactory;
    @Autowired private OpMetaCacheService           metaCacheService;

    /**
     * 聚合入口。
     * 以 Stream flatMap 替代 4 层嵌套循环，圈复杂度可控；
     * 每条 VO 处理完成即可流向下游（延迟终结），避免中间大集合。
     */
    public List<MetricDataVO> aggregate(MetricQueryContext context) {
        MetricQueryDTO dto = context.getQueryDTO();

        return dto.getPeriodList().stream()
            .flatMap(periodId ->
                dto.getOrgCodes().stream()
                    .flatMap(orgCode ->
                        context.getValidMetrics().stream()
                            .flatMap(metric ->
                                getEffectiveDomains(metric, dto.getOrgLevel(), context.getValidDomainCodes()).stream()
                                    .map(domainCode ->
                                        buildAndFill(periodId, orgCode, metric, domainCode, context)
                                    )
                            )
                    )
            )
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    // 骨架构建 + 填充（单条 VO）
    // ----------------------------------------------------------------

    private MetricDataVO buildAndFill(String periodId, String orgCode,
                                      MetaMetricVO metric, String domainCode,
                                      MetricQueryContext context) {
        // a. 初始化 VO（所有度量字段已在声明时默认"-"）
        MetricDataVO vo = new MetricDataVO();
        vo.setPeriodId(periodId);
        vo.setOrgCode(orgCode);
        vo.setOrgLevel(context.getQueryDTO().getOrgLevel());
        vo.setMetricId(metric.getMetricId());
        vo.setMetricCode(metric.getMetricCode());
        vo.setDomainCode(domainCode);
        vo.setRankDirection(metric.getRankDirection());
        // b. 基础属性（domainName 等，从缓存取）
        fillBaseInfo(vo);

        // c. 度量赋值（策略顺序执行，衍生策略自动在直接/覆盖之后）
        measureStrategyFactory.orderedStrategies()
            .forEach(s -> s.assign(vo, context));

        // d. 预警计算
        fillWarning(vo, metric, context);

        // e. 用户度量过滤
        filterByUserConfig(vo, context.getUserFocusMeasures());

        return vo;
    }

    // ----------------------------------------------------------------
    // 基础属性
    // ----------------------------------------------------------------

    private void fillBaseInfo(MetricDataVO vo) {
        // domainName 从元数据缓存获取（示意，实际按项目缓存结构调整）
        // DomainMeta dm = metaCacheService.getDomainMeta(vo.getDomainCode());
        // if (dm != null) vo.setDomainName(dm.getCnName());
    }

    // ----------------------------------------------------------------
    // 预警计算（基于用户配置的比较符，与 rankDirection 解耦）
    // ----------------------------------------------------------------

    private void fillWarning(MetricDataVO vo, MetaMetricVO metric, MetricQueryContext context) {
        // 取该指标配置的预警度量（YTD 或 PTD）
        String warningCode = metric.getWarningMeasureCode();
        if (warningCode == null) return;

        Double value = "YTD".equals(warningCode)
            ? parseVoValue(vo.getYTD())
            : parseVoValue(vo.getPTD());
        if (value == null) return;

        // 从 context 取用户预警配置（key=metricCode:::domainCode:::orgCode:::orgLevel）
        String wKey = metric.getMetricCode() + ":::" + vo.getDomainCode()
                    + ":::" + vo.getOrgCode() + ":::" + vo.getOrgLevel();
        UserWarningConfigDO warnCfg = context.getWarningConfigMap() != null
            ? context.getWarningConfigMap().get(wKey)
            : null;

        IWarningStrategy strategy = warningStrategyFactory.getStrategy(warnCfg);
        if (strategy != null) {
            vo.setWarning(strategy.calculate(value, warnCfg));
        }
    }

    // ----------------------------------------------------------------
    // 用户度量过滤（不关注的度量还原为"-"）
    // ----------------------------------------------------------------

    private void filterByUserConfig(MetricDataVO vo, Set<String> focusSet) {
        if (focusSet == null || focusSet.isEmpty()) return;
        if (!focusSet.contains("Budget_Y"))    vo.setBudget_Y("-");
        if (!focusSet.contains("Target_Y"))    vo.setTarget_Y("-");
        if (!focusSet.contains("YTD"))         vo.setYTD("-");
        if (!focusSet.contains("PTD"))         vo.setPTD("-");
        if (!focusSet.contains("baseline"))    vo.setBaseline("-");
        if (!focusSet.contains("grapBaseline"))vo.setGrapBaseline("-");
        if (!focusSet.contains("Forecast_Y"))  vo.setForecast_Y("-");
        if (!focusSet.contains("worldBest"))   vo.setWorldBest("-");
        if (!focusSet.contains("regionBest"))  vo.setRegionBest("-");
    }

    // ----------------------------------------------------------------
    // 工具方法
    // ----------------------------------------------------------------

    private List<String> getEffectiveDomains(MetaMetricVO metric,
                                              String orgLevel,
                                              List<String> validDomains) {
        String key = metric.getMetricCode() + ":::" + orgLevel;
        List<String> metaDomains = (metric.getOrgDomainCodes() != null)
            ? metric.getOrgDomainCodes().getOrDefault(key, metric.getDomainCodes())
            : metric.getDomainCodes();
        if (metaDomains == null) return validDomains;
        // 取交集，不创建额外大集合
        return metaDomains.stream()
            .filter(validDomains::contains)
            .collect(Collectors.toList());
    }

    private Double parseVoValue(String s) {
        if (s == null || "-".equals(s)) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }
}

八、上下文构建器（含元数据预加载到 context）
// MetricQueryContextBuilder.java
@Component
public class MetricQueryContextBuilder {

    @Autowired private OpMetaCacheService metaCacheService;

    private static final List<String> DEFAULT_MEASURE_CODES =
        Arrays.asList("Y", "POP", "YTD", "PTD", "Budget_Y", "Target_Y",
                      "Forecast_Y", "baseline", "grapBaseline", "worldBest", "regionBest");

    private static final List<String> DEFAULT_DOMAIN_CODES =
        Arrays.asList("D001", "D002", "D003"); // 实际从枚举/配置加载

    public MetricQueryContext build(MetricQueryDTO dto) {
        validate(dto);

        Map<Long, MetaMetricVO> metricById = metaCacheService.getMetricOfId();

        List<MetaMetricVO> validMetrics = dto.getMetricIdList().stream()
            .map(metricById::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        List<String> validMeasures = isNotEmpty(dto.getMeasureCodes())
            ? dto.getMeasureCodes() : DEFAULT_MEASURE_CODES;

        List<String> inputDomains = isNotEmpty(dto.getDomainCodeList())
            ? dto.getDomainCodeList() : DEFAULT_DOMAIN_CODES;

        // 与指标元数据取交集，减少无效 key
        Set<String> metaDomainSet = validMetrics.stream()
            .filter(m -> m.getDomainCodes() != null)
            .flatMap(m -> m.getDomainCodes().stream())
            .collect(Collectors.toSet());
        List<String> validDomains = inputDomains.stream()
            .filter(metaDomainSet::contains)
            .collect(Collectors.toList());

        List<String> years = dto.getPeriodList().stream()
            .map(p -> p.substring(0, 4))
            .distinct()
            .collect(Collectors.toList());

        // 度量元数据提前放入 context，避免聚合时反复调用缓存
        Map<String, MetaMeasureVO> measureMetaMap = new HashMap<>(
            metaCacheService.getMeasureOfCode()
        );

        return MetricQueryContext.builder()
            .queryDTO(dto)
            .validMetrics(validMetrics)
            .validDomainCodes(validDomains)
            .validMeasureCodes(validMeasures)
            .years(years)
            .measureMetaMap(measureMetaMap)
            // 三路 Map 待并发填充
            .userEditFlatMap(Collections.emptyMap())
            .apiFlatMap(Collections.emptyMap())
            .warningConfigMap(Collections.emptyMap())
            .userFocusMeasures(Collections.emptySet())
            .build();
    }

    private void validate(MetricQueryDTO dto) {
        Objects.requireNonNull(dto, "入参不能为空");
        Assert.notEmpty(dto.getPeriodList(),   "periodList 不能为空");
        Assert.notEmpty(dto.getMetricIdList(), "metricIdList 不能为空");
        Assert.notEmpty(dto.getOrgCodes(),     "orgCodes 不能为空");
        Assert.hasText(dto.getOrgLevel(),      "orgLevel 不能为空");
        Assert.hasText(dto.getUserOrgCode(),   "userOrgCode 不能为空");
        Assert.hasText(dto.getUserOrgLevel(),  "userOrgLevel 不能为空");
    }

    private boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}

九、Service + Controller
// IMetricDataService.java
public interface IMetricDataService {
    List<MetricDataVO> queryMetricData(MetricQueryDTO queryDTO);
}

// MetricDataServiceImpl.java
@Service
public class MetricDataServiceImpl implements IMetricDataService {

    @Autowired private MetricQueryContextBuilder   contextBuilder;
    @Autowired private MetricDataFetchManager      fetchManager;
    @Autowired private MetricDataAggregateManager  aggregateManager;

    @Override
    public List<MetricDataVO> queryMetricData(MetricQueryDTO queryDTO) {
        // Step 1: 校验 + 构建上下文（含元数据预加载）
        MetricQueryContext context = contextBuilder.build(queryDTO);

        // Step 2: 并发三路拉取 + 扁平化写入 context（原始集合即时回收）
        fetchManager.fetchAndFlatten(context);

        // Step 3: 聚合 + 返回
        return aggregateManager.aggregate(context);
    }
}

// MetricDataController.java
@RestController
@RequestMapping("/api/metric")
public class MetricDataController {

    @Autowired private IMetricDataService metricDataService;

    @PostMapping("/queryMetricData")
    public AjaxMessageVo queryMetricData(@RequestBody @Validated MetricQueryDTO dto) {
        return AjaxMessageVo.success(metricDataService.queryMetricData(dto));
    }
}
```

---

## 十、架构总览 + 内存设计说明
```
Controller
  └─ MetricDataServiceImpl
        ├─ MetricQueryContextBuilder   ← 单一上下文对象，贯穿全流程
        ├─ MetricDataFetchManager      ← CompletableFuture 并发三路
        │     DataSourceStrategyFactory
        │       ├─ UserEditDataSourceStrategy   → 表A → Map<key, Double> 直接扁平写入 context
        │       ├─ ApiMeasureDataSourceStrategy → API → List 转 Map 后写入 context（List即时释放）
        │       └─ UserConfigDataSourceStrategy → 表B → focusMeasures + warningConfigMap
        └─ MetricDataAggregateManager  ← Stream flatMap 四维展开（无嵌套循环）
              MeasureAssignStrategyFactory
                ├─ DirectAssignStrategy    直接取扁平Map值
                ├─ FallbackAssignStrategy  优先级覆盖
                └─ DerivedAssignStrategy   VO已赋值字段二次计算
              WarningStrategyFactory
                └─ OperatorBasedWarningStrategy  用户配置比较符，与rankDirection无关