这道题的核心难点在于：度量全集无序、无法直接排序，但页面需要有序、有筛选的度量子集。最佳方案是用枚举承载度量的有序配置，配合构建器模式组装。以下是完整的生产级代码：
核心设计思路
枚举（MeasureEnum）= 度量的"规格说明书"
  ↓ 定义：顺序、展示名称、别名替换规则、是否启用
数据库（t_metadata_measure）= 度量的"元数据仓库"
  ↓ 提供：measure_code 合法性校验、多语言名称来源
MeasureListBuilder = 装配工厂
  ↓ 按枚举顺序过滤、替换名称、组装 measureList
CardRespVO.measureList = 最终有序、有语义的展示集合

完整代码
1. 度量枚举（核心）
package com.example.metric.enums;

import lombok.Getter;

/**
 * 度量枚举 — 页面可展示度量的"唯一权威来源"
 *
 * <p>职责：
 * <ol>
 *   <li>定义页面度量的展示顺序（ordinal 即顺序）</li>
 *   <li>定义度量的展示名称（支持覆盖数据库名称）</li>
 *   <li>控制度量是否启用（新增/下线只改此枚举 + SQL脚本）</li>
 * </ol>
 *
 * <p>变更说明：
 * <ul>
 *   <li>新增度量：在此枚举末尾新增枚举项，同步执行 SQL insert</li>
 *   <li>下线度量：将 enabled 改为 false，同步执行 SQL delete（或保留数据）</li>
 *   <li>改名度量：修改 displayCnName / displayEnName，无需改数据库</li>
 * </ul>
 */
@Getter
public enum MeasureEnum {

    // ==================== 在此按展示顺序定义度量 ====================

    MEASURE_TOTAL_COUNT(
            "measure_total_count",
            "总数量",
            "Total Count",
            true
    ),

    MEASURE_PASS_RATE(
            "measure_pass_rate",
            "通过率",
            "Pass Rate",
            true
    ),

    MEASURE_AVG_SCORE(
            "measure_avg_score",
            "平均分",
            "Average Score",
            true
    ),

    MEASURE_MAX_VALUE(
            "measure_max_value",
            "最大值",
            "Max Value",
            true
    ),

    MEASURE_COVERAGE(
            "measure_coverage",
            "覆盖度",
            "Coverage",
            true
    ),

    // 已下线度量示例 —— enabled=false，保留枚举防止历史数据引用报错
    MEASURE_DEPRECATED_OLD(
            "measure_deprecated_old",
            "废弃度量",
            "Deprecated Measure",
            false
    );

    // ================================================================

    /**
     * 与 t_metadata_measure.measure_code 对应，是与 DB 的唯一契约
     */
    private final String measureCode;

    /**
     * 页面展示的中文名称（优先使用此值，若为空则 fallback 到数据库）
     */
    private final String displayCnName;

    /**
     * 页面展示的英文名称（优先使用此值，若为空则 fallback 到数据库）
     */
    private final String displayEnName;

    /**
     * 是否在页面启用
     */
    private final boolean enabled;

    MeasureEnum(String measureCode, String displayCnName, String displayEnName, boolean enabled) {
        this.measureCode = measureCode;
        this.displayCnName = displayCnName;
        this.displayEnName = displayEnName;
        this.enabled = enabled;
    }

    /**
     * 枚举的快速查找 Map（避免每次 values() 遍历）
     * 使用静态内部类实现懒加载，JVM 保证线程安全
     */
    private static class Holder {
        private static final java.util.Map<String, MeasureEnum> CODE_MAP;
        static {
            CODE_MAP = new java.util.HashMap<>(values().length * 2);
            for (MeasureEnum e : values()) {
                CODE_MAP.put(e.getMeasureCode(), e);
            }
        }
    }

    public static MeasureEnum fromCode(String measureCode) {
        return Holder.CODE_MAP.get(measureCode);
    }
}


5. MeasureList 装配器（核心构建逻辑）
package com.example.metric.builder;

import com.example.metric.entity.MetadataMeasure;
import com.example.metric.enums.MeasureEnum;
import com.example.metric.vo.CardMeasureVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 度量列表装配器
 *
 * <p>装配规则（优先级从高到低）：
 * <ol>
 *   <li>顺序由 {@link MeasureEnum} 的 ordinal 决定</li>
 *   <li>仅展示 enabled=true 的枚举项</li>
 *   <li>展示名称：枚举定义 > 数据库值（若枚举名为空则 fallback DB）</li>
 *   <li>若枚举中的 measureCode 在数据库中不存在，记录警告日志并跳过</li>
 * </ol>
 */
@Slf4j
public class MeasureListBuilder {

    /**
     * 构造有序的 measureList
     *
     * @param dbMeasures 从数据库查出的度量列表（无序、可能是全集的子集）
     * @return 按枚举顺序排列、已做名称替换的 CardMeasureVO 列表
     */
    public static List<CardMeasureVO> build(List<MetadataMeasure> dbMeasures) {
        if (CollectionUtils.isEmpty(dbMeasures)) {
            log.warn("[MeasureListBuilder] dbMeasures is empty, return empty list");
            return Collections.emptyList();
        }

        // 将DB数据转为 Map，O(1) 查找
        Map<String, MetadataMeasure> dbMeasureMap = dbMeasures.stream()
                .collect(Collectors.toMap(
                        MetadataMeasure::getMeasureCode,
                        m -> m,
                        // 同一 code 出现多次时保留第一条，防御性处理
                        (existing, duplicate) -> {
                            log.warn("[MeasureListBuilder] duplicate measureCode found: {}, keep first",
                                    existing.getMeasureCode());
                            return existing;
                        }
                ));

        List<CardMeasureVO> result = new ArrayList<>();

        for (MeasureEnum measureEnum : MeasureEnum.values()) {
            // 未启用的度量直接跳过
            if (!measureEnum.isEnabled()) {
                continue;
            }

            String code = measureEnum.getMeasureCode();
            MetadataMeasure dbMeasure = dbMeasureMap.get(code);

            if (dbMeasure == null) {
                // 枚举定义了但 DB 里没有 —— 数据不一致，告警但不中断
                log.warn("[MeasureListBuilder] measureCode '{}' defined in enum but not found in DB, skip",
                        code);
                continue;
            }

            result.add(buildVO(measureEnum, dbMeasure));
        }

        return result;
    }

    /**
     * 单个 VO 构建：枚举名称 > DB名称
     */
    private static CardMeasureVO buildVO(MeasureEnum measureEnum, MetadataMeasure dbMeasure) {
        CardMeasureVO vo = new CardMeasureVO();
        vo.setMeasureCode(measureEnum.getMeasureCode());

        // 中文名：枚举优先，空则 fallback 数据库
        String cnName = StringUtils.hasText(measureEnum.getDisplayCnName())
                ? measureEnum.getDisplayCnName()
                : dbMeasure.getMeasureCnName();
        vo.setMeasureCnName(cnName);

        // 英文名：枚举优先，空则 fallback 数据库
        String enName = StringUtils.hasText(measureEnum.getDisplayEnName())
                ? measureEnum.getDisplayEnName()
                : dbMeasure.getMeasureEnName();
        vo.setMeasureEnName(enName);

        return vo;
    }

    /**
     * 获取所有启用中的枚举 measureCode 集合（供 Mapper 按需查询使用）
     */
    public static Set<String> getEnabledMeasureCodes() {
        return Arrays.stream(MeasureEnum.values())
                .filter(MeasureEnum::isEnabled)
                .map(MeasureEnum::getMeasureCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

6. Service 层
package com.example.metric.service;

import com.example.metric.builder.MeasureListBuilder;
import com.example.metric.entity.MetadataMeasure;
import com.example.metric.entity.MetadataMetric;
import com.example.metric.mapper.MetadataMeasureMapper;
import com.example.metric.mapper.MetadataMetricMapper;
import com.example.metric.vo.CardMeasureVO;
import com.example.metric.vo.CardMetricVO;
import com.example.metric.vo.CardRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardService {

    private final MetadataMetricMapper metadataMetricMapper;
    private final MetadataMeasureMapper metadataMeasureMapper;

    /**
     * 查询卡片列表
     *
     * <p>measureList 的度量元数据相对稳定，可加缓存减少 DB 查询
     */
    public List<CardRespVO> listCards(Long categoryId) {
        // 1. 查询该分类下的指标列表
        List<MetadataMetric> metrics = metadataMetricMapper.selectByCategoryId(categoryId);
        if (metrics == null || metrics.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        // 2. 获取启用的度量 code 集合（由枚举驱动，不是全集）
        Set<String> enabledCodes = MeasureListBuilder.getEnabledMeasureCodes();

        // 3. 按需查询度量元数据（只查枚举启用的，而非全集）
        List<MetadataMeasure> dbMeasures = getMeasureMetadata(enabledCodes);

        // 4. 构建有序 measureList（公共，所有卡片共用同一份 measureList）
        List<CardMeasureVO> measureList = MeasureListBuilder.build(dbMeasures);

        // 5. 构建卡片列表
        return buildCardList(categoryId, metrics, measureList);
    }

    /**
     * 度量元数据缓存（度量是元数据，变更频率极低，适合本地缓存）
     * 生产环境建议配合 Caffeine 或 Redis，TTL 设置为 1h ~ 24h
     */
    @Cacheable(value = "measureMetadataCache", key = "'enabled_measures'")
    public List<MetadataMeasure> getMeasureMetadata(Set<String> enabledCodes) {
        log.info("[CardService] load measure metadata from DB, codes: {}", enabledCodes);
        return metadataMeasureMapper.selectByCodes(enabledCodes);
    }

    private List<CardRespVO> buildCardList(Long categoryId,
                                           List<MetadataMetric> metrics,
                                           List<CardMeasureVO> measureList) {
        // 此处演示单卡片场景，实际按业务分组逻辑处理
        CardRespVO card = new CardRespVO();
        card.setCategoryId(categoryId);
        card.setCategoryName("示例分类");   // 实际从分类表查
        card.setMainUnit("个");             // 实际从配置表查

        // 指标列表
        List<CardMetricVO> metricVOList = metrics.stream()
                .map(this::toMetricVO)
                .collect(Collectors.toList());
        card.setMetricList(metricVOList);

        // 度量表头列表（有序）
        card.setMeasureList(measureList);

        return java.util.Collections.singletonList(card);
    }

    private CardMetricVO toMetricVO(MetadataMetric metric) {
        CardMetricVO vo = new CardMetricVO();
        vo.setMetricId(metric.getMetricId());
        vo.setMetricCnName(metric.getMetricCnName());
        vo.setMetricEnName(metric.getMetricEnName());
        vo.setMetricType(metric.getMetricType());
        vo.setDomainCode(metric.getDomainCode());
        // measureValueMap 由具体业务数据填充，此处略
        return vo;
    }
}

7. Controller 层
package com.example.metric.controller;

import com.example.metric.service.CardService;
import com.example.metric.vo.CardRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    /**
     * 获取卡片列表
     *
     * @param categoryId 分类ID
     */
    @GetMapping("/list")
    public List<CardRespVO> listCards(@RequestParam Long categoryId) {
        return cardService.listCards(categoryId);
    }
}

变更流程总结：

新增度量 → 枚举末尾加枚举项（enabled=true）+ 执行 SQL insert
下线度量 → 枚举 enabled 改为 false（数据库记录保留，不破坏历史数据）
改度量展示名 → 只改枚举字段，零 SQL，重启生效（或配合缓存刷新）
调整顺序 → 调整枚举项的位置，ordinal 自动更新