package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.OpMeasure;
import org.example.entity.OpMetric;

import java.util.List;

/**
 * 指标度量Mapper接口
 */
@Mapper
public interface OpMetricMapper {

    /**
     * 查询所有指标ID
     */
    List<Long> selectAllMetricIds();

    /**
     * 根据指标ID列表查询指标基本信息
     */
    List<OpMetric> selectMetricsByIds(@Param("metricIds") List<Long> metricIds);

    /**
     * 根据指标ID查询关联的度量列表
     */
    List<OpMeasure> selectMeasuresByMetricId(@Param("metricId") Long metricId);

    /**
     * 批量查询指标关联的度量列表
     */
    List<OpMeasure> selectMeasuresByMetricIds(@Param("metricIds") List<Long> metricIds);

    /**
     * 根据指标ID查询关联的域编码列表
     */
    List<String> selectDomainCodesByMetricId(@Param("metricId") Long metricId);

    /**
     * 批量查询指标关联的域编码列表
     */
    List<String> selectDomainCodesByMetricIds(@Param("metricIds") List<Long> metricIds);

    /**
     * 根据指标ID查询关联的组织层级列表
     */
    List<String> selectOrgLevelsByMetricId(@Param("metricId") Long metricId);

    /**
     * 批量查询指标关联的组织层级列表
     */
    List<String> selectOrgLevelsByMetricIds(@Param("metricIds") List<Long> metricIds);
}