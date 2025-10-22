com.example.cache
 ���� config/
 ��   ���� CacheInitializer.java       # ����ʱ���ػ���
 ���� service/
 ��   ���� CacheService.java           # ͨ�û������ӿ�
 ��   ���� CacheServiceImpl.java       # ʵ����
 ��   ���� SchusterService.java        # �첽����ӿ�
 ��   ���� SampleSchusterService.java  # ʾ���첽����ʵ��
 ���� util/
 ��   ���� CacheKeyUtil.java           # MD5 + Key ���ɹ���
 ��   ���� AsyncUtil.java              # ͨ���첽��������
 ���� controller/
 ��   ���� DataController.java         # ʾ���ӿڵ���

1. �������ػ���
package com.example.cache.config;

import com.example.cache.service.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CacheInitializer implements CommandLineRunner {

    private final CacheService cacheService;

    @Override
    public void run(String... args) {
        // ����ʱ����Ԫ��������������
        cacheService.loadMetaData();
        cacheService.loadConfigData();
    }
}


2. ͨ�û������ӿ���ʵ��
package com.example.cache.service;

public interface CacheService {

    /** ����ʱ����Ԫ���� */
    void loadMetaData();

    /** ����ʱ������������ */
    void loadConfigData();

    /** ��ȡҵ�����ݣ��������ȣ� */
    <T> T getBusinessData(String prefix, Object params, Class<T> type, DataProvider<T> provider);

    /** �����ṩ�ߣ���������δ����ʱ���ã� */
    @FunctionalInterface
    interface DataProvider<T> {
        T get();
    }
}


ʵ���ࣨ����ȫ�������Ż���
package com.example.cache.service.impl;

import com.example.cache.service.CacheService;
import com.example.cache.util.CacheKeyUtil;
import com.example.cache.util.AsyncUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AsyncUtil asyncUtil;

    private static final String META_KEY = "sys:meta:data";
    private static final String CONFIG_KEY = "sys:config:data";

    // ======== �������� ========
    @Override
    public void loadMetaData() {
        // ģ�����ݿ��ѯ
        Object meta = "meta-data-from-db";
        redisTemplate.opsForValue().set(META_KEY, meta);
    }

    @Override
    public void loadConfigData() {
        Object config = "config-data-from-db";
        redisTemplate.opsForValue().set(CONFIG_KEY, config, Duration.ofMinutes(5));
    }

    // ======== ͨ�û����ѯ�߼� ========
    @Override
    public <T> T getBusinessData(String prefix, Object params, Class<T> type, DataProvider<T> provider) {
        String key = CacheKeyUtil.generateCacheKey(prefix, params);
        T cached = type.cast(redisTemplate.opsForValue().get(key));

        if (cached != null && CacheKeyUtil.isRecent(key, 5)) {
            return cached; // ���л�������5������
        }

        if (cached != null) {
            // �첽ˢ�¹��ڻ���
            asyncUtil.asyncCall("sampleSchusterService", "processData", key);
            return cached;
        }

        // ����δ���� �� ִ��ҵ���߼�
        T data = provider.get();
        redisTemplate.opsForValue().set(key, data, 8, TimeUnit.HOURS);
        return data;
    }
}


3. Key��ȫ���ɹ���
package com.example.cache.util;

import org.apache.commons.codec.digest.DigestUtils;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CacheKeyUtil {

    private static final Map<String, Long> TIMESTAMP_MAP = new ConcurrentHashMap<>();

    public static String generateCacheKey(String prefix, Object params) {
        // 1. ҵ������ֶΰ���ĸ˳������ȷ��һ����
        String paramString = serializeParams(params);
        String md5 = DigestUtils.md5Hex(paramString);

        // 2. ��ȫǰ׺ + ʱ�������ֹKey��ײ��
        String key = String.format("sys:%s:%s:%d", prefix, md5, Instant.now().getEpochSecond());
        TIMESTAMP_MAP.put(key, Instant.now().getEpochSecond());
        return key;
    }

    private static String serializeParams(Object params) {
        if (params instanceof Map<?, ?> map) {
            List<String> sorted = new ArrayList<>();
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.add(e.getKey() + "=" + e.getValue()));
            return String.join("&", sorted);
        }
        return Objects.toString(params);
    }

    public static boolean isRecent(String key, int minutes) {
        Long ts = TIMESTAMP_MAP.get(key);
        if (ts == null) return false;
        long now = Instant.now().getEpochSecond();
        return (now - ts) < minutes * 60;
    }
}


4. �첽�����ࣨͨ�÷�����ã�
package com.example.cache.util;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

@Component
@RequiredArgsConstructor
public class AsyncUtil {

    private final ApplicationContext applicationContext;

    public void asyncCall(String beanName, String methodName, Object arg) {
        try {
            Object bean = applicationContext.getBean(beanName);
            Method method = bean.getClass().getMethod(methodName, Object.class);
            // ����ϵͳ���첽���ִ�У��˴�ֻչʾ�ӿڣ�
            new Thread(() -> {
                try {
                    method.invoke(bean, arg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            throw new RuntimeException("Async call failed: " + e.getMessage(), e);
        }
    }
}


5. �첽����ӿ���ʵ��
package com.example.cache.service;

public interface SchusterService {
    void processData(Object arg);
}


6. ������ʾ��
package com.example.cache.controller;

import com.example.cache.service.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/data")
public class DataController {

    private final CacheService cacheService;

    @PostMapping("/query")
    public Object queryData(@RequestBody Map<String, Object> params) {
        return cacheService.getBusinessData("queryData", params, Object.class, 
            () -> "business-data-from-db");
    }
}


