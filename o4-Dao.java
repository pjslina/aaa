package org.example.mapper;

import org.example.dto.OpMetric;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface OpMetricMapper {

    List<OpMetric> selectMetricsWithMeasures();

    List<Map<String, String>> selectMetricMeasureMap();

    List<Map<String, Object>> selectMetricDomainCodes();

    List<Map<String, Object>> selectMetricOrgLevels();
}
