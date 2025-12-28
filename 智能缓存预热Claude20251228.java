异步访问日志记录与智能缓存预热完整解决方案
我将为您提供一个生产级别的完整解决方案，包括数据库设计、核心代码实现和架构说明。
一、数据库设计
1.1 访问日志表设计
-- 访问日志表
CREATE TABLE `api_access_log` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `service_class` VARCHAR(255) NOT NULL COMMENT 'Service类全限定名',
  `method_name` VARCHAR(128) NOT NULL COMMENT '方法名',
  `method_signature` VARCHAR(512) NOT NULL COMMENT '方法签名(含参数类型)',
  `request_params` TEXT NOT NULL COMMENT '请求参数JSON',
  `param_class_name` VARCHAR(255) NOT NULL COMMENT '参数类全限定名',
  `redis_key` VARCHAR(512) NOT NULL COMMENT 'Redis缓存Key',
  `cache_name` VARCHAR(64) NOT NULL COMMENT '缓存名称',
  `module_prefix` VARCHAR(64) NOT NULL COMMENT '模块前缀',
  `access_time` DATETIME NOT NULL COMMENT '访问时间',
  `access_count` INT(11) DEFAULT 1 COMMENT '访问次数(相同key累加)',
  `last_access_time` DATETIME NOT NULL COMMENT '最后访问时间',
  `priority_score` DECIMAL(10,2) DEFAULT 0.00 COMMENT '优先级分数',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redis_key` (`redis_key`),
  KEY `idx_service_method` (`service_class`, `method_name`),
  KEY `idx_last_access_time` (`last_access_time`),
  KEY `idx_priority_score` (`priority_score` DESC),
  KEY `idx_access_count` (`access_count` DESC),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API访问日志表';

-- 预热任务执行记录表
CREATE TABLE `cache_preheat_task_log` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_batch_id` VARCHAR(64) NOT NULL COMMENT '任务批次ID',
  `total_count` INT(11) DEFAULT 0 COMMENT '总预热数量',
  `success_count` INT(11) DEFAULT 0 COMMENT '成功数量',
  `fail_count` INT(11) DEFAULT 0 COMMENT '失败数量',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME COMMENT '结束时间',
  `duration_seconds` INT(11) COMMENT '执行时长(秒)',
  `status` VARCHAR(32) NOT NULL COMMENT '状态:RUNNING/SUCCESS/FAILED',
  `error_message` TEXT COMMENT '错误信息',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_batch_id` (`task_batch_id`),
  KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缓存预热任务执行日志';

二、核心代码实现
2.1 配置类
package com.company.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存预热配置
 */
@Configuration
@ConfigurationProperties(prefix = "cache.preheat")
public class CachePreheatConfig {
    
    /**
     * 是否启用访问日志记录
     */
    private boolean enableAccessLog = true;
    
    /**
     * 是否启用缓存预热
     */
    private boolean enablePreheat = true;
    
    /**
     * 预热任务执行间隔(cron表达式), 默认每8小时执行一次
     */
    private String preheatCron = "0 0 */8 * * ?";
    
    /**
     * 预热时请求间隔最小值(毫秒)
     */
    private int requestIntervalMin = 1000;
    
    /**
     * 预热时请求间隔最大值(毫秒)
     */
    private int requestIntervalMax = 5000;
    
    /**
     * 访问日志保留天数
     */
    private int logRetentionDays = 30;
    
    /**
     * 数据清理任务执行间隔(cron表达式), 默认每天凌晨2点执行
     */
    private String cleanupCron = "0 0 2 * * ?";
    
    /**
     * 优先级算法-时效性权重(0-1之间)
     */
    private double timelinessWeight = 0.6;
    
    /**
     * 优先级算法-频率权重(0-1之间)
     */
    private double frequencyWeight = 0.4;
    
    /**
     * 时效性衰减因子(天)
     */
    private int timelinessDecayDays = 7;
    
    /**
     * 单次预热最大数量
     */
    private int maxPreheatCount = 1000;
    
    /**
     * Redis缓存过期时间(秒), 默认24小时
     */
    private int cacheExpireSeconds = 86400;
    
    // Getters and Setters
    public boolean isEnableAccessLog() {
        return enableAccessLog;
    }

    public void setEnableAccessLog(boolean enableAccessLog) {
        this.enableAccessLog = enableAccessLog;
    }

    public boolean isEnablePreheat() {
        return enablePreheat;
    }

    public void setEnablePreheat(boolean enablePreheat) {
        this.enablePreheat = enablePreheat;
    }

    public String getPreheatCron() {
        return preheatCron;
    }

    public void setPreheatCron(String preheatCron) {
        this.preheatCron = preheatCron;
    }

    public int getRequestIntervalMin() {
        return requestIntervalMin;
    }

    public void setRequestIntervalMin(int requestIntervalMin) {
        this.requestIntervalMin = requestIntervalMin;
    }

    public int getRequestIntervalMax() {
        return requestIntervalMax;
    }

    public void setRequestIntervalMax(int requestIntervalMax) {
        this.requestIntervalMax = requestIntervalMax;
    }

    public int getLogRetentionDays() {
        return logRetentionDays;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public double getTimelinessWeight() {
        return timelinessWeight;
    }

    public void setTimelinessWeight(double timelinessWeight) {
        this.timelinessWeight = timelinessWeight;
    }

    public double getFrequencyWeight() {
        return frequencyWeight;
    }

    public void setFrequencyWeight(double frequencyWeight) {
        this.frequencyWeight = frequencyWeight;
    }

    public int getTimelinessDecayDays() {
        return timelinessDecayDays;
    }

    public void setTimelinessDecayDays(int timelinessDecayDays) {
        this.timelinessDecayDays = timelinessDecayDays;
    }

    public int getMaxPreheatCount() {
        return maxPreheatCount;
    }

    public void setMaxPreheatCount(int maxPreheatCount) {
        this.maxPreheatCount = maxPreheatCount;
    }

    public int getCacheExpireSeconds() {
        return cacheExpireSeconds;
    }

    public void setCacheExpireSeconds(int cacheExpireSeconds) {
        this.cacheExpireSeconds = cacheExpireSeconds;
    }
}

2.2 实体类
package com.company.cache.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * API访问日志实体
 */
public class ApiAccessLog implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private Long id;
    private String serviceClass;
    private String methodName;
    private String methodSignature;
    private String requestParams;
    private String paramClassName;
    private String redisKey;
    private String cacheName;
    private String modulePrefix;
    private Date accessTime;
    private Integer accessCount;
    private Date lastAccessTime;
    private BigDecimal priorityScore;
    private Date createTime;
    private Date updateTime;
    
    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getServiceClass() {
        return serviceClass;
    }

    public void setServiceClass(String serviceClass) {
        this.serviceClass = serviceClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public void setMethodSignature(String methodSignature) {
        this.methodSignature = methodSignature;
    }

    public String getRequestParams() {
        return requestParams;
    }

    public void setRequestParams(String requestParams) {
        this.requestParams = requestParams;
    }

    public String getParamClassName() {
        return paramClassName;
    }

    public void setParamClassName(String paramClassName) {
        this.paramClassName = paramClassName;
    }

    public String getRedisKey() {
        return redisKey;
    }

    public void setRedisKey(String redisKey) {
        this.redisKey = redisKey;
    }

    public String getCacheName() {
        return cacheName;
    }

    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    public String getModulePrefix() {
        return modulePrefix;
    }

    public void setModulePrefix(String modulePrefix) {
        this.modulePrefix = modulePrefix;
    }

    public Date getAccessTime() {
        return accessTime;
    }

    public void setAccessTime(Date accessTime) {
        this.accessTime = accessTime;
    }

    public Integer getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }

    public Date getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(Date lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }

    public BigDecimal getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(BigDecimal priorityScore) {
        this.priorityScore = priorityScore;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}

package com.company.cache.entity;

import java.io.Serializable;
import java.util.Date;

/**
 * 预热任务执行日志
 */
public class CachePreheatTaskLog implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private Long id;
    private String taskBatchId;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private Date startTime;
    private Date endTime;
    private Integer durationSeconds;
    private String status;
    private String errorMessage;
    private Date createTime;
    
    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskBatchId() {
        return taskBatchId;
    }

    public void setTaskBatchId(String taskBatchId) {
        this.taskBatchId = taskBatchId;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Integer successCount) {
        this.successCount = successCount;
    }

    public Integer getFailCount() {
        return failCount;
    }

    public void setFailCount(Integer failCount) {
        this.failCount = failCount;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}

2.3 DAO层
package com.company.cache.dao;

import com.company.cache.entity.ApiAccessLog;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * API访问日志DAO
 */
@Mapper
@Repository
public interface ApiAccessLogMapper {
    
    /**
     * 插入访问日志
     */
    @Insert("INSERT INTO api_access_log (service_class, method_name, method_signature, " +
            "request_params, param_class_name, redis_key, cache_name, module_prefix, " +
            "access_time, last_access_time, access_count, priority_score) " +
            "VALUES (#{serviceClass}, #{methodName}, #{methodSignature}, #{requestParams}, " +
            "#{paramClassName}, #{redisKey}, #{cacheName}, #{modulePrefix}, #{accessTime}, " +
            "#{lastAccessTime}, #{accessCount}, #{priorityScore})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApiAccessLog log);
    
    /**
     * 根据redisKey更新访问信息
     */
    @Update("UPDATE api_access_log SET access_count = access_count + 1, " +
            "last_access_time = #{lastAccessTime}, priority_score = #{priorityScore}, " +
            "update_time = NOW() WHERE redis_key = #{redisKey}")
    int updateByRedisKey(@Param("redisKey") String redisKey, 
                         @Param("lastAccessTime") Date lastAccessTime,
                         @Param("priorityScore") java.math.BigDecimal priorityScore);
    
    /**
     * 根据redisKey查询
     */
    @Select("SELECT * FROM api_access_log WHERE redis_key = #{redisKey}")
    ApiAccessLog selectByRedisKey(@Param("redisKey") String redisKey);
    
    /**
     * 按优先级分数查询前N条记录
     */
    @Select("SELECT * FROM api_access_log ORDER BY priority_score DESC LIMIT #{limit}")
    List<ApiAccessLog> selectTopByPriorityScore(@Param("limit") int limit);
    
    /**
     * 批量更新优先级分数
     */
    @Update("<script>" +
            "UPDATE api_access_log SET priority_score = #{priorityScore}, update_time = NOW() " +
            "WHERE id = #{id}" +
            "</script>")
    int updatePriorityScore(@Param("id") Long id, 
                           @Param("priorityScore") java.math.BigDecimal priorityScore);
    
    /**
     * 删除指定日期之前的记录
     */
    @Delete("DELETE FROM api_access_log WHERE create_time < #{beforeDate}")
    int deleteByCreateTimeBefore(@Param("beforeDate") Date beforeDate);
    
    /**
     * 查询所有记录(用于批量计算优先级)
     */
    @Select("SELECT id, access_count, last_access_time, create_time FROM api_access_log")
    List<ApiAccessLog> selectAllForPriorityCalculation();
    
    /**
     * 统计总记录数
     */
    @Select("SELECT COUNT(*) FROM api_access_log")
    int countTotal();
}

package com.company.cache.dao;

import com.company.cache.entity.CachePreheatTaskLog;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

/**
 * 预热任务日志DAO
 */
@Mapper
@Repository
public interface CachePreheatTaskLogMapper {
    
    /**
     * 插入任务日志
     */
    @Insert("INSERT INTO cache_preheat_task_log (task_batch_id, total_count, success_count, " +
            "fail_count, start_time, end_time, duration_seconds, status, error_message) " +
            "VALUES (#{taskBatchId}, #{totalCount}, #{successCount}, #{failCount}, " +
            "#{startTime}, #{endTime}, #{durationSeconds}, #{status}, #{errorMessage})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CachePreheatTaskLog log);
    
    /**
     * 更新任务日志
     */
    @Update("UPDATE cache_preheat_task_log SET total_count = #{totalCount}, " +
            "success_count = #{successCount}, fail_count = #{failCount}, " +
            "end_time = #{endTime}, duration_seconds = #{durationSeconds}, " +
            "status = #{status}, error_message = #{errorMessage} " +
            "WHERE task_batch_id = #{taskBatchId}")
    int updateByBatchId(CachePreheatTaskLog log);
}

2.4 核心服务层
package com.company.cache.service;

import com.company.cache.config.CachePreheatConfig;
import com.company.cache.dao.ApiAccessLogMapper;
import com.company.cache.entity.ApiAccessLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 访问日志服务
 */
@Service
public class ApiAccessLogService {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiAccessLogService.class);
    
    @Autowired
    private ApiAccessLogMapper apiAccessLogMapper;
    
    @Autowired
    private CachePreheatConfig config;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 记录访问日志(同一redisKey会累加访问次数)
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordAccessLog(String serviceClass, String methodName, String methodSignature,
                                Object requestParam, String paramClassName, String redisKey,
                                String cacheName, String modulePrefix) {
        try {
            Date now = new Date();
            
            // 查询是否已存在该redisKey的记录
            ApiAccessLog existingLog = apiAccessLogMapper.selectByRedisKey(redisKey);
            
            if (existingLog != null) {
                // 已存在,更新访问次数和最后访问时间
                BigDecimal priorityScore = calculatePriorityScore(
                    existingLog.getAccessCount() + 1, 
                    now, 
                    existingLog.getCreateTime()
                );
                apiAccessLogMapper.updateByRedisKey(redisKey, now, priorityScore);
                logger.debug("Updated access log for redisKey: {}, new count: {}", 
                    redisKey, existingLog.getAccessCount() + 1);
            } else {
                // 不存在,插入新记录
                ApiAccessLog log = new ApiAccessLog();
                log.setServiceClass(serviceClass);
                log.setMethodName(methodName);
                log.setMethodSignature(methodSignature);
                log.setRequestParams(objectMapper.writeValueAsString(requestParam));
                log.setParamClassName(paramClassName);
                log.setRedisKey(redisKey);
                log.setCacheName(cacheName);
                log.setModulePrefix(modulePrefix);
                log.setAccessTime(now);
                log.setLastAccessTime(now);
                log.setAccessCount(1);
                log.setPriorityScore(calculatePriorityScore(1, now, now));
                
                apiAccessLogMapper.insert(log);
                logger.debug("Inserted new access log for redisKey: {}", redisKey);
            }
        } catch (Exception e) {
            logger.error("Failed to record access log for redisKey: " + redisKey, e);
            // 不抛出异常,避免影响主流程
        }
    }
    
    /**
     * 计算优先级分数
     * 分数 = 时效性分数 * 时效权重 + 频率分数 * 频率权重
     */
    public BigDecimal calculatePriorityScore(int accessCount, Date lastAccessTime, Date createTime) {
        // 计算时效性分数(0-100分)
        long daysSinceLastAccess = TimeUnit.MILLISECONDS.toDays(
            System.currentTimeMillis() - lastAccessTime.getTime()
        );
        double timelinessScore = Math.max(0, 100 - (daysSinceLastAccess * 100.0 / config.getTimelinessDecayDays()));
        
        // 计算频率分数(对数缩放,0-100分)
        double frequencyScore = Math.min(100, Math.log10(accessCount + 1) * 50);
        
        // 加权计算总分
        double totalScore = timelinessScore * config.getTimelinessWeight() + 
                           frequencyScore * config.getFrequencyWeight();
        
        return BigDecimal.valueOf(totalScore).setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * 批量重新计算所有记录的优先级分数
     */
    @Transactional(rollbackFor = Exception.class)
    public void recalculateAllPriorityScores() {
        try {
            List<ApiAccessLog> logs = apiAccessLogMapper.selectAllForPriorityCalculation();
            logger.info("Start recalculating priority scores for {} records", logs.size());
            
            int updatedCount = 0;
            for (ApiAccessLog log : logs) {
                BigDecimal newScore = calculatePriorityScore(
                    log.getAccessCount(),
                    log.getLastAccessTime(),
                    log.getCreateTime()
                );
                apiAccessLogMapper.updatePriorityScore(log.getId(), newScore);
                updatedCount++;
            }
            
            logger.info("Successfully recalculated priority scores for {} records", updatedCount);
        } catch (Exception e) {
            logger.error("Failed to recalculate priority scores", e);
            throw new RuntimeException("Failed to recalculate priority scores", e);
        }
    }
    
    /**
     * 获取按优先级排序的前N条记录
     */
    public List<ApiAccessLog> getTopPriorityLogs(int limit) {
        return apiAccessLogMapper.selectTopByPriorityScore(limit);
    }
    
    /**
     * 清理过期数据
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanupExpiredLogs() {
        try {
            Date beforeDate = new Date(System.currentTimeMillis() - 
                TimeUnit.DAYS.toMillis(config.getLogRetentionDays()));
            
            int deletedCount = apiAccessLogMapper.deleteByCreateTimeBefore(beforeDate);
            logger.info("Cleaned up {} expired access logs before {}", deletedCount, beforeDate);
            return deletedCount;
        } catch (Exception e) {
            logger.error("Failed to cleanup expired logs", e);
            throw new RuntimeException("Failed to cleanup expired logs", e);
        }
    }
    
    /**
     * 获取总记录数
     */
    public int getTotalCount() {
        return apiAccessLogMapper.countTotal();
    }
}

package com.company.cache.service;

import com.company.cache.config.CachePreheatConfig;
import com.company.cache.dao.CachePreheatTaskLogMapper;
import com.company.cache.entity.ApiAccessLog;
import com.company.cache.entity.CachePreheatTaskLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存预热服务
 */
@Service
public class CachePreheatService {
    
    private static final Logger logger = LoggerFactory.getLogger(CachePreheatService.class);
    
    @Autowired
    private ApiAccessLogService apiAccessLogService;
    
    @Autowired
    private CachePreheatTaskLogMapper taskLogMapper;
    
    @Autowired
    private CachePreheatConfig config;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();
    
    /**
     * 执行缓存预热任务
     */
    public void executePreheatTask() {
        if (!config.isEnablePreheat()) {
            logger.info("Cache preheat is disabled, skip execution");
            return;
        }
        
        String batchId = UUID.randomUUID().toString();
        Date startTime = new Date();
        
        logger.info("Starting cache preheat task, batchId: {}", batchId);
        
        // 创建任务日志
        CachePreheatTaskLog taskLog = new CachePreheatTaskLog();
        taskLog.setTaskBatchId(batchId);
        taskLog.setStartTime(startTime);
        taskLog.setStatus("RUNNING");
        taskLogMapper.insert(taskLog);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        
        try {
            // 1. 重新计算所有记录的优先级分数
            apiAccessLogService.recalculateAllPriorityScores();
            
            // 2. 获取高优先级的记录
            int preheatLimit = Math.min(config.getMaxPreheatCount(), 
                apiAccessLogService.getTotalCount());
            List<ApiAccessLog> logsToP reheat = apiAccessLogService.getTopPriorityLogs(preheatLimit);
            
            logger.info("Got {} high-priority logs to preheat", logsToP reheat.size());
            
            taskLog.setTotalCount(logsToP reheat.size());
            
            // 3. 逐个预热
            for (ApiAccessLog log : logsToP reheat) {
                try {
                    preheatSingleCache(log);
                    successCount.incrementAndGet();
                    
                    // 添加随机延迟
                    randomSleep();
                    
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    logger.error("Failed to preheat cache for redisKey: " + log.getRedisKey(), e);
                }
            }
            
            // 4. 更新任务日志
            Date endTime = new Date();
            taskLog.setEndTime(endTime);
            taskLog.setSuccessCount(successCount.get());
            taskLog.setFailCount(failCount.get());
            taskLog.setDurationSeconds((int) ((endTime.getTime() - startTime.getTime()) / 1000));
            taskLog.setStatus("SUCCESS");
            
            taskLogMapper.updateByBatchId(taskLog);
            
            logger.info("Cache preheat task completed, batchId: {}, success: {}, fail: {}", 
                batchId, successCount.get(), failCount.get());
            
        } catch (Exception e) {
            logger.error("Cache preheat task failed, batchId: " + batchId, e);
            
            // 更新任务日志为失败
            taskLog.setEndTime(new Date());
            taskLog.setSuccessCount(successCount.get());
            taskLog.setFailCount(failCount.get());
            taskLog.setStatus("FAILED");
            taskLog.setErrorMessage(e.getMessage());
taskLogMapper.updateByBatchId(taskLog);
}
}

/**
 * 预热单个缓存
 */
private void preheatSingleCache(ApiAccessLog log) throws Exception {
    // 1. 获取Service Bean
    Class<?> serviceClass = Class.forName(log.getServiceClass());
    Object serviceBean = applicationContext.getBean(serviceClass);
    
    // 2. 反序列化请求参数
    Class<?> paramClass = Class.forName(log.getParamClassName());
    Object requestParam = objectMapper.readValue(log.getRequestParams(), paramClass);
    
    // 3. 获取方法并调用
    Method method = findMethod(serviceClass, log.getMethodName(), paramClass);
    if (method == null) {
        throw new NoSuchMethodException("Method not found: " + log.getMethodSignature());
    }
    
    Object result = method.invoke(serviceBean, requestParam);
    
    // 4. 将结果存入Redis
    redisTemplate.opsForValue().set(
        log.getRedisKey(), 
        result, 
        config.getCacheExpireSeconds(), 
        TimeUnit.SECONDS
    );
    
    logger.debug("Successfully preheated cache for redisKey: {}", log.getRedisKey());
}

/**
 * 查找匹配的方法
 */
private Method findMethod(Class<?> clazz, String methodName, Class<?> paramClass) {
    for (Method method : clazz.getMethods()) {
        if (method.getName().equals(methodName)) {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0].equals(paramClass)) {
                return method;
            }
        }
    }
    return null;
}

/**
 * 随机休眠
 */
private void randomSleep() {
    try {
        int sleepTime = config.getRequestIntervalMin() + 
            random.nextInt(config.getRequestIntervalMax() - config.getRequestIntervalMin());
        Thread.sleep(sleepTime);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.warn("Sleep interrupted", e);
    }
}

### 2.5 异步处理器
```java
package com.company.cache.async;

import com.company.cache.service.ApiAccessLogService;
import com.company.framework.async.IAsyncProcessHandler;
import com.company.framework.exception.ApplicationException;
import com.company.framework.vo.AjaxMessageVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 访问日志异步处理器
 */
@Component("accessLogAsyncHandler")
public class AccessLogAsyncHandler implements IAsyncProcessHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(AccessLogAsyncHandler.class);
    
    @Autowired
    private ApiAccessLogService apiAccessLogService;
    
    @Override
    public AjaxMessageVo handlerProcess(Serializable context) throws ApplicationException {
        try {
            if (context instanceof AccessLogContext) {
                AccessLogContext logContext = (AccessLogContext) context;
                
                apiAccessLogService.recordAccessLog(
                    logContext.getServiceClass(),
                    logContext.getMethodName(),
                    logContext.getMethodSignature(),
                    logContext.getRequestParam(),
                    logContext.getParamClassName(),
                    logContext.getRedisKey(),
                    logContext.getCacheName(),
                    logContext.getModulePrefix()
                );
                
                logger.debug("Successfully processed access log for redisKey: {}", 
                    logContext.getRedisKey());
                
                return AjaxMessageVo.success("Access log recorded");
            } else {
                throw new ApplicationException("Invalid context type");
            }
        } catch (Exception e) {
            logger.error("Failed to process access log", e);
            return AjaxMessageVo.failure("Failed to record access log: " + e.getMessage());
        }
    }
}
```
```java
package com.company.cache.async;

import java.io.Serializable;

/**
 * 访问日志上下文
 */
public class AccessLogContext implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String serviceClass;
    private String methodName;
    private String methodSignature;
    private Object requestParam;
    private String paramClassName;
    private String redisKey;
    private String cacheName;
    private String modulePrefix;
    
    public AccessLogContext() {
    }
    
    public AccessLogContext(String serviceClass, String methodName, String methodSignature,
                           Object requestParam, String paramClassName, String redisKey,
                           String cacheName, String modulePrefix) {
        this.serviceClass = serviceClass;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.requestParam = requestParam;
        this.paramClassName = paramClassName;
        this.redisKey = redisKey;
        this.cacheName = cacheName;
        this.modulePrefix = modulePrefix;
    }
    
    // Getters and Setters
    public String getServiceClass() {
        return serviceClass;
    }

    public void setServiceClass(String serviceClass) {
        this.serviceClass = serviceClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public void setMethodSignature(String methodSignature) {
        this.methodSignature = methodSignature;
    }

    public Object getRequestParam() {
        return requestParam;
    }

    public void setRequestParam(Object requestParam) {
        this.requestParam = requestParam;
    }

    public String getParamClassName() {
        return paramClassName;
    }

    public void setParamClassName(String paramClassName) {
        this.paramClassName = paramClassName;
    }

    public String getRedisKey() {
        return redisKey;
    }

    public void setRedisKey(String redisKey) {
        this.redisKey = redisKey;
    }

    public String getCacheName() {
        return cacheName;
    }

    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    public String getModulePrefix() {
        return modulePrefix;
    }

    public void setModulePrefix(String modulePrefix) {
        this.modulePrefix = modulePrefix;
    }
}
```

### 2.6 定时任务
```java
package com.company.cache.timer;

import com.company.cache.config.CachePreheatConfig;
import com.company.cache.service.CachePreheatService;
import com.company.framework.exception.ApplicationException;
import com.company.framework.timer.ITimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 缓存预热定时任务
 */
@Component("cachePreheatTimerTask")
public class CachePreheatTimerTask implements ITimerTask {
    
    private static final Logger logger = LoggerFactory.getLogger(CachePreheatTimerTask.class);
    
    @Autowired
    private CachePreheatService cachePreheatService;
    
    @Autowired
    private CachePreheatConfig config;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        if (!config.isEnablePreheat()) {
            logger.info("Cache preheat is disabled");
            return;
        }
        
        try {
            logger.info("Cache preheat timer task started");
            cachePreheatService.executePreheatTask();
            logger.info("Cache preheat timer task completed");
        } catch (Exception e) {
            logger.error("Cache preheat timer task failed", e);
            throw new ApplicationException("Cache preheat timer task failed", e);
        }
    }
}
```
```java
package com.company.cache.timer;

import com.company.cache.config.CachePreheatConfig;
import com.company.cache.service.ApiAccessLogService;
import com.company.framework.exception.ApplicationException;
import com.company.framework.timer.ITimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据清理定时任务
 */
@Component("dataCleanupTimerTask")
public class DataCleanupTimerTask implements ITimerTask {
    
    private static final Logger logger = LoggerFactory.getLogger(DataCleanupTimerTask.class);
    
    @Autowired
    private ApiAccessLogService apiAccessLogService;
    
    @Autowired
    private CachePreheatConfig config;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        try {
            logger.info("Data cleanup timer task started");
            int deletedCount = apiAccessLogService.cleanupExpiredLogs();
            logger.info("Data cleanup timer task completed, deleted {} records", deletedCount);
        } catch (Exception e) {
            logger.error("Data cleanup timer task failed", e);
            throw new ApplicationException("Data cleanup timer task failed", e);
        }
    }
}
```

### 2.7 AOP切面(自动记录访问日志)
```java
package com.company.cache.aspect;

import com.company.cache.annotation.CacheAccessLog;
import com.company.cache.async.AccessLogContext;
import com.company.cache.config.CachePreheatConfig;
import com.company.framework.async.AsyncTaskUtil;
import com.company.util.CacheKeyGenerator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 缓存访问日志AOP切面
 */
@Aspect
@Component
public class CacheAccessLogAspect {
    
    private static final Logger logger = LoggerFactory.getLogger(CacheAccessLogAspect.class);
    
    @Autowired
    private AsyncTaskUtil asyncTaskUtil;
    
    @Autowired
    private CachePreheatConfig config;
    
    @Around("@annotation(com.company.cache.annotation.CacheAccessLog)")
    public Object aroundCacheAccessLog(ProceedingJoinPoint joinPoint) throws Throwable {
        // 执行原方法
        Object result = joinPoint.proceed();
        
        // 如果未启用访问日志,直接返回
        if (!config.isEnableAccessLog()) {
            return result;
        }
        
        try {
            // 获取方法信息
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            CacheAccessLog annotation = method.getAnnotation(CacheAccessLog.class);
            
            // 获取参数
            Object[] args = joinPoint.getArgs();
            if (args.length == 0) {
                return result;
            }
            
            Object requestParam = args[0];
            
            // 生成Redis Key
            String redisKey = CacheKeyGenerator.generateKey(
                annotation.cacheName(),
                annotation.modulePrefix(),
                requestParam,
                annotation.keyFields()
            );
            
            // 构建访问日志上下文
            AccessLogContext context = new AccessLogContext(
                joinPoint.getTarget().getClass().getName(),
                method.getName(),
                signature.toLongString(),
                requestParam,
                requestParam.getClass().getName(),
                redisKey,
                annotation.cacheName(),
                annotation.modulePrefix()
            );
            
            // 异步记录访问日志
            asyncTaskUtil.invokeAsync("accessLogAsyncHandler", context);
            
        } catch (Exception e) {
            // 记录日志但不影响主流程
            logger.error("Failed to record cache access log", e);
        }
        
        return result;
    }
}
```

### 2.8 自定义注解
```java
package com.company.cache.annotation;

import java.lang.annotation.*;

/**
 * 缓存访问日志注解
 * 用于标记需要记录访问日志的Service方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CacheAccessLog {
    
    /**
     * 缓存名称
     */
    String cacheName();
    
    /**
     * 模块前缀
     */
    String modulePrefix();
    
    /**
     * 用于生成Redis Key的字段名
     */
    String[] keyFields();
}
```

### 2.9 使用示例
```java
package com.company.service;

import com.company.cache.annotation.CacheAccessLog;
import com.company.dto.MetricDTO;
import com.company.req.MetricReq;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 示例Service - 展示如何使用缓存访问日志
 */
@Service
public class MetricDataService {
    
    /**
     * 查询指标数据
     * 使用@CacheAccessLog注解自动记录访问日志
     */
    @CacheAccessLog(
        cacheName = "METRIC_DATA",
        modulePrefix = "METRIC",
        keyFields = {"startTime", "endTime", "metricType"}
    )
    public List<MetricDTO> queryMetrics(MetricReq req) {
        // 实际业务逻辑
        // ...
        return null;
    }
}
```

### 2.10 配置文件
```yaml
# application.yml
cache:
  preheat:
    # 是否启用访问日志记录
    enable-access-log: true
    # 是否启用缓存预热
    enable-preheat: true
    # 预热任务cron表达式(每8小时执行一次)
    preheat-cron: "0 0 */8 * * ?"
    # 预热请求间隔(毫秒)
    request-interval-min: 1000
    request-interval-max: 5000
    # 访问日志保留天数
    log-retention-days: 30
    # 数据清理cron表达式(每天凌晨2点执行)
    cleanup-cron: "0 0 2 * * ?"
    # 优先级算法权重
    timeliness-weight: 0.6
    frequency-weight: 0.4
    # 时效性衰减天数
    timeliness-decay-days: 7
    # 单次预热最大数量
    max-preheat-count: 1000
    # Redis缓存过期时间(秒)
    cache-expire-seconds: 86400
```

## 三、架构说明

### 3.1 核心流程

1. **访问日志记录流程**:
   - 用户调用Service方法(带@CacheAccessLog注解)
   - AOP切面拦截方法调用
   - 生成Redis Key
   - 异步记录访问信息到数据库
   - 同一Key会累加访问次数

2. **缓存预热流程**:
   - 定时任务触发
   - 重新计算所有记录的优先级分数
   - 按优先级获取Top N记录
   - 反序列化请求参数
   - 反射调用Service方法
   - 将结果存入Redis
   - 添加随机延迟避免压力突增

3. **数据清理流程**:
   - 定时任务触发
   - 删除超过保留期的记录
   - 优化数据库存储

### 3.2 优先级算法

**公式**: `总分 = 时效性分数 × 时效权重 + 频率分数 × 频率权重`

- **时效性分数**(0-100): 基于最后访问时间,越近分数越高
- **频率分数**(0-100): 基于访问次数,使用对数缩放避免极端值
- **权重可配置**: 默认时效性60%,频率40%

### 3.3 扩展性设计

1. **可配置参数**: 所有关键参数都可通过配置文件调整
2. **模块化组件**: 各组件职责单一,易于替换和扩展
3. **异步处理**: 访问日志记录不阻塞主流程
4. **反射调用**: 支持任意Service方法的动态调用

### 3.4 性能优化

1. **批量处理**: 优先级计算批量进行
2. **索引优化**: 数据库表建立合适索引
3. **随机延迟**: 避免对下游服务造成压力
4. **限流保护**: 单次预热有最大数量限制

### 3.5 监控与日志

1. **任务执行日志**: 记录每次预热任务的执行情况
2. **详细的DEBUG日志**: 便于问题排查
3. **异常处理**: 各层都有完善的异常处理机制

## 四、部署和使用

1. **执行数据库脚本**: 创建相关表
2. **添加Maven依赖**: Jackson、MyBatis等
3. **配置application.yml**: 根据实际情况调整参数
4. **在Service方法上添加@CacheAccessLog注解**
5. **启动应用**: 系统自动开始记录访问日志
6. **监控定时任务**: 查看预热任务执行情况

这个方案提供了完整的生产级别实现,具有良好的扩展性和可维护性,可以直接应用到您的项目中。

