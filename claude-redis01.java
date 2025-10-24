1. ���ĳ�������

package com.example.constant;

import java.util.concurrent.TimeUnit;

/**
 * ���泣��
 */
public interface CacheConstants {
    
    /**
     * ϵͳ��������
     */
    String CACHE_NAME = "APP_SYSTEM";
    
    /**
     * ģ��ǰ׺
     */
    interface ModulePrefix {
        String METADATA = "META";
        String BUSINESS = "BIZ";
        String USER = "USER";
        String CONFIG = "CFG";
    }
    
    /**
     * �������ʱ��
     */
    interface ExpireTime {
        long METADATA = 24 * 60 * 60; // 24Сʱ
        long BUSINESS = 8 * 60 * 60;  // 8Сʱ
        long HOT_DATA = 5 * 60;       // 5����
    }
    
    /**
     * ˢ����ֵ���룩
     */
    long REFRESH_THRESHOLD = 5 * 60; // 5����
    
    /**
     * ��¡����������
     */
    int BLOOM_FILTER_CAPACITY = 10000000;
    
    /**
     * ��¡������������
     */
    double BLOOM_FILTER_ERROR_RATE = 0.01;
}

2. ����Key������

package com.example.cache;

import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ����Key������
 * ������Key���ɺͽ���
 */
@Slf4j
public class CacheKeyGenerator {
    
    /**
     * �ֶλ��棨���ⷴ�俪����
     */
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    
    /**
     * Key�ָ���
     */
    private static final String SEPARATOR = ":";
    
    /**
     * ʱ����ָ���
     */
    private static final String TIMESTAMP_SEPARATOR = "@";
    
    /**
     * ���ɻ���Key
     * ��ʽ: CACHE_NAME:MODULE_PREFIX:HASH_VALUE@TIMESTAMP
     * 
     * @param cacheName ��������
     * @param modulePrefix ģ��ǰ׺
     * @param params ��������
     * @param fieldNames ���������ֶ�����Ϊ����ʹ�������ֶΣ�
     * @return ����Key
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
     * ���ɻ���Key������ʱ�����
     */
    public static String generateKeyWithoutTimestamp(String cacheName, String modulePrefix, 
                                                    Object params, String... fieldNames) {
        String hashValue = generateHashValue(params, fieldNames);
        return cacheName + SEPARATOR + modulePrefix + SEPARATOR + hashValue;
    }
    
    /**
     * ����Hashֵ
     */
    public static String generateHashValue(Object params, String... fieldNames) {
        if (params == null) {
            return "";
        }
        
        try {
            // 1. ��ȡ�ֶ�ֵ
            Map<String, Object> fieldMap = extractFields(params, fieldNames);
            
            // 2. ����ƴ��
            String sortedString = fieldMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + convertToString(entry.getValue()))
                    .collect(Collectors.joining("&"));
            
            // 3. ����SHA256����MD5����ȫ������Ҳ�ɽ��ܣ�
            return Hashing.sha256()
                    .hashString(sortedString, StandardCharsets.UTF_8)
                    .toString()
                    .substring(0, 16); // ȡǰ16λ����
            
        } catch (Exception e) {
            log.error("����Hashֵʧ��", e);
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }
    
    /**
     * ����Key��ȡʱ���
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
            log.error("����ʱ���ʧ��: {}", cacheKey, e);
            return 0L;
        }
    }
    
    /**
     * �ж��Ƿ���Ҫˢ��
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
     * ��ȡ����ʱ�����Key
     */
    public static String getKeyWithoutTimestamp(String cacheKey) {
        if (cacheKey == null || !cacheKey.contains(TIMESTAMP_SEPARATOR)) {
            return cacheKey;
        }
        
        int index = cacheKey.lastIndexOf(TIMESTAMP_SEPARATOR);
        return cacheKey.substring(0, index);
    }
    
    /**
     * ��ȡ�ֶ�
     */
    private static Map<String, Object> extractFields(Object obj, String[] fieldNames) 
            throws IllegalAccessException {
        
        Map<String, Object> fieldMap = new TreeMap<>();
        Class<?> clazz = obj.getClass();
        
        if (fieldNames == null || fieldNames.length == 0) {
            // ʹ�������ֶ�
            List<Field> fields = getCachedFields(clazz);
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value != null) {
                    fieldMap.put(field.getName(), value);
                }
            }
        } else {
            // ʹ��ָ���ֶ�
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
     * ��ȡ������ֶ��б�
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
     * �����ֶ�
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        List<Field> fields = getCachedFields(clazz);
        return fields.stream()
                .filter(f -> f.getName().equals(fieldName))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * ת��Ϊ�ַ���
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

3. ���������л�����
package com.example.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * ���������л�����
 * ʹ��FastJSON2ʵ��
 */
@Slf4j
public class SerializationUtil {
    
    /**
     * ���л�
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
            log.error("���л�ʧ��", e);
            throw new RuntimeException("���л�ʧ��", e);
        }
    }
    
    /**
     * �����л�
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
            log.error("�����л�ʧ��", e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
    
    /**
     * �����л����Ӷ���
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
            log.error("�����л�ʧ��", e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
}

4. �����ȵ㻺��
package com.example.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * �����ȵ㻺��
 * ʹ��Guava Cacheʵ�֣�ֻ����Key��������Value
 */
@Slf4j
@Component
public class LocalHotCache {
    
    /**
     * Key���棨�洢Key��������ʱ�䣩
     */
    private final Cache<String, Long> keyCache;
    
    /**
     * Ԫ���ݻ��棨���Ի���Value��
     */
    private final Cache<String, Object> metadataCache;
    
    public LocalHotCache() {
        // Key���棺���10000����5���ӹ���
        this.keyCache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
        
        // Ԫ���ݻ��棺���5000����1Сʱ����
        this.metadataCache = CacheBuilder.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()
                .build();
    }
    
    /**
     * ��¼Key����
     */
    public void recordKeyAccess(String key) {
        keyCache.put(key, System.currentTimeMillis());
    }
    
    /**
     * ��ȡKey������ʱ��
     */
    public Long getKeyAccessTime(String key) {
        return keyCache.getIfPresent(key);
    }
    
    /**
     * �ж�Key�Ƿ��ڱ��ػ���
     */
    public boolean containsKey(String key) {
        return keyCache.getIfPresent(key) != null;
    }
    
    /**
     * �Ƴ�Key
     */
    public void removeKey(String key) {
        keyCache.invalidate(key);
    }
    
    /**
     * ���Key����
     */
    public void clearKeys() {
        keyCache.invalidateAll();
    }
    
    /**
     * ����Ԫ���ݣ����Ի���Value��
     */
    public void putMetadata(String key, Object value) {
        metadataCache.put(key, value);
    }
    
    /**
     * ��ȡԪ����
     */
    public Object getMetadata(String key) {
        return metadataCache.getIfPresent(key);
    }
    
    /**
     * �Ƴ�Ԫ����
     */
    public void removeMetadata(String key) {
        metadataCache.invalidate(key);
    }
    
    /**
     * ���Ԫ���ݻ���
     */
    public void clearMetadata() {
        metadataCache.invalidateAll();
    }
    
    /**
     * ��ȡͳ����Ϣ
     */
    public void printStats() {
        log.info("Key����ͳ��: {}", keyCache.stats());
        log.info("Ԫ���ݻ���ͳ��: {}", metadataCache.stats());
    }
}

5. ��¡����������ֹ���洩͸��
package com.example.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ��¡������
 * ��ֹ���洩͸
 */
@Slf4j
@Component
public class CacheBloomFilter {
    
    private final BloomFilter<String> bloomFilter;
    
    public CacheBloomFilter() {
        // ��ʼ����¡������������1000��������1%
        this.bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                10000000,
                0.01
        );
    }
    
    /**
     * ���Key����¡������
     */
    public void add(String key) {
        bloomFilter.put(key);
    }
    
    /**
     * �ж�Key�Ƿ���ܴ���
     */
    public boolean mightContain(String key) {
        return bloomFilter.mightContain(key);
    }
}

6. Redis��װ����

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
 * Redis�����װ
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
     * �ֲ�ʽ������
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Lock> lockMap = 
            new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * ��ȡ����
     */
    public <T> T get(String key, Class<T> clazz) {
        try {
            // 1. ��¡��������飨��ֹ��͸��
            if (!bloomFilter.mightContain(key)) {
                log.debug("��¡�������ж�Key������: {}", key);
                return null;
            }
            
            // 2. ��Redis��ȡ
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 3. �����л�
            T result = SerializationUtil.deserialize(value, clazz);
            
            // 4. ��¼�����ȵ�
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * ���û���
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        try {
            // 1. ���л�
            byte[] serializedValue = SerializationUtil.serialize(value);
            
            // 2. ����Redis
            redisTemplate.set(key, serializedValue, ttl, unit);
            
            // 3. ��ӵ���¡������
            bloomFilter.add(key);
            
            // 4. ��¼�����ȵ�
            localHotCache.recordKeyAccess(key);
            
        } catch (Exception e) {
            log.error("���û���ʧ��, key: {}", key, e);
            throw new RuntimeException("���û���ʧ��", e);
        }
    }
    
    /**
     * ɾ������
     */
    public void remove(String key) {
        try {
            redisTemplate.remove(key);
            localHotCache.removeKey(key);
        } catch (Exception e) {
            log.error("ɾ������ʧ��, key: {}", key, e);
        }
    }
    
    /**
     * ��ջ���
     */
    public void clear(String cacheName) {
        try {
            redisTemplate.clear(cacheName);
            localHotCache.clearKeys();
        } catch (Exception e) {
            log.error("��ջ���ʧ��, cacheName: {}", cacheName, e);
        }
    }
    
    /**
     * ��ȡ����������ֹ���������
     */
    public Lock getLock(String key) {
        return lockMap.computeIfAbsent(key, k -> new ReentrantLock());
    }
    
    /**
     * �ͷ���
     */
    public void releaseLock(String key) {
        Lock lock = lockMap.get(key);
        if (lock != null) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("�ͷ���ʧ��, key: {}", key, e);
            }
        }
    }
}

7. �����������ӿ�

package com.example.service;

import java.io.Serializable;

/**
 * �������ݼ�����
 * ҵ��Serviceʵ�ִ˽ӿ�
 */
@FunctionalInterface
public interface CacheDataLoader<P extends Serializable, R> {
    
    /**
     * ��������
     * 
     * @param params ����
     * @return ����
     */
    R loadData(P params);
}

package com.example.service;

import java.io.Serializable;

/**
 * ��������
 */
public interface CacheConfig {
    
    /**
     * ��ȡ��������
     */
    String getCacheName();
    
    /**
     * ��ȡģ��ǰ׺
     */
    String getModulePrefix();
    
    /**
     * ��ȡ����ʱ�䣨�룩
     */
    long getExpireSeconds();
    
    /**
     * ��ȡˢ����ֵ���룩
     */
    default long getRefreshThreshold() {
        return 300; // Ĭ��5����
    }
    
    /**
     * ��ȡ����Key������ֶ�
     */
    default String[] getKeyFields() {
        return new String[0]; // �������ʾʹ�������ֶ�
    }
}

8. �������������
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
 * �������������
 * �ṩͳһ�Ļ�������ӿ�
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
     * ��ȡ�������ݣ����Զ�ˢ�£�
     * 
     * @param params �������
     * @param config ��������
     * @param loader ���ݼ�����
     * @param resultClass ��������
     * @return ����
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        // 1. ���ɻ���Key
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        // 2. ��鱾���ȵ㻺��
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("�����ȵ㻺������: {}", cacheKey);
            
            // �ж��Ƿ���Ҫ�첽ˢ��
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        // 3. ��Redis��ȡ
        R cachedData = redisService.get(cacheKey, resultClass);
        if (cachedData != null) {
            log.debug("Redis��������: {}", cacheKey);
            return cachedData;
        }
        
        // 4. ����δ���У��������ݣ���ֹ���������
        return loadDataWithLock(params, config, loader, cacheKey);
    }
    
    /**
     * �ж��Ƿ���Ҫ�첽ˢ��
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
     * �첽ˢ�»���
     */
    private <P extends Serializable, R> void asyncRefreshCache(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            String cacheKey) {
        
        log.info("�����첽ˢ��: {}", cacheKey);
        
        try {
            // �����첽������
            CacheRefreshContext context = new CacheRefreshContext();
            context.setCacheKey(cacheKey);
            context.setParams(params);
            context.setConfig(config);
            context.setLoader(loader);
            
            // �����첽����
            asyncTaskUtil.invokeAsync("cacheRefreshHandler", context);
            
        } catch (Exception e) {
            log.error("�첽ˢ��ʧ��: {}", cacheKey, e);
        }
    }
    
    /**
     * �������ݣ���������ֹ���������
     */
    private <P extends Serializable, R> R loadDataWithLock(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            String cacheKey) {
        
        Lock lock = redisService.getLock(cacheKey);
        lock.lock();
        
        try {
            // ˫�ؼ��
            R cachedData = redisService.get(cacheKey, (Class<R>) Object.class);
            if (cachedData != null) {
                return cachedData;
            }
            
            // ��������
            log.info("��������: {}", cacheKey);
            R data = loader.loadData(params);
            
            if (data != null) {
                // д�뻺��
                redisService.set(cacheKey, data, config.getExpireSeconds(), TimeUnit.SECONDS);
            }
            
            return data;
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * ˢ�»���
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
     * �첽ˢ��������
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

9. ����ˢ���첽������
package com.example.handler;

import com.example.cache.GenericCacheManager;
import com.example.service.IAsyncProcessHandler;
import com.example.vo.AjaxMessageVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * ����ˢ���첽������
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
            
            log.info("��ʼ�첽ˢ�»���: {}", refreshContext.getCacheKey());
            
            // ˢ�»���
            cacheManager.refreshCache(
                    refreshContext.getParams(),
                    refreshContext.getConfig(),
                    refreshContext.getLoader()
            );
            
            log.info("�첽ˢ�»������: {}", refreshContext.getCacheKey());
            
            return AjaxMessageVo.success();
            
        } catch (Exception e) {
            log.error("�첽ˢ�»���ʧ��", e);
            return AjaxMessageVo.error("ˢ��ʧ��: " + e.getMessage());
        }
    }
}

10. Ԫ���ݻ������
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