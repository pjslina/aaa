枚举设计
1. 度量元数据枚举
// MeasureEnum.java
@Getter
public enum MeasureEnum {

    // ==================== 公共度量（所有场景通用）====================
    BASELINE_Y("BASELINE_Y", "基线_年", "Baseline Y"),

    // ==================== 场景专属度量 ====================
    GLOBAL_G1_BEST_Y("GLOBAL_G1_BEST_Y", "Global G1最优_年", "Global G1 Best Y"),
    GLOBAL_G2_BEST_Y("GLOBAL_G2_BEST_Y", "Global G2最优_年", "Global G2 Best Y"),
    REGION_G2_BEST_Y("REGION_G2_BEST_Y", "Region G2最优_年", "Region G2 Best Y"),
    ;

    private final String measureCode;
    private final String measureCnName;
    private final String measureEnName;

    MeasureEnum(String measureCode, String measureCnName, String measureEnName) {
        this.measureCode = measureCode;
        this.measureCnName = measureCnName;
        this.measureEnName = measureEnName;
    }

    /** 快速转换为 VO */
    public CardMeasureVO toVO() {
        CardMeasureVO vo = new CardMeasureVO();
        vo.setMeasureCode(this.measureCode);
        vo.setMeasureCnName(this.measureCnName);
        vo.setMeasureEnName(this.measureEnName);
        return vo;
    }

    /** 按编码查找，找不到抛异常（用于防御性校验） */
    public static MeasureEnum ofCode(String code) {
        for (MeasureEnum e : values()) {
            if (e.measureCode.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown measureCode: " + code);
    }
}

2. 场景度量配置枚举（核心）

用 EnumSet 保证有序性（按枚举声明顺序），用 (orgLevel, tabCode) 二元组作为 key 路由。
// SceneMeasureEnum.java
@Getter
public enum SceneMeasureEnum {

    /**
     * 规则：公共度量统一放在 COMMON 场景
     * 命名规范：{ORGLEVEL}_{TABCODE} 或 COMMON
     */
    COMMON(null, null,
            EnumSet.of(
                    MeasureEnum.BASELINE_Y
            )),

    G0_A(OrgLevelEnum.G0, "A",
            EnumSet.of(
                    MeasureEnum.GLOBAL_G1_BEST_Y,
                    MeasureEnum.REGION_G2_BEST_Y
            )),

    G0_B(OrgLevelEnum.G0, "B",
            EnumSet.of(
                    MeasureEnum.GLOBAL_G2_BEST_Y,
                    MeasureEnum.REGION_G2_BEST_Y
            )),

    G1_B(OrgLevelEnum.G1, "B",
            EnumSet.of(
                    MeasureEnum.GLOBAL_G2_BEST_Y,
                    MeasureEnum.REGION_G2_BEST_Y
            )),
    ;

    private final OrgLevelEnum orgLevel;
    private final String tabCode;
    /** EnumSet 天然按枚举声明顺序迭代，保证有序 */
    private final EnumSet<MeasureEnum> extraMeasures;

    SceneMeasureEnum(OrgLevelEnum orgLevel, String tabCode, EnumSet<MeasureEnum> extraMeasures) {
        this.orgLevel = orgLevel;
        this.tabCode = tabCode;
        this.extraMeasures = extraMeasures;
    }

    // ----------------------------------------------------------------
    //  静态路由表：启动时构建，O(1) 查找，避免运行时遍历
    // ----------------------------------------------------------------
    private static final Map<String, SceneMeasureEnum> ROUTE_MAP;

    static {
        Map<String, SceneMeasureEnum> map = new HashMap<>();
        for (SceneMeasureEnum scene : values()) {
            if (scene == COMMON) continue;
            map.put(buildKey(scene.orgLevel, scene.tabCode), scene);
        }
        ROUTE_MAP = Collections.unmodifiableMap(map);
    }

    /** 按场景路由，找不到返回 empty（不抛异常，由上层决定如何处理） */
    public static Optional<SceneMeasureEnum> match(OrgLevelEnum orgLevel, String tabCode) {
        return Optional.ofNullable(ROUTE_MAP.get(buildKey(orgLevel, tabCode)));
    }

    private static String buildKey(OrgLevelEnum orgLevel, String tabCode) {
        return orgLevel.getCode() + ":" + tabCode;
    }
}

3. OrgLevelEnum（补全，之前已有）
// OrgLevelEnum.java
@Getter
public enum OrgLevelEnum {
    G0("G0"), G1("G1"), G2("G2"), G3("G3"), G4("G4");

    private final String code;

    OrgLevelEnum(String code) {
        this.code = code;
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

装配器（无需 DB，纯枚举驱动）
// MeasureAssembler.java
@Slf4j
@Component
public class MeasureAssembler {

    /**
     * 构造指定场景的 measureList
     * 顺序：公共度量（COMMON 声明顺序）在前，场景专属度量在后
     *
     * @param orgLevel 组织层级
     * @param tabCode  场景编码
     * @return 有序、去重的度量 VO 列表
     */
    public List<CardMeasureVO> assembleMeasureList(OrgLevelEnum orgLevel, String tabCode) {
        // 1. 公共度量（固定在前，顺序由 COMMON 枚举中 EnumSet 声明顺序决定）
        EnumSet<MeasureEnum> commonMeasures = SceneMeasureEnum.COMMON.getExtraMeasures();

        // 2. 场景专属度量（找不到场景则为空，不报错）
        EnumSet<MeasureEnum> extraMeasures = SceneMeasureEnum.match(orgLevel, tabCode)
                .map(SceneMeasureEnum::getExtraMeasures)
                .orElseGet(() -> {
                    log.warn("[MeasureAssembler] no scene config for orgLevel={}, tabCode={}, use common only",
                            orgLevel.getCode(), tabCode);
                    return EnumSet.noneOf(MeasureEnum.class);
                });

        // 3. 合并：公共在前 + 专属在后，LinkedHashSet 保序去重
        LinkedHashSet<MeasureEnum> ordered = new LinkedHashSet<>(commonMeasures);
        ordered.addAll(extraMeasures);

        // 4. 转换为 VO
        return ordered.stream()
                .map(MeasureEnum::toVO)
                .collect(Collectors.toList());
    }
}

Service 层（与之前保持一致）
// CardServiceImpl.java
@Slf4j
@Service
public class CardServiceImpl implements CardService {

    @Autowired
    private MeasureAssembler measureAssembler;

    @Override
    public List<CardRespVO> listCards(String orgLevelStr, String tabCode) {
        OrgLevelEnum orgLevel = OrgLevelEnum.of(orgLevelStr);

        // measureList 整个请求只组装一次，所有卡片复用
        List<CardMeasureVO> measureList = measureAssembler.assembleMeasureList(orgLevel, tabCode);

        List<CardRespVO> cards = queryCards(orgLevel, tabCode);
        cards.forEach(card -> card.setMeasureList(measureList));
        return cards;
    }

    private List<CardRespVO> queryCards(OrgLevelEnum orgLevel, String tabCode) {
        // 实际替换为 DB 查询
        CardRespVO card = new CardRespVO();
        card.setCategoryId(1L);
        card.setCategoryName("示例卡片");
        card.setMainUnit("万元");
        return Collections.singletonList(card);
    }
}

两套方案对比
维度DB 方案枚举方案新增度量写 SQL + 刷缓存，0 代码变更修改枚举 + 发版新增场景写 SQL + 刷缓存，0 代码变更新增 SceneMeasureEnum 条目 + 发版运行时性能本地缓存（30min 刷新）静态常量，无 IO数据一致性DB 是 source of truth代码即配置，编译期保证适用场景度量/场景变更频繁度量属于元数据，低频变更 ?

你的场景说明"度量属于元数据，不会轻易修改，变更通过 SQL 脚本上线"——枚举方案完全胜任，且更简洁，编译期即可发现配置错误，是更优选择。
扩展示例
新增一个场景 G2_C，只需在 SceneMeasureEnum 加一行：
G2_C(OrgLevelEnum.G2, "C",
        EnumSet.of(
                MeasureEnum.GLOBAL_G1_BEST_Y,
                MeasureEnum.GLOBAL_G2_BEST_Y
        )),
        
新增一个度量 REGION_G3_BEST_Y，两步：
// Step1: MeasureEnum 加枚举项
REGION_G3_BEST_Y("REGION_G3_BEST_Y", "Region G3最优_年", "Region G3 Best Y"),

// Step2: 在对应 SceneMeasureEnum 场景中引用
G2_C(OrgLevelEnum.G2, "C",
        EnumSet.of(
                MeasureEnum.GLOBAL_G1_BEST_Y,
                MeasureEnum.REGION_G3_BEST_Y  // 新增引用
        )),
        

性能瓶颈分析
当前路径：assembleMeasureList()
  │
  ├─ 1. SceneMeasureEnum.match()        → O(1) HashMap，已很快
  ├─ 2. LinkedHashSet 合并去重           → 每次请求都 new 对象 + addAll  ← 可优化
  ├─ 3. stream().map(toVO()).collect()   → 每次请求都 new List + new VO  ← 可优化
  └─ 4. OrgLevelEnum.of() 线性遍历       → O(n) 遍历                     ← 可优化
  
核心优化思路：(orgLevel, tabCode) 相同的请求，measureList 结果永远相同 → 启动时预计算，运行时直接返回不可变列表，零 GC 压力。
优化后完整代码1. OrgLevelEnum — O(1) 查找

@Getter
public enum OrgLevelEnum {
    G0("G0"), G1("G1"), G2("G2"), G3("G3"), G4("G4");

    private final String code;

    OrgLevelEnum(String code) {
        this.code = code;
    }

    // 启动时构建，O(1) 查找，替换原来的 for 循环
    private static final Map<String, OrgLevelEnum> CODE_MAP;
    static {
        Map<String, OrgLevelEnum> map = new HashMap<>();
        for (OrgLevelEnum e : values()) {
            map.put(e.code, e);
        }
        CODE_MAP = Collections.unmodifiableMap(map);
    }

    public static OrgLevelEnum of(String code) {
        OrgLevelEnum result = CODE_MAP.get(code);
        if (result == null) {
            throw new IllegalArgumentException("Unknown orgLevel: " + code);
        }
        return result;
    }
}

2. MeasureEnum — VO 对象预构建，禁止重复 new
@Getter
public enum MeasureEnum {

    BASELINE_Y("BASELINE_Y", "基线_年", "Baseline Y"),
    GLOBAL_G1_BEST_Y("GLOBAL_G1_BEST_Y", "Global G1最优_年", "Global G1 Best Y"),
    GLOBAL_G2_BEST_Y("GLOBAL_G2_BEST_Y", "Global G2最优_年", "Global G2 Best Y"),
    REGION_G2_BEST_Y("REGION_G2_BEST_Y", "Region G2最优_年", "Region G2 Best Y"),
    ;

    private final String measureCode;
    private final String measureCnName;
    private final String measureEnName;

    /** 每个枚举项对应的 VO 在类加载时创建且永不变更，全局唯一实例 */
    private final CardMeasureVO cachedVO;

    MeasureEnum(String measureCode, String measureCnName, String measureEnName) {
        this.measureCode = measureCode;
        this.measureCnName = measureCnName;
        this.measureEnName = measureEnName;
        // 枚举构造器中直接 new，整个 JVM 生命周期只 new 一次
        this.cachedVO = buildVO(measureCode, measureCnName, measureEnName);
    }

    private static CardMeasureVO buildVO(String code, String cnName, String enName) {
        CardMeasureVO vo = new CardMeasureVO();
        vo.setMeasureCode(code);
        vo.setMeasureCnName(cnName);
        vo.setMeasureEnName(enName);
        return vo;
    }

    /**
     * 直接返回预构建的 VO 实例，无任何对象分配
     * 注意：VO 必须是只读使用，调用方不得修改其字段
     */
    public CardMeasureVO getCachedVO() {
        return cachedVO;
    }
}

3. SceneMeasureEnum — 启动时预计算所有场景的结果列表
@Getter
public enum SceneMeasureEnum {

    COMMON(null, null,
            EnumSet.of(MeasureEnum.BASELINE_Y)),

    G0_A(OrgLevelEnum.G0, "A",
            EnumSet.of(MeasureEnum.GLOBAL_G1_BEST_Y, MeasureEnum.REGION_G2_BEST_Y)),

    G0_B(OrgLevelEnum.G0, "B",
            EnumSet.of(MeasureEnum.GLOBAL_G2_BEST_Y, MeasureEnum.REGION_G2_BEST_Y)),

    G1_B(OrgLevelEnum.G1, "B",
            EnumSet.of(MeasureEnum.GLOBAL_G2_BEST_Y, MeasureEnum.REGION_G2_BEST_Y)),
    ;

    private final OrgLevelEnum orgLevel;
    private final String tabCode;
    private final EnumSet<MeasureEnum> extraMeasures;

    /**
     * 核心优化：每个场景对应的最终 measureList 在类加载时预计算完毕
     * 运行时直接返回此引用，零计算、零对象分配
     */
    private final List<CardMeasureVO> cachedMeasureList;

    SceneMeasureEnum(OrgLevelEnum orgLevel, String tabCode, EnumSet<MeasureEnum> extraMeasures) {
        this.orgLevel = orgLevel;
        this.tabCode = tabCode;
        this.extraMeasures = extraMeasures;
        // 注意：此处 COMMON 尚未初始化完毕，不能引用 COMMON.extraMeasures
        // 所以 cachedMeasureList 延迟到静态块中计算
        this.cachedMeasureList = null; // 占位，真正赋值在 MEASURE_LIST_MAP 中
    }

    // ----------------------------------------------------------------
    //  静态路由表 + 预计算结果表，JVM 类加载时一次性构建
    // ----------------------------------------------------------------

    /** key: "orgLevel:tabCode" → 预计算好的不可变 measureList */
    private static final Map<String, List<CardMeasureVO>> MEASURE_LIST_MAP;

    /** 公共度量列表（无匹配场景时的兜底返回值） */
    private static final List<CardMeasureVO> COMMON_MEASURE_LIST;

    static {
        // 公共度量：按 EnumSet 声明顺序转换（EnumSet 按枚举 ordinal 有序）
        List<CardMeasureVO> commonList = COMMON.extraMeasures.stream()
                .map(MeasureEnum::getCachedVO)
                .collect(Collectors.toList());
        COMMON_MEASURE_LIST = Collections.unmodifiableList(commonList);

        Map<String, List<CardMeasureVO>> map = new HashMap<>();
        for (SceneMeasureEnum scene : values()) {
            if (scene == COMMON) continue;

            // 合并：公共在前 + 场景专属在后，LinkedHashSet 去重保序
            LinkedHashSet<MeasureEnum> ordered = new LinkedHashSet<>(COMMON.extraMeasures);
            ordered.addAll(scene.extraMeasures);

            // 转换为不可变 VO 列表（直接复用 cachedVO，不 new 任何对象）
            List<CardMeasureVO> voList = ordered.stream()
                    .map(MeasureEnum::getCachedVO)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(),
                            Collections::unmodifiableList
                    ));

            map.put(buildKey(scene.orgLevel, scene.tabCode), voList);
        }
        MEASURE_LIST_MAP = Collections.unmodifiableMap(map);
    }

    /**
     * 运行时查询入口
     * 直接返回预计算的不可变列表引用，无任何计算和对象分配，纯内存读取
     *
     * @return 不可变列表，调用方只读使用
     */
    public static List<CardMeasureVO> getMeasureList(OrgLevelEnum orgLevel, String tabCode) {
        List<CardMeasureVO> result = MEASURE_LIST_MAP.get(buildKey(orgLevel, tabCode));
        // 未配置场景降级返回公共度量，而非 null 或异常
        return result != null ? result : COMMON_MEASURE_LIST;
    }

    private static String buildKey(OrgLevelEnum orgLevel, String tabCode) {
        return orgLevel.getCode() + ":" + tabCode;
    }
}

4. MeasureAssembler — 薄层直通，无任何计算
@Slf4j
@Component
public class MeasureAssembler {

    /**
     * 运行时路径：
     *   OrgLevelEnum.of()          → HashMap.get()，O(1)
     *   SceneMeasureEnum.getMeasureList() → HashMap.get()，O(1)
     *   返回预构建的不可变列表引用，全程零对象分配
     */
    public List<CardMeasureVO> assembleMeasureList(String orgLevelStr, String tabCode) {
        OrgLevelEnum orgLevel = OrgLevelEnum.of(orgLevelStr);

        List<CardMeasureVO> measureList = SceneMeasureEnum.getMeasureList(orgLevel, tabCode);

        if (measureList == SceneMeasureEnum.getCommonMeasureList()) {
            // 说明走了降级，记录 warn 便于排查配置遗漏
            log.warn("[MeasureAssembler] no scene config for orgLevel={}, tabCode={}, fallback to common",
                    orgLevelStr, tabCode);
        }

        return measureList;
    }
}

为支持上面的降级日志判断，SceneMeasureEnum 补一个公开方法：
// 在 SceneMeasureEnum 中补充
public static List<CardMeasureVO> getCommonMeasureList() {
    return COMMON_MEASURE_LIST;
}

5. Service 层（无变化，保持整洁）
@Slf4j
@Service
public class CardServiceImpl implements CardService {

    @Autowired
    private MeasureAssembler measureAssembler;

    @Override
    public List<CardRespVO> listCards(String orgLevelStr, String tabCode) {
        // 整个请求只调用一次，所有卡片共享同一个列表引用
        List<CardMeasureVO> measureList = measureAssembler.assembleMeasureList(orgLevelStr, tabCode);

        List<CardRespVO> cards = queryCards();
        cards.forEach(card -> card.setMeasureList(measureList));
        return cards;
    }

    private List<CardRespVO> queryCards() {
        CardRespVO card = new CardRespVO();
        card.setCategoryId(1L);
        card.setCategoryName("示例卡片");
        card.setMainUnit("万元");
        return Collections.singletonList(card);
    }
}
```

---

## 优化效果对比
```
优化前（每次请求）：
  new LinkedHashSet → addAll → stream → new CardMeasureVO × N → new ArrayList
  对象分配：1 + 1 + N + 1 = N+3 个堆对象，触发 GC

优化后（每次请求）：
  HashMap.get() × 2 → 返回已有引用
  对象分配：0 个，GC 压力为零
  
优化点手段效果OrgLevelEnum.of()静态 HashMap 替换 for 遍历O(n) → O(1)MeasureEnum.toVO()枚举构造时预建 cachedVO每次 new VO → 复用单例assembleMeasureList()静态块预计算所有场景结果每次合并计算 → 直接取引用返回值unmodifiableList 包装防御性保护，防调用方误修改降级处理无匹配场景返回公共列表而非 null安全兜底 + warn 日志
最终运行时热路径只剩两次 HashMap.get()，理论上已达到该场景的性能上限。
