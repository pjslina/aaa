方案分析
核心问题：measureList 的构成 = 公共度量（全场景通用） + 动态度量（由 orgLevel + tabCode 决定），且度量是有序的元数据。
最佳实践选型：

度量元数据 → 数据库表 + 枚举兜底，避免硬编码
动态路由逻辑 → 策略模式 + Spring 注册中心，支持扩展不改代码
公共度量 → 组合优先于继承，抽取公共部分统一管理
有序性 → 数据库 sort_order 字段保证

完整代码
1. 数据库设计
-- 度量元数据表
CREATE TABLE card_measure_meta (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    measure_code VARCHAR(64) NOT NULL UNIQUE COMMENT '度量编码',
    measure_cn_name VARCHAR(128) NOT NULL COMMENT '中文名',
    measure_en_name VARCHAR(128) NOT NULL COMMENT '英文名',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序（全局顺序）',
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '度量元数据';

-- 场景度量映射表（orgLevel + tabCode → 哪些额外度量）
CREATE TABLE card_scene_measure_rel (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    org_level VARCHAR(16) NOT NULL COMMENT 'G0/G1/G2...',
    tab_code VARCHAR(64) NOT NULL COMMENT '场景编码',
    measure_code VARCHAR(64) NOT NULL COMMENT '度量编码',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '此场景下该度量的排序',
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_scene_measure (org_level, tab_code, measure_code)
) COMMENT '场景度量关系表';

-- 公共度量配置（所有场景均有，单独维护）
CREATE TABLE card_common_measure_rel (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    measure_code VARCHAR(64) NOT NULL UNIQUE COMMENT '度量编码',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    is_deleted TINYINT(1) NOT NULL DEFAULT 0
) COMMENT '公共度量（全场景通用）';

-- 初始化数据
INSERT INTO card_measure_meta (measure_code, measure_cn_name, measure_en_name, sort_order) VALUES
('BASELINE_Y', '基线_年', 'Baseline Y', 1),
('GLOBAL_G1_BEST_Y', 'Global G1最优_年', 'Global G1 Best Y', 2),
('GLOBAL_G2_BEST_Y', 'Global G2最优_年', 'Global G2 Best Y', 3),
('REGION_G2_BEST_Y', 'Region G2最优_年', 'Region G2 Best Y', 4);

INSERT INTO card_common_measure_rel (measure_code, sort_order) VALUES ('BASELINE_Y', 1);

INSERT INTO card_scene_measure_rel (org_level, tab_code, measure_code, sort_order) VALUES
('G0', 'A', 'GLOBAL_G1_BEST_Y', 1),
('G0', 'A', 'REGION_G2_BEST_Y', 2),
('G0', 'B', 'GLOBAL_G2_BEST_Y', 1),
('G0', 'B', 'REGION_G2_BEST_Y', 2),
('G1', 'B', 'GLOBAL_G2_BEST_Y', 1),
('G1', 'B', 'REGION_G2_BEST_Y', 2);

2. 枚举定义
// OrgLevelEnum.java
public enum OrgLevelEnum {
    G0("G0"), G1("G1"), G2("G2"), G3("G3"), G4("G4");

    private final String code;

    OrgLevelEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static OrgLevelEnum of(String code) {
        for (OrgLevelEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown orgLevel: " + code);
    }
}

3. DO / VO 实体
// CardMeasureMetaDO.java
@Data
public class CardMeasureMetaDO {
    private Long id;
    private String measureCode;
    private String measureCnName;
    private String measureEnName;
    private Integer sortOrder;
    private Integer isDeleted;
}

// CardCommonMeasureRelDO.java
@Data
public class CardCommonMeasureRelDO {
    private Long id;
    private String measureCode;
    private Integer sortOrder;
    private Integer isDeleted;
}

// CardSceneMeasureRelDO.java
@Data
public class CardSceneMeasureRelDO {
    private Long id;
    private String orgLevel;
    private String tabCode;
    private String measureCode;
    private Integer sortOrder;
    private Integer isDeleted;
}

// CardMeasureVO.java（已有，补全注解）
@Data
public class CardMeasureVO {
    private String measureCode;
    private String measureCnName;
    private String measureEnName;
}

// CardRespVO.java（已有）
@Data
public class CardRespVO {
    private Long categoryId;
    private String categoryName;
    private String mainUnit;
    private List<CardMetricVO> metricList;
    private List<CardMeasureVO> measureList;
}

4. Mapper 层
// CardMeasureMetaMapper.java
@Mapper
public interface CardMeasureMetaMapper {

    @Select("SELECT measure_code, measure_cn_name, measure_en_name, sort_order " +
            "FROM card_measure_meta WHERE is_deleted = 0")
    List<CardMeasureMetaDO> selectAll();

    @Select("<script>" +
            "SELECT measure_code, measure_cn_name, measure_en_name, sort_order " +
            "FROM card_measure_meta " +
            "WHERE is_deleted = 0 AND measure_code IN " +
            "<foreach item='code' collection='codes' open='(' separator=',' close=')'>" +
            "#{code}" +
            "</foreach>" +
            "ORDER BY sort_order ASC" +
            "</script>")
    List<CardMeasureMetaDO> selectByCodes(@Param("codes") Collection<String> codes);
}

// CardCommonMeasureRelMapper.java
@Mapper
public interface CardCommonMeasureRelMapper {

    @Select("SELECT measure_code, sort_order FROM card_common_measure_rel " +
            "WHERE is_deleted = 0 ORDER BY sort_order ASC")
    List<CardCommonMeasureRelDO> selectAllCommon();
}

// CardSceneMeasureRelMapper.java
@Mapper
public interface CardSceneMeasureRelMapper {

    @Select("SELECT measure_code, sort_order FROM card_scene_measure_rel " +
            "WHERE is_deleted = 0 AND org_level = #{orgLevel} AND tab_code = #{tabCode} " +
            "ORDER BY sort_order ASC")
    List<CardSceneMeasureRelDO> selectByScene(@Param("orgLevel") String orgLevel,
                                               @Param("tabCode") String tabCode);
}

5. 度量解析策略（Strategy Pattern）
// MeasureResolveContext.java
@Data
@Builder
public class MeasureResolveContext {
    private OrgLevelEnum orgLevel;
    private String tabCode;
}

// MeasureResolver.java — 策略接口
public interface MeasureResolver {
    /**
     * 是否支持该场景
     */
    boolean supports(MeasureResolveContext context);

    /**
     * 返回该场景下【额外】的度量编码（有序）
     * 公共度量由 MeasureAssembler 统一处理，这里只返回差异部分
     */
    List<String> resolveExtraMeasureCodes(MeasureResolveContext context);

    /**
     * 策略优先级（数字越小越优先，支持精确匹配 > 模糊匹配）
     */
    default int getOrder() {
        return Integer.MAX_VALUE;
    }
}

// DbDrivenMeasureResolver.java — 数据库驱动的通用策略（生产首选）
@Component
@Order(Ordered.LOWEST_PRECEDENCE)  // 兜底策略
public class DbDrivenMeasureResolver implements MeasureResolver {

    @Autowired
    private CardSceneMeasureRelMapper sceneMeasureRelMapper;

    @Override
    public boolean supports(MeasureResolveContext context) {
        // 通用兜底，所有场景都支持（数据库没配就返回空列表）
        return true;
    }

    @Override
    public List<String> resolveExtraMeasureCodes(MeasureResolveContext context) {
        List<CardSceneMeasureRelDO> rels = sceneMeasureRelMapper.selectByScene(
                context.getOrgLevel().getCode(), context.getTabCode());
        return rels.stream()
                   .map(CardSceneMeasureRelDO::getMeasureCode)
                   .collect(Collectors.toList());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

6. 度量缓存服务
// MeasureMetaCacheService.java
@Slf4j
@Service
public class MeasureMetaCacheService {

    @Autowired
    private CardMeasureMetaMapper measureMetaMapper;

    @Autowired
    private CardCommonMeasureRelMapper commonMeasureRelMapper;

    // 本地缓存：度量编码 -> VO（Spring Cache 亦可，此处用 Guava 更直观）
    private volatile Map<String, CardMeasureVO> measureMetaMap = Collections.emptyMap();
    private volatile List<String> commonMeasureCodes = Collections.emptyList();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 支持定时刷新 或 手动刷新（配合运维接口）
     * 生产建议：@Scheduled(cron = "0 0 2 * * ?") 低峰期刷新
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L)  // 30分钟刷新一次
    public void refresh() {
        try {
            List<CardMeasureMetaDO> allMeta = measureMetaMapper.selectAll();
            Map<String, CardMeasureVO> newMap = allMeta.stream()
                    .collect(Collectors.toMap(
                            CardMeasureMetaDO::getMeasureCode,
                            this::convertToVO,
                            (a, b) -> a,
                            LinkedHashMap::new
                    ));
            this.measureMetaMap = Collections.unmodifiableMap(newMap);

            List<CardCommonMeasureRelDO> commonRels = commonMeasureRelMapper.selectAllCommon();
            this.commonMeasureCodes = commonRels.stream()
                    .map(CardCommonMeasureRelDO::getMeasureCode)
                    .collect(Collectors.toUnmodifiableList());

            log.info("[MeasureMetaCache] refreshed, total={}, common={}",
                    newMap.size(), commonMeasureCodes.size());
        } catch (Exception e) {
            log.error("[MeasureMetaCache] refresh failed", e);
            // 刷新失败保留旧缓存，不抛出异常
        }
    }

    public CardMeasureVO getByCode(String measureCode) {
        return measureMetaMap.get(measureCode);
    }

    public List<String> getCommonMeasureCodes() {
        return commonMeasureCodes;
    }

    private CardMeasureVO convertToVO(CardMeasureMetaDO meta) {
        CardMeasureVO vo = new CardMeasureVO();
        vo.setMeasureCode(meta.getMeasureCode());
        vo.setMeasureCnName(meta.getMeasureCnName());
        vo.setMeasureEnName(meta.getMeasureEnName());
        return vo;
    }
}

7. 度量装配器（核心编排）
// MeasureAssembler.java
@Slf4j
@Component
public class MeasureAssembler {

    @Autowired
    private MeasureMetaCacheService metaCacheService;

    /**
     * 所有策略由 Spring 自动注入，按 @Order 排序
     */
    @Autowired
    private List<MeasureResolver> resolvers;

    /**
     * 构造指定场景的 measureList（公共度量在前，场景度量在后，均保持有序）
     *
     * @param orgLevel 组织层级
     * @param tabCode  场景编码
     * @return 有序、去重的度量 VO 列表
     */
    public List<CardMeasureVO> assembleMeasureList(OrgLevelEnum orgLevel, String tabCode) {
        MeasureResolveContext context = MeasureResolveContext.builder()
                .orgLevel(orgLevel)
                .tabCode(tabCode)
                .build();

        // 1. 公共度量（全场景通用，固定在前）
        List<String> commonCodes = metaCacheService.getCommonMeasureCodes();

        // 2. 找到第一个支持该场景的策略，获取额外度量
        List<String> extraCodes = resolvers.stream()
                .filter(r -> r.supports(context))
                .findFirst()
                .map(r -> r.resolveExtraMeasureCodes(context))
                .orElse(Collections.emptyList());

        // 3. 合并（公共在前 + 额外在后），LinkedHashSet 去重保序
        LinkedHashSet<String> orderedCodes = new LinkedHashSet<>();
        orderedCodes.addAll(commonCodes);
        orderedCodes.addAll(extraCodes);

        // 4. 从缓存中映射为 VO，过滤掉缓存中不存在的（异常保护）
        return orderedCodes.stream()
                .map(code -> {
                    CardMeasureVO vo = metaCacheService.getByCode(code);
                    if (vo == null) {
                        log.warn("[MeasureAssembler] measureCode={} not found in meta cache, skip", code);
                    }
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}

8. Service 层
// CardService.java
public interface CardService {
    List<CardRespVO> listCards(String orgLevel, String tabCode);
}
// CardServiceImpl.java
@Slf4j
@Service
public class CardServiceImpl implements CardService {

    @Autowired
    private MeasureAssembler measureAssembler;

    // 注入你的卡片基础数据查询逻辑（略，与本题无关）
    // @Autowired
    // private CardMetaMapper cardMetaMapper;

    @Override
    public List<CardRespVO> listCards(String orgLevelStr, String tabCode) {
        OrgLevelEnum orgLevel = OrgLevelEnum.of(orgLevelStr);

        // 1. 提前组装 measureList（同一次请求内所有卡片共享，只查一次）
        List<CardMeasureVO> measureList = measureAssembler.assembleMeasureList(orgLevel, tabCode);

        // 2. 查询卡片基础数据（示例，实际替换为真实查询）
        List<CardRespVO> cards = buildMockCards();

        // 3. 填充 measureList（所有卡片使用同一份度量列表）
        cards.forEach(card -> card.setMeasureList(measureList));

        return cards;
    }

    /** 示例卡片数据，实际替换为 DB 查询 */
    private List<CardRespVO> buildMockCards() {
        CardRespVO card = new CardRespVO();
        card.setCategoryId(1L);
        card.setCategoryName("示例卡片");
        card.setMainUnit("万元");
        return Collections.singletonList(card);
    }
}

9. Controller 层
// CardController.java
@Slf4j
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {

    @Autowired
    private CardService cardService;

    @GetMapping("/list")
    public Result<List<CardRespVO>> listCards(
            @RequestParam @NotBlank String orgLevel,
            @RequestParam @NotBlank String tabCode) {
        log.info("[CardController] listCards orgLevel={}, tabCode={}", orgLevel, tabCode);
        return Result.success(cardService.listCards(orgLevel, tabCode));
    }
}

10. 运维刷新接口（生产必备）
// MeasureCacheAdminController.java
@RestController
@RequestMapping("/admin/measure-cache")
public class MeasureCacheAdminController {

    @Autowired
    private MeasureMetaCacheService metaCacheService;

    /**
     * 新增/删除度量后，执行 SQL 上线，再调此接口刷新缓存
     * 生产环境建议加 IP 白名单或 Token 校验
     */
    @PostMapping("/refresh")
    public Result<String> refresh() {
        metaCacheService.refresh();
        return Result.success("度量元数据缓存刷新成功");
    }
}
```

---

## 架构总结
```
请求
  │
  ▼
CardController
  │  orgLevel + tabCode
  ▼
CardServiceImpl
  │
  ├─ MeasureAssembler.assembleMeasureList()
  │     │
  │     ├─ MeasureMetaCacheService.getCommonMeasureCodes()  ← 本地缓存（公共度量）
  │     │
  │     ├─ MeasureResolver（策略链，@Order排序，findFirst）
  │     │     └─ DbDrivenMeasureResolver（通用兜底，查DB）
  │     │
  │     └─ LinkedHashSet 合并去重保序 → 从缓存映射VO
  │
  └─ 卡片基础数据查询 → 填充 measureList → 返回
  
后续扩展（新场景只需加一条SQL + 可选新策略类）：

新增 orgLevel=G2, tabCode=C 的度量 → 仅插入 card_scene_measure_rel 记录，调刷新接口
若某场景需要特殊编排逻辑 → 新增实现 MeasureResolver 的 @Component，加 @Order 优先级即可
