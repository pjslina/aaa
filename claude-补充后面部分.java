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
 * Ԫ���ݻ������
 * ����1���������أ�ÿ��ˢ��
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
     * Ӧ��
     
     /**
 * ��ʱ����ÿ��ˢ��Ԫ����
 */
@Override
public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
    log.info("========== ��ʼ��ʱˢ��Ԫ���� ==========");
    loadAllMetadata();
    log.info("========== Ԫ����ˢ����� ==========");
}

/**
 * ��������Ԫ����
 */
private void loadAllMetadata() {
    try {
        // 1. ����ָ��Ԫ����
        loadMetrics();
        
        // 2. ���ض���Ԫ����
        loadMeasures();
        
        // 3. ������Ԫ����
        loadDomains();
        
        // 4. ������֯�㼶Ԫ����
        loadOrgLevels();
        
    } catch (Exception e) {
        log.error("����Ԫ����ʧ��", e);
        throw new ApplicationException("����Ԫ����ʧ��", e);
    }
}

/**
 * ����ָ��Ԫ����
 */
private void loadMetrics() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":metrics";
    
    List<MetricMetadata> metrics = metadataMapper.selectAllMetrics();
    
    // ����Redis���������ڣ�
    redisService.set(cacheKey, metrics, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    
    // ���뱾�ػ��棨���Ի���Value��
    localHotCache.putMetadata(cacheKey, metrics);
    
    log.info("����ָ��Ԫ������ɣ��� {} ��", metrics.size());
}

/**
 * ���ض���Ԫ����
 */
private void loadMeasures() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":measures";
    
    List<MeasureMetadata> measures = metadataMapper.selectAllMeasures();
    
    redisService.set(cacheKey, measures, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    localHotCache.putMetadata(cacheKey, measures);
    
    log.info("���ض���Ԫ������ɣ��� {} ��", measures.size());
}

/**
 * ������Ԫ����
 */
private void loadDomains() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":domains";
    
    List<DomainMetadata> domains = metadataMapper.selectAllDomains();
    
    redisService.set(cacheKey, domains, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    localHotCache.putMetadata(cacheKey, domains);
    
    log.info("������Ԫ������ɣ��� {} ��", domains.size());
}

/**
 * ������֯�㼶Ԫ����
 */
private void loadOrgLevels() {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":orgLevels";
    
    List<OrgLevelMetadata> orgLevels = metadataMapper.selectAllOrgLevels();
    
    redisService.set(cacheKey, orgLevels, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
    localHotCache.putMetadata(cacheKey, orgLevels);
    
    log.info("������֯�㼶Ԫ������ɣ��� {} ��", orgLevels.size());
}

/**
 * ��ȡԪ���ݣ��ȴӱ��ػ��棬�ٴ�Redis���������ݿ⣩
 */
public <T> T getMetadata(String metadataType, Class<T> clazz) {
    String cacheKey = CacheConstants.CACHE_NAME + ":" + 
                     CacheConstants.ModulePrefix.METADATA + ":" + metadataType;
    
    // 1. �ȴӱ��ػ����ȡ
    Object localData = localHotCache.getMetadata(cacheKey);
    if (localData != null) {
        log.debug("���ػ�������Ԫ����: {}", metadataType);
        return (T) localData;
    }
    
    // 2. ��Redis��ȡ
    T redisData = redisService.get(cacheKey, clazz);
    if (redisData != null) {
        log.debug("Redis��������Ԫ����: {}", metadataType);
        // ��д���ػ���
        localHotCache.putMetadata(cacheKey, redisData);
        return redisData;
    }
    
    // 3. �����ݿ��ѯ
    log.info("����δ���У������ݿ����Ԫ����: {}", metadataType);
    T dbData = loadMetadataFromDB(metadataType, clazz);
    
    if (dbData != null) {
        // д��Redis
        redisService.set(cacheKey, dbData, CacheConstants.ExpireTime.METADATA, TimeUnit.SECONDS);
        // д�뱾�ػ���
        localHotCache.putMetadata(cacheKey, dbData);
    }
    
    return dbData;
}

/**
 * �����ݿ����Ԫ����
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


### 11. ҵ��Serviceʾ����MeasureDataService��
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
 * �������ݷ���
 * ����4��ʹ�÷�������ӿ�
 */
@Slf4j
@Service
public class MeasureDataService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private OtherBusinessService otherBusinessService; // ����ҵ�����
    
    /**
     * ��������
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
            return CacheConstants.ExpireTime.BUSINESS; // 8Сʱ
        }
        
        @Override
        public long getRefreshThreshold() {
            return CacheConstants.REFRESH_THRESHOLD; // 5����
        }
        
        @Override
        public String[] getKeyFields() {
            // ָ������Key������ֶ�
            return new String[]{"code", "type", "orgCode"};
        }
    };
    
    /**
     * ��ȡָ�����ݣ�ʹ�û��棩
     * 
     * @param codeVO ��ѯ����
     * @return ָ���б�
     */
    public List<Metric> getMetrics(CodeVO codeVO) {
        // �������ݼ�����
        CacheDataLoader<CodeVO, List<Metric>> loader = params -> {
            log.info("ִ��ҵ���߼���������, params: {}", params);
            return loadMetricsFromBusiness(params);
        };
        
        // ʹ�÷��������������ȡ����
        return cacheManager.getCachedData(
                codeVO,
                CACHE_CONFIG,
                loader,
                List.class
        );
    }
    
    /**
     * ��ҵ���߼��������ݣ�����ҵ����
     */
    private List<Metric> loadMetricsFromBusiness(CodeVO codeVO) {
        // ����������ӵ�ҵ���߼�
        // ���ö��Service
        // ��ѯ������ݿ��
        // ��ʱ���ܼ���
        
        return otherBusinessService.queryMetrics(codeVO);
    }
}
```

### 12. ��һ��ҵ��Serviceʾ��
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
 * �û���Ϣ����ʾ��
 */
@Slf4j
@Service
public class UserInfoService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private UserMapper userMapper;
    
    /**
     * �û���Ϣ��������
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
            return 3600; // 1Сʱ
        }
        
        @Override
        public String[] getKeyFields() {
            return new String[]{"userId", "orgCode"};
        }
    };
    
    /**
     * ��ȡ�û���Ϣ
     */
    public UserInfo getUserInfo(UserQueryVO queryVO) {
        CacheDataLoader<UserQueryVO, UserInfo> loader = params -> {
            log.info("��ѯ�û���Ϣ, params: {}", params);
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

### 13. Controller��ʾ��
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
 * �������ݿ�����
 */
@Slf4j
@RestController
@RequestMapping("/api/measure")
@Api(tags = "�������ݽӿ�")
public class MeasureDataController {
    
    @Autowired
    private MeasureDataService measureDataService;
    
    @PostMapping("/metrics")
    @ApiOperation("��ȡָ������")
    public ApiResponse<List<Metric>> getMetrics(@RequestBody CodeVO codeVO) {
        try {
            List<Metric> metrics = measureDataService.getMetrics(codeVO);
            return ApiResponse.success(metrics);
        } catch (Exception e) {
            log.error("��ȡָ������ʧ��", e);
            return ApiResponse.error("��ȡ����ʧ��: " + e.getMessage());
        }
    }
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        
        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(true, "�ɹ�", data);
        }
        
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
```

### 14. ����ʹ�ù����ࣨ�򻯵��ã�
```java
package com.example.util;

import com.example.cache.GenericCacheManager;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * ����ʹ�ù�����
 * �ṩ�򻯵Ļ�����÷���
 */
@Component
public class CacheHelper {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    /**
     * ���ٻ�ȡ��������
     * 
     * @param params ����
     * @param config ��������
     * @param loader ���ݼ�����
     * @param resultClass ��������
     * @return ����
     */
    public <P extends Serializable, R> R get(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        return cacheManager.getCachedData(params, config, loader, resultClass);
    }
    
    /**
     * Lambda�򻯰汾
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
     * ����������
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

### 15. �����ļ���application.yml��
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

# ��������
cache:
  # Ԫ���ݻ������ʱ�䣨�룩
  metadata-expire: 86400
  # ҵ�񻺴����ʱ�䣨�룩
  business-expire: 28800
  # ˢ����ֵ���룩
  refresh-threshold: 300
  
# �̳߳�����
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

### 16. ��Ԫ����
```java
package com.example.service;

import com.example.cache.CacheKeyGenerator;
import com.example.vo.CodeVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ����Key����������
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
        
        // HashֵӦ����ͬ��ʱ�����ͬ��
        String hash1 = CacheKeyGenerator.getKeyWithoutTimestamp(key1);
        String hash2 = CacheKeyGenerator.getKeyWithoutTimestamp(key2);
        
        assertEquals(hash1, hash2);
        assertNotEquals(key1, key2); // ��Ϊʱ�����ͬ
        
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
        // ����һ���ɵ�Key
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
 * ���湦�ܲ���
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
        
        // ��һ�ε��ã�����δ���У�
        long start1 = System.currentTimeMillis();
        List<Metric> metrics1 = measureDataService.getMetrics(codeVO);
        long time1 = System.currentTimeMillis() - start1;
        
        assertNotNull(metrics1);
        System.out.println("��һ�ε��ú�ʱ: " + time1 + "ms");
        
        // �ڶ��ε��ã��������У�
        long start2 = System.currentTimeMillis();
        List<Metric> metrics2 = measureDataService.getMetrics(codeVO);
        long time2 = System.currentTimeMillis() - start2;
        
        assertNotNull(metrics2);
        System.out.println("�ڶ��ε��ú�ʱ: " + time2 + "ms");
        
        // �ڶ���Ӧ�ø���
        assertTrue(time2 < time1);
    }
}
```

## �����ܽ�

### ��������

1. **������**
   - �����ȵ㻺�棨ֻ����Key�������ڴ�ռ�ã�
   - Ԫ���ݿɻ��浽�����ڴ�
   - SHA256����Hash����
   - �ֶη��仺��
   - FastJSON2��Ч���л�

2. **�߰�ȫ**
   - ��¡��������ֹ���洩͸
   - ��������ֹ�������
   - �첽ˢ�·�ֹѩ��
   - ˫�ؼ��������

3. **����չ**
   - �����ӿ����
   - ����ģʽ��������
   - ����ʽ���֧��
   - ���������ҵ��

4. **��Ȧ���Ӷ�**
   - ��һְ��
   - ����ʽ�ӿ�
   - ��ʽ����
   - �����ķֲ�

### ʹ��ʾ��
```java
// ҵ��Serviceֻ��Ҫ��������
public List<Metric> getMetrics(CodeVO codeVO) {
    return cacheManager.getCachedData(
        codeVO,
        CACHE_CONFIG,
        params -> loadFromDB(params),
        List.class
    );
}
```

���������ȫ�����������������Ҿ�����������������


