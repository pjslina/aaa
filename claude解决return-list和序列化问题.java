1. 修改序列化工具类（使用fastjson）
package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * 高性能序列化工具（使用fastjson）
 */
@Slf4j
public class SerializationUtil {
    
    static {
        // 开启fastjson的AutoType支持（注意安全风险，生产环境需要配置白名单）
        ParserConfig.getGlobalInstance().setAutoTypeSupport(true);
        // 或者配置白名单
        // ParserConfig.getGlobalInstance().addAccept("com.example.");
    }
    
    /**
     * 序列化
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            String jsonString = JSON.toJSONString(obj, 
                    SerializerFeature.WriteClassName,
                    SerializerFeature.DisableCircularReferenceDetect);
            return jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("序列化失败", e);
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    /**
     * 反序列化
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            return JSON.parseObject(jsonString, clazz);
        } catch (Exception e) {
            log.error("反序列化失败", e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
    
    /**
     * 反序列化（从对象）
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object obj, Class<T> clazz) {
        if (obj == null) {
            return null;
        }
        
        if (clazz.isInstance(obj)) {
            return (T) obj;
        }
        
        try {
            // 先转成JSON字符串，再反序列化
            String jsonString = JSON.toJSONString(obj);
            return JSON.parseObject(jsonString, clazz);
        } catch (Exception e) {
            log.error("反序列化失败", e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
}


2. 新增TypeReference支持类

package com.example.cache;

import com.alibaba.fastjson.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.lang.reflect.Type;

/**
 * 泛型类型包装器
 * 用于处理List<T>等复杂泛型类型
 */
@Data
@AllArgsConstructor
public class CacheTypeWrapper {
    
    /**
     * 实际的Type
     */
    private Type type;
    
    /**
     * 创建List类型的包装器
     */
    public static CacheTypeWrapper forList(Class<?> elementType) {
        return new CacheTypeWrapper(new TypeReference<java.util.List<?>>() {
            @Override
            public Type getType() {
                return new com.alibaba.fastjson.util.ParameterizedTypeImpl(
                        new Type[]{elementType}, 
                        null, 
                        java.util.List.class
                );
            }
        }.getType());
    }
    
    /**
     * 创建简单类型的包装器
     */
    public static CacheTypeWrapper forClass(Class<?> clazz) {
        return new CacheTypeWrapper(clazz);
    }
}

3. 修改序列化工具类（增加Type支持）

package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * 高性能序列化工具（使用fastjson，支持复杂泛型）
 */
@Slf4j
public class SerializationUtil {
    
    static {
        ParserConfig.getGlobalInstance().setAutoTypeSupport(true);
    }
    
    /**
     * 序列化
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            String jsonString = JSON.toJSONString(obj, 
                    SerializerFeature.WriteClassName,
                    SerializerFeature.DisableCircularReferenceDetect);
            return jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("序列化失败", e);
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    /**
     * 反序列化（支持Type）
     */
    public static <T> T deserialize(byte[] bytes, Type type) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            return JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("反序列化失败，type: {}", type, e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
    
    /**
     * 反序列化（Class）
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return deserialize(bytes, (Type) clazz);
    }
    
    /**
     * 反序列化（从对象，支持Type）
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object obj, Type type) {
        if (obj == null) {
            return null;
        }
        
        try {
            String jsonString = JSON.toJSONString(obj);
            return JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("反序列化失败，type: {}", type, e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
    
    /**
     * 反序列化（从对象，Class）
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object obj, Class<T> clazz) {
        if (obj == null) {
            return null;
        }
        
        if (clazz.isInstance(obj)) {
            return (T) obj;
        }
        
        return deserialize(obj, (Type) clazz);
    }
}

4. 修改RedisService（支持Type）

package com.example.cache;

import com.example.util.SerializationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis缓存封装（支持复杂泛型类型）
 */
@Slf4j
@Component
public class RedisService {
    
    @Autowired
    private AppRedisTemplate redisTemplate;
    
    @Autowired
    private LocalHotCache localHotCache;
    
    @Autowired
    private CacheBloomFilter bloomFilter;
    
    private final java.util.concurrent.ConcurrentHashMap<String, Lock> lockMap = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 获取缓存（支持Type）
     */
    public <T> T get(String key, Type type) {
        try {
            if (!bloomFilter.mightContain(key)) {
                log.debug("布隆过滤器判定Key不存在: {}", key);
                return null;
            }
            
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 反序列化（支持Type）
            T result = SerializationUtil.deserialize(value, type);
            
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * 获取缓存（Class）
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, (Type) clazz);
    }
    
    /**
     * 设置缓存
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        try {
            byte[] serializedValue = SerializationUtil.serialize(value);
            redisTemplate.set(key, serializedValue, ttl, unit);
            bloomFilter.add(key);
            localHotCache.recordKeyAccess(key);
        } catch (Exception e) {
            log.error("设置缓存失败, key: {}", key, e);
            throw new RuntimeException("设置缓存失败", e);
        }
    }
    
    /**
     * 删除缓存
     */
    public void remove(String key) {
        try {
            redisTemplate.remove(key);
            localHotCache.removeKey(key);
        } catch (Exception e) {
            log.error("删除缓存失败, key: {}", key, e);
        }
    }
    
    /**
     * 清空缓存
     */
    public void clear(String cacheName) {
        try {
            redisTemplate.clear(cacheName);
            localHotCache.clearKeys();
        } catch (Exception e) {
            log.error("清空缓存失败, cacheName: {}", cacheName, e);
        }
    }
    
    /**
     * 获取本地锁
     */
    public Lock getLock(String key) {
        return lockMap.computeIfAbsent(key, k -> new ReentrantLock());
    }
    
    /**
     * 释放锁
     */
    public void releaseLock(String key) {
        Lock lock = lockMap.get(key);
        if (lock != null) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("释放锁失败, key: {}", key, e);
            }
        }
    }
}

5. 修改泛化缓存管理器（支持Type）
package com.example.cache;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import com.example.util.AsyncTaskUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * 泛化缓存管理器（支持复杂泛型类型）
 */
@Slf4j
@Component
public class GenericCacheManager {
    
    @Autowired
    private RedisService redisService;
    
    @Autowired
    private LocalHotCache localHotCache;
    
    @Autowired
    private AsyncTaskUtil asyncTaskUtil;
    
    /**
     * 获取缓存数据（使用Type）
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Type resultType) {
        
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("本地热点缓存命中: {}", cacheKey);
            
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        R cachedData = redisService.get(cacheKey, resultType);
        if (cachedData != null) {
            log.debug("Redis缓存命中: {}", cacheKey);
            return cachedData;
        }
        
        return loadDataWithLock(params, config, loader, cacheKey, resultType);
    }
    
    /**
     * 获取缓存数据（使用Class）
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        return getCachedData(params, config, loader, (Type) resultClass);
    }
    
    /**
     * 获取缓存数据（使用CacheTypeWrapper）
     */
    public <P extends Serializable, R> R getCachedDataWithType(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            CacheTypeWrapper typeWrapper) {
        
        return getCachedData(params, config, loader, typeWrapper.getType());
    }
    
    /**
     * 判断是否需要异步刷新
     */
    private boolean shouldAsyncRefresh(String cacheKey, long thresholdSeconds) {
        Long accessTime = localHotCache.getKeyAccessTime(cacheKey);
        if (accessTime == null) {
            return false;
        }
        
        long elapsed = (System.currentTimeMillis() - accessTime) / 1000;
        return elapsed >= thresholdSeconds;
    }
    
    /**
     * 异步刷新缓存
     */
    private <P extends Serializable, R> void asyncRefreshCache(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            String cacheKey) {
        
        log.info("触发异步刷新: {}", cacheKey);
        
        try {
            CacheRefreshContext context = new CacheRefreshContext();
            context.setCacheKey(cacheKey);
            context.setParams(params);
            context.setConfig(config);
            context.setLoader(loader);
            
            asyncTaskUtil.invokeAsync("cacheRefreshHandler", context);
            
        } catch (Exception e) {
            log.error("异步刷新失败: {}", cacheKey, e);
        }
    }
    
    /**
     * 加载数据（带锁，防止缓存击穿）
     */
    private <P extends Serializable, R> R loadDataWithLock(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            String cacheKey,
            Type resultType) {
        
        Lock lock = redisService.getLock(cacheKey);
        lock.lock();
        
        try {
            // 双重检查
            R cachedData = redisService.get(cacheKey, resultType);
            if (cachedData != null) {
                return cachedData;
            }
            
            log.info("加载数据: {}", cacheKey);
            R data = loader.loadData(params);
            
            if (data != null) {
                redisService.set(cacheKey, data, config.getExpireSeconds(), TimeUnit.SECONDS);
            }
            
            return data;
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 刷新缓存
     */
    public <P extends Serializable, R> void refreshCache(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader) {
        
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        R data = loader.loadData(params);
        if (data != null) {
            redisService.set(cacheKey, data, config.getExpireSeconds(), TimeUnit.SECONDS);
        }
    }
    
    @lombok.Data
    public static class CacheRefreshContext implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String cacheKey;
        private Object params;
        private CacheConfig config;
        private CacheDataLoader loader;
    }
}

6. 修改业务Service示例（返回List<T>）
package com.example.service.impl;

import com.example.cache.CacheTypeWrapper;
import com.example.cache.GenericCacheManager;
import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import com.example.vo.CodeVO;
import com.example.vo.MetricMeasureDataRestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 度量数据服务（返回List<MetricMeasureDataRestVO>）
 */
@Slf4j
@Service
public class MeasureDataService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private OtherBusinessService otherBusinessService;
    
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
            return CacheConstants.ExpireTime.BUSINESS;
        }
        
        @Override
        public long getRefreshThreshold() {
            return CacheConstants.REFRESH_THRESHOLD;
        }
        
        @Override
        public String[] getKeyFields() {
            return new String[]{"code", "type", "orgCode"};
        }
    };
    
    /**
     * 获取度量数据（返回List<MetricMeasureDataRestVO>）
     * 
     * @param codeVO 查询参数
     * @return 度量数据列表
     */
    public List<MetricMeasureDataRestVO> getMeasureData(CodeVO codeVO) {
        // 定义数据加载器
        CacheDataLoader<CodeVO, List<MetricMeasureDataRestVO>> loader = params -> {
            log.info("执行业务逻辑加载数据, params: {}", params);
            return loadMeasureDataFromBusiness(params);
        };
        
        // 使用CacheTypeWrapper指定List<MetricMeasureDataRestVO>类型
        CacheTypeWrapper typeWrapper = CacheTypeWrapper.forList(MetricMeasureDataRestVO.class);
        
        // 使用泛化缓存管理器获取数据
        return cacheManager.getCachedDataWithType(
                codeVO,
                CACHE_CONFIG,
                loader,
                typeWrapper
        );
    }
    
    /**
     * 从业务逻辑加载数据
     */
    private List<MetricMeasureDataRestVO> loadMeasureDataFromBusiness(CodeVO codeVO) {
        // 复杂的业务逻辑
        return otherBusinessService.queryMeasureData(codeVO);
    }
}

7. 更简化的使用方式（辅助方法）
package com.example.util;

import com.example.cache.CacheTypeWrapper;
import com.example.cache.GenericCacheManager;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * 缓存使用辅助工具
 */
@Component
public class CacheHelper {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    /**
     * 获取List类型的缓存数据
     * 
     * @param params 参数
     * @param config 配置
     * @param loader 加载器
     * @param elementClass List元素类型
     * @return List数据
     */
    public <P extends Serializable, T> List<T> getListData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, List<T>> loader,
            Class<T> elementClass) {
        
        CacheTypeWrapper typeWrapper = CacheTypeWrapper.forList(elementClass);
        return cacheManager.getCachedDataWithType(params, config, loader, typeWrapper);
    }
    
    /**
     * 获取单个对象的缓存数据
     */
    public <P extends Serializable, R> R getSingleData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        return cacheManager.getCachedData(params, config, loader, resultClass);
    }
}


8. 使用CacheHelper的简化示例
package com.example.service.impl;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import com.example.util.CacheHelper;
import com.example.vo.CodeVO;
import com.example.vo.MetricMeasureDataRestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 使用CacheHelper的简化示例
 */
@Slf4j
@Service
public class SimpleMeasureDataService {
    
    @Autowired
    private CacheHelper cacheHelper;
    
    @Autowired
    private OtherBusinessService otherBusinessService;
    
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
            return CacheConstants.ExpireTime.BUSINESS;
        }
        
        @Override
        public String[] getKeyFields() {
            return new String[]{"code", "type", "orgCode"};
        }
    };
    
    /**
     * 获取度量数据（最简化的调用方式）
     */
    public List<MetricMeasureDataRestVO> getMeasureData(CodeVO codeVO) {
        return cacheHelper.getListData(
                codeVO,
                CACHE_CONFIG,
                params -> otherBusinessService.queryMeasureData(params),
                MetricMeasureDataRestVO.class
        );
    }
}



方案二：不使用AutoType，手动处理类型信息（最安全）
1. 创建类型信息包装器

package com.example.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 缓存数据包装器
 * 手动存储类型信息，避免使用AutoType
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheDataWrapper implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 实际数据的完整类名
     */
    private String dataClassName;
    
    /**
     * 序列化后的JSON字符串
     */
    private String jsonData;
    
    /**
     * 数据类型：single-单对象，list-列表
     */
    private String dataType;
    
    /**
     * 如果是List，存储元素类型
     */
    private String elementClassName;
}

2. 修改序列化工具类（不使用AutoType）

package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.example.cache.CacheDataWrapper;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 安全的序列化工具（不使用AutoType）
 */
@Slf4j
public class SafeSerializationUtil {
    
    /**
     * 序列化（包装类型信息）
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            CacheDataWrapper wrapper = new CacheDataWrapper();
            
            // 判断数据类型
            if (obj instanceof List) {
                wrapper.setDataType("list");
                List<?> list = (List<?>) obj;
                if (!list.isEmpty()) {
                    wrapper.setElementClassName(list.get(0).getClass().getName());
                }
            } else {
                wrapper.setDataType("single");
            }
            
            // 存储类名
            wrapper.setDataClassName(obj.getClass().getName());
            
            // 序列化数据（不使用WriteClassName）
            String jsonData = JSON.toJSONString(obj, 
                    SerializerFeature.DisableCircularReferenceDetect);
            wrapper.setJsonData(jsonData);
            
            // 序列化包装器
            String wrapperJson = JSON.toJSONString(wrapper);
            return wrapperJson.getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("序列化失败", e);
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    /**
     * 反序列化（使用Type）
     */
    public static <T> T deserialize(byte[] bytes, Type expectedType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String wrapperJson = new String(bytes, StandardCharsets.UTF_8);
            
            // 反序列化包装器
            CacheDataWrapper wrapper = JSON.parseObject(wrapperJson, CacheDataWrapper.class);
            if (wrapper == null || wrapper.getJsonData() == null) {
                return null;
            }
            
            // 根据expectedType反序列化实际数据
            return JSON.parseObject(wrapper.getJsonData(), expectedType);
            
        } catch (Exception e) {
            log.error("反序列化失败，expectedType: {}", expectedType, e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
    
    /**
     * 反序列化（Class）
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return deserialize(bytes, (Type) clazz);
    }
    
    /**
     * 反序列化（从对象）
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object obj, Type expectedType) {
        if (obj == null) {
            return null;
        }
        
        try {
            // 如果obj是byte[]
            if (obj instanceof byte[]) {
                return deserialize((byte[]) obj, expectedType);
            }
            
            // 如果obj是String
            if (obj instanceof String) {
                return deserialize(((String) obj).getBytes(StandardCharsets.UTF_8), expectedType);
            }
            
            // 其他情况，先转JSON再反序列化
            String jsonString = JSON.toJSONString(obj);
            return JSON.parseObject(jsonString, expectedType);
            
        } catch (Exception e) {
            log.error("反序列化失败，expectedType: {}", expectedType, e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
}

3. 修改RedisService使用安全的序列化工具
package com.example.cache;

import com.example.util.SafeSerializationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis缓存封装（使用安全序列化）
 */
@Slf4j
@Component
public class RedisService {
    
    @Autowired
    private AppRedisTemplate redisTemplate;
    
    @Autowired
    private LocalHotCache localHotCache;
    
    @Autowired
    private CacheBloomFilter bloomFilter;
    
    private final java.util.concurrent.ConcurrentHashMap<String, Lock> lockMap = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 获取缓存（使用安全反序列化）
     */
    public <T> T get(String key, Type type) {
        try {
            if (!bloomFilter.mightContain(key)) {
                log.debug("布隆过滤器判定Key不存在: {}", key);
                return null;
            }
            
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 使用安全的反序列化
            T result = SafeSerializationUtil.deserialize(value, type);
            
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * 获取缓存（Class）
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, (Type) clazz);
    }
    
    /**
     * 设置缓存（使用安全序列化）
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        try {
            // 使用安全的序列化
            byte[] serializedValue = SafeSerializationUtil.serialize(value);
            
            redisTemplate.set(key, serializedValue, ttl, unit);
            bloomFilter.add(key);
            localHotCache.recordKeyAccess(key);
            
        } catch (Exception e) {
            log.error("设置缓存失败, key: {}", key, e);
            throw new RuntimeException("设置缓存失败", e);
        }
    }
    
    public void remove(String key) {
        try {
            redisTemplate.remove(key);
            localHotCache.removeKey(key);
        } catch (Exception e) {
            log.error("删除缓存失败, key: {}", key, e);
        }
    }
    
    public void clear(String cacheName) {
        try {
            redisTemplate.clear(cacheName);
            localHotCache.clearKeys();
        } catch (Exception e) {
            log.error("清空缓存失败, cacheName: {}", cacheName, e);
        }
    }
    
    public Lock getLock(String key) {
        return lockMap.computeIfAbsent(key, k -> new ReentrantLock());
    }
    
    public void releaseLock(String key) {
        Lock lock = lockMap.get(key);
        if (lock != null) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("释放锁失败, key: {}", key, e);
            }
        }
    }
}

