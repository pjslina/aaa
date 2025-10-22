package com.example.config;

import com.alibaba.fastjson2.support.spring.data.redis.FastJsonRedisSerializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * 自定义RedisTemplate配置
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key序列化：String
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value序列化：FastJSON
        FastJsonRedisSerializer<Object> fastJsonSerializer = new FastJsonRedisSerializer<>(Object.class);
        template.setValueSerializer(fastJsonSerializer);
        template.setHashValueSerializer(fastJsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}

package com.example.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步配置类
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 缓存刷新线程池
     */
    @Bean(name = "cacheRefreshExecutor")
    public Executor cacheRefreshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("cache-refresh-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 通用异步线程池
     */
    @Bean(name = "asyncExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new CustomAsyncExceptionHandler();
    }

    /**
     * 异步异常处理器
     */
    static class CustomAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("异步任务执行异常 - 方法: {}, 参数: {}, 异常: ", 
                    method.getName(), params, ex);
        }
    }
}





package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务配置
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {
}




package com.example.constant;

/**
 * 缓存常量
 */
public interface CacheConstants {

    /**
     * 系统Key前缀
     */
    String SYSTEM_PREFIX = "app:";

    /**
     * 元数据缓存前缀
     */
    String METADATA_PREFIX = SYSTEM_PREFIX + "metadata:";

    /**
     * 配置数据缓存前缀
     */
    String CONFIG_PREFIX = SYSTEM_PREFIX + "config:";

    /**
     * 业务数据缓存前缀
     */
    String BUSINESS_PREFIX = SYSTEM_PREFIX + "business:";

    /**
     * 请求缓存前缀
     */
    String REQUEST_PREFIX = SYSTEM_PREFIX + "request:";

    /**
     * 缓存过期时间（秒）
     */
    interface ExpireTime {
        long NO_EXPIRE = -1L;           // 永不过期
        long EIGHT_HOURS = 8 * 60 * 60L; // 8小时
        long ONE_DAY = 24 * 60 * 60L;    // 1天
        long FIVE_MINUTES = 5 * 60L;     // 5分钟
    }

    /**
     * 缓存刷新阈值（秒）
     */
    long REFRESH_THRESHOLD = 5 * 60L; // 5分钟
}




package com.example.util;

import com.alibaba.fastjson2.JSON;
import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 缓存Key生成工具
 */
@Slf4j
public class CacheKeyGenerator {

    /**
     * 根据对象生成MD5 Key
     * 
     * @param obj 对象
     * @param fields 需要参与计算的字段（如果为空，使用所有字段）
     * @return MD5值
     */
    public static String generateMD5Key(Object obj, String... fields) {
        if (obj == null) {
            return "";
        }

        try {
            Map<String, Object> fieldMap = new TreeMap<>();

            if (fields == null || fields.length == 0) {
                // 使用所有字段
                fieldMap = extractAllFields(obj);
            } else {
                // 使用指定字段
                fieldMap = extractSpecifiedFields(obj, fields);
            }

            // 转为JSON字符串
            String jsonString = JSON.toJSONString(fieldMap);

            // 计算MD5
            return Hashing.md5()
                    .hashString(jsonString, StandardCharsets.UTF_8)
                    .toString();

        } catch (Exception e) {
            log.error("生成MD5 Key失败", e);
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 生成带时间戳的缓存Key
     * 
     * @param prefix 前缀
     * @param md5Key MD5值
     * @param timestamp 时间戳
     * @return 完整的Key
     */
    public static String generateCacheKey(String prefix, String md5Key, long timestamp) {
        return prefix + md5Key + ":" + timestamp;
    }

    /**
     * 生成带时间戳的缓存Key（使用当前时间）
     */
    public static String generateCacheKey(String prefix, String md5Key) {
        return generateCacheKey(prefix, md5Key, System.currentTimeMillis());
    }

    /**
     * 从Key中提取时间戳
     */
    public static long extractTimestamp(String cacheKey) {
        try {
            int lastColonIndex = cacheKey.lastIndexOf(":");
            if (lastColonIndex > 0) {
                String timestampStr = cacheKey.substring(lastColonIndex + 1);
                return Long.parseLong(timestampStr);
            }
        } catch (Exception e) {
            log.error("提取时间戳失败: {}", cacheKey, e);
        }
        return 0L;
    }

    /**
     * 判断缓存是否需要刷新
     * 
     * @param cacheKey 缓存Key
     * @param thresholdSeconds 刷新阈值（秒）
     * @return true-需要刷新
     */
    public static boolean needRefresh(String cacheKey, long thresholdSeconds) {
        long timestamp = extractTimestamp(cacheKey);
        if (timestamp == 0) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedSeconds = (currentTime - timestamp) / 1000;

        return elapsedSeconds > thresholdSeconds;
    }

    /**
     * 提取对象的所有字段
     */
    private static Map<String, Object> extractAllFields(Object obj) throws IllegalAccessException {
        Map<String, Object> fieldMap = new TreeMap<>();
        Class<?> clazz = obj.getClass();

        while (clazz != null && clazz != Object.class) {
            Field[] declaredFields = clazz.getDeclaredFields();
            for (Field field : declaredFields) {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value != null) {
                    fieldMap.put(field.getName(), value);
                }
            }
            clazz = clazz.getSuperclass();
        }

        return fieldMap;
    }

    /**
     * 提取对象的指定字段
     */
    private static Map<String, Object> extractSpecifiedFields(Object obj, String[] fieldNames) 
            throws IllegalAccessException, NoSuchFieldException {
        Map<String, Object> fieldMap = new TreeMap<>();
        Class<?> clazz = obj.getClass();

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

        return fieldMap;
    }

    /**
     * 查找字段（支持父类）
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}




package com.example.cache;

import com.example.constant.CacheConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis缓存管理器
 */
@Slf4j
@Component
public class RedisCacheManager {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 设置缓存（永不过期）
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("设置缓存失败, key: {}", key, e);
        }
    }

    /**
     * 设置缓存（带过期时间）
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            log.error("设置缓存失败, key: {}", key, e);
        }
    }

    /**
     * 设置缓存（带过期时间，秒）
     */
    public void setWithExpire(String key, Object value, long expireSeconds) {
        set(key, value, expireSeconds, TimeUnit.SECONDS);
    }

    /**
     * 获取缓存
     */
    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("获取缓存失败, key: {}", key, e);
            return null;
        }
    }

    /**
     * 获取缓存（带类型转换）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = get(key);
        if (value == null) {
            return null;
        }

        try {
            return (T) value;
        } catch (ClassCastException e) {
            log.error("缓存类型转换失败, key: {}, targetClass: {}", key, clazz.getName(), e);
            return null;
        }
    }

    /**
     * 获取缓存，如果不存在则加载并缓存
     * 
     * @param key 缓存Key
     * @param loader 数据加载器
     * @param expireSeconds 过期时间（秒），-1表示永不过期
     * @return 缓存值
     */
    public <T> T getOrLoad(String key, Supplier<T> loader, long expireSeconds) {
        // 先从缓存获取
        Object cached = get(key);
        if (cached != null) {
            return (T) cached;
        }

        // 缓存不存在，加载数据
        T data = loader.get();
        if (data != null) {
            // 写入缓存
            if (expireSeconds > 0) {
                setWithExpire(key, data, expireSeconds);
            } else {
                set(key, data);
            }
        }

        return data;
    }

    /**
     * 删除缓存
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除缓存失败, key: {}", key, e);
        }
    }

    /**
     * 批量删除缓存
     */
    public void delete(Collection<String> keys) {
        try {
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.error("批量删除缓存失败, keys: {}", keys, e);
        }
    }

    /**
     * 删除匹配的缓存
     */
    public void deleteByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("删除缓存, pattern: {}, count: {}", pattern, keys.size());
            }
        } catch (Exception e) {
            log.error("删除匹配缓存失败, pattern: {}", pattern, e);
        }
    }

    /**
     * 判断Key是否存在
     */
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("判断Key存在失败, key: {}", key, e);
            return false;
        }
    }

    /**
     * 设置过期时间
     */
    public void expire(String key, long timeout, TimeUnit unit) {
        try {
            redisTemplate.expire(key, timeout, unit);
        } catch (Exception e) {
            log.error("设置过期时间失败, key: {}", key, e);
        }
    }

    /**
     * 获取过期时间（秒）
     */
    public Long getExpire(String key) {
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("获取过期时间失败, key: {}", key, e);
            return null;
        }
    }

    /**
     * Hash操作：设置
     */
    public void hSet(String key, String hashKey, Object value) {
        try {
            redisTemplate.opsForHash().put(key, hashKey, value);
        } catch (Exception e) {
            log.error("Hash设置失败, key: {}, hashKey: {}", key, hashKey, e);
        }
    }

    /**
     * Hash操作：获取
     */
    public Object hGet(String key, String hashKey) {
        try {
            return redisTemplate.opsForHash().get(key, hashKey);
        } catch (Exception e) {
            log.error("Hash获取失败, key: {}, hashKey: {}", key, hashKey, e);
            return null;
        }
    }

    /**
     * Hash操作：批量设置
     */
    public void hSetAll(String key, Map<String, Object> map) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
        } catch (Exception e) {
            log.error("Hash批量设置失败, key: {}", key, e);
        }
    }

    /**
     * Hash操作：获取所有
     */
    public Map<Object, Object> hGetAll(String key) {
        try {
            return redisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            log.error("Hash获取所有失败, key: {}", key, e);
            return null;
        }
    }

    /**
     * List操作：左push
     */
    public void lPush(String key, Object value) {
        try {
            redisTemplate.opsForList().leftPush(key, value);
        } catch (Exception e) {
            log.error("List左push失败, key: {}", key, e);
        }
    }

    /**
     * List操作：右push
     */
    public void rPush(String key, Object value) {
        try {
            redisTemplate.opsForList().rightPush(key, value);
        } catch (Exception e) {
            log.error("List右push失败, key: {}", key, e);
        }
    }

    /**
     * List操作：获取范围
     */
    public List<Object> lRange(String key, long start, long end) {
        try {
            return redisTemplate.opsForList().range(key, start, end);
        } catch (Exception e) {
            log.error("List获取范围失败, key: {}", key, e);
            return null;
        }
    }
}





package com.example.service;

/**
 * 异步刷新服务接口
 * 所有需要异步刷新的服务都实现此接口
 */
public interface AsyncRefreshService {

    /**
     * 处理数据（异步刷新）
     * 
     * @param params 参数
     * @return 处理结果
     */
    Object processData(Object... params);

    /**
     * 获取服务名称
     */
    default String getServiceName() {
        return this.getClass().getSimpleName();
    }
}


package com.example.util;

import com.example.service.AsyncRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步工具类
 * 提供通用的异步调用功能
 */
@Slf4j
@Component
public class AsyncUtil implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    /**
     * 服务缓存（提高性能）
     */
    private final Map<String, AsyncRefreshService> serviceCache = new ConcurrentHashMap<>();

    /**
     * 方法缓存
     */
    private final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 异步执行（使用AsyncRefreshService接口）
     * 
     * @param serviceClass 服务类
     * @param params 参数
     * @return CompletableFuture
     */
    @Async("cacheRefreshExecutor")
    public <T> CompletableFuture<T> executeAsync(Class<? extends AsyncRefreshService> serviceClass, 
                                                   Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AsyncRefreshService service = getService(serviceClass);
                return (T) service.processData(params);
            } catch (Exception e) {
                log.error("异步执行失败, serviceClass: {}", serviceClass.getName(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 异步执行（使用Bean名称）
     * 
     * @param beanName Bean名称
     * @param params 参数
     * @return CompletableFuture
     */
    @Async("cacheRefreshExecutor")
    public <T> CompletableFuture<T> executeAsync(String beanName, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AsyncRefreshService service = getService(beanName);
                return (T) service.processData(params);
            } catch (Exception e) {
                log.error("异步执行失败, beanName: {}", beanName, e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 异步执行（使用Bean名称和方法名）
     * 
     * @param beanName Bean名称
     * @param methodName 方法名
     * @param params 参数
     * @return CompletableFuture
     */
    @Async("cacheRefreshExecutor")
    public <T> CompletableFuture<T> executeAsync(String beanName, String methodName, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object bean = applicationContext.getBean(beanName);
                Method method = getMethod(bean.getClass(), methodName, params);
                return (T) method.invoke(bean, params);
            } catch (Exception e) {
                log.error("异步执行失败, beanName: {}, methodName: {}", beanName, methodName, e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 异步执行（不等待结果）
     * 
     * @param serviceClass 服务类
     * @param params 参数
     */
    @Async("cacheRefreshExecutor")
    public void executeAsyncNoWait(Class<? extends AsyncRefreshService> serviceClass, Object... params) {
        try {
            AsyncRefreshService service = getService(serviceClass);
            service.processData(params);
            log.info("异步任务执行完成, service: {}", serviceClass.getSimpleName());
        } catch (Exception e) {
            log.error("异步执行失败, serviceClass: {}", serviceClass.getName(), e);
        }
    }

    /**
     * 异步执行（不等待结果，使用Bean名称）
     */
    @Async("cacheRefreshExecutor")
    public void executeAsyncNoWait(String beanName, Object... params) {
        try {
            AsyncRefreshService service = getService(beanName);
            service.processData(params);
            log.info("异步任务执行完成, beanName: {}", beanName);
        } catch (Exception e) {
            log.error("异步执行失败, beanName: {}", beanName, e);
        }
    }

    /**
     * 获取服务实例（使用类）
     */
    private AsyncRefreshService getService(Class<? extends AsyncRefreshService> serviceClass) {
        String className = serviceClass.getName();
        return serviceCache.computeIfAbsent(className, k -> applicationContext.getBean(serviceClass));
    }

    /**
     * 获取服务实例（使用Bean名称）
     */
    private AsyncRefreshService getService(String beanName) {
        return serviceCache.computeIfAbsent(beanName, k -> (AsyncRefreshService) applicationContext.getBean(beanName));
    }

    /**
     * 获取方法（带缓存）
     */
    private Method getMethod(Class<?> clazz, String methodName, Object[] params) throws NoSuchMethodException {
        String cacheKey = clazz.getName() + "#" + methodName + "#" + params.length;
        return methodCache.computeIfAbsent(cacheKey, k -> {
            try {
                Class<?>[] paramTypes = new Class[params.length];
                for (int i = 0; i < params.length; i++) {
                    paramTypes[i] = params[i] != null ? params[i].getClass() : Object.class;
                }
                return clazz.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        });
    }
}



package com.example.service.impl;

import com.example.cache.RedisCacheManager;
import com.example.constant.CacheConstants;
import com.example.mapper.MetadataMapper;
import com.example.service.AsyncRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 元数据缓存服务
 * 功能1：启动时加载元数据到Redis，不设置过期时间，每天刷新一次
 */
@Slf4j
@Service
public class MetadataCacheService implements CommandLineRunner, AsyncRefreshService {

    @Autowired
    private RedisCacheManager cacheManager;

    @Autowired
    private MetadataMapper metadataMapper;

    /**
     * 应用启动时加载元数据
     */
    @Override
    public void run(String... args) {
        log.info("开始加载元数据到Redis...");
        loadMetadata();
        log.info("元数据加载完成");
    }

    /**
     * 每天凌晨1点刷新元数据
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void refreshMetadataDaily() {
        log.info("开始定时刷新元数据...");
        loadMetadata();
        log.info("元数据刷新完成");
    }

    /**
     * 加载元数据到Redis
     */
    public void loadMetadata() {
        try {
            // 1. 加载指标元数据
            loadMetrics();

            // 2. 加载度量元数据
            loadMeasures();

            // 3. 加载域元数据
            loadDomains();

            // 4. 加载组织层级元数据
            loadOrgLevels();

            log.info("元数据加载成功");

        } catch (Exception e) {
            log.error("加载元数据失败