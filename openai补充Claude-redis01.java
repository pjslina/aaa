1) CacheConstants.java
package com.example.cache;

public interface CacheConstants {
    // 默认过期时间（秒），0 = 不过期
    long DEFAULT_EXPIRE_SECONDS = 24 * 60 * 60; // 1 天

    // 缓存前缀示例
    String MODULE_PREFIX = "app:";
    String CACHE_PREFIX = MODULE_PREFIX + "cache:";

    // 刷新时使用的默认 ttl（可被 loader 覆盖）
    long DEFAULT_REFRESH_TTL_SECONDS = DEFAULT_EXPIRE_SECONDS;
}


2) CacheKeyGenerator.java
package com.example.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CacheKeyGenerator {

    private CacheKeyGenerator() {}

    // 简单高效的 key 生成：prefix + ":" + sha1(json)
    public static String genKey(String prefix, String raw) {
        if (raw == null) raw = "";
        return prefix + ":" + sha1(raw);
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // fallback
            return Integer.toHexString(input.hashCode());
        }
    }
}


3) CacheDataLoader.java (接口)

package com.example.cache;

/**
 * 泛型缓存加载器接口：
 * - load(cacheKey) 当缓存未命中或需要刷新时被调用同步/异步地加载数据
 * - getTtlSeconds() 返回应当写入缓存的过期时间（秒），若 <= 0 则表示不设置过期
 *
 * 业务侧需要实现此接口。
 */
public interface CacheDataLoader<T> {
    /**
     * 根据 cacheKey 加载数据（可能调用后端API/DB）
     * @param cacheKey 完整的缓存 key（包含前缀）
     * @return 加载到的数据，若后端无数据可返回 null
     * @throws Exception 允许抛出异常，调用者会捕获并处理
     */
    T load(String cacheKey) throws Exception;

    /**
     * loader 建议的缓存TTL（秒）。如果返回 <= 0，CacheManager 将使用默认TTL（CacheConstants.DEFAULT_EXPIRE_SECONDS）
     */
    default long getTtlSeconds() {
        return CacheConstants.DEFAULT_REFRESH_TTL_SECONDS;
    }
}


4) CacheManager.java（泛型核心类）

package com.example.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * CacheManager: 泛型缓存访问器
 *
 * 使用策略：
 * - 读缓存：若有值 -> 反序列化并返回，同时异步触发刷新（异步刷新使用 loader）
 * - 缓存未命中：对该 key 加锁（避免并发击穿），同步调用 loader.load()，将结果写入缓存并返回
 *
 * 线程安全：使用 per-key ReentrantLock
 */
public class CacheManager {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService refreshExecutor;

    // per-key lock to avoid stampede
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CacheManager(RedisTemplate<String, String> redisTemplate,
                        ObjectMapper objectMapper,
                        ExecutorService refreshExecutor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.refreshExecutor = refreshExecutor;
    }

    /**
     * 主要方法：获取缓存，泛型通过 clazz 指定。
     *
     * @param key 完整缓存 key（建议使用 CacheKeyGenerator）
     * @param loader 业务实现的 CacheDataLoader
     * @param clazz 期望的类型
     * @param <T> 类型
     * @return T or null
     */
    public <T> T get(String key, CacheDataLoader<T> loader, Class<T> clazz) {
        Objects.requireNonNull(key, "key required");
        Objects.requireNonNull(loader, "loader required");

        // try get from redis
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(json)) {
                // 命中：反序列化并返回，同时异步刷新（不阻塞用户请求）
                T value = objectMapper.readValue(json, clazz);
                asyncRefresh(key, loader, clazz);
                return value;
            }
        } catch (Exception e) {
            // 读取缓存失败时，记录异常并继续走下一步（防止整个功能不可用）
            // 这里简单打印，生产请使用 logger
            e.printStackTrace();
        }

        // 缓存未命中：对该 key 加锁，同步调用 loader
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // double-check after acquiring lock
            String json = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(json)) {
                T value = objectMapper.readValue(json, clazz);
                // release lock and async refresh
                asyncRefresh(key, loader, clazz);
                return value;
            }

            // 调用 loader 同步获取
            T loaded = loader.load(key);
            if (loaded != null) {
                writeToCache(key, loaded, loader.getTtlSeconds());
            } else {
                // Optionally cache a null placeholder to avoid hot-loop (可选)
                // writeNullPlaceholder(key);
            }
            return loaded;
        } catch (Exception e) {
            // loader 异常：直接抛出运行时异常或返回 null（由调用方决定）
            throw new RuntimeException("cache loader error for key=" + key, e);
        } finally {
            lock.unlock();
            // cleanup small optimization: remove unlocked lock entry if not used
            locks.computeIfPresent(key, (k, v) -> v.hasQueuedThreads() ? v : null);
        }
    }

    private <T> void asyncRefresh(String key, CacheDataLoader<T> loader, Class<T> clazz) {
        // submit async task: reload and replace cache
        refreshExecutor.submit(() -> {
            try {
                T fresh = loader.load(key);
                if (fresh != null) {
                    writeToCache(key, fresh, loader.getTtlSeconds());
                } else {
                    // optional: delete cache if loader returns null?
                    // redisTemplate.delete(key);
                }
            } catch (Exception e) {
                // refresh failed -> log and ignore
                e.printStackTrace();
            }
        });
    }

    private <T> void writeToCache(String key, T value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, json);
            }
        } catch (Exception e) {
            // 写缓存失败 -> log and ignore
            e.printStackTrace();
        }
    }
}


5) RedisConfig.java（Spring 配置）

package com.example.config;

import com.example.cache.CacheManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 示例使用 JedisConnectionFactory，按需替换 Lettuce / 配置
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("localhost", 6379);
        return new JedisConnectionFactory(cfg);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * 异步刷新线程池：可根据并发情况调整大小
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService refreshExecutor() {
        return Executors.newFixedThreadPool(8);
    }

    @Bean
    public CacheManager cacheManager(RedisTemplate<String, String> redisTemplate,
                                     ObjectMapper objectMapper,
                                     ExecutorService refreshExecutor) {
        return new CacheManager(redisTemplate, objectMapper, refreshExecutor);
    }
}


6) DefaultCacheDataLoader.java（示例业务实现 — 调后端接口）
package com.example.loader;

import com.example.cache.CacheDataLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * 示例：按 key 从后端 REST API 获取数据并返回（可以被 MetricService / 其它业务共享）
 * 假设 cacheKey 中包含可解析的参数（或者你也可以在 loader 内维护更多上下文）
 */
public class DefaultCacheDataLoader<T> implements CacheDataLoader<T> {

    private final RestTemplate restTemplate;
    private final Class<T> clazz;
    private final String backendUrlTemplate;
    private final long ttlSeconds;

    /**
     * @param restTemplate Spring 注入
     * @param clazz 返回对象类型
     * @param backendUrlTemplate 用于调用后端的 URL 模板（例如 "http://api.company.local/metric/{id}"）
     * @param ttlSeconds 缓存TTL
     */
    public DefaultCacheDataLoader(RestTemplate restTemplate, Class<T> clazz, String backendUrlTemplate, long ttlSeconds) {
        this.restTemplate = restTemplate;
        this.clazz = clazz;
        this.backendUrlTemplate = backendUrlTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public T load(String cacheKey) throws Exception {
        // cacheKey 可能包含前缀与哈希；实际系统中，你应传入能解析出参数的 key 或者在构造函数中传入额外参数
        // 为了示例，我们假设 cacheKey 的最后一段是资源 id（你可按需解析）
        String resourceId = parseIdFromKey(cacheKey);
        String url = backendUrlTemplate.replace("{id}", resourceId);
        ResponseEntity<T> resp = restTemplate.getForEntity(url, clazz);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        return resp.getBody();
    }

    private String parseIdFromKey(String key) {
        // 简单示例：key 以 ":" 分隔，最后一段为 id
        String[] parts = key.split(":");
        return parts[parts.length - 1];
    }

    @Override
    public long getTtlSeconds() {
        return ttlSeconds;
    }
}


说明：上面的 DefaultCacheDataLoader 仅为示例。实际业务通常更清晰地将参数（比如 id）传入 loader 的构造函数而不是从 cacheKey 反解析参数。
7) MetricService.java（业务调用示例）

package com.example.service;

import com.example.cache.CacheConstants;
import com.example.cache.CacheDataLoader;
import com.example.cache.CacheKeyGenerator;
import com.example.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 示例业务：查询指标对象 MetricVO，使用泛型 CacheManager
 */
@Service
public class MetricService {

    private final CacheManager cacheManager;
    private final RestTemplate restTemplate;

    @Autowired
    public MetricService(CacheManager cacheManager, RestTemplate restTemplate) {
        this.cacheManager = cacheManager;
        this.restTemplate = restTemplate;
    }

    /**
     * 根据 metricId 获取 MetricVO，优先走缓存
     */
    public MetricVO getMetricById(String metricId) {
        // 构造原始 keyData（可以是 JSON、id、组合参数等）
        String raw = metricId; // 简单情形
        String prefix = CacheConstants.CACHE_PREFIX + "metric";
        String cacheKey = CacheKeyGenerator.genKey(prefix, raw) + ":" + metricId; // 在 key 末尾保留 id 方便 loader parse

        // 构造 loader（可在 Spring 容器中复用 bean）
        CacheDataLoader<MetricVO> loader = new CacheDataLoader<MetricVO>() {
            @Override
            public MetricVO load(String key) throws Exception {
                // 直接用 restTemplate 调接口（示例）
                String url = "http://backend.internal/api/metrics/{id}";
                return restTemplate.getForObject(url, MetricVO.class, metricId);
            }

            @Override
            public long getTtlSeconds() {
                return 60 * 60; // 1 hour
            }
        };

        // 调用 cacheManager 获取
        return cacheManager.get(cacheKey, loader, MetricVO.class);
    }
}
8) RestTemplate Bean（若你还没有）
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class WebConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

9) 重要实现细节与建议（简短说明

异步刷新：当缓存命中时，我们不会阻塞用户请求，而是 refreshExecutor.submit(...) 异步刷新。线程数按业务流量设定（示例为 8）。如果你希望更强的流量控制，可用 BoundedExecutor、队列拒绝策略或限流。

缓存击穿保护：在缓存未命中时对单个 key 加锁（ReentrantLock），避免多个请求同时落到后端。

缓存穿透保护：Loader 返回 null 时是否写入空占位（短 TTL）由你决定。若后端频繁返回 null，建议写入短期占位以减少对后端的压力。

序列化：我用了 Jackson 将对象序列化为 JSON 存入 Redis（RedisTemplate<String,String>），这样对不同语言/服务友好。若你使用二进制序列化（如 JDK 序列化或 fastjson），可相应替换。

异常处理：示例中对异常采取简单打印/包装方式。生产系统请使用 SLF4J 记录、并在合适的场景重试或熔断。

可扩展性：CacheManager 是无状态的，可以在多个 service 共享。CacheDataLoader 由业务实现，便于注入不同策略（DB、HTTP、文件等）。

过期配置：CacheDataLoader#getTtlSeconds() 可以为不同类型的数据返回不同TTL，增强灵活性。

Key 设计：CacheKeyGenerator.genKey(prefix, raw) 返回了哈希部分；示例中我把 id 追加到 key 末尾以便 loader 能解析；你也可以选择将所有参数组成 JSON 再哈希，或采用更可读的 key 格式（但对长度和 redis 内存/性能要注意）。

