指标度量值查询接口 queryMetricData 完整实现
一、DO / DTO / VO / 元数据类
// ==================== 入参 ====================
// com.example.metric.dto.MetricQueryDTO.java
@Data
public class MetricQueryDTO implements Serializable {
    private String periodType;
    private List<String> periodList;
    private List<Long> metricIdList;
    private List<String> measureCodes;
    private List<String> domainCodeList;
    private String needHEMetric;
    private String tabCode;
    private List<String> orgCodes;
    private String orgLevel;
    private String userOrgCode;
    private String userOrgLevel;
}

// ==================== 出参 VO ====================
// com.example.metric.vo.MetricDataVO.java
@Data
public class MetricDataVO implements Serializable {
    private String periodId;
    private Long metricId;
    private String metricCode;
    private String domainCode;
    private String domainName;
    private String orgCode;
    private String orgLevel;
    private Integer rankDirection;
    private String warning;

    // ===== 用户编辑度量（表A） =====
    private String baseline      = "-";
    private String grapBaseline  = "-";
    private String Forecast_Y    = "-";
    private String yellowBaseline = "-"; // 内部使用，最终可过滤
    private String redBaseline   = "-";  // 内部使用，最终可过滤

    // ===== 下游API度量 =====
    private String Budget_Y  = "-";
    private String Target_Y  = "-";
    private String YTD       = "-";
    private String PTD       = "-";

    // ===== 衍生/其他度量（按实际扩展） =====
    private String worldBest   = "-";
    private String regionBest  = "-";
    // ... 其他几十个度量字段同理，均默认 "-"
}

// ==================== 元数据 VO ====================
// com.example.metric.vo.meta.MetaMetricVO.java
@Data
public class MetaMetricVO implements Serializable {
    private Long metricId;
    private String metricCode;
    private String cnName;
    private String enName;
    private String measureCnUnit;
    private String measureEnUnit;
    private String currencyFlag;
    private String periodCalcType;
    private Integer rankDirection;
    private List<String> domainCodes;
    /** key: metricCode:::orgLevel -> List<domainCode> */
    private Map<String, List<String>> orgDomainCodes;
    private List<String> orgLevels;
    /** 预警使用的度量Code：YTD 或 PTD */
    private String warningMeasureCode;
}

// com.example.metric.vo.meta.MetaMeasureVO.java
@Data
public class MetaMeasureVO implements Serializable {
    private Long metricId;
    private String enName;
    private String cnName;
    private String currencyFlag;
    private String enUnit;
    private String cnUnit;
    private Integer calcType;
    private Integer decimalPlaces;
    private Integer measureType;
    private String measureCode;
}

// ==================== 内部聚合上下文（替代超3参传递） ====================
// com.example.metric.domain.MetricQueryContext.java
@Data
@Builder
public class MetricQueryContext implements Serializable {
    // 原始入参
    private MetricQueryDTO queryDTO;
    // 解析后的有效数据
    private List<MetaMetricVO> validMetrics;
    private List<String> validDomainCodes;
    private List<String> validMeasureCodes;
    private List<String> years;            // periodList 截前4位去重
    // 三路结果
    private List<UserEditMeasureDTO> userEditList;
    private List<ApiMeasureDTO> apiMeasureList;
    private List<String> userFocusMeasures; // 用户关注的度量Code列表
}

// ==================== 数据源中间 DTO ====================
// com.example.metric.dto.UserEditMeasureDTO.java
/** 表A：用户编辑度量（按年） */
@Data
public class UserEditMeasureDTO implements Serializable {
    private String year;
    private String orgCode;
    private String orgLevel;
    private String metricCode;
    private String domainCode;
    private Double baseline;
    private Double forecastY;
    private Double yellowBaseline;
    private Double redBaseline;
}

// com.example.metric.dto.ApiMeasureDTO.java
/** 下游API度量（按月） */
@Data
public class ApiMeasureDTO implements Serializable {
    private String periodId;
    private String orgCode;
    private String orgLevel;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private Double value;
}

二、策略模式：数据源获取
// ==================== 数据源获取策略接口 ====================
// com.example.metric.strategy.datasource.IDataSourceStrategy.java
public interface IDataSourceStrategy<T> {
    /**
     * 数据源唯一标识
     */
    DataSourceType sourceType();

    /**
     * 异步获取数据，返回 CompletableFuture
     */
    CompletableFuture<List<T>> fetchAsync(MetricQueryContext context);
}

// com.example.metric.strategy.datasource.DataSourceType.java
public enum DataSourceType {
    USER_EDIT,   // 表A：用户编辑度量
    API_MEASURE, // 下游API度量
    USER_CONFIG  // 表B：用户度量配置
}

// com.example.metric.strategy.datasource.impl.UserEditDataSourceStrategy.java
@Component
public class UserEditDataSourceStrategy implements IDataSourceStrategy<UserEditMeasureDTO> {

    @Autowired
    private UserEditMeasureRepository userEditMeasureRepository;
    @Autowired
    private ItaskExecutorService taskExecutorService;

    @Override
    public DataSourceType sourceType() {
        return DataSourceType.USER_EDIT;
    }

    @Override
    public CompletableFuture<List<UserEditMeasureDTO>> fetchAsync(MetricQueryContext context) {
        MetricQueryDTO dto = context.getQueryDTO();
        return taskExecutorService.submitTask(
            () -> userEditMeasureRepository.queryByYearAndOrg(
                context.getYears(), dto.getOrgCodes(), dto.getOrgLevel()),
            "UserEdit-" + dto.getOrgLevel()
        );
    }
}

// com.example.metric.strategy.datasource.impl.ApiMeasureDataSourceStrategy.java
@Component
public class ApiMeasureDataSourceStrategy implements IDataSourceStrategy<ApiMeasureDTO> {

    @Autowired
    private DownstreamApiAdapter downstreamApiAdapter;
    @Autowired
    private ItaskExecutorService taskExecutorService;

    @Override
    public DataSourceType sourceType() {
        return DataSourceType.API_MEASURE;
    }

    @Override
    public CompletableFuture<List<ApiMeasureDTO>> fetchAsync(MetricQueryContext context) {
        MetricQueryDTO dto = context.getQueryDTO();
        return taskExecutorService.submitTask(
            () -> downstreamApiAdapter.queryMeasures(context),
            "ApiMeasure-" + dto.getOrgLevel()
        );
    }
}

// com.example.metric.strategy.datasource.impl.UserConfigDataSourceStrategy.java
@Component
public class UserConfigDataSourceStrategy implements IDataSourceStrategy<String> {

    @Autowired
    private UserMeasureConfigRepository userMeasureConfigRepository;
    @Autowired
    private ItaskExecutorService taskExecutorService;

    @Override
    public DataSourceType sourceType() {
        return DataSourceType.USER_CONFIG;
    }

    @Override
    public CompletableFuture<List<String>> fetchAsync(MetricQueryContext context) {
        MetricQueryDTO dto = context.getQueryDTO();
        return taskExecutorService.submitTask(
            () -> userMeasureConfigRepository.queryFocusMeasures(
                dto.getUserOrgCode(), dto.getUserOrgLevel()),
            "UserConfig-" + dto.getUserOrgCode()
        );
    }
}

// com.example.metric.strategy.datasource.DataSourceStrategyFactory.java
@Component
public class DataSourceStrategyFactory {

    private final Map<DataSourceType, IDataSourceStrategy<?>> strategyMap;

    @Autowired
    public DataSourceStrategyFactory(List<IDataSourceStrategy<?>> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(IDataSourceStrategy::sourceType, Function.identity()));
    }

    @SuppressWarnings("unchecked")
    public <T> IDataSourceStrategy<T> getStrategy(DataSourceType type) {
        return (IDataSourceStrategy<T>) strategyMap.get(type);
    }
}

三、策略模式：度量值赋值
// ==================== 度量赋值策略接口 ====================
// com.example.metric.strategy.measure.IMeasureAssignStrategy.java
public interface IMeasureAssignStrategy {
    /** 策略支持的度量Code列表 */
    List<String> supportedMeasureCodes();

    /**
     * 将度量值填充到 VO
     * @param vo       待填充的 VO
     * @param lookupCtx  数据查找上下文（含3路扁平化Map）
     */
    void assign(MetricDataVO vo, MeasureLookupContext lookupCtx);
}

// com.example.metric.strategy.measure.MeasureLookupContext.java
/** 三路扁平化后的 Map，key = periodId:::orgCode:::metricCode:::domainCode:::measureCode */
@Data
@Builder
public class MeasureLookupContext {
    /** 表A数据: key=year:::orgCode:::metricCode:::domainCode */
    private Map<String, UserEditMeasureDTO> userEditMap;
    /** API数据: key=periodId:::orgCode:::metricCode:::domainCode:::measureCode */
    private Map<String, Double> apiMeasureMap;
    /** 元数据 */
    private Map<String, MetaMeasureVO> measureMetaMap;
}

// ==================== 直接赋值策略（YTD、PTD、Target_Y 等） ====================
// com.example.metric.strategy.measure.impl.DirectAssignStrategy.java
@Component
public class DirectAssignStrategy implements IMeasureAssignStrategy {

    @Override
    public List<String> supportedMeasureCodes() {
        return Arrays.asList("YTD", "PTD", "Target_Y", "Budget_Y", "Forecast_Y", "worldBest", "regionBest");
    }

    @Override
    public void assign(MetricDataVO vo, MeasureLookupContext ctx) {
        // YTD/PTD/Target_Y/Budget_Y 来自 API
        setApiValue(vo, ctx, "YTD",       (v, s) -> v.setYTD(s));
        setApiValue(vo, ctx, "PTD",       (v, s) -> v.setPTD(s));
        setApiValue(vo, ctx, "Target_Y",  (v, s) -> v.setTarget_Y(s));
        setApiValue(vo, ctx, "Budget_Y",  (v, s) -> v.setBudget_Y(s));
        // Forecast_Y 来自表A
        setUserEditValue(vo, ctx, "Forecast_Y", (v, s) -> v.setForecast_Y(s));
    }

    private void setApiValue(MetricDataVO vo, MeasureLookupContext ctx,
                              String measureCode, BiConsumer<MetricDataVO, String> setter) {
        String key = buildApiKey(vo, measureCode);
        Double raw = ctx.getApiMeasureMap().get(key);
        if (raw != null) {
            setter.accept(vo, formatValue(raw, measureCode, ctx.getMeasureMetaMap()));
        }
    }

    private void setUserEditValue(MetricDataVO vo, MeasureLookupContext ctx,
                                   String field, BiConsumer<MetricDataVO, String> setter) {
        String key = buildUserEditKey(vo);
        UserEditMeasureDTO edit = ctx.getUserEditMap().get(key);
        if (edit != null && "Forecast_Y".equals(field) && edit.getForecastY() != null) {
            setter.accept(vo, formatValue(edit.getForecastY(), field, ctx.getMeasureMetaMap()));
        }
    }

    private String buildApiKey(MetricDataVO vo, String measureCode) {
        return vo.getPeriodId() + ":::" + vo.getOrgCode() + ":::" + vo.getMetricCode()
             + ":::" + vo.getDomainCode() + ":::" + measureCode;
    }

    private String buildUserEditKey(MetricDataVO vo) {
        // 表A按年存储，periodId取前4位
        String year = vo.getPeriodId().substring(0, 4);
        return year + ":::" + vo.getOrgCode() + ":::" + vo.getMetricCode() + ":::" + vo.getDomainCode();
    }
}

// ==================== 优先级覆盖策略（baseline） ====================
// com.example.metric.strategy.measure.impl.FallbackAssignStrategy.java
@Component
public class FallbackAssignStrategy implements IMeasureAssignStrategy {

    @Override
    public List<String> supportedMeasureCodes() {
        return Collections.singletonList("baseline");
    }

    @Override
    public void assign(MetricDataVO vo, MeasureLookupContext ctx) {
        // 优先取表A的 baseline，若为空则降级取 API 的 Budget_Y
        String userEditKey = buildUserEditKey(vo);
        UserEditMeasureDTO edit = ctx.getUserEditMap().get(userEditKey);

        if (edit != null && edit.getBaseline() != null) {
            vo.setBaseline(formatValue(edit.getBaseline(), "baseline", ctx.getMeasureMetaMap()));
        } else {
            // 降级取 Budget_Y
            String apiKey = buildApiKey(vo, "Budget_Y");
            Double raw = ctx.getApiMeasureMap().get(apiKey);
            if (raw != null) {
                vo.setBaseline(formatValue(raw, "Budget_Y", ctx.getMeasureMetaMap()));
            }
        }

        // 同时把 yellowBaseline / redBaseline 填入（供预警使用）
        if (edit != null) {
            if (edit.getYellowBaseline() != null) {
                vo.setYellowBaseline(formatValue(edit.getYellowBaseline(), "yellowBaseline", ctx.getMeasureMetaMap()));
            }
            if (edit.getRedBaseline() != null) {
                vo.setRedBaseline(formatValue(edit.getRedBaseline(), "redBaseline", ctx.getMeasureMetaMap()));
            }
        }
    }

    private String buildUserEditKey(MetricDataVO vo) {
        String year = vo.getPeriodId().substring(0, 4);
        return year + ":::" + vo.getOrgCode() + ":::" + vo.getMetricCode() + ":::" + vo.getDomainCode();
    }

    private String buildApiKey(MetricDataVO vo, String measureCode) {
        return vo.getPeriodId() + ":::" + vo.getOrgCode() + ":::" + vo.getMetricCode()
             + ":::" + vo.getDomainCode() + ":::" + measureCode;
    }
}

// ==================== 计算衍生策略（grapBaseline = YTD - baseline） ====================
// com.example.metric.strategy.measure.impl.DerivedAssignStrategy.java
@Component
public class DerivedAssignStrategy implements IMeasureAssignStrategy {

    @Override
    public List<String> supportedMeasureCodes() {
        return Collections.singletonList("grapBaseline");
    }

    @Override
    public void assign(MetricDataVO vo, MeasureLookupContext ctx) {
        // 基于已赋值的 YTD 和 baseline 做二次计算
        Double ytd      = parseDouble(vo.getYTD());
        Double baseline = parseDouble(vo.getBaseline());
        if (ytd != null && baseline != null) {
            vo.setGrapBaseline(formatValue(ytd - baseline, "grapBaseline", ctx.getMeasureMetaMap()));
        }
    }

    private Double parseDouble(String val) {
        if (val == null || "-".equals(val)) return null;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return null; }
    }
}

// ==================== 度量策略公共工具方法（抽象基类） ====================
// com.example.metric.strategy.measure.AbstractMeasureAssignStrategy.java
public abstract class AbstractMeasureAssignStrategy implements IMeasureAssignStrategy {

    protected String formatValue(Double raw, String measureCode, Map<String, MetaMeasureVO> metaMap) {
        if (raw == null) return "-";
        MetaMeasureVO meta = metaMap.get(measureCode);
        int places = (meta != null && meta.getDecimalPlaces() != null) ? meta.getDecimalPlaces() : 2;
        return String.valueOf(
            new BigDecimal(raw).setScale(places, RoundingMode.HALF_UP).doubleValue()
        );
    }
}
// 注：DirectAssignStrategy / FallbackAssignStrategy / DerivedAssignStrategy 均继承此类

// ==================== 度量策略工厂 ====================
// com.example.metric.strategy.measure.MeasureAssignStrategyFactory.java
@Component
public class MeasureAssignStrategyFactory {

    /** measureCode -> strategy */
    private final Map<String, IMeasureAssignStrategy> strategyMap;

    @Autowired
    public MeasureAssignStrategyFactory(List<IMeasureAssignStrategy> strategies) {
        this.strategyMap = new HashMap<>();
        strategies.forEach(s ->
            s.supportedMeasureCodes().forEach(code -> strategyMap.put(code, s))
        );
    }

    public IMeasureAssignStrategy getStrategy(String measureCode) {
        return strategyMap.getOrDefault(measureCode, null);
    }

    public Collection<IMeasureAssignStrategy> allStrategies() {
        // 去重：同一个策略对象只执行一次
        return new LinkedHashSet<>(strategyMap.values());
    }
}

四、策略模式：预警计算
// ==================== 预警计算策略接口 ====================
// com.example.metric.strategy.warning.IWarningStrategy.java
public interface IWarningStrategy {
    /**
     * @param rankDirection 1=正向（越大越好）, -1=负向（越小越好）
     * @return 策略是否适用
     */
    boolean support(int rankDirection);

    /**
     * 计算预警级别
     * @param value           当前值（YTD 或 PTD）
     * @param yellowBaseline  黄色阈值
     * @param redBaseline     红色阈值
     */
    String calculate(Double value, Double yellowBaseline, Double redBaseline);
}

// ==================== 正向预警（越大越好：< yellow=yellow, < red=red, else green） ====================
// com.example.metric.strategy.warning.impl.PositiveWarningStrategy.java
@Component
public class PositiveWarningStrategy implements IWarningStrategy {

    @Override
    public boolean support(int rankDirection) {
        return rankDirection == 1;
    }

    @Override
    public String calculate(Double value, Double yellowBaseline, Double redBaseline) {
        if (value == null) return null;
        if (redBaseline != null && value < redBaseline) return "red";
        if (yellowBaseline != null && value < yellowBaseline) return "yellow";
        return "green";
    }
}

// ==================== 负向预警（越小越好） ====================
// com.example.metric.strategy.warning.impl.NegativeWarningStrategy.java
@Component
public class NegativeWarningStrategy implements IWarningStrategy {

    @Override
    public boolean support(int rankDirection) {
        return rankDirection == -1;
    }

    @Override
    public String calculate(Double value, Double yellowBaseline, Double redBaseline) {
        if (value == null) return null;
        if (redBaseline != null && value > redBaseline) return "red";
        if (yellowBaseline != null && value > yellowBaseline) return "yellow";
        return "green";
    }
}

// ==================== 预警策略工厂 ====================
// com.example.metric.strategy.warning.WarningStrategyFactory.java
@Component
public class WarningStrategyFactory {

    private final List<IWarningStrategy> strategies;

    @Autowired
    public WarningStrategyFactory(List<IWarningStrategy> strategies) {
        this.strategies = strategies;
    }

    public IWarningStrategy getStrategy(int rankDirection) {
        return strategies.stream()
            .filter(s -> s.support(rankDirection))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No warning strategy for rankDirection=" + rankDirection));
    }
}

五、Repository / Adapter 层
// ==================== 表A Repository ====================
// com.example.metric.repository.UserEditMeasureRepository.java
@Repository
public interface UserEditMeasureRepository {
    /**
     * 按年+组织查询用户编辑度量
     */
    List<UserEditMeasureDTO> queryByYearAndOrg(List<String> years,
                                                List<String> orgCodes,
                                                String orgLevel);
}

// com.example.metric.repository.impl.UserEditMeasureRepositoryImpl.java
@Repository
public class UserEditMeasureRepositoryImpl implements UserEditMeasureRepository {

    @Autowired
    private UserEditMeasureMapper mapper; // MyBatis Mapper

    @Override
    public List<UserEditMeasureDTO> queryByYearAndOrg(List<String> years,
                                                       List<String> orgCodes,
                                                       String orgLevel) {
        UserEditQueryParam param = new UserEditQueryParam();
        param.setYears(years);
        param.setOrgCodes(orgCodes);
        param.setOrgLevel(orgLevel);
        return mapper.selectByYearAndOrg(param);
    }
}

// ==================== 下游 API Adapter ====================
// com.example.metric.adapter.DownstreamApiAdapter.java
@Component
public class DownstreamApiAdapter {

    @Autowired
    private RestTemplate restTemplate; // 或 Feign Client

    /**
     * 调用下游系统，获取按月存储的度量值
     */
    public List<ApiMeasureDTO> queryMeasures(MetricQueryContext context) {
        MetricQueryDTO dto = context.getQueryDTO();
        // 构建下游请求参数
        DownstreamApiRequest req = DownstreamApiRequest.builder()
            .periodIds(dto.getPeriodList())
            .orgCodes(dto.getOrgCodes())
            .orgLevel(dto.getOrgLevel())
            .metricCodes(context.getValidMetrics().stream()
                .map(MetaMetricVO::getMetricCode).collect(Collectors.toList()))
            .domainCodes(context.getValidDomainCodes())
            .measureCodes(context.getValidMeasureCodes())
            .build();
        // 调用下游，返回原始数据后做适配
        DownstreamApiResponse resp = restTemplate.postForObject(
            "/api/downstream/metrics", req, DownstreamApiResponse.class);
        return adaptResponse(resp);
    }

    private List<ApiMeasureDTO> adaptResponse(DownstreamApiResponse resp) {
        if (resp == null || resp.getData() == null) return Collections.emptyList();
        return resp.getData().stream().map(item -> {
            ApiMeasureDTO dto = new ApiMeasureDTO();
            // 字段映射
            dto.setPeriodId(item.getPeriodId());
            dto.setOrgCode(item.getOrgCode());
            dto.setOrgLevel(item.getOrgLevel());
            dto.setMetricCode(item.getMetricCode());
            dto.setDomainCode(item.getDomainCode());
            dto.setMeasureCode(item.getMeasureCode());
            dto.setValue(item.getValue());
            return dto;
        }).collect(Collectors.toList());
    }
}

六、Manager 层：并发编排 + 数据聚合
// ==================== 并发编排 Manager ====================
// com.example.metric.manager.MetricDataFetchManager.java
@Component
public class MetricDataFetchManager {

    @Autowired
    private DataSourceStrategyFactory strategyFactory;
    @Autowired
    private ItaskExecutorService taskExecutorService;

    /**
     * 并发执行三路数据获取
     */
    @SuppressWarnings("unchecked")
    public void fetchAllAsync(MetricQueryContext context) {
        IDataSourceStrategy<UserEditMeasureDTO> userEditStrategy =
            strategyFactory.getStrategy(DataSourceType.USER_EDIT);
        IDataSourceStrategy<ApiMeasureDTO> apiStrategy =
            strategyFactory.getStrategy(DataSourceType.API_MEASURE);
        IDataSourceStrategy<String> configStrategy =
            strategyFactory.getStrategy(DataSourceType.USER_CONFIG);

        // 三路并发
        CompletableFuture<List<UserEditMeasureDTO>> userEditFuture = userEditStrategy.fetchAsync(context);
        CompletableFuture<List<ApiMeasureDTO>> apiFuture = apiStrategy.fetchAsync(context);
        CompletableFuture<List<String>> configFuture = configStrategy.fetchAsync(context);

        // 等待全部完成，任一异常则整体失败
        CompletableFuture.allOf(userEditFuture, apiFuture, configFuture).join();

        // 写回 context
        context.setUserEditList(userEditFuture.join());
        context.setApiMeasureList(apiFuture.join());
        context.setUserFocusMeasures(configFuture.join());
    }
}

// ==================== 数据聚合 Manager ====================
// com.example.metric.manager.MetricDataAggregateManager.java
@Component
public class MetricDataAggregateManager {

    @Autowired
    private MeasureAssignStrategyFactory measureStrategyFactory;
    @Autowired
    private WarningStrategyFactory warningStrategyFactory;
    @Autowired
    private OpMetaCacheService metaCacheService;

    /**
     * 聚合入口
     */
    public List<MetricDataVO> aggregate(MetricQueryContext context) {
        // Step1: 三路数据扁平化 -> Map 索引
        MeasureLookupContext lookupCtx = buildLookupContext(context);

        // Step2: 构建4维度骨架并逐条填充（Map索引替代4层嵌套）
        return buildSkeleton(context)
            .stream()
            .map(vo -> fillVO(vo, lookupCtx, context))
            .filter(vo -> vo != null)
            .collect(Collectors.toList());
    }

    /**
     * 三路数据扁平化为 Map，避免聚合时全量遍历
     */
    private MeasureLookupContext buildLookupContext(MetricQueryContext context) {
        // 表A扁平化: key = year:::orgCode:::metricCode:::domainCode
        Map<String, UserEditMeasureDTO> userEditMap = context.getUserEditList().stream()
            .collect(Collectors.toMap(
                e -> e.getYear() + ":::" + e.getOrgCode() + ":::" + e.getMetricCode() + ":::" + e.getDomainCode(),
                Function.identity(),
                (a, b) -> a  // 若重复取第一条
            ));

        // API扁平化: key = periodId:::orgCode:::metricCode:::domainCode:::measureCode
        Map<String, Double> apiMeasureMap = context.getApiMeasureList().stream()
            .collect(Collectors.toMap(
                e -> e.getPeriodId() + ":::" + e.getOrgCode() + ":::" + e.getMetricCode()
                   + ":::" + e.getDomainCode() + ":::" + e.getMeasureCode(),
                ApiMeasureDTO::getValue,
                (a, b) -> a
            ));

        // 度量元数据: key = measureCode
        Map<String, MetaMeasureVO> measureMetaMap = metaCacheService.getMeasureOfCode().values().stream()
            .collect(Collectors.toMap(MetaMeasureVO::getMeasureCode, Function.identity(), (a, b) -> a));

        return MeasureLookupContext.builder()
            .userEditMap(userEditMap)
            .apiMeasureMap(apiMeasureMap)
            .measureMetaMap(measureMetaMap)
            .build();
    }

    /**
     * 构建4维度笛卡尔积骨架
     * periodId × orgCode × metricCode × domainCode
     * 使用 flatMap 展开，圈复杂度=1
     */
    private List<MetricDataVO> buildSkeleton(MetricQueryContext context) {
        MetricQueryDTO dto = context.getQueryDTO();
        Map<Long, MetaMetricVO> metricMetaMap = metaCacheService.getMetricOfId();

        return dto.getPeriodList().stream()
            .flatMap(periodId -> dto.getOrgCodes().stream()
                .flatMap(orgCode -> context.getValidMetrics().stream()
                    .flatMap(metric -> getEffectiveDomains(metric, dto.getOrgLevel(), context.getValidDomainCodes())
                        .stream()
                        .map(domainCode -> {
                            MetricDataVO vo = new MetricDataVO();
                            vo.setPeriodId(periodId);
                            vo.setOrgCode(orgCode);
                            vo.setOrgLevel(dto.getOrgLevel());
                            vo.setMetricId(metric.getMetricId());
                            vo.setMetricCode(metric.getMetricCode());
                            vo.setDomainCode(domainCode);
                            vo.setRankDirection(metric.getRankDirection());
                            return vo;
                        })
                    )
                )
            )
            .collect(Collectors.toList());
    }

    /**
     * 获取指标在当前 orgLevel 下的有效 domainCode（与入参取交集）
     */
    private List<String> getEffectiveDomains(MetaMetricVO metric, String orgLevel, List<String> validDomains) {
        String key = metric.getMetricCode() + ":::" + orgLevel;
        List<String> metaDomains = metric.getOrgDomainCodes() != null
            ? metric.getOrgDomainCodes().getOrDefault(key, metric.getDomainCodes())
            : metric.getDomainCodes();
        if (metaDomains == null) return validDomains;
        return metaDomains.stream()
            .filter(validDomains::contains)
            .collect(Collectors.toList());
    }

    /**
     * 填充单条 VO：基础属性 + 度量赋值 + 预警
     */
    private MetricDataVO fillVO(MetricDataVO vo, MeasureLookupContext lookupCtx, MetricQueryContext context) {
        // a. 基础属性（domainName 等从元数据/枚举中取，此处简化示意）
        fillBaseInfo(vo);

        // b. 所有度量策略逐一执行（策略工厂保证去重，顺序由 LinkedHashSet 维护）
        measureStrategyFactory.allStrategies()
            .forEach(strategy -> strategy.assign(vo, lookupCtx));

        // c. 预警计算
        fillWarning(vo, context);

        // d. 用户度量过滤（不展示的字段置为 null 或 "-"，由调用方决定是否序列化过滤）
        filterByUserConfig(vo, context.getUserFocusMeasures());

        return vo;
    }

    private void fillBaseInfo(MetricDataVO vo) {
        // domainName 从领域枚举/缓存中获取，示意
        // vo.setDomainName(domainCacheService.getName(vo.getDomainCode()));
    }

    private void fillWarning(MetricDataVO vo, MetricQueryContext context) {
        // 获取该指标配置的 warningMeasureCode
        MetaMetricVO metricMeta = metaCacheService.getMetricOfId().get(vo.getMetricId());
        if (metricMeta == null) return;

        String warningMeasureCode = metricMeta.getWarningMeasureCode(); // "YTD" or "PTD"
        String rawVal = "YTD".equals(warningMeasureCode) ? vo.getYTD() : vo.getPTD();
        Double value  = parseDouble(rawVal);
        Double yellow = parseDouble(vo.getYellowBaseline());
        Double red    = parseDouble(vo.getRedBaseline());

        if (value == null) return;
        IWarningStrategy warningStrategy = warningStrategyFactory.getStrategy(metricMeta.getRankDirection());
        vo.setWarning(warningStrategy.calculate(value, yellow, red));
    }

    /**
     * 根据用户关注配置，过滤不展示的度量（将不关注的度量置为 "-"）
     */
    private void filterByUserConfig(MetricDataVO vo, List<String> userFocusMeasures) {
        if (userFocusMeasures == null || userFocusMeasures.isEmpty()) return;
        Set<String> focusSet = new HashSet<>(userFocusMeasures);
        // 非关注度量置为 "-"
        if (!focusSet.contains("Budget_Y"))  vo.setBudget_Y("-");
        if (!focusSet.contains("Target_Y"))  vo.setTarget_Y("-");
        if (!focusSet.contains("YTD"))       vo.setYTD("-");
        if (!focusSet.contains("baseline"))  vo.setBaseline("-");
        if (!focusSet.contains("grapBaseline")) vo.setGrapBaseline("-");
        if (!focusSet.contains("Forecast_Y"))vo.setForecast_Y("-");
        // 预警相关辅助字段不对外暴露
        vo.setYellowBaseline("-");
        vo.setRedBaseline("-");
    }

    private Double parseDouble(String val) {
        if (val == null || "-".equals(val)) return null;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return null; }
    }
}

七、Service 层
// ==================== Service 接口 ====================
// com.example.metric.service.IMetricDataService.java
public interface IMetricDataService {
    List<MetricDataVO> queryMetricData(MetricQueryDTO queryDTO);
}

// ==================== Service 实现 ====================
// com.example.metric.service.impl.MetricDataServiceImpl.java
@Service
public class MetricDataServiceImpl implements IMetricDataService {

    @Autowired
    private MetricQueryContextBuilder contextBuilder;
    @Autowired
    private MetricDataFetchManager fetchManager;
    @Autowired
    private MetricDataAggregateManager aggregateManager;

    @Override
    public List<MetricDataVO> queryMetricData(MetricQueryDTO queryDTO) {
        // Step1: 校验 + 构建查询上下文
        MetricQueryContext context = contextBuilder.build(queryDTO);

        // Step2: 并发三路获取
        fetchManager.fetchAllAsync(context);

        // Step3: 聚合
        return aggregateManager.aggregate(context);
    }
}

// ==================== 上下文构建器 ====================
// com.example.metric.domain.MetricQueryContextBuilder.java
@Component
public class MetricQueryContextBuilder {

    @Autowired
    private OpMetaCacheService metaCacheService;

    /** measureCodes 默认全量枚举 */
    private static final List<String> DEFAULT_MEASURE_CODES =
        Arrays.asList("Y", "POP", "YTD", "PTD", "Budget_Y", "Target_Y", "Forecast_Y", "baseline", "grapBaseline");

    /** domainCodes 默认全量枚举 */
    private static final List<String> DEFAULT_DOMAIN_CODES =
        Arrays.asList("D001", "D002", "D003"); // 从枚举/配置加载

    public MetricQueryContext build(MetricQueryDTO dto) {
        validateRequired(dto);

        // 解析有效指标
        Map<Long, MetaMetricVO> metricMap = metaCacheService.getMetricOfId();
        List<MetaMetricVO> validMetrics = dto.getMetricIdList().stream()
            .map(metricMap::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // 有效 measureCodes
        List<String> validMeasureCodes = (dto.getMeasureCodes() == null || dto.getMeasureCodes().isEmpty())
            ? DEFAULT_MEASURE_CODES
            : dto.getMeasureCodes();

        // 有效 domainCodes（与指标元数据取交集）
        List<String> inputDomains = (dto.getDomainCodeList() == null || dto.getDomainCodeList().isEmpty())
            ? DEFAULT_DOMAIN_CODES
            : dto.getDomainCodeList();
        Set<String> metaDomains = validMetrics.stream()
            .filter(m -> m.getDomainCodes() != null)
            .flatMap(m -> m.getDomainCodes().stream())
            .collect(Collectors.toSet());
        List<String> validDomains = inputDomains.stream()
            .filter(metaDomains::contains)
            .collect(Collectors.toList());

        // 年份列表（periodList 截前4位去重）
        List<String> years = dto.getPeriodList().stream()
            .map(p -> p.substring(0, 4))
            .distinct()
            .collect(Collectors.toList());

        return MetricQueryContext.builder()
            .queryDTO(dto)
            .validMetrics(validMetrics)
            .validDomainCodes(validDomains)
            .validMeasureCodes(validMeasureCodes)
            .years(years)
            .build();
    }

    private void validateRequired(MetricQueryDTO dto) {
        Assert.notNull(dto, "入参不能为空");
        Assert.notEmpty(dto.getPeriodList(), "periodList 不能为空");
        Assert.notEmpty(dto.getMetricIdList(), "metricIdList 不能为空");
        Assert.notEmpty(dto.getOrgCodes(), "orgCodes 不能为空");
        Assert.hasText(dto.getOrgLevel(), "orgLevel 不能为空");
        Assert.hasText(dto.getUserOrgCode(), "userOrgCode 不能为空");
        Assert.hasText(dto.getUserOrgLevel(), "userOrgLevel 不能为空");
    }
}

八、Controller 层
// com.example.metric.controller.MetricDataController.java
@RestController
@RequestMapping("/api/metric")
public class MetricDataController {

    @Autowired
    private IMetricDataService metricDataService;

    @PostMapping("/queryMetricData")
    public AjaxMessageVo queryMetricData(@RequestBody @Validated MetricQueryDTO queryDTO) {
        List<MetricDataVO> result = metricDataService.queryMetricData(queryDTO);
        return AjaxMessageVo.success(result);
    }
}
```

---

## 九、架构总览与关键设计说明
```
Controller
    └─ MetricDataServiceImpl
            ├─ MetricQueryContextBuilder      [Step1: 校验 + 解析元数据 + 构建Context]
            ├─ MetricDataFetchManager         [Step2: 并发三路获取]
            │       └─ DataSourceStrategyFactory
            │               ├─ UserEditDataSourceStrategy   → 表A
            │               ├─ ApiMeasureDataSourceStrategy → 下游API
            │               └─ UserConfigDataSourceStrategy → 表B
            └─ MetricDataAggregateManager     [Step3: 聚合]
                    ├─ buildLookupContext()    [flatMap扁平化三路 → Map索引]
                    ├─ buildSkeleton()         [Stream flatMap替代4层循环]
                    ├─ MeasureAssignStrategyFactory
                    │       ├─ DirectAssignStrategy    [直接赋值]
                    │       ├─ FallbackAssignStrategy  [优先级覆盖]
                    │       └─ DerivedAssignStrategy   [计算衍生]
                    └─ WarningStrategyFactory
                            ├─ PositiveWarningStrategy [正向]
                            └─ NegativeWarningStrategy [负向]