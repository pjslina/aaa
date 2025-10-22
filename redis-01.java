package com.example.config;

import com.alibaba.fastjson2.support.spring.data.redis.FastJsonRedisSerializer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis������
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * �Զ���RedisTemplate����
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key���л���String
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value���л���FastJSON
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
 * �첽������
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * ����ˢ���̳߳�
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
     * ͨ���첽�̳߳�
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
     * �첽�쳣������
     */
    static class CustomAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("�첽����ִ���쳣 - ����: {}, ����: {}, �쳣: ", 
                    method.getName(), params, ex);
        }
    }
}





package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ��ʱ��������
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {
}




package com.example.constant;

/**
 * ���泣��
 */
public interface CacheConstants {

    /**
     * ϵͳKeyǰ׺
     */
    String SYSTEM_PREFIX = "app:";

    /**
     * Ԫ���ݻ���ǰ׺
     */
    String METADATA_PREFIX = SYSTEM_PREFIX + "metadata:";

    /**
     * �������ݻ���ǰ׺
     */
    String CONFIG_PREFIX = SYSTEM_PREFIX + "config:";

    /**
     * ҵ�����ݻ���ǰ׺
     */
    String BUSINESS_PREFIX = SYSTEM_PREFIX + "business:";

    /**
     * ���󻺴�ǰ׺
     */
    String REQUEST_PREFIX = SYSTEM_PREFIX + "request:";

    /**
     * �������ʱ�䣨�룩
     */
    interface ExpireTime {
        long NO_EXPIRE = -1L;           // ��������
        long EIGHT_HOURS = 8 * 60 * 60L; // 8Сʱ
        long ONE_DAY = 24 * 60 * 60L;    // 1��
        long FIVE_MINUTES = 5 * 60L;     // 5����
    }

    /**
     * ����ˢ����ֵ���룩
     */
    long REFRESH_THRESHOLD = 5 * 60L; // 5����
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
 * ����Key���ɹ���
 */
@Slf4j
public class CacheKeyGenerator {

    /**
     * ���ݶ�������MD5 Key
     * 
     * @param obj ����
     * @param fields ��Ҫ���������ֶΣ����Ϊ�գ�ʹ�������ֶΣ�
     * @return MD5ֵ
     */
    public static String generateMD5Key(Object obj, String... fields) {
        if (obj == null) {
            return "";
        }

        try {
            Map<String, Object> fieldMap = new TreeMap<>();

            if (fields == null || fields.length == 0) {
                // ʹ�������ֶ�
                fieldMap = extractAllFields(obj);
            } else {
                // ʹ��ָ���ֶ�
                fieldMap = extractSpecifiedFields(obj, fields);
            }

            // תΪJSON�ַ���
            String jsonString = JSON.toJSONString(fieldMap);

            // ����MD5
            return Hashing.md5()
                    .hashString(jsonString, StandardCharsets.UTF_8)
                    .toString();

        } catch (Exception e) {
            log.error("����MD5 Keyʧ��", e);
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * ���ɴ�ʱ����Ļ���Key
     * 
     * @param prefix ǰ׺
     * @param md5Key MD5ֵ
     * @param timestamp ʱ���
     * @return ������Key
     */
    public static String generateCacheKey(String prefix, String md5Key, long timestamp) {
        return prefix + md5Key + ":" + timestamp;
    }

    /**
     * ���ɴ�ʱ����Ļ���Key��ʹ�õ�ǰʱ�䣩
     */
    public static String generateCacheKey(String prefix, String md5Key) {
        return generateCacheKey(prefix, md5Key, System.currentTimeMillis());
    }

    /**
     * ��Key����ȡʱ���
     */
    public static long extractTimestamp(String cacheKey) {
        try {
            int lastColonIndex = cacheKey.lastIndexOf(":");
            if (lastColonIndex > 0) {
                String timestampStr = cacheKey.substring(lastColonIndex + 1);
                return Long.parseLong(timestampStr);
            }
        } catch (Exception e) {
            log.error("��ȡʱ���ʧ��: {}", cacheKey, e);
        }
        return 0L;
    }

    /**
     * �жϻ����Ƿ���Ҫˢ��
     * 
     * @param cacheKey ����Key
     * @param thresholdSeconds ˢ����ֵ���룩
     * @return true-��Ҫˢ��
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
     * ��ȡ����������ֶ�
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
     * ��ȡ�����ָ���ֶ�
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
     * �����ֶΣ�֧�ָ��ࣩ
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
 * Redis���������
 */
@Slf4j
@Component
public class RedisCacheManager {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * ���û��棨�������ڣ�
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("���û���ʧ��, key: {}", key, e);
        }
    }

    /**
     * ���û��棨������ʱ�䣩
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            log.error("���û���ʧ��, key: {}", key, e);
        }
    }

    /**
     * ���û��棨������ʱ�䣬�룩
     */
    public void setWithExpire(String key, Object value, long expireSeconds) {
        set(key, value, expireSeconds, TimeUnit.SECONDS);
    }

    /**
     * ��ȡ����
     */
    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }

    /**
     * ��ȡ���棨������ת����
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
            log.error("��������ת��ʧ��, key: {}, targetClass: {}", key, clazz.getName(), e);
            return null;
        }
    }

    /**
     * ��ȡ���棬�������������ز�����
     * 
     * @param key ����Key
     * @param loader ���ݼ�����
     * @param expireSeconds ����ʱ�䣨�룩��-1��ʾ��������
     * @return ����ֵ
     */
    public <T> T getOrLoad(String key, Supplier<T> loader, long expireSeconds) {
        // �ȴӻ����ȡ
        Object cached = get(key);
        if (cached != null) {
            return (T) cached;
        }

        // ���治���ڣ���������
        T data = loader.get();
        if (data != null) {
            // д�뻺��
            if (expireSeconds > 0) {
                setWithExpire(key, data, expireSeconds);
            } else {
                set(key, data);
            }
        }

        return data;
    }

    /**
     * ɾ������
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("ɾ������ʧ��, key: {}", key, e);
        }
    }

    /**
     * ����ɾ������
     */
    public void delete(Collection<String> keys) {
        try {
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.error("����ɾ������ʧ��, keys: {}", keys, e);
        }
    }

    /**
     * ɾ��ƥ��Ļ���
     */
    public void deleteByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("ɾ������, pattern: {}, count: {}", pattern, keys.size());
            }
        } catch (Exception e) {
            log.error("ɾ��ƥ�仺��ʧ��, pattern: {}", pattern, e);
        }
    }

    /**
     * �ж�Key�Ƿ����
     */
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("�ж�Key����ʧ��, key: {}", key, e);
            return false;
        }
    }

    /**
     * ���ù���ʱ��
     */
    public void expire(String key, long timeout, TimeUnit unit) {
        try {
            redisTemplate.expire(key, timeout, unit);
        } catch (Exception e) {
            log.error("���ù���ʱ��ʧ��, key: {}", key, e);
        }
    }

    /**
     * ��ȡ����ʱ�䣨�룩
     */
    public Long getExpire(String key) {
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("��ȡ����ʱ��ʧ��, key: {}", key, e);
            return null;
        }
    }

    /**
     * Hash����������
     */
    public void hSet(String key, String hashKey, Object value) {
        try {
            redisTemplate.opsForHash().put(key, hashKey, value);
        } catch (Exception e) {
            log.error("Hash����ʧ��, key: {}, hashKey: {}", key, hashKey, e);
        }
    }

    /**
     * Hash��������ȡ
     */
    public Object hGet(String key, String hashKey) {
        try {
            return redisTemplate.opsForHash().get(key, hashKey);
        } catch (Exception e) {
            log.error("Hash��ȡʧ��, key: {}, hashKey: {}", key, hashKey, e);
            return null;
        }
    }

    /**
     * Hash��������������
     */
    public void hSetAll(String key, Map<String, Object> map) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
        } catch (Exception e) {
            log.error("Hash��������ʧ��, key: {}", key, e);
        }
    }

    /**
     * Hash��������ȡ����
     */
    public Map<Object, Object> hGetAll(String key) {
        try {
            return redisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            log.error("Hash��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }

    /**
     * List��������push
     */
    public void lPush(String key, Object value) {
        try {
            redisTemplate.opsForList().leftPush(key, value);
        } catch (Exception e) {
            log.error("List��pushʧ��, key: {}", key, e);
        }
    }

    /**
     * List��������push
     */
    public void rPush(String key, Object value) {
        try {
            redisTemplate.opsForList().rightPush(key, value);
        } catch (Exception e) {
            log.error("List��pushʧ��, key: {}", key, e);
        }
    }

    /**
     * List��������ȡ��Χ
     */
    public List<Object> lRange(String key, long start, long end) {
        try {
            return redisTemplate.opsForList().range(key, start, end);
        } catch (Exception e) {
            log.error("List��ȡ��Χʧ��, key: {}", key, e);
            return null;
        }
    }
}





package com.example.service;

/**
 * �첽ˢ�·���ӿ�
 * ������Ҫ�첽ˢ�µķ���ʵ�ִ˽ӿ�
 */
public interface AsyncRefreshService {

    /**
     * �������ݣ��첽ˢ�£�
     * 
     * @param params ����
     * @return ������
     */
    Object processData(Object... params);

    /**
     * ��ȡ��������
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
 * �첽������
 * �ṩͨ�õ��첽���ù���
 */
@Slf4j
@Component
public class AsyncUtil implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    /**
     * ���񻺴棨������ܣ�
     */
    private final Map<String, AsyncRefreshService> serviceCache = new ConcurrentHashMap<>();

    /**
     * ��������
     */
    private final Map<String, Method> methodCache = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * �첽ִ�У�ʹ��AsyncRefreshService�ӿڣ�
     * 
     * @param serviceClass ������
     * @param params ����
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
                log.error("�첽ִ��ʧ��, serviceClass: {}", serviceClass.getName(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * �첽ִ�У�ʹ��Bean���ƣ�
     * 
     * @param beanName Bean����
     * @param params ����
     * @return CompletableFuture
     */
    @Async("cacheRefreshExecutor")
    public <T> CompletableFuture<T> executeAsync(String beanName, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AsyncRefreshService service = getService(beanName);
                return (T) service.processData(params);
            } catch (Exception e) {
                log.error("�첽ִ��ʧ��, beanName: {}", beanName, e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * �첽ִ�У�ʹ��Bean���ƺͷ�������
     * 
     * @param beanName Bean����
     * @param methodName ������
     * @param params ����
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
                log.error("�첽ִ��ʧ��, beanName: {}, methodName: {}", beanName, methodName, e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * �첽ִ�У����ȴ������
     * 
     * @param serviceClass ������
     * @param params ����
     */
    @Async("cacheRefreshExecutor")
    public void executeAsyncNoWait(Class<? extends AsyncRefreshService> serviceClass, Object... params) {
        try {
            AsyncRefreshService service = getService(serviceClass);
            service.processData(params);
            log.info("�첽����ִ�����, service: {}", serviceClass.getSimpleName());
        } catch (Exception e) {
            log.error("�첽ִ��ʧ��, serviceClass: {}", serviceClass.getName(), e);
        }
    }

    /**
     * �첽ִ�У����ȴ������ʹ��Bean���ƣ�
     */
    @Async("cacheRefreshExecutor")
    public void executeAsyncNoWait(String beanName, Object... params) {
        try {
            AsyncRefreshService service = getService(beanName);
            service.processData(params);
            log.info("�첽����ִ�����, beanName: {}", beanName);
        } catch (Exception e) {
            log.error("�첽ִ��ʧ��, beanName: {}", beanName, e);
        }
    }

    /**
     * ��ȡ����ʵ����ʹ���ࣩ
     */
    private AsyncRefreshService getService(Class<? extends AsyncRefreshService> serviceClass) {
        String className = serviceClass.getName();
        return serviceCache.computeIfAbsent(className, k -> applicationContext.getBean(serviceClass));
    }

    /**
     * ��ȡ����ʵ����ʹ��Bean���ƣ�
     */
    private AsyncRefreshService getService(String beanName) {
        return serviceCache.computeIfAbsent(beanName, k -> (AsyncRefreshService) applicationContext.getBean(beanName));
    }

    /**
     * ��ȡ�����������棩
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
 * Ԫ���ݻ������
 * ����1������ʱ����Ԫ���ݵ�Redis�������ù���ʱ�䣬ÿ��ˢ��һ��
 */
@Slf4j
@Service
public class MetadataCacheService implements CommandLineRunner, AsyncRefreshService {

    @Autowired
    private RedisCacheManager cacheManager;

    @Autowired
    private MetadataMapper metadataMapper;

    /**
     * Ӧ������ʱ����Ԫ����
     */
    @Override
    public void run(String... args) {
        log.info("��ʼ����Ԫ���ݵ�Redis...");
        loadMetadata();
        log.info("Ԫ���ݼ������");
    }

    /**
     * ÿ���賿1��ˢ��Ԫ����
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void refreshMetadataDaily() {
        log.info("��ʼ��ʱˢ��Ԫ����...");
        loadMetadata();
        log.info("Ԫ����ˢ�����");
    }

    /**
     * ����Ԫ���ݵ�Redis
     */
    public void loadMetadata() {
        try {
            // 1. ����ָ��Ԫ����
            loadMetrics();

            // 2. ���ض���Ԫ����
            loadMeasures();

            // 3. ������Ԫ����
            loadDomains();

            // 4. ������֯�㼶Ԫ����
            loadOrgLevels();

            log.info("Ԫ���ݼ��سɹ�");

        } catch (Exception e) {
            log.error("����Ԫ����ʧ��