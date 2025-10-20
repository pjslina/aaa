package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * ����ʵ��
 */
@Data
public class OpMeasure implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String measureCode;
    private String enName;
    private String cnName;
    private String unit;
    private String fixedValue;
    private String status;
    private Date createTime;
    private Date updateTime;
}
package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * ָ��ʵ��
 */
@Data
public class OpMetric implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long metricId;
    private String metricCode;
    private String metricName;
    private String currency;
    private String status;
    private Date createTime;
    private Date updateTime;

    // ��������֯�㼶�б�
    private List<String> orgLevels;

    // ������������б�
    private List<String> domainCodes;

    // �����Ķ����б�
    private List<OpMeasure> measures;
}
package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * ��ʵ��
 */
@Data
public class OpMetricDomain implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String domainCode;
    private String domainNameCn;
    private String domainNameEn;
    private String status;
    private Date createTime;
    private Date updateTime;
}
package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * ��֯�㼶ʵ��
 */
@Data
public class OpMetricOrg implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String orgLevel;
    private String status;
    private Date createTime;
    private Date updateTime;
}
package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * ������ϵʵ��
 */
@Data
public class OpMetricRel implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long metricId;
    private Long relId;
    private Integer relType; // 1-��2-��֯�㼶��3-����
    private String status;
    private Date createTime;
    private Date updateTime;
}