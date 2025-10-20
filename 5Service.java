package org.example.service;

import org.example.entity.OpMetric;

import java.util.List;
import java.util.Map;

/**
 * ָ���������ӿ�
 */
public interface OpMetricService {

    /**
     * ����1����ѯָ���б����������б�
     * ÿ��OpMetric�������List<OpMeasure>
     *
     * @return ָ���б�
     */
    List<OpMetric> listMetricsWithMeasures();

    /**
     * ����2������Map<metricCode+measureCode, OpMeasure>
     * key��ʽ��metricCode + "_" + measureCode
     * value��OpMeasure�����JSON�ַ�����toString()
     *
     * @return Map<String, String>
     */
    Map<String, String> getMetricMeasureMap();

    /**
     * ����3����ѯָ���б�������������֯�㼶��
     * ÿ��OpMetric������
     * - List<String> orgLevels
     * - List<String> domainCodes
     *
     * @return ָ���б�
     */
    List<OpMetric> listMetricsWithDomainsAndOrgs();

    /**
     * ����ָ��ID�б��ѯָ�꣨����������
     *
     * @param metricIds ָ��ID�б�
     * @return ָ���б�
     */
    List<OpMetric> listMetricsWithMeasuresByIds(List<Long> metricIds);
}