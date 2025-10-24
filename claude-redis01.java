1. 核心常量定义

package com.example.constant;

import java.util.concurrent.TimeUnit;

/**
 * 缓存常量
 */
public interface CacheConstants {
    
    /**
     * 系统缓存名称
     */
    String CACHE_NAME = "APP_SYSTEM";
    
    /**
     * 模块前缀
     */
    interface ModulePrefix {
        String METADATA = "META";
        String BUSINESS = "BIZ";
        String USER = "USER";
        String CONFIG = "CFG";
    }
    
    /**
     * 缓存过期时间
     */
    interface ExpireTime {
        long METADATA = 24 * 60 * 60; // 24小时
        long BUSINESS = 8 * 60 * 60;  // 8小时
        long HOT_DATA = 5 * 60;       // 5分钟
    }
    
    /**
     * 刷新阈值（秒）
     */
    long REFRESH_THRESHOLD = 5 * 60; // 5分钟
    
    /**
     * 布隆过滤器容量
     */
    int BLOOM_FILTER_CAPACITY = 10000000;
    
    /**
     * 布隆过滤器误判率
     */
    double BLOOM_FILTER_ERROR_RATE = 0.01;
}

2. 缓存Key生成器

package com.example.cache;

import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 缓存Key生成器
 * 高性能Key生成和解析
 */
@Slf4j
public class CacheKeyGenerator {
    
    /**
     * 字段缓存（避免反射开销）
     */
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    
    /**
     * Key分隔符
     */
    private static final String SEPARATOR = ":";
    
    /**
     * 时间戳分隔符
     */
    private static final String TIMESTAMP_SEPARATOR = "@";
    
    /**
     * 生成缓存Key
     * 格式: CACHE_NAME:MODULE_PREFIX:HASH_VALUE@TIMESTAMP
     * 
     * @param cacheName 缓存名称
     * @param modulePrefix 模块前缀
     * @param params 参数对象
     * @param fieldNames 参与计算的字段名（为空则使用所有字段）
     * @return 缓存Key
     */
    public static String generateKey(String cacheName, String modulePrefix, 
                                     Object params, String... fieldNames) {
        String hashValue = generateHashValue(params, fieldNames);
        long timestamp = System.currentTimeMillis();
        
        return cacheName + SEPARATOR + 
               modulePrefix + SEPARATOR + 
               hashValue + TIMESTAMP_SEPARATOR + 
               timestamp;
    }
    
    /**
     * 生成缓存Key（不带时间戳）
     */
    public static String generateKeyWithoutTimestamp(String cacheName, String modulePrefix, 
                                                    Object params, String... fieldNames) {
        String hashValue = generateHashValue(params, fieldNames);
        return cacheName + SEPARATOR + modulePrefix + SEPARATOR + hashValue;
    }
    
    /**
     * 生成Hash值
     */
    public static String generateHashValue(Object params, String... fieldNames) {
        if (params == null) {
            return "";
        }
        
        try {
            // 1. 提取字段值
            Map<String, Object> fieldMap = extractFields(params, fieldNames);
            
            // 2. 排序并拼接
            String sortedString = fieldMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + convertToString(entry.getValue()))
                    .collect(Collectors.joining("&"));
            
            // 3. 计算SHA256（比MD5更安全，性能也可接受）
            return Hashing.sha256()
                    .hashString(sortedString, StandardCharsets.UTF_8)
                    .toString()
                    .substring(0, 16); // 取前16位即可
            
        } catch (Exception e) {
            log.error("生成Hash值失败", e);
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }
    
    /**
     * 解析Key获取时间戳
     */
    public static long parseTimestamp(String cacheKey) {
        if (cacheKey == null || !cacheKey.contains(TIMESTAMP_SEPARATOR)) {
            return 0L;
        }
        
        try {
            int index = cacheKey.lastIndexOf(TIMESTAMP_SEPARATOR);
            String timestampStr = cacheKey.substring(index + 1);
            return Long.parseLong(timestampStr);
        } catch (Exception e) {
            log.error("解析时间戳失败: {}", cacheKey, e);
            return 0L;
        }
    }
    
    /**
     * 判断是否需要刷新
     */
    public static boolean needRefresh(String cacheKey, long thresholdSeconds) {
        long timestamp = parseTimestamp(cacheKey);
        if (timestamp == 0) {
            return true;
        }
        
        long elapsedSeconds = (System.currentTimeMillis() - timestamp) / 1000;
        return elapsedSeconds >= thresholdSeconds;
    }
    
    /**
     * 获取不带时间戳的Key
     */
    public static String getKeyWithoutTimestamp(String cacheKey) {
        if (cacheKey == null || !cacheKey.contains(TIMESTAMP_SEPARATOR)) {
            return cacheKey;
        }
        
        int index = cacheKey.lastIndexOf(TIMESTAMP_SEPARATOR);
        return cacheKey.substring(0, index);
    }
    
    /**
     * 提取字段
     */
    private static Map<String, Object> extractFields(Object obj, String[] fieldNames) 
            throws IllegalAccessException {
        
        Map<String, Object> fieldMap = new TreeMap<>();
        Class<?> clazz = obj.getClass();
        
        if (fieldNames == null || fieldNames.length == 0) {
            // 使用所有字段
            List<Field> fields = getCachedFields(clazz);
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value != null) {
                    fieldMap.put(field.getName(), value);
                }
            }
        } else {
            // 使用指定字段
            for (String fieldName : fieldNames) {
                Field field = findField(clazz, fieldName);
                if (field != null) {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value != null) {
                        fieldMap.put(fieldName, value);
                    }
                }
            }
        }
        
        return fieldMap;
    }
    
    /**
     * 获取缓存的字段列表
     */
    private static List<Field> getCachedFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, k -> {
            List<Field> fields = new ArrayList<>();
            Class<?> currentClass = clazz;
            
            while (currentClass != null && currentClass != Object.class) {
                fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
                currentClass = currentClass.getSuperclass();
            }
            
            return fields;
        });
    }
    
    /**
     * 查找字段
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        List<Field> fields = getCachedFields(clazz);
        return fields.stream()
                .filter(f -> f.getName().equals(fieldName))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 转换为字符串
     */
    private static String convertToString(Object value) {
        if (value == null) {
            return "";
        }
        
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            return collection.stream()
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.joining(","));
        }
        
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            return Arrays.stream(array)
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.joining(","));
        }
        
        return value.toString();
    }
}

3. 高性能序列化工具
package com.example.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * 高性能序列化工具
 * 使用FastJSON2实现
 */
@Slf4j
public class SerializationUtil {
    
    /**
     * 序列化
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            String jsonString = JSON.toJSONString(obj, 
                    JSONWriter.Feature.WriteClassName,
                    JSONWriter.Feature.FieldBased);
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
            return JSON.parseObject(jsonString, clazz, 
                    JSONReader.Feature.SupportAutoType,
                    JSONReader.Feature.FieldBased);
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
            String jsonString = JSON.toJSONString(obj);
            return JSON.parseObject(jsonString, clazz);
        } catch (Exception e) {
            log.error("反序列化失败", e);
            throw new RuntimeException("反序列化失败", e);
        }
    }
}

4. 本地热点缓存
package com.example.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地热点缓存
 * 使用Guava Cache实现，只缓存Key，不缓存Value
 */
@Slf4j
@Component
public class LocalHotCache {
    
    /**
     * Key缓存（存储Key和最后访问时间）
     */
    private final Cache<String, Long> keyCache;
    
    /**
     * 元数据缓存（可以缓存Value）
     */
    private final Cache<String, Object> metadataCache;
    
    public LocalHotCache() {
        // Key缓存：最大10000个，5分钟过期
        this.keyCache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
        
        // 元数据缓存：最大5000个，1小时过期
        this.metadataCache = CacheBuilder.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()
                .build();
    }
    
    /**
     * 记录Key访问
     */
    public void recordKeyAccess(String key) {
        keyCache.put(key, System.currentTimeMillis());
    }
    
    /**
     * 获取Key最后访问时间
     */
    public Long getKeyAccessTime(String key) {
        return keyCache.getIfPresent(key);
    }
    
    /**
     * 判断Key是否在本地缓存
     */
    public boolean containsKey(String key) {
        return keyCache.getIfPresent(key) != null;
    }
    
    /**
     * 移除Key
     */
    public void removeKey(String key) {
        keyCache.invalidate(key);
    }
    
    /**
     * 清空Key缓存
     */
    public void clearKeys() {
        keyCache.invalidateAll();
    }
    
    /**
     * 设置元数据（可以缓存Value）
     */
    public void putMetadata(String key, Object value) {
        metadataCache.put(key, value);
    }
    
    /**
     * 获取元数据
     */
    public Object getMetadata(String key) {
        return metadataCache.getIfPresent(key);
    }
    
    /**
     * 移除元数据
     */
    public void removeMetadata(String key) {
        metadataCache.invalidate(key);
    }
    
    /**
     * 清空元数据缓存
     */
    public void clearMetadata() {
        metadataCache.invalidateAll();
    }
    
    /**
     * 获取统计信息
     */
    public void printStats() {
        log.info("Key缓存统计: {}", keyCache.stats());
        log.info("元数据缓存统计: {}", metadataCache.stats());
    }
}

5. 布隆过滤器（防止缓存穿透）
package com.example.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 布隆过滤器
 * 防止缓存穿透
 */
@Slf4j
@Component
public class CacheBloomFilter {
    
    private final BloomFilter<String> bloomFilter;
    
    public CacheBloomFilter() {
        // 初始化布隆过滤器：容量1000万，误判率1%
        this.bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                10000000,
                0.01
        );
    }
    
    /**
     * 添加Key到布隆过滤器
     */
    public void add(String key) {
        bloomFilter.put(key);
    }
    
    /**
     * 判断Key是否可能存在
     */
    public boolean mightContain(String key) {
        return bloomFilter.mightContain(key);
    }
}

6. Redis封装服务

package com.example.cache;

import com.example.util.SerializationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis缓存封装
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
    
    /**
     * 分布式锁管理
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Lock> lockMap = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * 获取缓存
     */
    public <T> T get(String key, Class<T> clazz) {
        try {
            // 1. 布隆过滤器检查（防止穿透）
            if (!bloomFilter.mightContain(key)) {
                log.debug("布隆过滤器判定Key不存在: {}", key);
                return null;
            }
            
            // 2. 从Redis获取
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 3. 反序列化
            T result = SerializationUtil.deserialize(value, clazz);
            
            // 4. 记录本地热点
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * 设置缓存
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        try {
            // 1. 序列化
            byte[] serializedValue = SerializationUtil.serialize(value);
            
            // 2. 存入Redis
            redisTemplate.set(key, serializedValue, ttl, unit);
            
            // 3. 添加到布隆过滤器
            bloomFilter.add(key);
            
            // 4. 记录本地热点
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
     * 获取本地锁（防止缓存击穿）
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

7. 泛化缓存服务接口

package com.example.service;

import java.io.Serializable;

/**
 * 缓存数据加载器
 * 业务Service实现此接口
 */
@FunctionalInterface
public interface CacheDataLoader<P extends Serializable, R> {
    
    /**
     * 加载数据
     * 
     * @param params 参数
     * @return 数据
     */
    R loadData(P params);
}

package com.example.service;

import java.io.Serializable;

/**
 * 缓存配置
 */
public interface CacheConfig {
    
    /**
     * 获取缓存名称
     */
    String getCacheName();
    
    /**
     * 获取模块前缀
     */
    String getModulePrefix();
    
    /**
     * 获取过期时间（秒）
     */
    long getExpireSeconds();
    
    /**
     * 获取刷新阈值（秒）
     */
    default long getRefreshThreshold() {
        return 300; // 默认5分钟
    }
    
    /**
     * 获取参与Key计算的字段
     */
    default String[] getKeyFields() {
        return new String[0]; // 空数组表示使用所有字段
    }
}

8. 泛化缓存管理器
package com.example.cache;

import com.example.constant.CacheConstants;
import com.example.service.CacheConfig;
import com.example.service.CacheDataLoader;
import com.example.util.AsyncTaskUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * 泛化缓存管理器
 * 提供统一的缓存操作接口
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
     * 获取缓存数据（带自动刷新）
     * 
     * @param params 请求参数
     * @param config 缓存配置
     * @param loader 数据加载器
     * @param resultClass 返回类型
     * @return 数据
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        // 1. 生成缓存Key
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        // 2. 检查本地热点缓存
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("本地热点缓存命中: {}", cacheKey);
            
            // 判断是否需要异步刷新
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        // 3. 从Redis获取
        R cachedData = redisService.get(cacheKey, resultClass);
        if (cachedData != null) {
            log.debug("Redis缓存命中: {}", cacheKey);
            return cachedData;
        }
        
        // 4. 缓存未命中，加载数据（防止缓存击穿）
        return loadDataWithLock(params, config, loader, cacheKey);
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
            // 构造异步上下文
            CacheRefreshContext context = new CacheRefreshContext();
            context.setCacheKey(cacheKey);
            context.setParams(params);
            context.setConfig(config);
            context.setLoader(loader);
            
            // 调用异步任务
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
            String cacheKey) {
        
        Lock lock = redisService.getLock(cacheKey);
        lock.lock();
        
        try {
            // 双重检查
            R cachedData = redisService.get(cacheKey, (Class<R>) Object.class);
            if (cachedData != null) {
                return cachedData;
            }
            
            // 加载数据
            log.info("加载数据: {}", cacheKey);
            R data = loader.loadData(params);
            
            if (data != null) {
                // 写入缓存
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
    
    /**
     * 异步刷新上下文
     */
    @lombok.Data
    public static class CacheRefreshContext implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String cacheKey;
        private Object params;
        private CacheConfig config;
        private CacheDataLoader loader;
    }
}

9. 缓存刷新异步处理器
package com.example.handler;

import com.example.cache.GenericCacheManager;
import com.example.service.IAsyncProcessHandler;
import com.example.vo.AjaxMessageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 缓存刷新异步处理器
 */
@Slf4j
@Component("cacheRefreshHandler")
public class CacheRefreshHandler implements IAsyncProcessHandler {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Override
    public AjaxMessageVo handlerProcess(Serializable context) throws ApplicationException {
        try {
            GenericCacheManager.CacheRefreshContext refreshContext = 
                    (GenericCacheManager.CacheRefreshContext) context;
            
            log.info("开始异步刷新缓存: {}", refreshContext.getCacheKey());
            
            // 刷新缓存
            cacheManager.refreshCache(
                    refreshContext.getParams(),
                    refreshContext.getConfig(),
                    refreshContext.getLoader()
            );
            
            log.info("异步刷新缓存完成: {}", refreshContext.getCacheKey());
            
            return AjaxMessageVo.success();
            
        } catch (Exception e) {
            log.error("异步刷新缓存失败", e);
            return AjaxMessageVo.error("刷新失败: " + e.getMessage());
        }
    }
}

10. 元数据缓存服务
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