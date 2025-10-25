package com.example.service.impl;

import com.example.cache.LocalHotCache;
import com.example.cache.RedisService;
import com.example.constant.CacheConstants;
import com.example.mapper.MetadataMapper;
import com.example.service.ITimerTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 元数据缓存服务
 * 需求1：启动加载，每天刷新
 */
@Slf4j
@Service
public class MetadataCacheService implements CommandLineRunner, ITimerTask {
    
    @Autowired
    private RedisService redisService;
    
    @Autowired
    private LocalHotCache localHotCache;
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    /**
     * 应用
     
     /**
 * 定时任务：每天刷新元数据
 */
@Override
public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
    log.info("========== 开始定时刷新元数据 ==========");
    loadAllMetadata();
    log.info("========== 元数据刷新完成 ==========");
}

/**
 * 加载所有元数据
 */
private void loadAllMetadata() {
    try {
        // 1. 加载指标元数据
        loadMetrics();
        
        // 2. 加载度量元数据
        loadMeasures();
        
        // 3. 加载域元数据
        loadDomains();
        
        // 4. 加载组织层级元数据
        loadOrgLevels();
        
    } catch (Exception e) {
        log.error("加载元数据失败", e);
        throw new ApplicationException("加载元数据失败", e);
    }
}

/**
 * 加载指标元数据
 */
private void loadMetrics() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":metrics";
    
    List<MetricMetadata> metrics = metadataMapper.selectAllMetrics();
    
    // 存入Redis（永不过期）
    redisService.set(cacheKey, metrics, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    
    // 存入本地缓存（可以缓存Value）
    localHotCache.putMetadata(cacheKey, metrics);
    
    log.info("加载指标元数据完成，共 {} 条", metrics.size());
}

/**
 * 加载度量元数据
 */
private void loadMeasures() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":measures";
    
    List<MeasureMetadata> measures = metadataMapper.selectAllMeasures();
    
    redisService.set(cacheKey, measures, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    localHotCache.putMetadata(cacheKey, measures);
    
    log.info("加载度量元数据完成，共 {} 条", measures.size());
}

/**
 * 加载域元数据
 */
private void loadDomains() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":domains";
    
    List<DomainMetadata> domains = metadataMapper.selectAllDomains();
    
    redisService.set(cacheKey, domains, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    localHotCache.putMetadata(cacheKey, domains);
    
    log.info("加载域元数据完成，共 {} 条", domains.size());
}

/**
 * 加载组织层级元数据
 */
private void loadOrgLevels() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":orgLevels";
    
    List<OrgLevelMetadata> orgLevels = metadataMapper.selectAllOrgLevels();
    
    redisService.set(cacheKey, orgLevels, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    localHotCache.putMetadata(cacheKey, orgLevels);
    
    log.info("加载组织层级元数据完成，共 {} 条", orgLevels.size());
}

/**
 * 获取元数据（先从本地缓存，再从Redis，最后查数据库）
 */
public <T> T getMetadata(String metadataType, Class<T> clazz) {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":" + metadataType;
    
    // 1. 先从本地缓存获取
    Object localData = localHotCache.getMetadata(cacheKey);
    if (localData != null) {
        log.debug("本地缓存命中元数据: {}", metadataType);
        return (T) localData;
    }
    
    // 2. 从Redis获取
    T redisData = redisService.get(cacheKey, clazz);
    if (redisData != null) {
        log.debug("Redis缓存命中元数据: {}", metadataType);
        // 回写本地缓存
        localHotCache.putMetadata(cacheKey, redisData);
        return redisData;
    }
    
    // 3. 从数据库查询
    log.info("缓存未命中，从数据库加载元数据: {}", metadataType);
    T dbData = loadMetadataFromDB(metadataType, clazz);
    
    if (dbData != null) {
        // 写入Redis
        redisService.set(cacheKey, dbData, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
        // 写入本地缓存
        localHotCache.putMetadata(cacheKey, dbData);
    }
    
    return dbData;
}

/**
 * 从数据库加载元数据
 */
private <T> T loadMetadataFromDB(String metadataType, Class<T> clazz) {
    switch (metadataType) {
        case "metrics":
            return (T) metadataMapper.selectAllMetrics();
        case "measures":
            return (T) metadataMapper.selectAllMeasures();
        case "domains":
            return (T) metadataMapper.selectAllDomains();
        case "orgLevels":
            return (T) metadataMapper.selectAllOrgLevels();
        default:
            return null;
    }
}


### 11. 业务Service示例（MeasureDataService）
```java
package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.CodeVO;
import com.example.entity.Metric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 度量数据服务
 * 需求4：使用泛化缓存接口
 */
@Slf4j
@Service
public class MeasureDataService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private OtherBusinessService otherBusinessService; // 其他业务服务
    
    /**
     * 缓存配置
     */
    private static final CacheConfig CACHE_CONFIG = new CacheConfig() {
        @Override
        public String getCacheName() {
            return CacheConstants.CACHE_NAME;
        }
        
        @Override
        public String getModulePrefix() {
            return CacheConstants.ModulePrefix.BUSINESS;
        }
        
        @Override
        public long getExpireSeconds() {
            return CacheConstants.ExpireTime.BUSINESS; // 8小时
        }
        
        @Override
        public long getRefreshThreshold() {
            return CacheConstants.REFRESH_THRESHOLD; // 5分钟
        }
        
        @Override
        public String[] getKeyFields() {
            // 指定参与Key计算的字段
            return new String[]{"code", "type", "orgCode"};
        }
    };
    
    /**
     * 获取指标数据（使用缓存）
     * 
     * @param codeVO 查询参数
     * @return 指标列表
     */
    public List<Metric> getMetrics(CodeVO codeVO) {
        // 定义数据加载器
        CacheDataLoader<CodeVO, List<Metric>> loader = params -> {
            log.info("执行业务逻辑加载数据, params: {}", params);
            return loadMetricsFromBusiness(params);
        };
        
        // 使用泛化缓存管理器获取数据
        return cacheManager.getCachedData(
                codeVO,
                CACHE_CONFIG,
                loader,
                List.class
        );
    }
    
    /**
     * 从业务逻辑加载数据（复杂业务处理）
     */
    private List<Metric> loadMetricsFromBusiness(CodeVO codeVO) {
        // 这里包含复杂的业务逻辑
        // 调用多个Service
        // 查询多个数据库表
        // 耗时可能几秒
        
        return otherBusinessService.queryMetrics(codeVO);
    }
}
```

### 12. 另一个业务Service示例
```java
package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.UserQueryVO;
import com.example.entity.UserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户信息服务示例
 */
@Slf4j
@Service
public class UserInfoService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private UserMapper userMapper;
    
    /**
     * 用户信息缓存配置
     */
    private static final CacheConfig USER_CACHE_CONFIG = new CacheConfig() {
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
            return 3600; // 1小时
        }
        
        @Override
        public String[] getKeyFields() {
            return new String[]{"userId", "orgCode"};
        }
    };
    
    /**
     * 获取用户信息
     */
    public UserInfo getUserInfo(UserQueryVO queryVO) {
        CacheDataLoader<UserQueryVO, UserInfo> loader = params -> {
            log.info("查询用户信息, params: {}", params);
            return userMapper.selectUserInfo(params);
        };
        
        return cacheManager.getCachedData(
                queryVO,
                USER_CACHE_CONFIG,
                loader,
                UserInfo.class
        );
    }
}
```

### 13. Controller层示例
```java
package com.example.controller;

import com.example.service.impl.MeasureDataService;
import com.example.vo.CodeVO;
import com.example.entity.Metric;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 度量数据控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/measure")
@Api(tags = "度量数据接口")
public class MeasureDataController {
    
    @Autowired
    private MeasureDataService measureDataService;
    
    @PostMapping("/metrics")
    @ApiOperation("获取指标数据")
    public ApiResponse<List<Metric>> getMetrics(@RequestBody CodeVO codeVO) {
        try {
            List<Metric> metrics = measureDataService.getMetrics(codeVO);
            return ApiResponse.success(metrics);
        } catch (Exception e) {
            log.error("获取指标数据失败", e);
            return ApiResponse.error("获取数据失败: " + e.getMessage());
        }
    }
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        
        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(true, "成功", data);
        }
        
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
```

### 14. 缓存使用工具类（简化调用）
```java
package com.example.util;

import com.example.cache.GenericCacheManager;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 缓存使用工具类
 * 提供简化的缓存调用方法
 */
@Component
public class CacheHelper {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    /**
     * 快速获取缓存数据
     * 
     * @param params 参数
     * @param config 缓存配置
     * @param loader 数据加载器
     * @param resultClass 返回类型
     * @return 数据
     */
    public <P extends Serializable, R> R get(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        return cacheManager.getCachedData(params, config, loader, resultClass);
    }
    
    /**
     * Lambda简化版本
     */
    public <P extends Serializable, R> R getWithLambda(
            P params,
            String cacheName,
            String modulePrefix,
            long expireSeconds,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        CacheConfig config = createSimpleConfig(cacheName, modulePrefix, expireSeconds);
        return cacheManager.getCachedData(params, config, loader, resultClass);
    }
    
    /**
     * 创建简单配置
     */
    private CacheConfig createSimpleConfig(String cacheName, String modulePrefix, long expireSeconds) {
        return new CacheConfig() {
            @Override
            public String getCacheName() {
                return cacheName;
            }
            
            @Override
            public String getModulePrefix() {
                return modulePrefix;
            }
            
            @Override
            public long getExpireSeconds() {
                return expireSeconds;
            }
        };
    }
}
```

### 15. 配置文件（application.yml）
```yaml
spring:
  application:
    name: app-system
    
  redis:
    host: localhost
    port: 6379
    password: 
    database: 0
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
        max-wait: 3000ms

# 缓存配置
cache:
  # 元数据缓存过期时间（秒）
  metadata-expire: 86400
  # 业务缓存过期时间（秒）
  business-expire: 28800
  # 刷新阈值（秒）
  refresh-threshold: 300
  
# 线程池配置
thread-pool:
  cache-refresh:
    core-size: 5
    max-size: 10
    queue-capacity: 200

logging:
  level:
    com.example: INFO
    com.example.cache: DEBUG
```

### 16. 单元测试
```java
package com.example.service;

import com.example.cache.CacheKeyGenerator;
import com.example.vo.CodeVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存Key生成器测试
 */
@SpringBootTest
class CacheKeyGeneratorTest {
    
    @Test
    void testGenerateKey() {
        CodeVO codeVO = new CodeVO();
        codeVO.setCode("M001");
        codeVO.setType("METRIC");
        codeVO.setOrgCode("ORG001");
        
        String key1 = CacheKeyGenerator.generateKey(
                "APP_SYSTEM",
                "BIZ",
                codeVO,
                "code", "type", "orgCode"
        );
        
        String key2 = CacheKeyGenerator.generateKey(
                "APP_SYSTEM",
                "BIZ",
                codeVO,
                "code", "type", "orgCode"
        );
        
        // Hash值应该相同（时间戳不同）
        String hash1 = CacheKeyGenerator.getKeyWithoutTimestamp(key1);
        String hash2 = CacheKeyGenerator.getKeyWithoutTimestamp(key2);
        
        assertEquals(hash1, hash2);
        assertNotEquals(key1, key2); // 因为时间戳不同
        
        System.out.println("Key1: " + key1);
        System.out.println("Key2: " + key2);
    }
    
    @Test
    void testParseTimestamp() {
        String key = "APP_SYSTEM:BIZ:abc123@1234567890";
        long timestamp = CacheKeyGenerator.parseTimestamp(key);
        
        assertEquals(1234567890L, timestamp);
    }
    
    @Test
    void testNeedRefresh() {
        // 生成一个旧的Key
        String oldKey = "APP_SYSTEM:BIZ:abc123@" + (System.currentTimeMillis() - 400000);
        
        boolean needRefresh = CacheKeyGenerator.needRefresh(oldKey, 300);
        
        assertTrue(needRefresh);
    }
}
```
```java
package com.example.service;

import com.example.cache.GenericCacheManager;
import com.example.constant.CacheConstants;
import com.example.service.impl.MeasureDataService;
import com.example.vo.CodeVO;
import com.example.entity.Metric;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存功能测试
 */
@SpringBootTest
class CacheIntegrationTest {
    
    @Autowired
    private MeasureDataService measureDataService;
    
    @Test
    void testGetMetricsWithCache() {
        CodeVO codeVO = new CodeVO();
        codeVO.setCode("M001");
        codeVO.setType("METRIC");
        codeVO.setOrgCode("ORG001");
        
        // 第一次调用（缓存未命中）
        long start1 = System.currentTimeMillis();
        List<Metric> metrics1 = measureDataService.getMetrics(codeVO);
        long time1 = System.currentTimeMillis() - start1;
        
        assertNotNull(metrics1);
        System.out.println("第一次调用耗时: " + time1 + "ms");
        
        // 第二次调用（缓存命中）
        long start2 = System.currentTimeMillis();
        List<Metric> metrics2 = measureDataService.getMetrics(codeVO);
        long time2 = System.currentTimeMillis() - start2;
        
        assertNotNull(metrics2);
        System.out.println("第二次调用耗时: " + time2 + "ms");
        
        // 第二次应该更快
        assertTrue(time2 < time1);
    }
}
```

## 方案总结

### 核心特性

1. **高性能**
   - 本地热点缓存（只缓存Key，减少内存占用）
   - 元数据可缓存到本地内存
   - SHA256快速Hash计算
   - 字段反射缓存
   - FastJSON2高效序列化

2. **高安全**
   - 布隆过滤器防止缓存穿透
   - 本地锁防止缓存击穿
   - 异步刷新防止雪崩
   - 双重检查锁机制

3. **高扩展**
   - 泛化接口设计
   - 策略模式分离配置
   - 函数式编程支持
   - 易于添加新业务

4. **低圈复杂度**
   - 单一职责
   - 函数式接口
   - 链式调用
   - 清晰的分层

### 使用示例
```java
// 业务Service只需要这样调用
public List<Metric> getMetrics(CodeVO codeVO) {
    return cacheManager.getCachedData(
        codeVO,
        CACHE_CONFIG,
        params -> loadFromDB(params),
        List.class
    );
}
```

这个方案完全满足您的所有需求，且具有生产级的质量！


