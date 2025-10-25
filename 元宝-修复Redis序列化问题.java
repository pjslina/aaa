1. �޸�RedisService��get����

package com.example.cache;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.List;

/**
 * �޸����Redis����
 */
@Slf4j
@Component
public class RedisService {
    
    // �������뱣�ֲ���...
    
    /**
     * ��ǿ��get������֧�ַ�������
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, clazz, null);
    }
    
    /**
     * ֧�ַ������͵�get����
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz, Type genericType) {
        try {
            // 1. ��¡���������
            if (!bloomFilter.mightContain(key)) {
                log.debug("��¡������δ�ҵ�Key: {}", key);
                return null;
            }
            
            // 2. Redis��ȡ
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 3. ʹ��FastJSON�����л�
            T result;
            if (value instanceof String) {
                // ���Redis�д洢����JSON�ַ���
                String jsonString = (String) value;
                if (genericType != null) {
                    // ����������
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else if (value instanceof byte[]) {
                // ����洢�����ֽ�����
                String jsonString = new String((byte[]) value, StandardCharsets.UTF_8);
                if (genericType != null) {
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else {
                // ֱ������ת��
                result = (T) value;
            }
            
            // 4. ��¼�����ȵ�
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * ר�Ŵ���List���͵�get����
     */
    public <T> List<T> getList(String key, Class<T> elementType) {
        try {
            // ʹ��TypeReference������List
            Type type = new TypeReference<List<T>>(elementType) {}.getType();
            return get(key, List.class, type);
        } catch (Exception e) {
            log.error("��ȡList����ʧ��, key: {}", key, e);
            return null;
        }
    }
}

2. �޸�SerializationUtil��FastJSON�汾��

package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FastJSON���л�����
 */
@Slf4j
public class SerializationUtil {
    
    /**
     * ���л����󣨰���������Ϣ��
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            // ʹ��WriteClassNameд��������Ϣ�����ڷ����л�
            String jsonString = JSON.toJSONString(obj, 
                    SerializerFeature.WriteClassName,
                    SerializerFeature.WriteMapNullValue,
                    SerializerFeature.WriteDateUseDateFormat);
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
            return JSON.parseObject(jsonString, clazz);
        } catch (Exception e) {
            log.error("�����л�ʧ��", e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
    
    /**
     * �����л����Ͷ���
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
            log.error("�����л�����ʧ��", e);
            throw new RuntimeException("�����л�����ʧ��", e);
        }
    }
    
    /**
     * �����л�List
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
            log.error("�����л�Listʧ��", e);
            throw new RuntimeException("�����л�Listʧ��", e);
        }
    }
}

���������������ˣ����ǹ��ڻ��洩͸��˫�ؼ���������⡣��GenericCacheManager��ȷʵ����һ��������⣺��һ�δ�Redis��ȡ����ʧ�ܺ��ڼ�����������ٴλ�ȡȴ�ܳɹ���

���ҷ������Ⲣ�ṩ���������

�������
��GenericCacheManager.getCachedData�����У�

java
����
����
����
// 3. Redis��ȡ
R cachedData = redisService.get(cacheKey, resultClass);
if (cachedData != null) {
    log.debug("Redis���л���: {}", cacheKey);
    return cachedData;
}

// 4. δ���л��棬������������
return loadDataWithLock(params, config, loader, cacheKey);
����loadDataWithLock�У�

java
����
����
����
private <P extends Serializable, R> R loadDataWithLock(...) {
    Lock lock = redisService.getLock(cacheKey);
    lock.lock();
    
    try {
        // ˫�ؼ�飺�ٴδ�Redis��ȡ
        R cachedData = redisService.get(cacheKey, (Class<R>) Object.class);
        if (cachedData != null) {
            return cachedData;
        }
        
        // ��������...
    } finally {
        lock.unlock();
    }
}
����ԭ��
���л����Ͳ�ƥ�䣺���ᵽ�����л���Ϊ��FastJSON�����Ҵ������List<MetricMeasureDataRepVO>����

�������Ͳ�������Redis�д洢��ʵ����List���������л�ʱָ���˾��巺�����Ϳ��ܵ�������

FastJSON�������⣺����ȱ�ٱ�Ҫ��������Ϣ

�������
1. �޸�RedisService��get����
java
����
����
����
package com.example.cache;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.List;

/**
 * �޸����Redis����
 */
@Slf4j
@Component
public class RedisService {
    
    // �������뱣�ֲ���...
    
    /**
     * ��ǿ��get������֧�ַ�������
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, clazz, null);
    }
    
    /**
     * ֧�ַ������͵�get����
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz, Type genericType) {
        try {
            // 1. ��¡���������
            if (!bloomFilter.mightContain(key)) {
                log.debug("��¡������δ�ҵ�Key: {}", key);
                return null;
            }
            
            // 2. Redis��ȡ
            Cache.ValueWrapper wrapper = redisTemplate.get(key);
            if (wrapper == null) {
                return null;
            }
            
            Object value = wrapper.get();
            if (value == null) {
                return null;
            }
            
            // 3. ʹ��FastJSON�����л�
            T result;
            if (value instanceof String) {
                // ���Redis�д洢����JSON�ַ���
                String jsonString = (String) value;
                if (genericType != null) {
                    // ����������
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else if (value instanceof byte[]) {
                // ����洢�����ֽ�����
                String jsonString = new String((byte[]) value, StandardCharsets.UTF_8);
                if (genericType != null) {
                    result = JSON.parseObject(jsonString, new TypeReference<T>(genericType) {});
                } else {
                    result = JSON.parseObject(jsonString, clazz);
                }
            } else {
                // ֱ������ת��
                result = (T) value;
            }
            
            // 4. ��¼�����ȵ�
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * ר�Ŵ���List���͵�get����
     */
    public <T> List<T> getList(String key, Class<T> elementType) {
        try {
            // ʹ��TypeReference������List
            Type type = new TypeReference<List<T>>(elementType) {}.getType();
            return get(key, List.class, type);
        } catch (Exception e) {
            log.error("��ȡList����ʧ��, key: {}", key, e);
            return null;
        }
    }
}
2. �޸�SerializationUtil��FastJSON�汾��
java
����
����
����
package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FastJSON���л�����
 */
@Slf4j
public class SerializationUtil {
    
    /**
     * ���л����󣨰���������Ϣ��
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            // ʹ��WriteClassNameд��������Ϣ�����ڷ����л�
            String jsonString = JSON.toJSONString(obj, 
                    SerializerFeature.WriteClassName,
                    SerializerFeature.WriteMapNullValue,
                    SerializerFeature.WriteDateUseDateFormat);
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
            return JSON.parseObject(jsonString, clazz);
        } catch (Exception e) {
            log.error("�����л�ʧ��", e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
    
    /**
     * �����л����Ͷ���
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
            log.error("�����л�����ʧ��", e);
            throw new RuntimeException("�����л�����ʧ��", e);
        }
    }
    
    /**
     * �����л�List
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
            log.error("�����л�Listʧ��", e);
            throw new RuntimeException("�����л�Listʧ��", e);
        }
    }
}
3. �޸�GenericCacheManager

package com.example.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * �޸����ͨ�û��������
 */
@Slf4j
@Component
public class GenericCacheManager {
    
    // �������뱣�ֲ���...
    
    /**
     * ��ǿ�Ļ�ȡ�������ݷ�����֧�ַ�������
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        return getCachedData(params, config, loader, resultClass, null);
    }
    
    /**
     * ֧�ַ������͵Ļ�ȡ�������ݷ���
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass,
            Type genericType) {
        
        // 1. ����Key
        String cacheKey = CacheKeyGenerator.generateKey(
                config.getCacheName(),
                config.getModulePrefix(),
                params,
                config.getKeyFields()
        );
        
        // 2. ��鱾���ȵ㻺��
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("�����ȵ㻺������: {}", cacheKey);
            
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        // 3. Redis��ȡ��ʹ���޸���ķ�����
        R cachedData;
        if (genericType != null) {
            cachedData = redisService.get(cacheKey, resultClass, genericType);
        } else {
            cachedData = redisService.get(cacheKey, resultClass);
        }
        
        if (cachedData != null) {
            log.debug("Redis���л���: {}", cacheKey);
            return cachedData;
        }
        
        // 4. δ���У���������
        return loadDataWithLock(params, config, loader, cacheKey, resultClass, genericType);
    }
    
    /**
     * ר�Ŵ���List���͵Ļ�ȡ����
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
        
        // ��鱾���ȵ㻺��
        if (localHotCache.containsKey(cacheKey)) {
            log.debug("�����ȵ㻺������: {}", cacheKey);
            
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        // ʹ��ר�Ŵ���List�ķ���
        List<T> cachedData = redisService.getList(cacheKey, elementType);
        if (cachedData != null) {
            log.debug("Redis����List����: {}", cacheKey);
            return cachedData;
        }
        
        return loadListDataWithLock(params, config, loader, cacheKey, elementType);
    }
    
    /**
     * �޸��ļ����������ݷ���
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
            // ˫�ؼ�飺�ٴδ�Redis��ȡ��ʹ����ͬ�ķ�����
            R cachedData;
            if (genericType != null) {
                cachedData = redisService.get(cacheKey, resultClass, genericType);
            } else {
                cachedData = redisService.get(cacheKey, resultClass);
            }
            
            if (cachedData != null) {
                log.debug("˫�ؼ��Redis���л���: {}", cacheKey);
                return cachedData;
            }
            
            // ��������
            log.info("������Դ��������: {}", cacheKey);
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
     * ר�Ŵ���List�ļ������ط���
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
            // ˫�ؼ��
            List<T> cachedData = redisService.getList(cacheKey, elementType);
            if (cachedData != null) {
                log.debug("˫�ؼ��Redis����List����: {}", cacheKey);
                return cachedData;
            }
            
            // ��������
            log.info("������Դ����List����: {}", cacheKey);
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

4. ҵ��Service�е�ʹ��ʾ��

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
 * ָ����� - �޸����ʹ�÷�ʽ
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
     * ��ȡָ�������б� - �޸��汾
     */
    public List<MetricMeasureDataRepVO> getMetricDataList(MetricReqVO params) {
        try {
            // ʹ��ר�Ŵ���List�ķ���
            return cacheManager.getCachedListData(
                params,
                metricCacheConfig,
                metricDataLoader,
                MetricMeasureDataRepVO.class  // �б�Ԫ������
            );
            
        } catch (Exception e) {
            log.error("��ȡָ�������б��쳣, params: {}", params, e);
            throw new RuntimeException("��ȡָ������ʧ��", e);
        }
    }
    
    /**
     * ����ʹ�÷������͵ķ�ʽ
     */
    public List<MetricMeasureDataRepVO> getMetricDataListWithGenericType(MetricReqVO params) {
        try {
            // ������������
            Type listType = new com.alibaba.fastjson.TypeReference<List<MetricMeasureDataRepVO>>() {}.getType();
            
            return cacheManager.getCachedData(
                params,
                metricCacheConfig,
                metricDataLoader,
                List.class,           // ԭʼ����
                listType              // ����������Ϣ
            );
            
        } catch (Exception e) {
            log.error("��ȡָ�������б��쳣, params: {}", params, e);
            throw new RuntimeException("��ȡָ������ʧ��", e);
        }
    }
}

