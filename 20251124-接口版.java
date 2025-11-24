// ============= 基于接口的优雅设计方案 =============

/**
 * ==================== 第一层：核心接口定义 ====================
 */

/**
 * 聚合请求接口
 */
public interface IAggregationRequest {
    
    /**
     * 获取场景类型
     */
    String getSceneType();
    
    /**
     * 获取会计期列表
     */
    List<String> getPeriodIds();
}

/**
 * 聚合响应接口
 */
public interface IAggregationResponse {
    
    /**
     * 获取会计期ID
     */
    String getPeriodId();
    
    /**
     * 设置会计期ID
     */
    void setPeriodId(String periodId);
}

/**
 * 元数据接口（已存在，补充完整）
 */
public interface IMetadata {
    // 标记接口，具体元数据由子类实现
}

/**
 * 原始数据接口（从API返回的数据）
 */
public interface IRawData {
    
    /**
     * 获取会计期ID
     */
    String getPeriodId();
    
    /**
     * 获取组织编码
     */
    String getOrgCode();
    
    /**
     * 获取领域编码
     */
    String getDomainCode();
}

/**
 * 转换后的度量数据接口
 */
public interface IMeasureData {
    
    /**
     * 获取度量编码
     */
    String getMeasureCode();
    
    /**
     * 获取会计期ID
     */
    String getPeriodId();
    
    /**
     * 获取组织编码
     */
    String getOrgCode();
    
    /**
     * 获取领域编码
     */
    String getDomainCode();
}

/**
 * ==================== 第二层：聚合维度接口 ====================
 */

/**
 * 聚合维度提供者接口
 * 用于从数据中提取聚合维度信息
 */
public interface IAggregationDimensionProvider<T> {
    
    /**
     * 提取会计期
     */
    String extractPeriodId(T data);
    
    /**
     * 提取组织编码
     */
    String extractOrgCode(T data);
    
    /**
     * 提取领域编码
     */
    String extractDomainCode(T data);
    
    /**
     * 提取度量编码
     */
    String extractMeasureCode(T data);
}

/**
 * 聚合Key生成器接口
 */
public interface IAggregationKeyGenerator {
    
    /**
     * 生成聚合Key
     * @param metricCode 指标编码
     * @param orgCode 组织编码
     * @param domainCode 领域编码
     * @return 聚合Key，格式：metricCode:::orgCode:::domainCode
     */
    String generateKey(String metricCode, String orgCode, String domainCode);
    
    /**
     * 解析聚合Key
     * @param key 聚合Key
     * @return [metricCode, orgCode, domainCode]
     */
    String[] parseKey(String key);
}

/**
 * 指标查找器接口
 * 用于通过度量编码查找对应的指标编码
 */
public interface IMetricFinder<META extends IMetadata> {
    
    /**
     * 根据度量编码查找指标编码
     */
    String findMetricCode(String measureCode, META metadata);
}

/**
 * ==================== 第三层：默认实现 ====================
 */

/**
 * 默认聚合Key生成器（使用对象池优化）
 */
@Component
public class DefaultAggregationKeyGenerator implements IAggregationKeyGenerator {
    
    @Autowired
    private ObjectPoolManager poolManager;
    
    private static final String SEPARATOR = ":::";
    
    @Override
    public String generateKey(String metricCode, String orgCode, String domainCode) {
        StringBuilderPool sbPool = poolManager.getStringBuilderPool();
        
        try (PooledObject<StringBuilder> pooled = sbPool.acquirePooled()) {
            StringBuilder sb = pooled.get();
            sb.append(metricCode != null ? metricCode : "")
              .append(SEPARATOR)
              .append(orgCode != null ? orgCode : "")
              .append(SEPARATOR)
              .append(domainCode != null ? domainCode : "");
            return sb.toString();
        }
    }
    
    @Override
    public String[] parseKey(String key) {
        if (key == null || key.isEmpty()) {
            return new String[3];
        }
        return key.split(SEPARATOR, 3);
    }
}

/**
 * 默认维度提供者（从IMeasureData接口获取）
 */
public class DefaultDimensionProvider<T extends IMeasureData> 
        implements IAggregationDimensionProvider<T> {
    
    @Override
    public String extractPeriodId(T data) {
        return data != null ? data.getPeriodId() : null;
    }
    
    @Override
    public String extractOrgCode(T data) {
        return data != null ? data.getOrgCode() : null;
    }
    
    @Override
    public String extractDomainCode(T data) {
        return data != null ? data.getDomainCode() : null;
    }
    
    @Override
    public String extractMeasureCode(T data) {
        return data != null ? data.getMeasureCode() : null;
    }
}

/**
 * 默认指标查找器
 */
public class DefaultMetricFinder implements IMetricFinder<MeasureMetadata> {
    
    @Override
    public String findMetricCode(String measureCode, MeasureMetadata metadata) {
        if (metadata == null || metadata.getMetricToMeasuresCache() == null) {
            return null;
        }
        return metadata.getMetricToMeasuresCache().get(measureCode);
    }
}

/**
 * ==================== 第四层：具体实现示例 ====================
 */

/**
 * 度量请求实现
 */
@Data
public class MeasureReqVO implements IAggregationRequest {
    
    private String sceneType;
    private List<String> periodIds;
    private List<String> metricCodes;
    private List<String> domainCodes;
    private List<String> orgCodes;
    private String orgLevel;
    
    @Override
    public String getSceneType() {
        return sceneType;
    }
    
    @Override
    public List<String> getPeriodIds() {
        return periodIds;
    }
}

/**
 * 度量响应实现
 */
@Data
public class OpMetricDataRespVO implements IAggregationResponse {
    
    private String periodId;
    private Map<String, List<MeasureDataVO>> measureMap;
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public void setPeriodId(String periodId) {
        this.periodId = periodId;
    }
}

/**
 * 原始数据实现
 */
@Data
public class MeasureRawData implements IRawData {
    
    private String periodId;
    private String orgCode;
    private String domainCode;
    private String measureCode;
    private BigDecimal value;
    private String unit;
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public String getOrgCode() {
        return orgCode;
    }
    
    @Override
    public String getDomainCode() {
        return domainCode;
    }
}

/**
 * 增强的度量数据实现
 */
@Data
public class EnhancedMeasureDataVO implements IMeasureData {
    
    // 度量信息
    private String measureCode;
    private String unit;
    private String originValue;
    private String fixedValue;
    private String currency;
    
    // 维度信息
    private String periodId;
    private String orgCode;
    private String domainCode;
    
    @Override
    public String getMeasureCode() {
        return measureCode;
    }
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public String getOrgCode() {
        return orgCode;
    }
    
    @Override
    public String getDomainCode() {
        return domainCode;
    }
}

/**
 * ==================== 第五层：改进的聚合器（基于接口） ====================
 */

/**
 * 通用页数据聚合器接口
 */
public interface IPageDataAggregator<T extends IMeasureData, 
                                      RESP extends IAggregationResponse, 
                                      CTX extends IAggregationContext<?, ?>> {
    
    /**
     * 聚合单页数据
     */
    void aggregatePage(List<T> pageData, CTX context);
    
    /**
     * 获取最终聚合结果
     */
    Map<String, RESP> getFinalResult();
    
    /**
     * 清空结果
     */
    void clear();
}

/**
 * 通用页聚合器实现（基于接口）
 */
public class GenericPageAggregator<T extends IMeasureData, 
                                     RESP extends IAggregationResponse,
                                     META extends IMetadata,
                                     CTX extends IAggregationContext<?, META>> 
        implements IPageDataAggregator<T, RESP, CTX> {
    
    private final Logger logger = LoggerFactory.getLogger(GenericPageAggregator.class);
    
    // 最终结果
    private final ConcurrentHashMap<String, RESP> finalResult;
    
    // 策略组件（通过构造函数注入）
    private final IAggregationKeyGenerator keyGenerator;
    private final IAggregationDimensionProvider<T> dimensionProvider;
    private final IMetricFinder<META> metricFinder;
    private final ResponseCreator<T, RESP> responseCreator;
    
    public GenericPageAggregator(
            IAggregationKeyGenerator keyGenerator,
            IAggregationDimensionProvider<T> dimensionProvider,
            IMetricFinder<META> metricFinder,
            ResponseCreator<T, RESP> responseCreator) {
        
        this.finalResult = new ConcurrentHashMap<>(16);
        this.keyGenerator = keyGenerator;
        this.dimensionProvider = dimensionProvider;
        this.metricFinder = metricFinder;
        this.responseCreator = responseCreator;
    }
    
    @Override
    public void aggregatePage(List<T> pageData, CTX context) {
        if (pageData == null || pageData.isEmpty()) {
            return;
        }
        
        META metadata = context.getMetadata();
        
        for (T measureData : pageData) {
            try {
                // 1. 通过接口提取维度信息
                String periodId = dimensionProvider.extractPeriodId(measureData);
                String orgCode = dimensionProvider.extractOrgCode(measureData);
                String domainCode = dimensionProvider.extractDomainCode(measureData);
                String measureCode = dimensionProvider.extractMeasureCode(measureData);
                
                // 2. 查找指标编码
                String metricCode = metricFinder.findMetricCode(measureCode, metadata);
                
                if (periodId == null || metricCode == null) {
                    continue;
                }
                
                // 3. 获取或创建响应对象
                RESP respVO = finalResult.computeIfAbsent(periodId, k -> 
                    responseCreator.createResponse(periodId));
                
                // 4. 生成聚合Key
                String key = keyGenerator.generateKey(metricCode, orgCode, domainCode);
                
                // 5. 添加度量数据
                responseCreator.addMeasureData(respVO, key, measureData);
                
            } catch (Exception e) {
                logger.error("聚合数据失败", e);
            }
        }
    }
    
    @Override
    public Map<String, RESP> getFinalResult() {
        return new HashMap<>(finalResult);
    }
    
    @Override
    public void clear() {
        finalResult.clear();
    }
}

/**
 * 响应创建器接口
 */
@FunctionalInterface
public interface ResponseCreator<T extends IMeasureData, RESP extends IAggregationResponse> {
    
    /**
     * 创建响应对象
     */
    RESP createResponse(String periodId);
    
    /**
     * 添加度量数据到响应对象
     */
    default void addMeasureData(RESP response, String key, T measureData) {
        // 默认实现，子类可覆盖
        if (response instanceof OpMetricDataRespVO) {
            OpMetricDataRespVO opResp = (OpMetricDataRespVO) response;
            
            List<MeasureDataVO> measureList = opResp.getMeasureMap()
                .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            
            if (measureData instanceof EnhancedMeasureDataVO) {
                measureList.add(toStandardVO((EnhancedMeasureDataVO) measureData));
            }
        }
    }
    
    /**
     * 转换为标准VO
     */
    default MeasureDataVO toStandardVO(EnhancedMeasureDataVO enhanced) {
        MeasureDataVO vo = new MeasureDataVO();
        vo.setMeasureCode(enhanced.getMeasureCode());
        vo.setUnit(enhanced.getUnit());
        vo.setOriginValue(enhanced.getOriginValue());
        vo.setFixedValue(enhanced.getFixedValue());
        vo.setCurrency(enhanced.getCurrency());
        return vo;
    }
}

/**
 * 度量响应创建器实现
 */
public class MeasureResponseCreator 
        implements ResponseCreator<EnhancedMeasureDataVO, OpMetricDataRespVO> {
    
    @Override
    public OpMetricDataRespVO createResponse(String periodId) {
        OpMetricDataRespVO resp = new OpMetricDataRespVO();
        resp.setPeriodId(periodId);
        resp.setMeasureMap(new ConcurrentHashMap<>(64));
        return resp;
    }
    
    @Override
    public void addMeasureData(OpMetricDataRespVO response, String key, 
                                EnhancedMeasureDataVO measureData) {
        List<MeasureDataVO> measureList = response.getMeasureMap()
            .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        
        measureList.add(toStandardVO(measureData));
    }
}

/**
 * ==================== 第六层：改进的过滤器（基于接口） ====================
 */

/**
 * 通用数据过滤器接口（基于接口）
 */
public interface IDataFilter<T, CTX extends IAggregationContext<?, ?>> {
    
    String getName();
    
    default int getOrder() {
        return 100;
    }
    
    boolean test(T data, CTX context);
}

/**
 * 基于接口的过滤器示例
 */
@Component
public class InterfaceBasedOrgFilter<T extends IRawData, CTX extends IAggregationContext<?, ?>> 
        implements IDataFilter<T, CTX> {
    
    @Override
    public String getName() {
        return "InterfaceBasedOrgFilter";
    }
    
    @Override
    public int getOrder() {
        return 10;
    }
    
    @Override
    public boolean test(T data, CTX context) {
        if (!(context.getRequest() instanceof IAggregationRequest)) {
            return true;
        }
        
        // 从接口获取组织编码
        String orgCode = data.getOrgCode();
        
        // 这里需要具体的请求类型来获取过滤条件
        // 可以通过上下文属性传递
        List<String> allowedOrgCodes = context.getAttribute("allowedOrgCodes");
        
        if (allowedOrgCodes == null || allowedOrgCodes.isEmpty()) {
            return true;
        }
        
        return allowedOrgCodes.contains(orgCode);
    }
}

/**
 * ==================== 第七层：改进的转换器（基于接口） ====================
 */

/**
 * 通用数据转换器接口（基于接口）
 */
public interface IDataConverter<FROM, TO, CTX extends IAggregationContext<?, ?>> {
    
    String getName();
    
    TO convert(FROM source, CTX context);
    
    default List<TO> convertBatch(List<FROM> sources, CTX context) {
        return sources.stream()
            .map(source -> convert(source, context))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

/**
 * 基于接口的转换器示例
 */
@Component
public class InterfaceBasedMeasureConverter<FROM extends IRawData, 
                                              TO extends IMeasureData,
                                              CTX extends IAggregationContext<?, ?>> 
        implements IDataConverter<FROM, TO, CTX> {
    
    @Override
    public String getName() {
        return "InterfaceBasedMeasureConverter";
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public TO convert(FROM source, CTX context) {
        if (source == null) {
            return null;
        }
        
        // 创建增强的度量数据
        EnhancedMeasureDataVO vo = new EnhancedMeasureDataVO();
        
        // 从接口获取维度信息
        vo.setPeriodId(source.getPeriodId());
        vo.setOrgCode(source.getOrgCode());
        vo.setDomainCode(source.getDomainCode());
        
        // 如果是 MeasureRawData，获取其他信息
        if (source instanceof MeasureRawData) {
            MeasureRawData rawData = (MeasureRawData) source;
            
            IMetadata metadata = context.getMetadata();
            if (metadata instanceof MeasureMetadata) {
                MeasureMetadata measureMetadata = (MeasureMetadata) metadata;
                MeasureInfo measureInfo = measureMetadata.getMeasureMap()
                    .get(rawData.getMeasureCode());
                
                if (measureInfo != null) {
                    vo.setMeasureCode(rawData.getMeasureCode());
                    vo.setUnit(measureInfo.getUnit());
                    vo.setCurrency(measureInfo.getCurrency());
                    
                    String originValue = rawData.getValue().toPlainString();
                    vo.setOriginValue(originValue);
                    
                    Integer precision = measureInfo.getPrecision();
                    if (precision != null && precision >= 0) {
                        BigDecimal rounded = rawData.getValue()
                            .setScale(precision, RoundingMode.HALF_UP);
                        vo.setFixedValue(rounded.toPlainString());
                    } else {
                        vo.setFixedValue(originValue);
                    }
                }
            }
        }
        
        return (TO) vo;
    }
}

// ============= 基于接口的完整使用示例 =============

/**
 * ==================== 聚合器工厂 ====================
 */

/**
 * 聚合器工厂（基于接口）
 */
@Component
public class AggregatorFactory {
    
    @Autowired
    private ObjectPoolManager poolManager;
    
    /**
     * 创建度量数据聚合器
     */
    public IPageDataAggregator<EnhancedMeasureDataVO, OpMetricDataRespVO, MeasureAggregationContext> 
            createMeasureAggregator() {
        
        // 1. 创建Key生成器（使用对象池优化）
        IAggregationKeyGenerator keyGenerator = new DefaultAggregationKeyGenerator();
        if (poolManager != null) {
            // 如果有对象池管理器，注入它
            try {
                Field field = DefaultAggregationKeyGenerator.class.getDeclaredField("poolManager");
                field.setAccessible(true);
                field.set(keyGenerator, poolManager);
            } catch (Exception e) {
                // 忽略，使用默认实现
            }
        }
        
        // 2. 创建维度提供者
        IAggregationDimensionProvider<EnhancedMeasureDataVO> dimensionProvider = 
            new DefaultDimensionProvider<>();
        
        // 3. 创建指标查找器
        IMetricFinder<MeasureMetadata> metricFinder = new DefaultMetricFinder();
        
        // 4. 创建响应创建器
        ResponseCreator<EnhancedMeasureDataVO, OpMetricDataRespVO> responseCreator = 
            new MeasureResponseCreator();
        
        // 5. 组装聚合器
        return new GenericPageAggregator<>(
            keyGenerator, 
            dimensionProvider, 
            metricFinder, 
            responseCreator
        );
    }
    
    /**
     * 创建自定义聚合器（用户可以自己实现各个接口）
     */
    public <T extends IMeasureData, 
            RESP extends IAggregationResponse, 
            META extends IMetadata,
            CTX extends IAggregationContext<?, META>> 
    IPageDataAggregator<T, RESP, CTX> createCustomAggregator(
            IAggregationKeyGenerator keyGenerator,
            IAggregationDimensionProvider<T> dimensionProvider,
            IMetricFinder<META> metricFinder,
            ResponseCreator<T, RESP> responseCreator) {
        
        return new GenericPageAggregator<>(
            keyGenerator, 
            dimensionProvider, 
            metricFinder, 
            responseCreator
        );
    }
}

/**
 * ==================== 改进的编排器（基于接口） ====================
 */

/**
 * 基于接口的数据聚合编排器
 */
@Component
public class InterfaceBasedDataAggregationOrchestrator<REQ extends IAggregationRequest,
                                                         RESP extends IAggregationResponse,
                                                         META extends IMetadata> {
    
    private final Logger logger = LoggerFactory.getLogger(InterfaceBasedDataAggregationOrchestrator.class);
    
    @Autowired
    private StreamApiOrchestrator<MeasureRawData, MeasureAggregationContext> apiOrchestrator;
    
    @Autowired
    private FilterFactory filterFactory;
    
    @Autowired
    private ConverterFactory converterFactory;
    
    @Autowired
    private AggregatorFactory aggregatorFactory;
    
    /**
     * 执行聚合（泛型版本）
     */
    public Map<String, RESP> executeAggregation(
            IAggregationContext<REQ, META> context,
            List<ApiOrchestrationConfig> apiConfigs,
            Map<String, IStreamApiExecutor<MeasureRawData, MeasureAggregationContext>> apiExecutors,
            IPageDataAggregator<EnhancedMeasureDataVO, RESP, ?> aggregator) 
            throws ApplicationException {
        
        String sceneType = context.getSceneType();
        
        // 1. 创建过滤器链
        FilterChain<MeasureRawData, MeasureAggregationContext> filterChain = 
            filterFactory.createFilterChain(sceneType, MeasureRawData.class);
        
        // 2. 创建转换器
        IDataConverter<MeasureRawData, EnhancedMeasureDataVO, MeasureAggregationContext> converter = 
            converterFactory.getConverter(sceneType);
        
        // 3. 创建组合页处理器
        CompositePageProcessor<MeasureRawData, EnhancedMeasureDataVO, MeasureAggregationContext> pageProcessor = 
            new CompositePageProcessor<>(filterChain, converter, 
                (PageDataAggregator<EnhancedMeasureDataVO, MeasureAggregationContext>) aggregator);
        
        // 4. 执行 API 编排
        apiOrchestrator.orchestrateWithPageProcessor(
            apiConfigs, apiExecutors, (MeasureAggregationContext) context, pageProcessor);
        
        // 5. 返回最终聚合结果
        return aggregator.getFinalResult();
    }
}

/**
 * ==================== 改进的服务实现（基于接口） ====================
 */

/**
 * 基于接口的指标数据服务
 */
@Service
public class InterfaceBasedMetricDataService {
    
    private final Logger logger = LoggerFactory.getLogger(InterfaceBasedMetricDataService.class);
    
    @Autowired
    private FixedMetadataService metadataService;
    
    @Autowired
    private SceneConfigService sceneConfigService;
    
    @Autowired
    private StreamApiExecutorRegistry apiExecutorRegistry;
    
    @Autowired
    private InterfaceBasedDataAggregationOrchestrator<MeasureReqVO, OpMetricDataRespVO, MeasureMetadata> orchestrator;
    
    @Autowired
    private AggregatorFactory aggregatorFactory;
    
    /**
     * 获取度量数据（使用接口）
     */
    public List<OpMetricDataRespVO> getMeasures(MeasureReqVO reqVO) {
        
        try {
            // 1. 参数验证
            validateRequest(reqVO);
            
            // 2. 获取元数据
            MeasureMetadata metadata = metadataService.getMetadata();
            
            // 3. 构建上下文
            MeasureAggregationContext context = new MeasureAggregationContext(reqVO, metadata);
            
            // 设置过滤条件到上下文（供过滤器使用）
            context.setAttribute("allowedOrgCodes", reqVO.getOrgCodes());
            context.setAttribute("allowedDomainCodes", reqVO.getDomainCodes());
            context.setAttribute("allowedPeriodIds", reqVO.getPeriodIds());
            
            // 4. 获取场景配置
            String sceneType = reqVO.getSceneType();
            List<ApiOrchestrationConfig> apiConfigs = sceneConfigService.getApiConfigs(sceneType);
            
            // 5. 获取 API 执行器
            Map<String, IStreamApiExecutor<MeasureRawData, MeasureAggregationContext>> executors = 
                apiExecutorRegistry.getExecutorsForScene(sceneType);
            
            // 6. 创建聚合器（基于接口）
            IPageDataAggregator<EnhancedMeasureDataVO, OpMetricDataRespVO, MeasureAggregationContext> aggregator = 
                aggregatorFactory.createMeasureAggregator();
            
            // 7. 执行聚合
            Map<String, OpMetricDataRespVO> resultMap = 
                orchestrator.executeAggregation(context, apiConfigs, executors, aggregator);
            
            // 8. 转换为列表返回
            return new ArrayList<>(resultMap.values());
            
        } catch (Exception e) {
            logger.error("获取度量数据失败", e);
            throw new RuntimeException("获取度量数据失败", e);
        }
    }
    
    private void validateRequest(MeasureReqVO reqVO) {
        if (reqVO == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }
        
        if (reqVO.getSceneType() == null || reqVO.getSceneType().trim().isEmpty()) {
            throw new IllegalArgumentException("场景类型不能为空");
        }
        
        if (reqVO.getPeriodIds() == null || reqVO.getPeriodIds().isEmpty()) {
            throw new IllegalArgumentException("会计期不能为空");
        }
    }
}

/**
 * ==================== 扩展示例：销售数据聚合 ====================
 */

/**
 * 销售请求
 */
@Data
public class SalesReqVO implements IAggregationRequest {
    private String sceneType;
    private List<String> periodIds;
    private List<String> productCodes;
    private List<String> regionCodes;
    
    @Override
    public String getSceneType() {
        return sceneType;
    }
    
    @Override
    public List<String> getPeriodIds() {
        return periodIds;
    }
}

/**
 * 销售响应
 */
@Data
public class SalesRespVO implements IAggregationResponse {
    private String periodId;
    private Map<String, List<SalesDataVO>> salesMap;
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public void setPeriodId(String periodId) {
        this.periodId = periodId;
    }
}

/**
 * 销售原始数据
 */
@Data
public class SalesRawData implements IRawData {
    private String periodId;
    private String productCode;
    private String regionCode;
    private BigDecimal amount;
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public String getOrgCode() {
        return regionCode;  // 用regionCode代替orgCode
    }
    
    @Override
    public String getDomainCode() {
        return productCode;  // 用productCode代替domainCode
    }
}

/**
 * 销售度量数据
 */
@Data
public class SalesDataVO implements IMeasureData {
    private String periodId;
    private String productCode;
    private String regionCode;
    private String amount;
    
    @Override
    public String getMeasureCode() {
        return "SALES_AMOUNT";
    }
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public String getOrgCode() {
        return regionCode;
    }
    
    @Override
    public String getDomainCode() {
        return productCode;
    }
}

/**
 * 销售元数据
 */
@Data
public class SalesMetadata implements IMetadata {
    private Map<String, ProductInfo> productMap;
    private Map<String, RegionInfo> regionMap;
}

/**
 * 销售数据转换器
 */
@Component
public class SalesDataConverter 
        implements IDataConverter<SalesRawData, SalesDataVO, IAggregationContext<?, ?>> {
    
    @Override
    public String getName() {
        return "SalesDataConverter";
    }
    
    @Override
    public SalesDataVO convert(SalesRawData source, IAggregationContext<?, ?> context) {
        if (source == null) {
            return null;
        }
        
        SalesDataVO vo = new SalesDataVO();
        vo.setPeriodId(source.getPeriodId());
        vo.setProductCode(source.getProductCode());
        vo.setRegionCode(source.getRegionCode());
        vo.setAmount(source.getAmount().toPlainString());
        
        return vo;
    }
}

/**
 * 销售响应创建器
 */
public class SalesResponseCreator implements ResponseCreator<SalesDataVO, SalesRespVO> {
    
    @Override
    public SalesRespVO createResponse(String periodId) {
        SalesRespVO resp = new SalesRespVO();
        resp.setPeriodId(periodId);
        resp.setSalesMap(new ConcurrentHashMap<>());
        return resp;
    }
    
    @Override
    public void addMeasureData(SalesRespVO response, String key, SalesDataVO salesData) {
        List<SalesDataVO> salesList = response.getSalesMap()
            .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        salesList.add(salesData);
    }
}

/**
 * 销售指标查找器
 */
public class SalesMetricFinder implements IMetricFinder<SalesMetadata> {
    
    @Override
    public String findMetricCode(String measureCode, SalesMetadata metadata) {
        // 销售数据的指标查找逻辑
        return "SALES";  // 简化示例
    }
}

/**
 * 销售数据服务
 */
@Service
public class SalesDataService {
    
    @Autowired
    private AggregatorFactory aggregatorFactory;
    
    public List<SalesRespVO> getSalesData(SalesReqVO reqVO) {
        
        // 1. 准备组件
        IAggregationKeyGenerator keyGenerator = new DefaultAggregationKeyGenerator();
        IAggregationDimensionProvider<SalesDataVO> dimensionProvider = new DefaultDimensionProvider<>();
        IMetricFinder<SalesMetadata> metricFinder = new SalesMetricFinder();
        ResponseCreator<SalesDataVO, SalesRespVO> responseCreator = new SalesResponseCreator();
        
        // 2. 创建聚合器
        IPageDataAggregator<SalesDataVO, SalesRespVO, ?> aggregator = 
            aggregatorFactory.createCustomAggregator(
                keyGenerator, dimensionProvider, metricFinder, responseCreator);
        
        // 3. 执行聚合逻辑...
        
        return new ArrayList<>(aggregator.getFinalResult().values());
    }
}

/**
 * ==================== 使用示例 ====================
 */

@Component
public class InterfaceBasedUsageExample {
    
    @Autowired
    private InterfaceBasedMetricDataService metricDataService;
    
    @Autowired
    private SalesDataService salesDataService;
    
    /**
     * 示例1：使用度量数据服务
     */
    public void exampleMeasureData() {
        // 构建请求（实现了 IAggregationRequest 接口）
        MeasureReqVO request = new MeasureReqVO();
        request.setSceneType("SCENE_A");
        request.setPeriodIds(Arrays.asList("2024-01", "2024-02"));
        request.setMetricCodes(Arrays.asList("REVENUE", "COST"));
        request.setOrgCodes(Arrays.asList("ORG001", "ORG002"));
        request.setDomainCodes(Arrays.asList("FINANCE"));
        
        // 调用服务（返回实现了 IAggregationResponse 接口的对象）
        List<OpMetricDataRespVO> results = metricDataService.getMeasures(request);
        
        // 处理结果
        for (IAggregationResponse response : results) {
            System.out.println("会计期: " + response.getPeriodId());
            
            if (response instanceof OpMetricDataRespVO) {
                OpMetricDataRespVO opResp = (OpMetricDataRespVO) response;
                System.out.println("维度组合数: " + opResp.getMeasureMap().size());
            }
        }
    }
    
    /**
     * 示例2：使用销售数据服务（完全不同的实现）
     */
    public void exampleSalesData() {
        // 构建销售请求（同样实现了 IAggregationRequest 接口）
        SalesReqVO request = new SalesReqVO();
        request.setSceneType("SALES_SCENE");
        request.setPeriodIds(Arrays.asList("2024-01"));
        request.setProductCodes(Arrays.asList("PROD001", "PROD002"));
        request.setRegionCodes(Arrays.asList("EAST", "WEST"));
        
        // 调用销售服务
        List<SalesRespVO> results = salesDataService.getSalesData(request);
        
        // 处理结果
        for (IAggregationResponse response : results) {
            System.out.println("会计期: " + response.getPeriodId());
            
            if (response instanceof SalesRespVO) {
                SalesRespVO salesResp = (SalesRespVO) response;
                System.out.println("销售维度数: " + salesResp.getSalesMap().size());
            }
        }
    }
}

/**
 * ==================== 配置示例 ====================
 */

@Configuration
public class InterfaceBasedAggregationConfiguration {
    
    /**
     * 注册默认的Key生成器
     */
    @Bean
    public IAggregationKeyGenerator defaultKeyGenerator(ObjectPoolManager poolManager) {
        DefaultAggregationKeyGenerator generator = new DefaultAggregationKeyGenerator();
        // poolManager 会自动注入
        return generator;
    }
    
    /**
     * 注册聚合器工厂
     */
    @Bean
    public AggregatorFactory aggregatorFactory() {
        return new AggregatorFactory();
    }
}

# 基于接口的架构设计指南

## ? 设计理念

### 核心思想
**面向接口编程**：所有核心组件都基于接口定义，业务方只需实现接口即可快速接入聚合框架。

### 设计分层

```
┌─────────────────────────────────────────────┐
│  第一层：核心接口定义                        │
│  - IAggregationRequest (请求接口)            │
│  - IAggregationResponse (响应接口)           │
│  - IMetadata (元数据接口)                    │
│  - IRawData (原始数据接口)                   │
│  - IMeasureData (度量数据接口)               │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│  第二层：策略接口定义                        │
│  - IAggregationKeyGenerator (Key生成器)      │
│  - IAggregationDimensionProvider (维度提供者)│
│  - IMetricFinder (指标查找器)                │
│  - ResponseCreator (响应创建器)              │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│  第三层：默认实现                             │
│  - DefaultAggregationKeyGenerator            │
│  - DefaultDimensionProvider                  │
│  - DefaultMetricFinder                       │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│  第四层：业务实现                             │
│  - MeasureReqVO (实现 IAggregationRequest)   │
│  - OpMetricDataRespVO (实现 IAggregationResp)│
│  - MeasureRawData (实现 IRawData)            │
│  - EnhancedMeasureDataVO (实现 IMeasureData) │
└─────────────────────────────────────────────┘
```

---

## ? 核心接口说明

### 1. IAggregationRequest（请求接口）

**作用**：定义聚合请求的基本结构

```java
public interface IAggregationRequest {
    String getSceneType();        // 场景类型
    List<String> getPeriodIds();  // 会计期列表
}
```

**实现示例**：
```java
@Data
public class MeasureReqVO implements IAggregationRequest {
    private String sceneType;
    private List<String> periodIds;
    private List<String> metricCodes;
    private List<String> orgCodes;
    // ... 其他业务字段
    
    @Override
    public String getSceneType() {
        return sceneType;
    }
    
    @Override
    public List<String> getPeriodIds() {
        return periodIds;
    }
}
```

---

### 2. IAggregationResponse（响应接口）

**作用**：定义聚合响应的基本结构

```java
public interface IAggregationResponse {
    String getPeriodId();           // 获取会计期
    void setPeriodId(String periodId);  // 设置会计期
}
```

**实现示例**：
```java
@Data
public class OpMetricDataRespVO implements IAggregationResponse {
    private String periodId;
    private Map<String, List<MeasureDataVO>> measureMap;
    
    @Override
    public String getPeriodId() {
        return periodId;
    }
    
    @Override
    public void setPeriodId(String periodId) {
        this.periodId = periodId;
    }
}
```

---

### 3. IRawData（原始数据接口）

**作用**：定义从API返回的原始数据的基本结构

```java
public interface IRawData {
    String getPeriodId();    // 会计期
    String getOrgCode();     // 组织编码
    String getDomainCode();  // 领域编码
}
```

**实现示例**：
```java
@Data
public class MeasureRawData implements IRawData {
    private String periodId;
    private String orgCode;
    private String domainCode;
    private String measureCode;
    private BigDecimal value;
    
    @Override
    public String getPeriodId() { return periodId; }
    
    @Override
    public String getOrgCode() { return orgCode; }
    
    @Override
    public String getDomainCode() { return domainCode; }
}
```

---

### 4. IMeasureData（度量数据接口）

**作用**：定义转换后的度量数据的基本结构

```java
public interface IMeasureData {
    String getMeasureCode();  // 度量编码
    String getPeriodId();     // 会计期
    String getOrgCode();      // 组织编码
    String getDomainCode();   // 领域编码
}
```

**实现示例**：
```java
@Data
public class EnhancedMeasureDataVO implements IMeasureData {
    // 度量信息
    private String measureCode;
    private String unit;
    private String originValue;
    private String fixedValue;
    
    // 维度信息
    private String periodId;
    private String orgCode;
    private String domainCode;
    
    @Override
    public String getMeasureCode() { return measureCode; }
    
    @Override
    public String getPeriodId() { return periodId; }
    
    @Override
    public String getOrgCode() { return orgCode; }
    
    @Override
    public String getDomainCode() { return domainCode; }
}
```

---

## ? 策略接口说明

### 1. IAggregationKeyGenerator（Key生成器）

**作用**：生成和解析聚合Key

```java
public interface IAggregationKeyGenerator {
    // 生成Key：metricCode:::orgCode:::domainCode
    String generateKey(String metricCode, String orgCode, String domainCode);
    
    // 解析Key：返回 [metricCode, orgCode, domainCode]
    String[] parseKey(String key);
}
```

**默认实现**（使用对象池优化）：
```java
@Component
public class DefaultAggregationKeyGenerator implements IAggregationKeyGenerator {
    
    @Autowired
    private ObjectPoolManager poolManager;
    
    @Override
    public String generateKey(String metricCode, String orgCode, String domainCode) {
        // 使用对象池优化字符串拼接
        try (PooledObject<StringBuilder> pooled = 
                poolManager.getStringBuilderPool().acquirePooled()) {
            StringBuilder sb = pooled.get();
            sb.append(metricCode != null ? metricCode : "")
              .append(":::")
              .append(orgCode != null ? orgCode : "")
              .append(":::")
              .append(domainCode != null ? domainCode : "");
            return sb.toString();
        }
    }
    
    @Override
    public String[] parseKey(String key) {
        return key != null ? key.split(":::", 3) : new String[3];
    }
}
```

**自定义实现示例**：
```java
public class CustomKeyGenerator implements IAggregationKeyGenerator {
    
    @Override
    public String generateKey(String metricCode, String orgCode, String domainCode) {
        // 自定义格式：metric|org|domain
        return String.join("|", metricCode, orgCode, domainCode);
    }
    
    @Override
    public String[] parseKey(String key) {
        return key.split("\\|", 3);
    }
}
```

---

### 2. IAggregationDimensionProvider（维度提供者）

**作用**：从数据中提取聚合维度

```java
public interface IAggregationDimensionProvider<T> {
    String extractPeriodId(T data);
    String extractOrgCode(T data);
    String extractDomainCode(T data);
    String extractMeasureCode(T data);
}
```

**默认实现**（基于IMeasureData接口）：
```java
public class DefaultDimensionProvider<T extends IMeasureData> 
        implements IAggregationDimensionProvider<T> {
    
    @Override
    public String extractPeriodId(T data) {
        return data != null ? data.getPeriodId() : null;
    }
    
    @Override
    public String extractOrgCode(T data) {
        return data != null ? data.getOrgCode() : null;
    }
    
    @Override
    public String extractDomainCode(T data) {
        return data != null ? data.getDomainCode() : null;
    }
    
    @Override
    public String extractMeasureCode(T data) {
        return data != null ? data.getMeasureCode() : null;
    }
}
```

**自定义实现示例**（从上下文获取维度）：
```java
public class ContextBasedDimensionProvider<T> 
        implements IAggregationDimensionProvider<T> {
    
    private final IAggregationContext<?, ?> context;
    
    public ContextBasedDimensionProvider(IAggregationContext<?, ?> context) {
        this.context = context;
    }
    
    @Override
    public String extractPeriodId(T data) {
        // 从上下文获取
        return context.getAttribute("currentPeriodId");
    }
    
    // ... 其他方法类似
}
```

---

### 3. IMetricFinder（指标查找器）

**作用**：通过度量编码查找对应的指标编码

```java
public interface IMetricFinder<META extends IMetadata> {
    String findMetricCode(String measureCode, META metadata);
}
```

**默认实现**：
```java
public class DefaultMetricFinder implements IMetricFinder<MeasureMetadata> {
    
    @Override
    public String findMetricCode(String measureCode, MeasureMetadata metadata) {
        if (metadata == null || metadata.getMetricToMeasuresCache() == null) {
            return null;
        }
        return metadata.getMetricToMeasuresCache().get(measureCode);
    }
}
```

**自定义实现示例**：
```java
public class SalesMetricFinder implements IMetricFinder<SalesMetadata> {
    
    @Override
    public String findMetricCode(String measureCode, SalesMetadata metadata) {
        // 销售数据的查找逻辑
        if ("AMOUNT".equals(measureCode)) {
            return "SALES";
        }
        return "UNKNOWN";
    }
}
```

---

### 4. ResponseCreator（响应创建器）

**作用**：创建响应对象并添加度量数据

```java
@FunctionalInterface
public interface ResponseCreator<T extends IMeasureData, RESP extends IAggregationResponse> {
    
    // 创建响应对象
    RESP createResponse(String periodId);
    
    // 添加度量数据（默认实现）
    default void addMeasureData(RESP response, String key, T measureData) {
        // 子类可覆盖
    }
}
```

**实现示例**：
```java
public class MeasureResponseCreator 
        implements ResponseCreator<EnhancedMeasureDataVO, OpMetricDataRespVO> {
    
    @Override
    public OpMetricDataRespVO createResponse(String periodId) {
        OpMetricDataRespVO resp = new OpMetricDataRespVO();
        resp.setPeriodId(periodId);
        resp.setMeasureMap(new ConcurrentHashMap<>());
        return resp;
    }
    
    @Override
    public void addMeasureData(OpMetricDataRespVO response, String key, 
                                EnhancedMeasureDataVO measureData) {
        List<MeasureDataVO> measureList = response.getMeasureMap()
            .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        
        // 转换并添加
        measureList.add(toStandardVO(measureData));
    }
    
    private MeasureDataVO toStandardVO(EnhancedMeasureDataVO enhanced) {
        MeasureDataVO vo = new MeasureDataVO();
        vo.setMeasureCode(enhanced.getMeasureCode());
        vo.setUnit(enhanced.getUnit());
        vo.setOriginValue(enhanced.getOriginValue());
        vo.setFixedValue(enhanced.getFixedValue());
        vo.setCurrency(enhanced.getCurrency());
        return vo;
    }
}
```

---

## ? 如何使用

### 场景1：使用默认实现

**步骤1：实现基本接口**
```java
// 1. 请求实现 IAggregationRequest
@Data
public class MyRequest implements IAggregationRequest {
    private String sceneType;
    private List<String> periodIds;
    // ... 业务字段
}

// 2. 响应实现 IAggregationResponse
@Data
public class MyResponse implements IAggregationResponse {
    private String periodId;
    private Map<String, List<MyData>> dataMap;
}

// 3. 原始数据实现 IRawData
@Data
public class MyRawData implements IRawData {
    private String periodId;
    private String orgCode;
    private String domainCode;
    // ... 业务字段
}

// 4. 度量数据实现 IMeasureData
@Data
public class MyMeasureData implements IMeasureData {
    private String measureCode;
    private String periodId;
    private String orgCode;
    private String domainCode;
    // ... 业务字段
}
```

**步骤2：使用工厂创建聚合器**
```java
@Autowired
private AggregatorFactory aggregatorFactory;

// 使用默认实现
IPageDataAggregator<MyMeasureData, MyResponse, MyContext> aggregator = 
    aggregatorFactory.createMeasureAggregator();
```

**步骤3：执行聚合**
```java
List<MyMeasureData> pageData = ...;
aggregator.aggregatePage(pageData, context);

Map<String, MyResponse> result = aggregator.getFinalResult();
```

---

### 场景2：自定义实现

**步骤1：实现自定义策略**
```java
// 1. 自定义Key生成器
public class MyKeyGenerator implements IAggregationKeyGenerator {
    @Override
    public String generateKey(String metricCode, String orgCode, String domainCode) {
        // 自定义格式
        return metricCode + "|" + orgCode + "|" + domainCode;
    }
    
    @Override
    public String[] parseKey(String key) {
        return key.split("\\|", 3);
    }
}

// 2. 自定义维度提供者
public class MyDimensionProvider implements IAggregationDimensionProvider<MyData> {
    @Override
    public String extractPeriodId(MyData data) {
        // 自定义提取逻辑
        return data.getCustomPeriod();
    }
    // ... 其他方法
}

// 3. 自定义指标查找器
public class MyMetricFinder implements IMetricFinder<MyMetadata> {
    @Override
    public String findMetricCode(String measureCode, MyMetadata metadata) {
        // 自定义查找逻辑
        return metadata.findMetric(measureCode);
    }
}

// 4. 自定义响应创建器
public class MyResponseCreator implements ResponseCreator<MyData, MyResponse> {
    @Override
    public MyResponse createResponse(String periodId) {
        MyResponse resp = new MyResponse();
        resp.setPeriodId(periodId);
        return resp;
    }
    
    @Override
    public void addMeasureData(MyResponse response, String key, MyData data) {
        // 自定义添加逻辑
    }
}
```

**步骤2：组装自定义聚合器**
```java
// 创建自定义组件
IAggregationKeyGenerator keyGenerator = new MyKeyGenerator();
IAggregationDimensionProvider<MyData> dimensionProvider = new MyDimensionProvider();
IMetricFinder<MyMetadata> metricFinder = new MyMetricFinder();
ResponseCreator<MyData, MyResponse> responseCreator = new MyResponseCreator();

// 使用工厂创建自定义聚合器
IPageDataAggregator<MyData, MyResponse, MyContext> aggregator = 
    aggregatorFactory.createCustomAggregator(
        keyGenerator, dimensionProvider, metricFinder, responseCreator);
```

---

## ? 完整示例对比

### 度量数据聚合
```java
// 请求
MeasureReqVO request = new MeasureReqVO();  // implements IAggregationRequest
request.setSceneType("SCENE_A");
request.setPeriodIds(Arrays.asList("2024-01"));

// 执行
List<OpMetricDataRespVO> results = metricDataService.getMeasures(request);

// 处理（使用接口）
for (IAggregationResponse response : results) {
    System.out.println("Period: " + response.getPeriodId());
}
```

### 销售数据聚合（完全不同的业务）
```java
// 请求
SalesReqVO request = new SalesReqVO();  // implements IAggregationRequest
request.setSceneType("SALES_SCENE");
request.setPeriodIds(Arrays.asList("2024-01"));

// 执行
List<SalesRespVO> results = salesDataService.getSalesData(request);

// 处理（使用相同的接口）
for (IAggregationResponse response : results) {
    System.out.println("Period: " + response.getPeriodId());
}
```

**关键点**：两个完全不同的业务，使用相同的接口和框架！

---

## ? 优势总结

### 1. 高度解耦
- 业务逻辑只依赖接口
- 实现可以随时替换
- 框架代码与业务代码分离

### 2. 易于扩展
- 新增业务只需实现接口
- 不需要修改框架代码
- 支持多种业务场景

### 3. 易于测试
- 可以轻松 Mock 接口
- 单元测试更简单
- 集成测试更灵活

### 4. 代码复用
- 框架代码高度复用
- 默认实现开箱即用
- 自定义实现灵活可控

### 5. 类型安全
- 泛型保证类型安全
- 编译期发现错误
- IDE 智能提示

---

## ? 最佳实践

1. **优先使用默认实现**：除非有特殊需求，否则使用默认实现
2. **接口隔离**：每个接口只定义必要的方法
3. **组合优于继承**：通过组合不同的策略实现复杂功能
4. **明确职责**：每个接口只负责一件事
5. **文档完善**：为每个接口和实现提供清晰的文档

---

## ? 快速开始清单

- [ ] 定义业务数据类，实现基本接口（Request、Response、RawData、MeasureData）
- [ ] 选择使用默认实现还是自定义实现
- [ ] 如果自定义，实现策略接口（KeyGenerator、DimensionProvider、MetricFinder、ResponseCreator）
- [ ] 使用 AggregatorFactory 创建聚合器
- [ ] 集成到服务层，执行聚合
- [ ] 测试验证

只需要这6步，就可以快速接入聚合框架！

完美！现在我已经为您提供了一个完整的基于接口的优雅设计方案。让我总结一下核心要点：
? 基于接口的设计方案总结
? 提供的完整内容

基于接口的优雅设计方案 - 包含：

5个核心接口（Request、Response、Metadata、RawData、MeasureData）
4个策略接口（KeyGenerator、DimensionProvider、MetricFinder、ResponseCreator）
所有接口的默认实现
完整的业务实现示例


基于接口的完整使用示例 - 包含：

聚合器工厂（AggregatorFactory）
改进的编排器
改进的服务实现
扩展示例（销售数据聚合）


架构设计指南 - 包含：

详细的设计理念
每个接口的说明和示例
两种使用场景（默认实现 vs 自定义实现）
最佳实践建议



? 核心设计亮点
1. 5层接口体系

第1层：核心数据接口
├── IAggregationRequest（请求）
├── IAggregationResponse（响应）
├── IMetadata（元数据）
├── IRawData（原始数据）
└── IMeasureData（度量数据）

第2层：策略接口
├── IAggregationKeyGenerator（Key生成）
├── IAggregationDimensionProvider（维度提取）
├── IMetricFinder（指标查找）
└── ResponseCreator（响应创建）

第3层：默认实现
第4层：业务实现
第5层：服务层

2. 关键接口说明
IAggregationKeyGenerator - 聚合Key生成
public interface IAggregationKeyGenerator {
    // 生成Key（使用对象池优化）
    String generateKey(String metricCode, String orgCode, String domainCode);
    
    // 解析Key
    String[] parseKey(String key);
}

IAggregationDimensionProvider - 维度提取
public interface IAggregationDimensionProvider<T> {
    String extractPeriodId(T data);
    String extractOrgCode(T data);
    String extractDomainCode(T data);
    String extractMeasureCode(T data);
}
ResponseCreator - 响应创建
@FunctionalInterface
public interface ResponseCreator<T extends IMeasureData, RESP extends IAggregationResponse> {
    RESP createResponse(String periodId);
    default void addMeasureData(RESP response, String key, T measureData) {}
}

3. 两种使用方式
方式1：使用默认实现（推荐，快速开发）
// 只需实现基本接口
public class MyRequest implements IAggregationRequest { }
public class MyResponse implements IAggregationResponse { }
public class MyData implements IMeasureData { }

// 使用工厂创建聚合器
IPageDataAggregator aggregator = aggregatorFactory.createMeasureAggregator();

方式2：自定义实现（灵活，满足特殊需求）
// 实现策略接口
IAggregationKeyGenerator keyGen = new MyKeyGenerator();
IAggregationDimensionProvider dimProvider = new MyDimensionProvider();
IMetricFinder metricFinder = new MyMetricFinder();
ResponseCreator respCreator = new MyResponseCreator();

// 组装自定义聚合器
IPageDataAggregator aggregator = aggregatorFactory.createCustomAggregator(
    keyGen, dimProvider, metricFinder, respCreator);

? 设计优势

高度解耦：业务代码只依赖接口，不依赖具体实现
易于扩展：新增业务只需实现接口，无需修改框架
易于测试：可以轻松Mock接口进行单元测试
代码复用：框架代码高度复用，默认实现开箱即用
类型安全：泛型保证编译期类型安全

? 快速接入步骤

? 实现4个基本接口（Request、Response、RawData、MeasureData）
? 选择使用默认实现或自定义实现
? 使用 AggregatorFactory 创建聚合器
? 集成到服务层执行聚合

只需这4步，就能快速接入框架，并且保持了高度的灵活性和扩展性！