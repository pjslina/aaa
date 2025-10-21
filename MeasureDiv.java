package com.example.dto;

import lombok.Data;

/**
 * 度量数据配置VO
 */
@Data
public class MeasureDataVO {
    private String measureCode;
    
    /**
     * 金额单位标识
     * Y-金额类型，N-非金额类型
     */
    private String amountUnit;
    
    /**
     * 海外标识
     * Y-海外，N-国内
     */
    private String overseasFlag;
    
    /**
     * 计算类型
     * 1-不做任何处理，原样输出
     * 2-国内除以100000000，海外除以1000000
     * 3-乘以100
     */
    private Integer calcType;
    
    /**
     * 小数位数
     * 0-整数，1-保留1位小数，2-保留2位小数
     */
    private Integer decimalPlaces;
}

package com.example.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 金额单位标识枚举
 */
@Getter
@AllArgsConstructor
public enum AmountUnitEnum {
    AMOUNT("Y", "金额类型"),
    NON_AMOUNT("N", "非金额类型");

    private final String code;
    private final String desc;

    public static AmountUnitEnum fromCode(String code) {
        if (code == null) {
            return NON_AMOUNT;
        }
        for (AmountUnitEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return NON_AMOUNT;
    }

    public boolean isAmount() {
        return this == AMOUNT;
    }
}

package com.example.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 海外标识枚举
 */
@Getter
@AllArgsConstructor
public enum OverseasFlagEnum {
    OVERSEAS("Y", "海外"),
    DOMESTIC("N", "国内");

    private final String code;
    private final String desc;

    public static OverseasFlagEnum fromCode(String code) {
        if (code == null) {
            return DOMESTIC;
        }
        for (OverseasFlagEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return DOMESTIC;
    }

    public boolean isOverseas() {
        return this == OVERSEAS;
    }
}

package com.example.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 计算类型枚举
 */
@Getter
@AllArgsConstructor
public enum CalcTypeEnum {
    /**
     * 不做任何处理
     */
    NO_CHANGE(1, "不做任何处理") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value;
        }
    },

    /**
     * 国内除以100000000（亿），海外除以1000000（百万）
     */
    DIVIDE(2, "除法运算") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            if (isOverseas) {
                return value.divide(OVERSEAS_DIVISOR, 10, BigDecimal.ROUND_HALF_UP);
            } else {
                return value.divide(DOMESTIC_DIVISOR, 10, BigDecimal.ROUND_HALF_UP);
            }
        }
    },

    /**
     * 乘以100
     */
    MULTIPLY(3, "乘以100") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value.multiply(HUNDRED);
        }
    },

    /**
     * 除以1000（千）
     */
    DIVIDE_THOUSAND(4, "除以1000") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value.divide(THOUSAND, 10, BigDecimal.ROUND_HALF_UP);
        }
    },

    /**
     * 除以10000（万）
     */
    DIVIDE_TEN_THOUSAND(5, "除以10000") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value.divide(TEN_THOUSAND, 10, BigDecimal.ROUND_HALF_UP);
        }
    };

    private final Integer code;
    private final String desc;

    // 常量定义
    private static final BigDecimal DOMESTIC_DIVISOR = new BigDecimal("100000000"); // 亿
    private static final BigDecimal OVERSEAS_DIVISOR = new BigDecimal("1000000");   // 百万
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    /**
     * 执行计算
     */
    public abstract BigDecimal calculate(BigDecimal value, boolean isOverseas);

    /**
     * 根据code获取枚举
     */
    public static CalcTypeEnum fromCode(Integer code) {
        if (code == null) {
            return NO_CHANGE;
        }
        for (CalcTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return NO_CHANGE;
    }
}

package com.example.strategy;

import com.example.dto.MeasureDataVO;

import java.math.BigDecimal;

/**
 * 数据计算策略接口
 */
public interface DataCalculationStrategy {

    /**
     * 判断是否适用当前策略
     */
    boolean isApplicable(MeasureDataVO config);

    /**
     * 执行计算
     */
    BigDecimal calculate(BigDecimal value, MeasureDataVO config);

    /**
     * 策略优先级（数字越小优先级越高）
     */
    int getPriority();
}

package com.example.strategy.impl;

import com.example.dto.MeasureDataVO;
import com.example.enums.AmountUnitEnum;
import com.example.enums.CalcTypeEnum;
import com.example.enums.OverseasFlagEnum;
import com.example.strategy.DataCalculationStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 金额类型计算策略
 */
@Component
public class AmountCalculationStrategy implements DataCalculationStrategy {

    @Override
    public boolean isApplicable(MeasureDataVO config) {
        return AmountUnitEnum.fromCode(config.getAmountUnit()).isAmount();
    }

    @Override
    public BigDecimal calculate(BigDecimal value, MeasureDataVO config) {
        // 获取计算类型
        CalcTypeEnum calcType = CalcTypeEnum.fromCode(config.getCalcType());
        
        // 判断是否海外
        boolean isOverseas = OverseasFlagEnum.fromCode(config.getOverseasFlag()).isOverseas();

        // 执行计算
        return calcType.calculate(value, isOverseas);
    }

    @Override
    public int getPriority() {
        return 1; // 高优先级
    }
}


package com.example.strategy.impl;

import com.example.dto.MeasureDataVO;
import com.example.enums.AmountUnitEnum;
import com.example.enums.CalcTypeEnum;
import com.example.strategy.DataCalculationStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 非金额类型计算策略
 */
@Component
public class NonAmountCalculationStrategy implements DataCalculationStrategy {

    @Override
    public boolean isApplicable(MeasureDataVO config) {
        return !AmountUnitEnum.fromCode(config.getAmountUnit()).isAmount();
    }

    @Override
    public BigDecimal calculate(BigDecimal value, MeasureDataVO config) {
        // 非金额类型不考虑海外标识
        CalcTypeEnum calcType = CalcTypeEnum.fromCode(config.getCalcType());
        return calcType.calculate(value, false);
    }

    @Override
    public int getPriority() {
        return 2; // 次优先级
    }
}

package com.example.strategy;

import com.example.dto.MeasureDataVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 策略管理器
 */
@Slf4j
@Component
public class DataCalculationStrategyManager {

    @Autowired
    private List<DataCalculationStrategy> strategies;

    private List<DataCalculationStrategy> sortedStrategies;

    @PostConstruct
    public void init() {
        // 按优先级排序
        sortedStrategies = strategies.stream()
                .sorted(Comparator.comparingInt(DataCalculationStrategy::getPriority))
                .collect(Collectors.toList());

        log.info("初始化数据计算策略，共 {} 个策略", sortedStrategies.size());
    }

    /**
     * 获取适用的策略并执行计算
     */
    public BigDecimal calculate(BigDecimal value, MeasureDataVO config) {
        for (DataCalculationStrategy strategy : sortedStrategies) {
            if (strategy.isApplicable(config)) {
                return strategy.calculate(value, config);
            }
        }

        log.warn("未找到适用的计算策略，返回原值");
        return value;
    }
}

package com.example.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * 数字格式化工具类
 */
public class NumberFormatUtil {

    // 缓存DecimalFormat实例（线程不安全，使用ThreadLocal）
    private static final ThreadLocal<DecimalFormat[]> FORMAT_CACHE = ThreadLocal.withInitial(() -> {
        DecimalFormat[] formats = new DecimalFormat[10]; // 支持0-9位小数
        for (int i = 0; i < 10; i++) {
            formats[i] = createDecimalFormat(i);
        }
        return formats;
    });

    /**
     * 创建DecimalFormat
     */
    private static DecimalFormat createDecimalFormat(int decimalPlaces) {
        if (decimalPlaces == 0) {
            return new DecimalFormat("#");
        }

        StringBuilder pattern = new StringBuilder("#.");
        for (int i = 0; i < decimalPlaces; i++) {
            pattern.append("#");
        }

        DecimalFormat format = new DecimalFormat(pattern.toString());
        format.setRoundingMode(RoundingMode.HALF_UP);
        format.setGroupingUsed(false); // 不使用千位分隔符
        return format;
    }

    /**
     * 格式化BigDecimal为指定小数位的字符串
     * 
     * @param value         数值
     * @param decimalPlaces 小数位数
     * @return 格式化后的字符串
     */
    public static String format(BigDecimal value, int decimalPlaces) {
        if (value == null) {
            return null;
        }

        if (decimalPlaces < 0) {
            decimalPlaces = 0;
        }

        if (decimalPlaces >= 10) {
            // 超过缓存范围，动态创建
            return createDecimalFormat(decimalPlaces).format(value);
        }

        // 使用缓存的格式化器
        return FORMAT_CACHE.get()[decimalPlaces].format(value);
    }

    /**
     * 设置小数位数（使用BigDecimal的setScale方法）
     * 
     * @param value         数值
     * @param decimalPlaces 小数位数
     * @return 设置后的BigDecimal
     */
    public static BigDecimal setScale(BigDecimal value, int decimalPlaces) {
        if (value == null) {
            return null;
        }

        if (decimalPlaces < 0) {
            decimalPlaces = 0;
        }

        return value.setScale(decimalPlaces, RoundingMode.HALF_UP);
    }
}

package com.example.service;

import com.example.dto.MeasureDataVO;
import com.example.strategy.DataCalculationStrategyManager;
import com.example.util.NumberFormatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 数据处理服务
 */
@Slf4j
@Service
public class DataProcessService {

    @Autowired
    private DataCalculationStrategyManager strategyManager;

    /**
     * 处理数据
     * 
     * @param inputData  输入的double数据
     * @param config     配置对象
     * @return 处理后的字符串
     */
    public String processData(Double inputData, MeasureDataVO config) {
        // 1. 空值和零值检查
        if (isNullOrZero(inputData)) {
            return formatNullOrZero(inputData);
        }

        // 2. 转换为BigDecimal（避免精度丢失）
        BigDecimal value = BigDecimal.valueOf(inputData);

        // 3. 执行计算策略
        BigDecimal calculated = strategyManager.calculate(value, config);

        // 4. 格式化输出
        Integer decimalPlaces = config.getDecimalPlaces() != null ? config.getDecimalPlaces() : 2;
        String result = NumberFormatUtil.format(calculated, decimalPlaces);

        log.debug("数据处理完成: {} -> {}, 配置: {}", inputData, result, config.getMeasureCode());

        return result;
    }

    /**
     * 批量处理数据
     * 
     * @param inputDataList 输入数据列表
     * @param config        配置对象
     * @return 处理后的字符串列表
     */
    public java.util.List<String> batchProcessData(java.util.List<Double> inputDataList, MeasureDataVO config) {
        return inputDataList.stream()
                .map(data -> processData(data, config))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 判断是否为null或零
     */
    private boolean isNullOrZero(Double value) {
        if (value == null) {
            return true;
        }

        // 使用BigDecimal比较，避免浮点数精度问题
        BigDecimal bd = BigDecimal.valueOf(value);
        return bd.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * 格式化null或零值
     */
    private String formatNullOrZero(Double value) {
        if (value == null) {
            return null;
        }

        // 判断是整数0还是0.00
        if (value == 0.0) {
            return "0";
        } else if (value == 0.00) {
            return "0.00";
        } else {
            return String.valueOf(value);
        }
    }
}


package com.example.service;

import com.example.dto.MeasureDataVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据处理服务测试
 */
@SpringBootTest
class DataProcessServiceTest {

    @Autowired
    private DataProcessService dataProcessService;

    @Test
    void testNullValue() {
        MeasureDataVO config = createConfig("Y", "Y", 2, 2);
        String result = dataProcessService.processData(null, config);
        assertNull(result);
    }

    @Test
    void testZeroValue() {
        MeasureDataVO config = createConfig("Y", "Y", 2, 2);
        String result = dataProcessService.processData(0.0, config);
        assertEquals("0", result);
    }

    @Test
    void testAmountOverseasDivide() {
        // 金额类型 + 海外 + 除法计算 + 保留2位小数
        MeasureDataVO config = createConfig("Y", "Y", 2, 2);
        String result = dataProcessService.processData(5000000.0, config);
        assertEquals("5", result); // 5000000 / 1000000 = 5
    }

    @Test
    void testAmountDomesticDivide() {
        // 金额类型 + 国内 + 除法计算 + 保留2位小数
        MeasureDataVO config = createConfig("Y", "N", 2, 2);
        String result = dataProcessService.processData(500000000.0, config);
        assertEquals("5", result); // 500000000 / 100000000 = 5
    }

    @Test
    void testAmountDomesticDivideWithDecimal() {
        // 金额类型 + 国内 + 除法计算 + 保留2位小数
        MeasureDataVO config = createConfig("Y", "N", 2, 2);
        String result = dataProcessService.processData(123456789.0, config);
        assertEquals("1.23", result); // 123456789 / 100000000 = 1.23456789 -> 1.23
    }

    @Test
    void testNonAmountMultiply() {
        // 非金额类型 + 乘以100 + 保留2位小数
        MeasureDataVO config = createConfig("N", "Y", 3, 2);
        String result = dataProcessService.processData(0.8523, config);
        assertEquals("85.23", result); // 0.8523 * 100 = 85.23
    }

    @Test
    void testNonAmountNoChange() {
        // 非金额类型 + 不处理 + 保留0位小数
        MeasureDataVO config = createConfig("N", "N", 1, 0);
        String result = dataProcessService.processData(12345.67, config);
        assertEquals("12346", result); // 四舍五入到整数
    }

    @Test
    void testDecimalPlacesZero() {
        // 保留0位小数
        MeasureDataVO config = createConfig("Y", "Y", 2, 0);
        String result = dataProcessService.processData(1234567.0, config);
        assertEquals("1", result); // 1234567 / 1000000 = 1.234567 -> 1
    }

    @Test
    void testDecimalPlacesOne() {
        // 保留1位小数
        MeasureDataVO config = createConfig("Y", "Y", 2, 1);
        String result = dataProcessService.processData(1567890.0, config);
        assertEquals("1.6", result); // 1567890 / 1000000 = 1.56789 -> 1.6
    }

    @Test
    void testComplexScenario() {
        // 复杂场景：金额 + 国内 + 除法 + 2位小数
        MeasureDataVO config = createConfig("Y", "N", 2, 2);
        String result = dataProcessService.processData(987654321.12, config);
        assertEquals("9.88", result); // 987654321.12 / 100000000 = 9.8765... -> 9.88
    }

    /**
     * 创建配置对象
     */
    private MeasureDataVO createConfig(String amountUnit, String overseasFlag, 
                                      Integer calcType, Integer decimalPlaces) {
        MeasureDataVO config = new MeasureDataVO();
        config.setMeasureCode("TEST");
        config.setAmountUnit(amountUnit);
        config.setOverseasFlag(overseasFlag);
        config.setCalcType(calcType);
        config.setDecimalPlaces(decimalPlaces);
        return config;
    }
}