基于您提供的缓存框架代码，我来补充完整的实现，包括CacheDataLoader的实现类和业务Service的调用方式。

1. CacheDataLoader 实现类
package com.example.service.impl;

import com.example.service.CacheDataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 用户信息数据加载器实现
 */
@Slf4j
@Component
public class UserDataLoader implements CacheDataLoader<UserQueryParams, UserVO> {
    
    @Override
    public UserVO loadData(UserQueryParams params) {
        try {
            log.info("开始从数据库加载用户数据, 参数: {}", params);
            
            // 这里实际调用Mapper或Repository从数据库加载数据
            UserVO user = userMapper.selectUserById(params.getUserId());
            
            if (user == null) {
                log.warn("未找到用户数据, userId: {}", params.getUserId());
                return null;
            }
            
            log.info("成功加载用户数据, userId: {}", params.getUserId());
            return user;
            
        } catch (Exception e) {
            log.error("加载用户数据异常, params: {}", params, e);
            throw new RuntimeException("数据加载失败", e);
        }
    }
}

/**
 * 元数据加载器实现
 */
@Slf4j
@Component
public class MetadataLoader implements CacheDataLoader<MetadataQuery, MetadataVO> {
    
    @Override
    public MetadataVO loadData(MetadataQuery params) {
        log.info("加载元数据, type: {}, code: {}", params.getType(), params.getCode());
        
        // 实际的数据加载逻辑
        return metadataMapper.selectMetadata(params);
    }
}

/**
 * 业务配置加载器
 */
@Slf4j
@Component 
public class ConfigDataLoader implements CacheDataLoader<ConfigQuery, ConfigVO> {
    
    @Override
    public ConfigVO loadData(ConfigQuery params) {
        log.info("加载业务配置, configType: {}, bizId: {}", 
                params.getConfigType(), params.getBizId());
        
        return configMapper.selectConfig(params);
    }
}

2. 参数对象定义
package com.example.vo;

import lombok.Data;
import java.io.Serializable;

/**
 * 用户查询参数
 */
@Data
public class UserQueryParams implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long userId;
    private String userCode;
    private Boolean includeDetail;
    
    public UserQueryParams(Long userId) {
        this.userId = userId;
    }
    
    public UserQueryParams(String userCode) {
        this.userCode = userCode;
    }
}

/**
 * 元数据查询参数
 */
@Data
public class MetadataQuery implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String type;
    private String code;
    private String system;
    
    public MetadataQuery(String type, String code) {
        this.type = type;
        this.code = code;
    }
}

/**
 * 配置查询参数
 */
@Data
public class ConfigQuery implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String configType;
    private String bizId;
    private String env;
    
    public ConfigQuery(String configType, String bizId) {
        this.configType = configType;
        this.bizId = bizId;
    }
}

3. 缓存配置实现
package com.example.config;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import org.springframework.stereotype.Component;

/**
 * 用户缓存配置
 */
@Component
public class UserCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return CacheConstants.ModulePrefix.USER;
    }
    
    @Override
    public long getExpireSeconds() {
        return CacheConstants.ExpireTime.BUSINESS;
    }
    
    @Override
    public long getRefreshThreshold() {
        return CacheConstants.REFRESH_THRESHOLD;
    }
    
    @Override
    public String[] getKeyFields() {
        // 指定使用userId字段作为缓存key的一部分
        return new String[]{"userId"};
    }
}

/**
 * 元数据缓存配置
 */
@Component
public class MetadataCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return CacheConstants.ModulePrefix.METADATA;
    }
    
    @Override
    public long getExpireSeconds() {
        return CacheConstants.ExpireTime.METADATA;
    }
    
    @Override
    public String[] getKeyFields() {
        return new String[]{"type", "code"};
    }
}

/**
 * 配置缓存配置
 */
@Component
public class ConfigCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return CacheConstants.ModulePrefix.CONFIG;
    }
    
    @Override
    public long getExpireSeconds() {
        return 12 * 60 * 60; // 12小时
    }
}

4. 业务Service完整实现
package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.config.UserCacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.UserQueryParams;
import com.example.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现 - 展示如何调用缓存框架
 */
@Slf4j
@Service
public class UserService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private UserCacheConfig userCacheConfig;
    
    @Autowired
    private UserDataLoader userDataLoader;
    
    // 也可以直接使用Lambda表达式实现CacheDataLoader
    private final CacheDataLoader<UserQueryParams, UserVO> lambdaLoader = 
        params -> {
            // 直接在这里实现数据加载逻辑
            log.info("通过Lambda加载用户数据: {}", params.getUserId());
            return userMapper.selectUserById(params.getUserId());
        };
    
    /**
     * 根据用户ID获取用户信息（使用缓存）
     */
    public UserVO getUserById(Long userId) {
        try {
            UserQueryParams params = new UserQueryParams(userId);
            
            // 调用缓存管理器获取数据
            UserVO user = cacheManager.getCachedData(
                params,                    // 查询参数
                userCacheConfig,          // 缓存配置
                userDataLoader,           // 数据加载器
                UserVO.class              // 返回类型
            );
            
            if (user == null) {
                log.warn("未找到用户信息, userId: {}", userId);
            }
            
            return user;
            
        } catch (Exception e) {
            log.error("获取用户信息异常, userId: {}", userId, e);
            throw new RuntimeException("获取用户信息失败", e);
        }
    }
    
    /**
     * 根据用户编码获取用户信息（使用Lambda表达式）
     */
    public UserVO getUserByCode(String userCode) {
        UserQueryParams params = new UserQueryParams(userCode);
        
        return cacheManager.getCachedData(
            params,
            userCacheConfig,
            lambdaLoader,  // 使用Lambda表达式
            UserVO.class
        );
    }
    
    /**
     * 强制刷新缓存
     */
    public void refreshUserCache(Long userId) {
        try {
            UserQueryParams params = new UserQueryParams(userId);
            
            cacheManager.refreshCache(
                params,
                userCacheConfig,
                userDataLoader
            );
            
            log.info("用户缓存刷新成功, userId: {}", userId);
            
        } catch (Exception e) {
            log.error("刷新用户缓存异常, userId: {}", userId, e);
        }
    }
}

5. 元数据服务示例

package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.config.MetadataCacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.MetadataQuery;
import com.example.vo.MetadataVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 元数据服务实现
 */
@Slf4j
@Service
public class MetadataService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private MetadataCacheConfig metadataConfig;
    
    /**
     * 使用匿名内部类实现CacheDataLoader
     */
    private final CacheDataLoader<MetadataQuery, MetadataVO> metadataLoader = 
        new CacheDataLoader<MetadataQuery, MetadataVO>() {
            @Override
            public MetadataVO loadData(MetadataQuery params) {
                log.info("加载元数据: type={}, code={}", params.getType(), params.getCode());
                return metadataMapper.selectByTypeAndCode(params.getType(), params.getCode());
            }
        };
    
    /**
     * 获取元数据
     */
    public MetadataVO getMetadata(String type, String code) {
        MetadataQuery params = new MetadataQuery(type, code);
        
        return cacheManager.getCachedData(
            params,
            metadataConfig,
            metadataLoader,
            MetadataVO.class
        );
    }
    
    /**
     * 批量获取元数据（每个都走缓存）
     */
    public List<MetadataVO> getMetadataList(List<MetadataQuery> queries) {
        return queries.stream()
            .map(query -> cacheManager.getCachedData(
                query, metadataConfig, metadataLoader, MetadataVO.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
}

6. 完整的VO和Mapper示例
package com.example.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 用户值对象
 */
@Data
public class UserVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long userId;
    private String userCode;
    private String userName;
    private String email;
    private String phone;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}

/**
 * 元数据值对象
 */
@Data
public class MetadataVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long id;
    private String type;
    private String code;
    private String name;
    private String value;
    private String description;
    private Integer sortOrder;
    private Integer status;
}

// Mapper接口示例
package com.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    UserVO selectUserById(@Param("userId") Long userId);
    UserVO selectUserByCode(@Param("userCode") String userCode);
}

@Mapper
public interface MetadataMapper {
    MetadataVO selectMetadata(@Param("query") MetadataQuery query);
    MetadataVO selectByTypeAndCode(@Param("type") String type, @Param("code") String code);
}

使用方式总结
定义参数对象：实现Serializable接口的查询参数

实现CacheConfig：配置缓存名称、过期时间等

实现CacheDataLoader：提供数据加载逻辑

在Service中调用：通过GenericCacheManager.getCachedData()方法

这样的设计使得缓存逻辑与业务逻辑完全解耦，业务Service只需要关注数据加载的具体实现，缓存框架会自动处理缓存命中、过期刷新、分布式锁等问题。
---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

您提出了一个很好的问题！确实，Set的无序性会导致缓存key不同，从而无法命中缓存。

在您提供的CacheKeyGenerator代码中，虽然对Collection类型进行了处理，但存在一个潜在问题。让我分析并修复这个问题：

问题分析
在CacheKeyGenerator.convertToString方法中：
private static String convertToString(Object value) {
    if (value == null) {
        return "";
    }
    
    if (value instanceof Collection) {
        Collection<?> collection = (Collection<?>) value;
        return collection.stream()
                .map(Object::toString)
                .sorted()  // 这里对元素进行了排序
                .collect(Collectors.joining(","));
    }
    
    // ... 其他代码
}

虽然代码中对集合元素进行了排序，但如果Set中的元素不是Comparable类型，或者排序逻辑有问题，仍然可能导致key不一致。

修复方案
让我为您提供一个更健壮的解决方案：

1. 修复CacheKeyGenerator

package com.example.cache;

// 其他import保持不变
import java.util.TreeSet;

/**
 * 增强的缓存Key生成器
 */
@Slf4j
public class CacheKeyGenerator {
    
    // 其他代码保持不变...
    
    /**
     * 增强的转换方法，确保Set类型的一致性
     */
    private static String convertToString(Object value) {
        if (value == null) {
            return "";
        }
        
        if (value instanceof Set) {
            Set<?> set = (Set<?>) value;
            // 使用TreeSet确保顺序一致性，并对每个元素进行字符串转换和排序
            return set.stream()
                    .map(Object::toString)
                    .sorted()  // 按字符串自然顺序排序
                    .collect(Collectors.joining(","));
        }
        
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            return collection.stream()
                    .map(Object::toString)
                    .sorted()  // 按字符串自然顺序排序
                    .collect(Collectors.joining(","));
        }
        
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            return Arrays.stream(array)
                    .map(Object::toString)
                    .sorted()  // 按字符串自然顺序排序
                    .collect(Collectors.joining(","));
        }
        
        return value.toString();
    }
    
    /**
     * 专门处理Set类型的hash值生成
     */
    public static String generateSetHash(Set<?> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        
        String sortedString = set.stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(","));
        
        return Hashing.sha256()
                .hashString(sortedString, StandardCharsets.UTF_8)
                .toString()
                .substring(0, 16);
    }
}

2. MetricReqVO的参数处理
package com.example.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.Set;

/**
 * 指标查询请求VO
 */
@Data
public class MetricReqVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Set<String> periods;        // 期间集合
    private Set<String> domainCodes;   // 领域编码集合
    private Integer type;              // 类型
    private String code;               // 编码
    
    /**
     * 重写toString方法，确保Set的顺序一致性
     */
    @Override
    public String toString() {
        return "MetricReqVO{" +
                "periods=" + (periods != null ? 
                    periods.stream().sorted().collect(Collectors.toList()) : "null") +
                ", domainCodes=" + (domainCodes != null ? 
                    domainCodes.stream().sorted().collect(Collectors.toList()) : "null") +
                ", type=" + type +
                ", code='" + code + '\'' +
                '}';
    }
    
    /**
     * 重写equals和hashCode方法，考虑Set内容的相等性（忽略顺序）
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        MetricReqVO that = (MetricReqVO) o;
        
        if (periods != null ? !new TreeSet<>(periods).equals(new TreeSet<>(that.periods)) : that.periods != null) 
            return false;
        if (domainCodes != null ? !new TreeSet<>(domainCodes).equals(new TreeSet<>(that.domainCodes)) : that.domainCodes != null) 
            return false;
        if (type != null ? !type.equals(that.type) : that.type != null) return false;
        return code != null ? code.equals(that.code) : that.code == null;
    }
    
    @Override
    public int hashCode() {
        int result = periods != null ? new TreeSet<>(periods).hashCode() : 0;
        result = 31 * result + (domainCodes != null ? new TreeSet<>(domainCodes).hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (code != null ? code.hashCode() : 0);
        return result;
    }
}


3. 专门的数据加载器实现

package com.example.service.impl;

import com.example.service.CacheDataLoader;
import com.example.vo.MetricReqVO;
import com.example.vo.MetricResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * 指标数据加载器 - 专门处理Set参数
 */
@Slf4j
@Component
public class MetricDataLoader implements CacheDataLoader<MetricReqVO, MetricResultVO> {
    
    @Override
    public MetricResultVO loadData(MetricReqVO params) {
        try {
            log.info("开始加载指标数据, 参数: {}", params);
            
            // 在实际查询前，可以对Set参数进行标准化处理
            MetricReqVO normalizedParams = normalizeParams(params);
            
            // 调用Mapper或Service查询数据
            MetricResultVO result = metricMapper.selectMetrics(normalizedParams);
            
            log.info("指标数据加载完成, 参数: {}, 结果条数: {}", 
                    params, result != null ? result.getDataCount() : 0);
            
            return result;
            
        } catch (Exception e) {
            log.error("加载指标数据异常, params: {}", params, e);
            throw new RuntimeException("指标数据加载失败", e);
        }
    }
    
    /**
     * 参数标准化：确保Set参数的顺序一致性
     */
    private MetricReqVO normalizeParams(MetricReqVO params) {
        MetricReqVO normalized = new MetricReqVO();
        
        // 对Set进行排序，确保查询条件的一致性
        if (params.getPeriods() != null) {
            normalized.setPeriods(new TreeSet<>(params.getPeriods()));
        }
        if (params.getDomainCodes() != null) {
            normalized.setDomainCodes(new TreeSet<>(params.getDomainCodes()));
        }
        
        normalized.setType(params.getType());
        normalized.setCode(params.getCode());
        
        return normalized;
    }
}

4. 缓存配置
package com.example.config;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import org.springframework.stereotype.Component;

/**
 * 指标缓存配置
 */
@Component
public class MetricCacheConfig implements CacheConfig {
    
    @Override
    public String getCacheName() {
        return CacheConstants.CACHE_NAME;
    }
    
    @Override
    public String getModulePrefix() {
        return "METRIC"; // 自定义模块前缀
    }
    
    @Override
    public long getExpireSeconds() {
        return 30 * 60; // 30分钟
    }
    
    @Override
    public long getRefreshThreshold() {
        return 5 * 60; // 5分钟
    }
    
    @Override
    public String[] getKeyFields() {
        // 指定所有参与缓存key生成的字段
        return new String[]{"periods", "domainCodes", "type", "code"};
    }
}

5. 业务Service实现

package com.example.service.impl;

import com.example.cache.CacheKeyGenerator;
import com.example.cache.GenericCacheManager;
import com.example.config.MetricCacheConfig;
import com.example.vo.MetricReqVO;
import com.example.vo.MetricResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 指标服务实现
 */
@Slf4j
@Service
public class MetricService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private MetricCacheConfig metricCacheConfig;
    
    @Autowired
    private MetricDataLoader metricDataLoader;
    
    /**
     * 获取指标数据（带缓存）
     */
    public MetricResultVO getMetrics(Set<String> periods, Set<String> domainCodes, 
                                    Integer type, String code) {
        MetricReqVO params = new MetricReqVO();
        params.setPeriods(periods);
        params.setDomainCodes(domainCodes);
        params.setType(type);
        params.setCode(code);
        
        return getMetrics(params);
    }
    
    /**
     * 获取指标数据（主要方法）
     */
    public MetricResultVO getMetrics(MetricReqVO params) {
        try {
            // 生成缓存key用于日志记录
            String cacheKey = CacheKeyGenerator.generateKey(
                metricCacheConfig.getCacheName(),
                metricCacheConfig.getModulePrefix(),
                params,
                metricCacheConfig.getKeyFields()
            );
            
            log.debug("获取指标数据, 参数: {}, 缓存Key: {}", params, cacheKey);
            
            MetricResultVO result = cacheManager.getCachedData(
                params,
                metricCacheConfig,
                metricDataLoader,
                MetricResultVO.class
            );
            
            return result;
            
        } catch (Exception e) {
            log.error("获取指标数据异常, params: {}", params, e);
            throw new RuntimeException("获取指标数据失败", e);
        }
    }
    
    /**
     * 测试方法：验证Set顺序不影响缓存key
     */
    public void testCacheKeyConsistency() {
        Set<String> periods1 = Set.of("098343", "de2343", "fdfe31");
        Set<String> periods2 = Set.of("de2343", "fdfe31", "098343"); // 顺序不同
        
        MetricReqVO params1 = new MetricReqVO();
        params1.setPeriods(periods1);
        params1.setDomainCodes(Set.of("A", "B"));
        params1.setType(1);
        params1.setCode("test");
        
        MetricReqVO params2 = new MetricReqVO();
        params2.setPeriods(periods2);
        params2.setDomainCodes(Set.of("B", "A")); // 顺序也不同
        params2.setType(1);
        params2.setCode("test");
        
        String key1 = CacheKeyGenerator.generateKeyWithoutTimestamp(
            metricCacheConfig.getCacheName(),
            metricCacheConfig.getModulePrefix(),
            params1,
            metricCacheConfig.getKeyFields()
        );
        
        String key2 = CacheKeyGenerator.generateKeyWithoutTimestamp(
            metricCacheConfig.getCacheName(),
            metricCacheConfig.getModulePrefix(),
            params2,
            metricCacheConfig.getKeyFields()
        );
        
        log.info("参数1缓存Key: {}", key1);
        log.info("参数2缓存Key: {}", key2);
        log.info("缓存Key是否一致: {}", key1.equals(key2));
    }
}

关键改进点
Set处理增强：在convertToString方法中，对Set类型进行专门处理，确保元素排序

参数标准化：在数据加载前对参数进行标准化处理

VO对象优化：重写equals和hashCode方法，考虑Set内容的相等性

测试验证：提供测试方法验证缓存key的一致性

验证结果
经过上述改进后，即使Set中元素的顺序不同，只要内容相同，生成的缓存key就会完全一致，从而确保缓存命中率。

您可以通过调用testCacheKeyConsistency()方法来验证这一点。
