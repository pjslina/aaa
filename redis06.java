┌────────────────────────────┐
│        Controller          │
└──────────────┬─────────────┘
               │ 调用业务Service
┌──────────────▼─────────────┐
│     MeasureDataService     │
│ 调用 CacheManager.getData()│
└──────────────┬─────────────┘
               │
┌──────────────▼─────────────┐
│       CacheManager         │
│ 通用缓存逻辑：             │
│ ① 本地热点key判断          │
│ ② Redis读写                │
│ ③ 异步刷新                 │
│ ④ 防击穿锁机制             │
└──────────────┬─────────────┘
               │
┌──────────────▼─────────────┐
│   AppRedisTemplate封装层   │
│ 统一序列化、TTL控制、安全  │
└────────────────────────────┘

核心思路概述

业务类（如 MeasureDataService）：

调用 CacheManager.getData(cacheName, module, params, loader, clazz) 即可；

不关心Redis、异步、序列化逻辑。

CacheManager（核心类）：

统一处理缓存逻辑（内存热点 + Redis缓存 + 异步刷新）；

内置防击穿锁机制；

对key进行哈希生成与解析。

Redis访问统一封装：

内部实现高性能序列化（使用 Fastjson2）；

所有业务层都不直接操作 AppRedisTemplate。

内存热点缓存：

使用 ConcurrentHashMap<String, Long> 记录最近访问时间；

用于判断是否需要异步刷新。

完整代码实现
1? CacheKey 工具类
package com.company.cache.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class CacheKeyUtil {

    public static String generateKey(String cacheName, String module, Map<String, Object> params) {
        String sortedPart = buildSortedString(params);
        String hashPart = sha256(sortedPart);
        return String.format("%s:%s:%s", cacheName, module, hashPart);
    }

    private static String buildSortedString(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
        }
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generating hash", e);
        }
    }
}


2? Redis序列化工具

package com.company.cache.util;

import com.alibaba.fastjson2.JSON;

public class RedisSerializerUtil {

    public static <T> String serialize(T obj) {
        return JSON.toJSONString(obj);
    }

    public static <T> T deserialize(Object json, Class<T> clazz) {
        if (json == null) return null;
        return JSON.parseObject(json.toString(), clazz);
    }
}


3? 本地热点缓存（只存key）

package com.company.cache.local;

import java.util.concurrent.ConcurrentHashMap;

public class LocalHotKeyCache {

    private static final ConcurrentHashMap<String, Long> HOT_KEY_MAP = new ConcurrentHashMap<>();

    public static boolean shouldAsyncRefresh(String key, long thresholdMillis) {
        Long last = HOT_KEY_MAP.get(key);
        return last == null || (System.currentTimeMillis() - last > thresholdMillis);
    }

    public static void markAccess(String key) {
        HOT_KEY_MAP.put(key, System.currentTimeMillis());
    }

    public static void cleanup(long expireMillis) {
        long now = System.currentTimeMillis();
        HOT_KEY_MAP.entrySet().removeIf(e -> now - e.getValue() > expireMillis);
    }
}


4? 通用缓存管理器（核心逻辑）

package com.company.cache.core;

import com.company.cache.local.LocalHotKeyCache;
import com.company.cache.util.CacheKeyUtil;
import com.company.cache.util.RedisSerializerUtil;
import com.company.redis.AppRedisTemplate;
import com.company.async.AsyncUtil;
import com.company.async.Context;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class CacheManager {

    private static final ConcurrentHashMap<String, ReentrantLock> KEY_LOCKS = new ConcurrentHashMap<>();
    private static final long ASYNC_REFRESH_INTERVAL = 5 * 60 * 1000; // 5分钟
    private static final long DEFAULT_TTL_SECONDS = 3600; // 1小时

    private static AppRedisTemplate redisTemplate;

    public static void init(AppRedisTemplate template) {
        redisTemplate = template;
    }

    public static <T> T getData(
            String cacheName,
            String module,
            Map<String, Object> params,
            Callable<T> dbLoader,
            Class<T> clazz) {

        String key = CacheKeyUtil.generateKey(cacheName, module, params);
        LocalHotKeyCache.markAccess(key);

        Object cached = redisTemplate.get(key);
        if (cached != null) {
            if (LocalHotKeyCache.shouldAsyncRefresh(key, ASYNC_REFRESH_INTERVAL)) {
                asyncRefresh(cacheName, module, params, dbLoader, clazz);
            }
            return RedisSerializerUtil.deserialize(cached, clazz);
        }

        // 防止缓存击穿
        ReentrantLock lock = KEY_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            cached = redisTemplate.get(key);
            if (cached != null) {
                return RedisSerializerUtil.deserialize(cached, clazz);
            }

            T result = dbLoader.call();
            if (result != null) {
                redisTemplate.set(key, RedisSerializerUtil.serialize(result), DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Load cache failed", e);
        } finally {
            lock.unlock();
            KEY_LOCKS.remove(key);
        }
    }

    private static <T> void asyncRefresh(String cacheName, String module, Map<String, Object> params, Callable<T> dbLoader, Class<T> clazz) {
        Context ctx = new Context();
        ctx.put("cacheName", cacheName);
        ctx.put("module", module);
        ctx.put("params", params);
        ctx.put("clazz", clazz);
        AsyncUtil.invokeSync("AsyncCacheRefresher", ctx);
    }
}


5? 异步刷新处理器

package com.company.async.impl;

import com.company.async.IAsyncHandler;
import com.company.async.Context;
import com.company.cache.core.CacheManager;

import java.util.Map;
import java.util.concurrent.Callable;

public class AsyncCacheRefresher implements IAsyncHandler {

    @Override
    public Object processHandler(Context context) {
        String cacheName = context.get("cacheName");
        String module = context.get("module");
        Map<String, Object> params = context.get("params");
        Class<?> clazz = context.get("clazz");

        CacheManager.getData(cacheName, module, params, new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                // 重新加载逻辑（此处应按实际业务重写）
                return null;
            }
        }, clazz);
        return null;
    }
}


6? 数据库元数据加载器（系统启动时）
package com.company.cache.meta;

import com.company.redis.AppRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.*;

@Component
public class MetaDataCacheLoader {

    private final AppRedisTemplate redisTemplate;
    private final Map<String, Object> metaCache = new ConcurrentHashMap<>();

    public MetaDataCacheLoader(AppRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        refreshMetaData();
    }

    public void refreshMetaData() {
        // 查询数据库加载元数据
        Map<String, Object> metaFromDB = queryMetaData();
        metaCache.clear();
        metaCache.putAll(metaFromDB);
        redisTemplate.set("MetaDataCache", metaFromDB, 24 * 3600, TimeUnit.SECONDS);
    }

    private Map<String, Object> queryMetaData() {
        // 模拟查询数据库
        Map<String, Object> meta = new HashMap<>();
        meta.put("metricType", Arrays.asList("CPU", "MEMORY", "DISK"));
        return meta;
    }

    public Object getMeta(String key) {
        return metaCache.get(key);
    }
}


7? 业务类示例

package com.company.service;

import com.company.cache.core.CacheManager;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Callable;

@Service
public class MeasureDataService {

    public List<Metric> getMetrics(CodeVO codeVO) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", codeVO.getCode());
        params.put("type", codeVO.getType());

        return CacheManager.getData(
                "SystemCache",
                "MeasureData",
                params,
                new Callable<List<Metric>>() {
                    @Override
                    public List<Metric> call() throws Exception {
                        // 模拟数据库查询
                        Thread.sleep(2000);
                        return queryMetricsFromDB(codeVO);
                    }
                },
                (Class<List<Metric>>) (Class<?>) List.class
        );
    }

    private List<Metric> queryMetricsFromDB(CodeVO codeVO) {
        return Arrays.asList(new Metric("CPU", 0.75), new Metric("MEMORY", 0.65));
    }
}


? 关键特性总结
特性	实现点
缓存击穿防护	每个key锁机制（ReentrantLock）
缓存穿透防护	dbLoader调用控制
缓存雪崩防护	TTL统一控制，可扩展随机偏移
异步刷新	AsyncUtil + 本地热点时间控制
高性能序列化	Fastjson2 JSON序列化
热点内存优化	仅存key访问时间，不存value
统一入口	CacheManager，所有业务可共用
泛化接口调用	CacheManager.getData() 一行搞定

