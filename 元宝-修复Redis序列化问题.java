1. 修复RedisService的get方法

package com.example.cache;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 修复后的Redis服务
 */
@Slf4j
@Component
public class RedisService {
    
    // 其他代码保持不变...
    
    /**
     * 增强的get方法，支持泛型类型
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, clazz, null);
    }
    
    /**
     * 支持泛型类型的get方法
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz, Type genericType) {
        try {
            // 1. 布隆过滤器检查
            if (!bloomFilter.mightContain(key)) {
                log.debug("布隆过滤器未找到Key: {}", key);
                return null;
            }
            
            // 2. Redis获取
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 3. 使用FastJSON反序列化
            T result;
            if (value instanceof String) {
                // 如果Redis中存储的是JSON字符串
                String jsonString = (String) value;
                if (genericType != null) {
                    // 处理泛型类型
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else if (value instanceof byte[]) {
                // 如果存储的是字节数组
                String jsonString = new String((byte[]) value, StandardCharsets.UTF_8);
                if (genericType != null) {
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else {
                // 直接类型转换
                result = (T) value;
            }
            
            // 4. 记录本地热点
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * 专门处理List类型的get方法
     */
    public <T> List<T> getList(String key, Class<T> elementType) {
        try {
            // 使用TypeReference处理泛型List
            Type type = new TypeReference<List<T>>(elementType) {}.getType();
            return get(key, List.class, type);
        } catch (Exception e) {
            log.error("获取List缓存失败, key: {}", key, e);
            return null;
        }
    }
}

2. 修复SerializationUtil（FastJSON版本）

package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FastJSON序列化工具
 */
@Slf4j
public class SerializationUtil {
    
    /**
     * 序列化对象（包含类型信息）
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            // 使用WriteClassName写入类型信息，便于反序列化
            String jsonString = JSON.toJSONString(obj, 
                    SerializerFeature.WriteClassName,
                    SerializerFeature.WriteMapNullValue,
                    SerializerFeature.WriteDateUseDateFormat);
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
     * 反序列化泛型对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes, Type type) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            return (T) JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("反序列化泛型失败", e);
            throw new RuntimeException("反序列化泛型失败", e);
        }
    }
    
    /**
     * 反序列化List
     */
    public static <T> List<T> deserializeList(byte[] bytes, Class<T> elementType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            Type type = new TypeReference<List<T>>(elementType) {}.getType();
            return JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("反序列化List失败", e);
            throw new RuntimeException("反序列化List失败", e);
        }
    }
}

我明白您的问题了！这是关于缓存穿透和双重检查锁的问题。在GenericCacheManager中确实存在一个设计问题：第一次从Redis获取缓存失败后，在加锁的情况下再次获取却能成功。

让我分析问题并提供解决方案：

问题分析
在GenericCacheManager.getCachedData方法中：

java
下载
复制
运行
// 3. Redis获取
R cachedData = redisService.get(cacheKey, resultClass);
if (cachedData != null) {
    log.debug("Redis命中缓存: {}", cacheKey);
    return cachedData;
}

// 4. 未命中缓存，加锁加载数据
return loadDataWithLock(params, config, loader, cacheKey);
而在loadDataWithLock中：

java
下载
复制
运行
private <P extends Serializable, R> R loadDataWithLock(...) {
    Lock lock = redisService.getLock(cacheKey);
    lock.lock();
    
    try {
        // 双重检查：再次从Redis获取
        R cachedData = redisService.get(cacheKey, (Class<R>) Object.class);
        if (cachedData != null) {
            return cachedData;
        }
        
        // 加载数据...
    } finally {
        lock.unlock();
    }
}
问题原因
序列化类型不匹配：您提到将序列化改为了FastJSON，并且传入的是List<MetricMeasureDataRepVO>类型

泛型类型擦除：在Redis中存储的实际是List，但反序列化时指定了具体泛型类型可能导致问题

FastJSON配置问题：可能缺少必要的类型信息

解决方案
1. 修复RedisService的get方法
java
下载
复制
运行
package com.example.cache;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 修复后的Redis服务
 */
@Slf4j
@Component
public class RedisService {
    
    // 其他代码保持不变...
    
    /**
     * 增强的get方法，支持泛型类型
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, clazz, null);
    }
    
    /**
     * 支持泛型类型的get方法
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz, Type genericType) {
        try {
            // 1. 布隆过滤器检查
            if (!bloomFilter.mightContain(key)) {
                log.debug("布隆过滤器未找到Key: {}", key);
                return null;
            }
            
            // 2. Redis获取
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 3. 使用FastJSON反序列化
            T result;
            if (value instanceof String) {
                // 如果Redis中存储的是JSON字符串
                String jsonString = (String) value;
                if (genericType != null) {
                    // 处理泛型类型
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else if (value instanceof byte[]) {
                // 如果存储的是字节数组
                String jsonString = new String((byte[]) value, StandardCharsets.UTF_8);
                if (genericType != null) {
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else {
                // 直接类型转换
                result = (T) value;
            }
            
            // 4. 记录本地热点
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * 专门处理List类型的get方法
     */
    public <T> List<T> getList(String key, Class<T> elementType) {
        try {
            // 使用TypeReference处理泛型List
            Type type = new TypeReference<List<T>>(elementType) {}.getType();
            return get(key, List.class, type);
        } catch (Exception e) {
            log.error("获取List缓存失败, key: {}", key, e);
            return null;
        }
    }
}
2. 修复SerializationUtil（FastJSON版本）
java
下载
复制
运行
package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FastJSON序列化工具
 */
@Slf4j
public class SerializationUtil {
    
    /**
     * 序列化对象（包含类型信息）
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            // 使用WriteClassName写入类型信息，便于反序列化
            String jsonString = JSON.toJSONString(obj, 
                    SerializerFeature.WriteClassName,
                    SerializerFeature.WriteMapNullValue,
                    SerializerFeature.WriteDateUseDateFormat);
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
     * 反序列化泛型对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes, Type type) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            return (T) JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("反序列化泛型失败", e);
            throw new RuntimeException("反序列化泛型失败", e);
        }
    }
    
    /**
     * 反序列化List
     */
    public static <T> List<T> deserializeList(byte[] bytes, Class<T> elementType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            Type type = new TypeReference<List<T>>(elementType) {}.getType();
            return JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("反序列化List失败", e);
            throw new RuntimeException("反序列化List失败", e);
        }
    }
}
3. 修复GenericCacheManager

package com.example.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * 修复后的通用缓存管理器
 */
@Slf4j
@Component
public class GenericCacheManager {
    
    // 其他代码保持不变...
    
    /**
     * 增强的获取缓存数据方法，支持泛型类型
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        return getCachedData(params, config, loader, resultClass, null);
    }
    
    /**
     * 支持泛型类型的获取缓存数据方法
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass,
            Type genericType) {
        
        // 1. 生成Key
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        // 2. 检查本地热点缓存
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("本地热点缓存命中: {}", cacheKey);
            
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        // 3. Redis获取（使用修复后的方法）
        R cachedData;
        if (genericType != null) {
            cachedData = redisService.get(cacheKey, resultClass, genericType);
        } else {
            cachedData = redisService.get(cacheKey, resultClass);
        }
        
        if (cachedData != null) {
            log.debug("Redis命中缓存: {}", cacheKey);
            return cachedData;
        }
        
        // 4. 未命中，加锁加载
        return loadDataWithLock(params, config, loader, cacheKey, resultClass, genericType);
    }
    
    /**
     * 专门处理List类型的获取方法
     */
    public <P extends Serializable, T> List<T> getCachedListData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, List<T>> loader,
            Class<T> elementType) {
        
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        // 检查本地热点缓存
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("本地热点缓存命中: {}", cacheKey);
            
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        // 使用专门处理List的方法
        List<T> cachedData = redisService.getList(cacheKey, elementType);
        if (cachedData != null) {
            log.debug("Redis命中List缓存: {}", cacheKey);
            return cachedData;
        }
        
        return loadListDataWithLock(params, config, loader, cacheKey, elementType);
    }
    
    /**
     * 修复的加锁加载数据方法
     */
    private <P extends Serializable, R> R loadDataWithLock(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            String cacheKey,
            Class<R> resultClass,
            Type genericType) {
        
        Lock lock = redisService.getLock(cacheKey);
        lock.lock();
        
        try {
            // 双重检查：再次从Redis获取（使用相同的方法）
            R cachedData;
            if (genericType != null) {
                cachedData = redisService.get(cacheKey, resultClass, genericType);
            } else {
                cachedData = redisService.get(cacheKey, resultClass);
            }
            
            if (cachedData != null) {
                log.debug("双重检查Redis命中缓存: {}", cacheKey);
                return cachedData;
            }
            
            // 加载数据
            log.info("从数据源加载数据: {}", cacheKey);
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
     * 专门处理List的加锁加载方法
     */
    private <P extends Serializable, T> List<T> loadListDataWithLock(
            P params,
            CacheConfig config,
            CacheDataLoader<P, List<T>> loader,
            String cacheKey,
            Class<T> elementType) {
        
        Lock lock = redisService.getLock(cacheKey);
        lock.lock();
        
        try {
            // 双重检查
            List<T> cachedData = redisService.getList(cacheKey, elementType);
            if (cachedData != null) {
                log.debug("双重检查Redis命中List缓存: {}", cacheKey);
                return cachedData;
            }
            
            // 加载数据
            log.info("从数据源加载List数据: {}", cacheKey);
            List<T> data = loader.loadData(params);
            
            if (data != null) {
                redisService.set(cacheKey, data, config.getExpireSeconds(), TimeUnit.SECONDS);
            }
            
            return data;
            
        } finally {
            lock.unlock();
        }
    }
}

4. 业务Service中的使用示例

package com.example.service.impl;

import com.example.cache.GenericCacheManager;
import com.example.config.MetricCacheConfig;
import com.example.vo.MetricMeasureDataRepVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.List;

/**
 * 指标服务 - 修复后的使用方式
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
     * 获取指标数据列表 - 修复版本
     */
    public List<MetricMeasureDataRepVO> getMetricDataList(MetricReqVO params) {
        try {
            // 使用专门处理List的方法
            return cacheManager.getCachedListData(
                params,
                metricCacheConfig,
                metricDataLoader,
                MetricMeasureDataRepVO.class  // 列表元素类型
            );
            
        } catch (Exception e) {
            log.error("获取指标数据列表异常, params: {}", params, e);
            throw new RuntimeException("获取指标数据失败", e);
        }
    }
    
    /**
     * 或者使用泛型类型的方式
     */
    public List<MetricMeasureDataRepVO> getMetricDataListWithGenericType(MetricReqVO params) {
        try {
            // 创建泛型类型
            Type listType = new com.alibaba.fastjson.TypeReference<List<MetricMeasureDataRepVO>>() {}.getType();
            
            return cacheManager.getCachedData(
                params,
                metricCacheConfig,
                metricDataLoader,
                List.class,           // 原始类型
                listType              // 泛型类型信息
            );
            
        } catch (Exception e) {
            log.error("获取指标数据列表异常, params: {}", params, e);
            throw new RuntimeException("获取指标数据失败", e);
        }
    }
}

