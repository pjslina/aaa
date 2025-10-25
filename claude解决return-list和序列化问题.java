1. �޸����л������ࣨʹ��fastjson��
package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * ���������л����ߣ�ʹ��fastjson��
 */
@Slf4j
public class SerializationUtil {
    
    static {
        // ����fastjson��AutoType֧�֣�ע�ⰲȫ���գ�����������Ҫ���ð�������
        ParserConfig.getGlobalInstance().setAutoTypeSupport(true);
        // �������ð�����
        // ParserConfig.getGlobalInstance().addAccept("com.example.");
    }
    
    /**
     * ���л�
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
            // ��ת��JSON�ַ������ٷ����л�
            String jsonString = JSON.toJSONString(obj);
            return JSON.parseObject(jsonString, clazz);
        } catch (Exception e) {
            log.error("�����л�ʧ��", e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
}


2. ����TypeReference֧����

package com.example.cache;

import com.alibaba.fastjson.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.lang.reflect.Type;

/**
 * �������Ͱ�װ��
 * ���ڴ���List<T>�ȸ��ӷ�������
 */
@Data
@AllArgsConstructor
public class CacheTypeWrapper {
    
    /**
     * ʵ�ʵ�Type
     */
    private Type type;
    
    /**
     * ����List���͵İ�װ��
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
     * ���������͵İ�װ��
     */
    public static CacheTypeWrapper forClass(Class<?> clazz) {
        return new CacheTypeWrapper(clazz);
    }
}

3. �޸����л������ࣨ����Type֧�֣�

package com.example.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * ���������л����ߣ�ʹ��fastjson��֧�ָ��ӷ��ͣ�
 */
@Slf4j
public class SerializationUtil {
    
    static {
        ParserConfig.getGlobalInstance().setAutoTypeSupport(true);
    }
    
    /**
     * ���л�
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
            log.error("���л�ʧ��", e);
            throw new RuntimeException("���л�ʧ��", e);
        }
    }
    
    /**
     * �����л���֧��Type��
     */
    public static <T> T deserialize(byte[] bytes, Type type) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String jsonString = new String(bytes, StandardCharsets.UTF_8);
            return JSON.parseObject(jsonString, type);
        } catch (Exception e) {
            log.error("�����л�ʧ�ܣ�type: {}", type, e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
    
    /**
     * �����л���Class��
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return deserialize(bytes, (Type) clazz);
    }
    
    /**
     * �����л����Ӷ���֧��Type��
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
            log.error("�����л�ʧ�ܣ�type: {}", type, e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
    
    /**
     * �����л����Ӷ���Class��
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

4. �޸�RedisService��֧��Type��

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
 * Redis�����װ��֧�ָ��ӷ������ͣ�
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
     * ��ȡ���棨֧��Type��
     */
    public <T> T get(String key, Type type) {
        try {
            if (!bloomFilter.mightContain(key)) {
                log.debug("��¡�������ж�Key������: {}", key);
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
            
            // �����л���֧��Type��
            T result = SerializationUtil.deserialize(value, type);
            
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * ��ȡ���棨Class��
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, (Type) clazz);
    }
    
    /**
     * ���û���
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        try {
            byte[] serializedValue = SerializationUtil.serialize(value);
            redisTemplate.set(key, serializedValue, ttl, unit);
            bloomFilter.add(key);
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
     * ��ȡ������
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

5. �޸ķ��������������֧��Type��
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
 * ���������������֧�ָ��ӷ������ͣ�
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
     * ��ȡ�������ݣ�ʹ��Type��
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
            log.debug("�����ȵ㻺������: {}", cacheKey);
            
            if (shouldAsyncRefresh(cacheKey, config.getRefreshThreshold())) {
                asyncRefreshCache(params, config, loader, cacheKey);
            }
        }
        
        R cachedData = redisService.get(cacheKey, resultType);
        if (cachedData != null) {
            log.debug("Redis��������: {}", cacheKey);
            return cachedData;
        }
        
        return loadDataWithLock(params, config, loader, cacheKey, resultType);
    }
    
    /**
     * ��ȡ�������ݣ�ʹ��Class��
     */
    public <P extends Serializable, R> R getCachedData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        return getCachedData(params, config, loader, (Type) resultClass);
    }
    
    /**
     * ��ȡ�������ݣ�ʹ��CacheTypeWrapper��
     */
    public <P extends Serializable, R> R getCachedDataWithType(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            CacheTypeWrapper typeWrapper) {
        
        return getCachedData(params, config, loader, typeWrapper.getType());
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
            CacheRefreshContext context = new CacheRefreshContext();
            context.setCacheKey(cacheKey);
            context.setParams(params);
            context.setConfig(config);
            context.setLoader(loader);
            
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
            String cacheKey,
            Type resultType) {
        
        Lock lock = redisService.getLock(cacheKey);
        lock.lock();
        
        try {
            // ˫�ؼ��
            R cachedData = redisService.get(cacheKey, resultType);
            if (cachedData != null) {
                return cachedData;
            }
            
            log.info("��������: {}", cacheKey);
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
    
    @lombok.Data
    public static class CacheRefreshContext implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private String cacheKey;
        private Object params;
        private CacheConfig config;
        private CacheDataLoader loader;
    }
}

6. �޸�ҵ��Serviceʾ��������List<T>��
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
 * �������ݷ��񣨷���List<MetricMeasureDataRestVO>��
 */
@Slf4j
@Service
public class MeasureDataService {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    @Autowired
    private OtherBusinessService otherBusinessService;
    
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
     * ��ȡ�������ݣ�����List<MetricMeasureDataRestVO>��
     * 
     * @param codeVO ��ѯ����
     * @return ���������б�
     */
    public List<MetricMeasureDataRestVO> getMeasureData(CodeVO codeVO) {
        // �������ݼ�����
        CacheDataLoader<CodeVO, List<MetricMeasureDataRestVO>> loader = params -> {
            log.info("ִ��ҵ���߼���������, params: {}", params);
            return loadMeasureDataFromBusiness(params);
        };
        
        // ʹ��CacheTypeWrapperָ��List<MetricMeasureDataRestVO>����
        CacheTypeWrapper typeWrapper = CacheTypeWrapper.forList(MetricMeasureDataRestVO.class);
        
        // ʹ�÷��������������ȡ����
        return cacheManager.getCachedDataWithType(
                codeVO,
                CACHE_CONFIG,
                loader,
                typeWrapper
        );
    }
    
    /**
     * ��ҵ���߼���������
     */
    private List<MetricMeasureDataRestVO> loadMeasureDataFromBusiness(CodeVO codeVO) {
        // ���ӵ�ҵ���߼�
        return otherBusinessService.queryMeasureData(codeVO);
    }
}

7. ���򻯵�ʹ�÷�ʽ������������
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
 * ����ʹ�ø�������
 */
@Component
public class CacheHelper {
    
    @Autowired
    private GenericCacheManager cacheManager;
    
    /**
     * ��ȡList���͵Ļ�������
     * 
     * @param params ����
     * @param config ����
     * @param loader ������
     * @param elementClass ListԪ������
     * @return List����
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
     * ��ȡ��������Ļ�������
     */
    public <P extends Serializable, R> R getSingleData(
            P params,
            CacheConfig config,
            CacheDataLoader<P, R> loader,
            Class<R> resultClass) {
        
        return cacheManager.getCachedData(params, config, loader, resultClass);
    }
}


8. ʹ��CacheHelper�ļ�ʾ��
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
 * ʹ��CacheHelper�ļ�ʾ��
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
     * ��ȡ�������ݣ���򻯵ĵ��÷�ʽ��
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



����������ʹ��AutoType���ֶ�����������Ϣ���ȫ��
1. ����������Ϣ��װ��

package com.example.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * �������ݰ�װ��
 * �ֶ��洢������Ϣ������ʹ��AutoType
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheDataWrapper implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * ʵ�����ݵ���������
     */
    private String dataClassName;
    
    /**
     * ���л����JSON�ַ���
     */
    private String jsonData;
    
    /**
     * �������ͣ�single-������list-�б�
     */
    private String dataType;
    
    /**
     * �����List���洢Ԫ������
     */
    private String elementClassName;
}

2. �޸����л������ࣨ��ʹ��AutoType��

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
 * ��ȫ�����л����ߣ���ʹ��AutoType��
 */
@Slf4j
public class SafeSerializationUtil {
    
    /**
     * ���л�����װ������Ϣ��
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return null;
        }
        
        try {
            CacheDataWrapper wrapper = new CacheDataWrapper();
            
            // �ж���������
            if (obj instanceof List) {
                wrapper.setDataType("list");
                List<?> list = (List<?>) obj;
                if (!list.isEmpty()) {
                    wrapper.setElementClassName(list.get(0).getClass().getName());
                }
            } else {
                wrapper.setDataType("single");
            }
            
            // �洢����
            wrapper.setDataClassName(obj.getClass().getName());
            
            // ���л����ݣ���ʹ��WriteClassName��
            String jsonData = JSON.toJSONString(obj, 
                    SerializerFeature.DisableCircularReferenceDetect);
            wrapper.setJsonData(jsonData);
            
            // ���л���װ��
            String wrapperJson = JSON.toJSONString(wrapper);
            return wrapperJson.getBytes(StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.error("���л�ʧ��", e);
            throw new RuntimeException("���л�ʧ��", e);
        }
    }
    
    /**
     * �����л���ʹ��Type��
     */
    public static <T> T deserialize(byte[] bytes, Type expectedType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String wrapperJson = new String(bytes, StandardCharsets.UTF_8);
            
            // �����л���װ��
            CacheDataWrapper wrapper = JSON.parseObject(wrapperJson, CacheDataWrapper.class);
            if (wrapper == null || wrapper.getJsonData() == null) {
                return null;
            }
            
            // ����expectedType�����л�ʵ������
            return JSON.parseObject(wrapper.getJsonData(), expectedType);
            
        } catch (Exception e) {
            log.error("�����л�ʧ�ܣ�expectedType: {}", expectedType, e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
    
    /**
     * �����л���Class��
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        return deserialize(bytes, (Type) clazz);
    }
    
    /**
     * �����л����Ӷ���
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(Object obj, Type expectedType) {
        if (obj == null) {
            return null;
        }
        
        try {
            // ���obj��byte[]
            if (obj instanceof byte[]) {
                return deserialize((byte[]) obj, expectedType);
            }
            
            // ���obj��String
            if (obj instanceof String) {
                return deserialize(((String) obj).getBytes(StandardCharsets.UTF_8), expectedType);
            }
            
            // �����������תJSON�ٷ����л�
            String jsonString = JSON.toJSONString(obj);
            return JSON.parseObject(jsonString, expectedType);
            
        } catch (Exception e) {
            log.error("�����л�ʧ�ܣ�expectedType: {}", expectedType, e);
            throw new RuntimeException("�����л�ʧ��", e);
        }
    }
}

3. �޸�RedisServiceʹ�ð�ȫ�����л�����
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
 * Redis�����װ��ʹ�ð�ȫ���л���
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
     * ��ȡ���棨ʹ�ð�ȫ�����л���
     */
    public <T> T get(String key, Type type) {
        try {
            if (!bloomFilter.mightContain(key)) {
                log.debug("��¡�������ж�Key������: {}", key);
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
            
            // ʹ�ð�ȫ�ķ����л�
            T result = SafeSerializationUtil.deserialize(value, type);
            
            localHotCache.recordKeyAccess(key);
            
            return result;
            
        } catch (Exception e) {
            log.error("��ȡ����ʧ��, key: {}", key, e);
            return null;
        }
    }
    
    /**
     * ��ȡ���棨Class��
     */
    public <T> T get(String key, Class<T> clazz) {
        return get(key, (Type) clazz);
    }
    
    /**
     * ���û��棨ʹ�ð�ȫ���л���
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        try {
            // ʹ�ð�ȫ�����л�
            byte[] serializedValue = SafeSerializationUtil.serialize(value);
            
            redisTemplate.set(key, serializedValue, ttl, unit);
            bloomFilter.add(key);
            localHotCache.recordKeyAccess(key);
            
        } catch (Exception e) {
            log.error("���û���ʧ��, key: {}", key, e);
            throw new RuntimeException("���û���ʧ��", e);
        }
    }
    
    public void remove(String key) {
        try {
            redisTemplate.remove(key);
            localHotCache.removeKey(key);
        } catch (Exception e) {
            log.error("ɾ������ʧ��, key: {}", key, e);
        }
    }
    
    public void clear(String cacheName) {
        try {
            redisTemplate.clear(cacheName);
            localHotCache.clearKeys();
        } catch (Exception e) {
            log.error("��ջ���ʧ��, cacheName: {}", cacheName, e);
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
                log.error("�ͷ���ʧ��, key: {}", key, e);
            }
        }
    }
}

