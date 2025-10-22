好 — 我会把整个方案按你最新要求做成一套 可直接复用、参数合规（方法参数 ≤ 3 个）、高性能、可扩展且安全 的实现。
关键点说明（先说结论，再给代码）：

Redis 存储策略（兼顾你的要求与性能）：
我按照你明确要求把 时间戳放到实际数据 Key（prefix:md5:ts），但为避免用 KEYS 或 SCAN 带来的性能问题，额外维护一个 固定指针 Key：prefix:md5 -> prefix:md5:ts（指向当前生效的数据 key）。这样读取时只需一次 GET prefix:md5 再一次 GET prefix:md5:ts，没有 KEYS/SCAN，性能高且满足你的“key 包含时间戳”要求。
写入时写入 prefix:md5:ts（数据，TTL=8小时），同时更新 prefix:md5 指针（同样设置 TTL）。元数据可选择写入不设置 TTL（每日刷新覆盖指针与数据）。

MD5 计算：选取与业务相关字段（fieldsToUse）构造 TreeMap 保证稳定顺序，再 JSON 序列化并 MD5，确保一致性（参数少改动就不变）。方法封装在 CacheKeyUtil。

缓存读写策略：

读取：先 GET pointerKey（prefix:md5），若存在则 GET dataKey（prefix:md5:ts）并解析 ts。若数据不存在则回源并写入。

缓存过期：数据 key TTL=8小时（可配置）。指针 key TTL 同步设置以免指针长期指向已过期数据。

刷新阈值（例如 5 分钟）：当 pointer 指向的数据时间戳超过阈值时，立即返回旧数据，并 异步刷新缓存（不阻塞请求）。

异步刷新 & 通用调用：提供 AsyncUtil（Spring bean）能按 beanName+methodName 异步触发任意 Service 方法（支持传单一参数：Map<String,Object>）。为了满足你们公司“异步公共代码存在”的约束，我把 AsyncUtil 的实现写成可被替换/委托的形式（如果你们已有公共异步实现，只需实现 AsyncInvoker 接口并注入即可）。

方法参数约束：所有公开方法 参数 ≤ 3 个，复杂配置都放到封装对象 CacheRequest（一个参数）或 RefreshContext（一个参数）中，便于扩展且符合代码规范。

下面是完整代码（按模块给出，直接可复制到项目里）——我把注释写得比较详细，便于你直接使用或替换公司公共实现部分。

1) RedisConfig（只示例 RedisTemplate 与 TaskExecutor；若公司已统一请替换）
// RedisConfig.java
package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // 简单线程池用于异步执行；若公司已有请替换注入
    @Bean("cacheAsyncExecutor")
    public TaskExecutor cacheAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(10);
        exec.setMaxPoolSize(50);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("cache-async-");
        exec.initialize();
        return exec;
    }
}


2) 工具类：MD5、Key 生成
// CacheKeyUtil.java
package com.example.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class CacheKeyUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 计算业务参数的 md5，fields 为 null 则使用全部 params 的 key（按字典序）
     */
    public static String md5OfParams(Map<String, Object> params, List<String> fields) {
        try {
            Map<String, Object> sorted = new TreeMap<>();
            if (params != null) {
                if (fields == null || fields.isEmpty()) {
                    sorted.putAll(params);
                } else {
                    for (String f : fields) {
                        if (params.containsKey(f)) sorted.put(f, params.get(f));
                    }
                }
            }
            String json = MAPPER.writeValueAsString(sorted);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("md5OfParams failed", e);
        }
    }

    public static String pointerKey(String prefix, String md5) {
        return prefix + ":" + md5;
    }

    public static String dataKey(String prefix, String md5, long tsMillis) {
        return prefix + ":" + md5 + ":" + tsMillis;
    }

    /**
     * 从 dataKey 解析出 ts（假设格式 prefix:md5:ts）
     */
    public static long extractTsFromDataKey(String dataKey) {
        if (dataKey == null) return 0L;
        int last = dataKey.lastIndexOf(':');
        if (last < 0) return 0L;
        try {
            return Long.parseLong(dataKey.substring(last + 1));
        } catch (Exception e) {
            return 0L;
        }
    }
}


3) CacheRequest（封装参数，方法参数 ≤1）
// CacheRequest.java
package com.example.cache;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 封装一次缓存请求的参数（便于方法参数少于等于3个）
 */
public class CacheRequest {
    private final String keyPrefix;          // e.g. "api:example"
    private final Map<String, Object> params;// 请求参数（用于 MD5）
    private final List<String> md5Fields;    // 参与 MD5 的字段（可 null 表示全部）
    private final Supplier<Object> loader;   // 回源加载函数（无参 Supplier）

    public CacheRequest(String keyPrefix,
                        Map<String, Object> params,
                        List<String> md5Fields,
                        Supplier<Object> loader) {
        this.keyPrefix = keyPrefix;
        this.params = params;
        this.md5Fields = md5Fields;
        this.loader = loader;
    }

    public String getKeyPrefix() { return keyPrefix; }
    public Map<String, Object> getParams() { return params; }
    public List<String> getMd5Fields() { return md5Fields; }
    public Supplier<Object> getLoader() { return loader; }
}


4) CacheWrapper（保存在 dataKey 的值，放入数据，方便以后扩展）

我们把实际业务数据直接写在 dataKey 的 value 中（可以是任何对象），同时 pointer 指向该 dataKey 字符串。CacheWrapper 在这里用于演示/扩展（也可以直接存业务对象）。

// CacheWrapper.java
package com.example.cache;

import java.io.Serializable;

public class CacheWrapper implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long ts;
    private final Object data;

    public CacheWrapper(long ts, Object data) {
        this.ts = ts;
        this.data = data;
    }

    public long getTs() { return ts; }
    public Object getData() { return data; }
}


5) 接口：AsyncInvoker（可替换/委托公司公共实现）

// AsyncInvoker.java
package com.example.async;

import java.util.Map;

/**
 * 抽象异步调用接口。若你们有公共异步框架，实现此接口并注入容器即可。
 * 方法参数 ≤ 3 个：这里仅 2 个。
 */
public interface AsyncInvoker {
    /**
     * 异步调用指定 beanName 的 methodName，并传递参数 map（通常服务实现按 Map 接收）。
     */
    void invokeAsync(String beanName, String methodName, Map<String, Object> arg);
}


6) 默认 AsyncUtil 实现（若公司已有公共实现可替换）
// DefaultAsyncInvoker.java
package com.example.async;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 默认实现：使用 TaskExecutor 异步并通过反射调用 bean method(Map)
 * 若公司已有公共实现，请实现 AsyncInvoker 并覆盖注入。
 */
@Component
public class DefaultAsyncInvoker implements AsyncInvoker {

    private final TaskExecutor executor;
    private final ApplicationContext ctx;

    @Autowired
    public DefaultAsyncInvoker(TaskExecutor executor, ApplicationContext ctx) {
        this.executor = executor;
        this.ctx = ctx;
    }

    @Override
    public void invokeAsync(String beanName, String methodName, Map<String, Object> arg) {
        executor.execute(() -> {
            try {
                Object bean = ctx.getBean(beanName);
                // 优先查找单参数 Map 方法；若没有再尝试无参方法
                Method target = null;
                for (Method m : bean.getClass().getMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Map.class)) {
                        target = m;
                        break;
                    }
                    if (m.getParameterCount() == 0) {
                        target = m; // fallback
                    }
                }
                if (target == null) throw new NoSuchMethodException("No method " + methodName + " on bean " + beanName);
                if (target.getParameterCount() == 1) target.invoke(bean, arg);
                else target.invoke(bean);
            } catch (Exception e) {
                // 记录日志（替换为你们公司的 logger）
                e.printStackTrace();
            }
        });
    }
}


7) CacheService（核心逻辑，方法参数 ≤ 3 个）

// CacheService.java
package com.example.cache;

import com.example.util.CacheKeyUtil;
import com.example.async.AsyncInvoker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 核心缓存服务：负责读/写逻辑与异步刷新触发
 * 对外方法参数 ≤3 个（这里主要是一个 CacheRequest）
 */
@Service
public class CacheService {

    private final RedisTemplate<String, Object> redis;
    private final AsyncInvoker asyncInvoker;

    // TTL for data keys and pointer keys
    private final Duration dataTtl = Duration.ofHours(8);

    // 刷新阈值（毫秒）；超过该阈值后异步刷新
    private final long refreshThresholdMillis = 5 * 60 * 1000L;

    @Autowired
    public CacheService(RedisTemplate<String, Object> redis, AsyncInvoker asyncInvoker) {
        this.redis = redis;
        this.asyncInvoker = asyncInvoker;
    }

    /**
     * 主要公开方法：参数 1 个（CacheRequest）
     *
     * 读取流程：
     * 1) 计算 md5 -> pointerKey
     * 2) GET pointerKey -> dataKey
     * 3) GET dataKey -> 返回数据（若超阈值触发异步刷新）
     * 4) 若 pointerKey 或 dataKey 缺失 -> 回源并写入 dataKey + 更新 pointerKey
     */
    public Object getOrLoad(CacheRequest request, String asyncBeanName, String asyncMethodName) {
        String md5 = CacheKeyUtil.md5OfParams(request.getParams(), request.getMd5Fields());
        String pointerKey = CacheKeyUtil.pointerKey(request.getKeyPrefix(), md5);

        // 1) 获取 pointer -> 指向 dataKey（格式 prefix:md5:ts）
        Object pointerObj = redis.opsForValue().get(pointerKey);
        if (pointerObj instanceof String dataKey) {
            // 尝试拿数据
            Object dataObj = redis.opsForValue().get(dataKey);
            if (dataObj != null) {
                long ts = CacheKeyUtil.extractTsFromDataKey(dataKey);
                long age = System.currentTimeMillis() - ts;
                // 若超过阈值触发异步刷新（但立即返回旧数据）
                if (age > refreshThresholdMillis) {
                    triggerAsyncRefresh(asyncBeanName, asyncMethodName, request, md5);
                }
                return dataObj;
            }
            // pointer 指向的数据 key 过期/丢失 -> 走回源逻辑
        }

        // pointer 不存在或 dataKey 不存在 -> 同步回源并写入
        Object loaded = request.getLoader().get(); // 回源（DB / 计算）
        long now = System.currentTimeMillis();
        String dataKey = CacheKeyUtil.dataKey(request.getKeyPrefix(), md5, now);

        // 写入 dataKey 与 pointerKey（都设置 TTL=8小时）
        redis.opsForValue().set(dataKey, loaded, dataTtl);
        redis.opsForValue().set(pointerKey, dataKey, dataTtl);

        return loaded;
    }

    /**
     * 触发异步刷新：只提交必要信息到 async service（参数 ≤3：beanName, methodName, context）
     * 我们由调用方传入 asyncBeanName 和 asyncMethodName（可配置），
     * 异步入参为 Map（包含 keyPrefix、md5 等），便于业务实现者拿到上下文。
     */
    private void triggerAsyncRefresh(String beanName, String methodName, CacheRequest request, String md5) {
        try {
            Map<String, Object> context = Map.of(
                    "keyPrefix", request.getKeyPrefix(),
                    "md5", md5,
                    "params", request.getParams()
            );
            asyncInvoker.invokeAsync(beanName, methodName, context);
        } catch (Exception e) {
            // 记录日志但不影响主流程
            e.printStackTrace();
        }
    }

    /**
     * 给异步刷新器/外部 Service 调用的写回方法（参数 ≤3）
     * 异步服务可以在完成后直接调用此方法，或者使用 cacheService 提供的方法引用。
     *
     * @param keyPrefix 前缀
     * @param md5       md5
     * @param data      刷新后的数据（会写入新的 dataKey 并更新 pointerKey）
     */
    public void writeRefreshedData(String keyPrefix, String md5, Object data) {
        long now = System.currentTimeMillis();
        String dataKey = CacheKeyUtil.dataKey(keyPrefix, md5, now);
        String pointerKey = CacheKeyUtil.pointerKey(keyPrefix, md5);
        redis.opsForValue().set(dataKey, data, dataTtl);
        redis.opsForValue().set(pointerKey, dataKey, dataTtl);
    }
}


8) 示例 SchusterService（异步刷新实现示例）

// SchusterService.java
package com.example.service;

import java.util.Map;

/**
 * 你的异步业务接口：processData 方法签名尽量简单（参数为 Map）
 * 注：方法参数 ≤3 个——这里只用 1 个 Map 参数。
 */
public interface SchusterService {
    void processData(Map<String, Object> context);
}


9) Controller 使用示例（调用入口）

// ExampleController.java
package com.example.web;

import com.example.cache.CacheRequest;
import com.example.cache.CacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/example")
public class ExampleController {

    private final CacheService cacheService;

    @Autowired
    public ExampleController(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * 入口方法：参数 ≤3（path/body/query 限制在 controller 层）
     */
    @PostMapping("/data")
    public Object getData(@RequestBody Map<String, Object> params) {
        List<String> md5Fields = List.of("userId", "orgId", "metricId"); // 按业务选择字段

        CacheRequest req = new CacheRequest(
                "api:example",     // keyPrefix
                params,
                md5Fields,
                () -> loadFromDb(params) // 回源 loader（Supplier）
        );

        // 异步刷新由 schusterService.processData 处理（beanName 与 methodName）
        return cacheService.getOrLoad(req, "schusterService", "processData");
    }

    private Object loadFromDb(Map<String, Object> params) {
        // 实际业务回源，同步执行（第一次缓存未命中时会走此逻辑）
        return Map.of("data", "sync-loaded", "time", System.currentTimeMillis(), "params", params);
    }
}


10) 启动加载与定时刷新（示例，仅演示，用你们公司的公共实现替换）

你最后一行说“异步和定时任务的实现，你不用管，我们系统有公共代码”。
所以以下只是示例。如果你们已有公共 @Scheduled 与异步框架，请跳过或把调用逻辑放入你们的公共任务中。

// StartupLoader.java
package com.example.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时加载元数据和配置（示例）
 */
@Component
public class StartupLoader implements ApplicationRunner {

    private final RedisTemplate<String, Object> redis;

    public StartupLoader(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 元数据：写入不设置过期（可选）
        Map<String, Object> meta = loadMetaFromDb();
        redis.opsForValue().set("meta:all", meta);
        // 配置数据：写入（也可设置 TTL）
        Map<String, Object> cfg = loadConfigFromDb();
        redis.opsForValue().set("config:all", cfg);
    }

    private Map<String, Object> loadMetaFromDb() {
        // TODO: DB 查询
        return Map.of("meta", "value", "loadedAt", System.currentTimeMillis());
    }

    private Map<String, Object> loadConfigFromDb() {
        // TODO
        return Map.of("config", "value", "loadedAt", System.currentTimeMillis());
    }
}


重要安全与性能建议（必须阅读）

避免把任意用户输入直接当作 key 的一部分（已用 md5 对参数做摘要）。但仍需保证 keyPrefix 与 md5 组合不会被恶意构造成过长 key（md5 固定长度，所以安全）。

防止缓存穿透 / 热点击穿：建议在 getOrLoad 的“回源”路径上加入并发保护（如单机的 ConcurrentHashMap + FutureTask 记忆化或 Redis 分布式锁）。我可以给你补上这块（单机/分布式两种实现）。

治理异步失败：AsyncInvoker 内部应有失败重试策略/限频与告警（示例代码仅打印异常）。生产环境请接入公司监控与重试。

序列化：RedisTemplate 使用 GenericJackson2JsonRedisSerializer，可以序列化绝大多数对象；若序列化安全或兼容性有更高要求，请统一 ObjectMapper 配置并限制可以序列化的类型。

Key TTL 的一致性：我们同时给 dataKey 与 pointerKey 设相同 TTL，避免 pointer 指向过期 dataKey 的长久悬挂（如果需要更严格，可在读时检测并回源）。

日志：示例中 e.printStackTrace() 仅为演示；请务必替换为你公司统一 logger 并把异常、耗时、请求参数 hash 等上报到 APM/监控系统。