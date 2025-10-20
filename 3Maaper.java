package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.OpMeasure;
import org.example.entity.OpMetric;

import java.util.List;

/**
 * ָ�����Mapper�ӿ�
 */
@Mapper
public interface OpMetricMapper {

    /**
     * ��ѯ����ָ��ID
     */
    List<Long> selectAllMetricIds();

    /**
     * ����ָ��ID�б��ѯָ�������Ϣ
     */
    List<OpMetric> selectMetricsByIds(@Param("metricIds") List<Long> metricIds);

    /**
     * ����ָ��ID��ѯ�����Ķ����б�
     */
    List<OpMeasure> selectMeasuresByMetricId(@Param("metricId") Long metricId);

    /**
     * ������ѯָ������Ķ����б�
     */
    List<OpMeasure> selectMeasuresByMetricIds(@Param("metricIds") List<Long> metricIds);

    /**
     * ����ָ��ID��ѯ������������б�
     */
    List<String> selectDomainCodesByMetricId(@Param("metricId") Long metricId);

    /**
     * ������ѯָ�������������б�
     */
    List<String> selectDomainCodesByMetricIds(@Param("metricIds") List<Long> metricIds);

    /**
     * ����ָ��ID��ѯ��������֯�㼶�б�
     */
    List<String> selectOrgLevelsByMetricId(@Param("metricId") Long metricId);

    /**
     * ������ѯָ���������֯�㼶�б�
     */
    List<String> selectOrgLevelsByMetricIds(@Param("metricIds") List<Long> metricIds);
}