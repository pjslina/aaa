com.example.cache
 ├─ config/
 │   └─ CacheInitializer.java       # 启动时加载缓存
 ├─ service/
 │   ├─ CacheService.java           # 通用缓存服务接口
 │   ├─ CacheServiceImpl.java       # 实现类
 │   ├─ SchusterService.java        # 异步任务接口
 │   └─ SampleSchusterService.java  # 示例异步任务实现
 ├─ util/
 │   ├─ CacheKeyUtil.java           # MD5 + Key 生成工具
 │   └─ AsyncUtil.java              # 通用异步触发工具
 ├─ controller/
 │   └─ DataController.java         # 示例接口调用

1. 启动加载缓存
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
        // 启动时加载元数据与配置数据
        cacheService.loadMetaData();
        cacheService.loadConfigData();
    }
}


2. 通用缓存服务接口与实现
package com.example.cache.service;

public interface CacheService {

    /** 启动时加载元数据 */
    void loadMetaData();

    /** 启动时加载配置数据 */
    void loadConfigData();

    /** 获取业务数据（缓存优先） */
    <T> T getBusinessData(String prefix, Object params, Class<T> type, DataProvider<T> provider);

    /** 数据提供者（仅当缓存未命中时调用） */
    @FunctionalInterface
    interface DataProvider<T> {
        T get();
    }
}


实现类（含安全与性能优化）
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

    // ======== 启动加载 ========
    @Override
    public void loadMetaData() {
        // 模拟数据库查询
        Object meta = "meta-data-from-db";
        redisTemplate.opsForValue().set(META_KEY, meta);
    }

    @Override
    public void loadConfigData() {
        Object config = "config-data-from-db";
        redisTemplate.opsForValue().set(CONFIG_KEY, config, Duration.ofMinutes(5));
    }

    // ======== 通用缓存查询逻辑 ========
    @Override
    public <T> T getBusinessData(String prefix, Object params, Class<T> type, DataProvider<T> provider) {
        String key = CacheKeyUtil.generateCacheKey(prefix, params);
        T cached = type.cast(redisTemplate.opsForValue().get(key));

        if (cached != null && CacheKeyUtil.isRecent(key, 5)) {
            return cached; // 命中缓存且在5分钟内
        }

        if (cached != null) {
            // 异步刷新过期缓存
            asyncUtil.asyncCall("sampleSchusterService", "processData", key);
            return cached;
        }

        // 缓存未命中 → 执行业务逻辑
        T data = provider.get();
        redisTemplate.opsForValue().set(key, data, 8, TimeUnit.HOURS);
        return data;
    }
}


3. Key安全生成工具
package com.example.cache.util;

import org.apache.commons.codec.digest.DigestUtils;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CacheKeyUtil {

    private static final Map<String, Long> TIMESTAMP_MAP = new ConcurrentHashMap<>();

    public static String generateCacheKey(String prefix, Object params) {
        // 1. 业务参数字段按字母顺序排序，确保一致性
        String paramString = serializeParams(params);
        String md5 = DigestUtils.md5Hex(paramString);

        // 2. 安全前缀 + 时间戳（防止Key碰撞）
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


4. 异步工具类（通用反射调用）
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
            // 交给系统的异步框架执行（此处只展示接口）
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


5. 异步任务接口与实现
package com.example.cache.service;

public interface SchusterService {
    void processData(Object arg);
}


6. 控制器示例
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


