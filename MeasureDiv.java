package com.example.dto;

import lombok.Data;

/**
 * ������������VO
 */
@Data
public class MeasureDataVO {
    private String measureCode;
    
    /**
     * ��λ��ʶ
     * Y-������ͣ�N-�ǽ������
     */
    private String amountUnit;
    
    /**
     * �����ʶ
     * Y-���⣬N-����
     */
    private String overseasFlag;
    
    /**
     * ��������
     * 1-�����κδ���ԭ�����
     * 2-���ڳ���100000000���������1000000
     * 3-����100
     */
    private Integer calcType;
    
    /**
     * С��λ��
     * 0-������1-����1λС����2-����2λС��
     */
    private Integer decimalPlaces;
}

package com.example.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ��λ��ʶö��
 */
@Getter
@AllArgsConstructor
public enum AmountUnitEnum {
    AMOUNT("Y", "�������"),
    NON_AMOUNT("N", "�ǽ������");

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
 * �����ʶö��
 */
@Getter
@AllArgsConstructor
public enum OverseasFlagEnum {
    OVERSEAS("Y", "����"),
    DOMESTIC("N", "����");

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
 * ��������ö��
 */
@Getter
@AllArgsConstructor
public enum CalcTypeEnum {
    /**
     * �����κδ���
     */
    NO_CHANGE(1, "�����κδ���") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value;
        }
    },

    /**
     * ���ڳ���100000000���ڣ����������1000000������
     */
    DIVIDE(2, "��������") {
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
     * ����100
     */
    MULTIPLY(3, "����100") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value.multiply(HUNDRED);
        }
    },

    /**
     * ����1000��ǧ��
     */
    DIVIDE_THOUSAND(4, "����1000") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value.divide(THOUSAND, 10, BigDecimal.ROUND_HALF_UP);
        }
    },

    /**
     * ����10000����
     */
    DIVIDE_TEN_THOUSAND(5, "����10000") {
        @Override
        public BigDecimal calculate(BigDecimal value, boolean isOverseas) {
            return value.divide(TEN_THOUSAND, 10, BigDecimal.ROUND_HALF_UP);
        }
    };

    private final Integer code;
    private final String desc;

    // ��������
    private static final BigDecimal DOMESTIC_DIVISOR = new BigDecimal("100000000"); // ��
    private static final BigDecimal OVERSEAS_DIVISOR = new BigDecimal("1000000");   // ����
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    /**
     * ִ�м���
     */
    public abstract BigDecimal calculate(BigDecimal value, boolean isOverseas);

    /**
     * ����code��ȡö��
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
 * ���ݼ�����Խӿ�
 */
public interface DataCalculationStrategy {

    /**
     * �ж��Ƿ����õ�ǰ����
     */
    boolean isApplicable(MeasureDataVO config);

    /**
     * ִ�м���
     */
    BigDecimal calculate(BigDecimal value, MeasureDataVO config);

    /**
     * �������ȼ�������ԽС���ȼ�Խ�ߣ�
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
 * ������ͼ������
 */
@Component
public class AmountCalculationStrategy implements DataCalculationStrategy {

    @Override
    public boolean isApplicable(MeasureDataVO config) {
        return AmountUnitEnum.fromCode(config.getAmountUnit()).isAmount();
    }

    @Override
    public BigDecimal calculate(BigDecimal value, MeasureDataVO config) {
        // ��ȡ��������
        CalcTypeEnum calcType = CalcTypeEnum.fromCode(config.getCalcType());
        
        // �ж��Ƿ���
        boolean isOverseas = OverseasFlagEnum.fromCode(config.getOverseasFlag()).isOverseas();

        // ִ�м���
        return calcType.calculate(value, isOverseas);
    }

    @Override
    public int getPriority() {
        return 1; // �����ȼ�
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
 * �ǽ�����ͼ������
 */
@Component
public class NonAmountCalculationStrategy implements DataCalculationStrategy {

    @Override
    public boolean isApplicable(MeasureDataVO config) {
        return !AmountUnitEnum.fromCode(config.getAmountUnit()).isAmount();
    }

    @Override
    public BigDecimal calculate(BigDecimal value, MeasureDataVO config) {
        // �ǽ�����Ͳ����Ǻ����ʶ
        CalcTypeEnum calcType = CalcTypeEnum.fromCode(config.getCalcType());
        return calcType.calculate(value, false);
    }

    @Override
    public int getPriority() {
        return 2; // �����ȼ�
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
 * ���Թ�����
 */
@Slf4j
@Component
public class DataCalculationStrategyManager {

    @Autowired
    private List<DataCalculationStrategy> strategies;

    private List<DataCalculationStrategy> sortedStrategies;

    @PostConstruct
    public void init() {
        // �����ȼ�����
        sortedStrategies = strategies.stream()
                .sorted(Comparator.comparingInt(DataCalculationStrategy::getPriority))
                .collect(Collectors.toList());

        log.info("��ʼ�����ݼ�����ԣ��� {} ������", sortedStrategies.size());
    }

    /**
     * ��ȡ���õĲ��Բ�ִ�м���
     */
    public BigDecimal calculate(BigDecimal value, MeasureDataVO config) {
        for (DataCalculationStrategy strategy : sortedStrategies) {
            if (strategy.isApplicable(config)) {
                return strategy.calculate(value, config);
            }
        }

        log.warn("δ�ҵ����õļ�����ԣ�����ԭֵ");
        return value;
    }
}

package com.example.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * ���ָ�ʽ��������
 */
public class NumberFormatUtil {

    // ����DecimalFormatʵ�����̲߳���ȫ��ʹ��ThreadLocal��
    private static final ThreadLocal<DecimalFormat[]> FORMAT_CACHE = ThreadLocal.withInitial(() -> {
        DecimalFormat[] formats = new DecimalFormat[10]; // ֧��0-9λС��
        for (int i = 0; i < 10; i++) {
            formats[i] = createDecimalFormat(i);
        }
        return formats;
    });

    /**
     * ����DecimalFormat
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
        format.setGroupingUsed(false); // ��ʹ��ǧλ�ָ���
        return format;
    }

    /**
     * ��ʽ��BigDecimalΪָ��С��λ���ַ���
     * 
     * @param value         ��ֵ
     * @param decimalPlaces С��λ��
     * @return ��ʽ������ַ���
     */
    public static String format(BigDecimal value, int decimalPlaces) {
        if (value == null) {
            return null;
        }

        if (decimalPlaces < 0) {
            decimalPlaces = 0;
        }

        if (decimalPlaces >= 10) {
            // �������淶Χ����̬����
            return createDecimalFormat(decimalPlaces).format(value);
        }

        // ʹ�û���ĸ�ʽ����
        return FORMAT_CACHE.get()[decimalPlaces].format(value);
    }

    /**
     * ����С��λ����ʹ��BigDecimal��setScale������
     * 
     * @param value         ��ֵ
     * @param decimalPlaces С��λ��
     * @return ���ú��BigDecimal
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
 * ���ݴ������
 */
@Slf4j
@Service
public class DataProcessService {

    @Autowired
    private DataCalculationStrategyManager strategyManager;

    /**
     * ��������
     * 
     * @param inputData  �����double����
     * @param config     ���ö���
     * @return �������ַ���
     */
    public String processData(Double inputData, MeasureDataVO config) {
        // 1. ��ֵ����ֵ���
        if (isNullOrZero(inputData)) {
            return formatNullOrZero(inputData);
        }

        // 2. ת��ΪBigDecimal�����⾫�ȶ�ʧ��
        BigDecimal value = BigDecimal.valueOf(inputData);

        // 3. ִ�м������
        BigDecimal calculated = strategyManager.calculate(value, config);

        // 4. ��ʽ�����
        Integer decimalPlaces = config.getDecimalPlaces() != null ? config.getDecimalPlaces() : 2;
        String result = NumberFormatUtil.format(calculated, decimalPlaces);

        log.debug("���ݴ������: {} -> {}, ����: {}", inputData, result, config.getMeasureCode());

        return result;
    }

    /**
     * ������������
     * 
     * @param inputDataList ���������б�
     * @param config        ���ö���
     * @return �������ַ����б�
     */
    public java.util.List<String> batchProcessData(java.util.List<Double> inputDataList, MeasureDataVO config) {
        return inputDataList.stream()
                .map(data -> processData(data, config))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * �ж��Ƿ�Ϊnull����
     */
    private boolean isNullOrZero(Double value) {
        if (value == null) {
            return true;
        }

        // ʹ��BigDecimal�Ƚϣ����⸡������������
        BigDecimal bd = BigDecimal.valueOf(value);
        return bd.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * ��ʽ��null����ֵ
     */
    private String formatNullOrZero(Double value) {
        if (value == null) {
            return null;
        }

        // �ж�������0����0.00
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
 * ���ݴ���������
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
        // ������� + ���� + �������� + ����2λС��
        MeasureDataVO config = createConfig("Y", "Y", 2, 2);
        String result = dataProcessService.processData(5000000.0, config);
        assertEquals("5", result); // 5000000 / 1000000 = 5
    }

    @Test
    void testAmountDomesticDivide() {
        // ������� + ���� + �������� + ����2λС��
        MeasureDataVO config = createConfig("Y", "N", 2, 2);
        String result = dataProcessService.processData(500000000.0, config);
        assertEquals("5", result); // 500000000 / 100000000 = 5
    }

    @Test
    void testAmountDomesticDivideWithDecimal() {
        // ������� + ���� + �������� + ����2λС��
        MeasureDataVO config = createConfig("Y", "N", 2, 2);
        String result = dataProcessService.processData(123456789.0, config);
        assertEquals("1.23", result); // 123456789 / 100000000 = 1.23456789 -> 1.23
    }

    @Test
    void testNonAmountMultiply() {
        // �ǽ������ + ����100 + ����2λС��
        MeasureDataVO config = createConfig("N", "Y", 3, 2);
        String result = dataProcessService.processData(0.8523, config);
        assertEquals("85.23", result); // 0.8523 * 100 = 85.23
    }

    @Test
    void testNonAmountNoChange() {
        // �ǽ������ + ������ + ����0λС��
        MeasureDataVO config = createConfig("N", "N", 1, 0);
        String result = dataProcessService.processData(12345.67, config);
        assertEquals("12346", result); // �������뵽����
    }

    @Test
    void testDecimalPlacesZero() {
        // ����0λС��
        MeasureDataVO config = createConfig("Y", "Y", 2, 0);
        String result = dataProcessService.processData(1234567.0, config);
        assertEquals("1", result); // 1234567 / 1000000 = 1.234567 -> 1
    }

    @Test
    void testDecimalPlacesOne() {
        // ����1λС��
        MeasureDataVO config = createConfig("Y", "Y", 2, 1);
        String result = dataProcessService.processData(1567890.0, config);
        assertEquals("1.6", result); // 1567890 / 1000000 = 1.56789 -> 1.6
    }

    @Test
    void testComplexScenario() {
        // ���ӳ�������� + ���� + ���� + 2λС��
        MeasureDataVO config = createConfig("Y", "N", 2, 2);
        String result = dataProcessService.processData(987654321.12, config);
        assertEquals("9.88", result); // 987654321.12 / 100000000 = 9.8765... -> 9.88
    }

    /**
     * �������ö���
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