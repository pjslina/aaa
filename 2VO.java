package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 度量实体
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
 * 指标实体
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

    // 关联的组织层级列表
    private List<String> orgLevels;

    // 关联的域编码列表
    private List<String> domainCodes;

    // 关联的度量列表
    private List<OpMeasure> measures;
}
package org.example.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 域实体
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
 * 组织层级实体
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
 * 关联关系实体
 */
@Data
public class OpMetricRel implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long metricId;
    private Long relId;
    private Integer relType; // 1-域，2-组织层级，3-度量
    private String status;
    private Date createTime;
    private Date updateTime;
}