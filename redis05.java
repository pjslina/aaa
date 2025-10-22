关键设计要点（先读一遍，方便看代码）

元数据（Metadata）

启动时从 DB 加载到内存 MetadataCache（ConcurrentHashMap），并写入 Redis（永久/长 TTL）。

每天定时刷新（你们已有定时框架，只需调用 MetadataCache.refreshFromDb()）。

读取时：先查内存；如果没命中，查 Redis；Redis 没命中再查 DB 并回写 Redis 与内存。读路径尽量短，写路径异步或批量化（可按需扩展）。

请求响应缓存（ResponseCache）

Redis 作为主缓存，内存保持一个短期热点缓存（ConcurrentHashMap）以减少网络延迟（可选）。

缓存值包装了 value、writeTs（写入时间）用于判断是否“超过 5 分钟”。

命中且 now - writeTs > 5min 时：立刻返回旧值，同时触发异步刷新（用你们的 AsyncUtil.invokeSync），以实现“先返回，后台更新”模式。

未命中时：加 key-level 锁（避免并发穿透），同步加载数据并写入 Redis（然后释放锁）。

Key 设计与安全

Key 结构示例： <cacheName>::<modulePrefix>::<fieldA>::<tsMillis>::<base64url(HMAC)>

fieldA 必须是“不可变字段”由业务构造（你会提供），签名使用 HMAC-SHA256（服务端 secret 存于配置）。解析时先验证签名再解析其它字段，防止伪造/篡改。

解析函数 KeyUtil.parseKey(...) 返回 KeyInfo{cacheName,modulePrefix,fieldA,timestamp} 并会抛出异常或返回 null 若签名不对。

对 MeasureDataService 的接入

给出 CachedServiceInvoker#invokeWithCache(...) 泛化接口，你可以在每个耗时 Service 中直接调用或通过 AOP 织入。

invokeWithCache 接受生成缓存 key 的函数、实际查询函数、缓存过期时间与“是否超过 5 分钟需后台刷新”的阈值等。


将下面类按文件分开（每个类为一个 .java），并把 com.example.cache 换成你的包名。注意：AppRedisTemplate 我在代码里按更合理的签名声明了一版（set(String key, Object value, long timeout, TimeUnit unit)）。如果你们的真实实现不同，仅需替换方法签名即可（注释里有提示）。

1) AppRedisTemplate（公司提供 - 我写了常见签名的接口，若真实不同请替换实现）
package com.example.cache;

import java.util.concurrent.TimeUnit;

public interface AppRedisTemplate {
    Object get(String key);
    // NOTE: 我推断 set 需要 value, timeout, unit；如果你们真实签名不同，请替换这里和后续调用处。
    void set(String key, Object value, long timeout, TimeUnit unit);
    void remove(String key);
    void clear(String cacheName);
}

2) KeyUtil — 生成与解析带 HMAC 的安全 key
package com.example.cache;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public class KeyUtil {
    private static final String HMAC_ALGO = "HmacSHA256";
    private final byte[] secret;

    public KeyUtil(String secret) {
        this.secret = Objects.requireNonNull(secret).getBytes(StandardCharsets.UTF_8);
    }

    public String generateKey(String cacheName, String modulePrefix, String fieldA, long tsMillis) {
        // canonical components
        String payload = String.join("::", cacheName, modulePrefix, fieldA, String.valueOf(tsMillis));
        String sig = hmacBase64Url(payload);
        return payload + "::" + sig;
    }

    public KeyInfo parseKey(String key) throws IllegalArgumentException {
        String[] parts = key.split("::");
        if (parts.length != 5) throw new IllegalArgumentException("bad key format");
        String cacheName = parts[0];
        String modulePrefix = parts[1];
        String fieldA = parts[2];
        long ts = Long.parseLong(parts[3]);
        String sig = parts[4];

        String payload = String.join("::", cacheName, modulePrefix, fieldA, String.valueOf(ts));
        String expected = hmacBase64Url(payload);
        if (!constantTimeEquals(sig, expected)) {
            throw new IllegalArgumentException("invalid key signature");
        }
        return new KeyInfo(cacheName, modulePrefix, fieldA, ts);
    }

    private String hmacBase64Url(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // constant-time comparison
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    public static class KeyInfo {
        public final String cacheName;
        public final String modulePrefix;
        public final String fieldA;
        public final long tsMillis;

        public KeyInfo(String cacheName, String modulePrefix, String fieldA, long tsMillis) {
            this.cacheName = cacheName;
            this.modulePrefix = modulePrefix;
            this.fieldA = fieldA;
            this.tsMillis = tsMillis;
        }
    }
}


3) MetadataCache — 启动加载 + 内存 + Redis 回退
package com.example.cache;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 元数据缓存（示例）：
 * - 内存缓存（ConcurrentHashMap）
 * - Redis 作为备份/持久（使用 AppRedisTemplate）
 * - 提供 getOrLoad(key, loader) 方法（优先内存 -> redis -> db(loader)）
 */
@Component
public class MetadataCache implements InitializingBean {

    private final AppRedisTemplate redis;
    private final KeyUtil keyUtil;
    // 内存缓存（不设置过期，定时任务会刷新）
    private final ConcurrentHashMap<String, Object> memCache = new ConcurrentHashMap<>();

    // redis TTL for metadata (long but finite)
    private final long redisTtlSeconds = TimeUnit.DAYS.toSeconds(7); // 示例：7天（根据你们需要调整）

    public MetadataCache(AppRedisTemplate redis, KeyUtil keyUtil) {
        this.redis = redis;
        this.keyUtil = keyUtil;
    }

    // called on app start
    @Override
    public void afterPropertiesSet() throws Exception {
        // 这里触发一次全量加载（调用你们的 loader）
        refreshFromDb();
    }

    // 供定时任务每天调用
    public void refreshFromDb() {
        // TODO: 调用 DB 或 DAO 层加载所有元数据并更新 memCache & redis
        // 这里给出示例伪代码，你需要用真实的 DAO 替换 fetchAllMeta()
        Map<String, Object> all = fetchAllMetaFromDb();
        if (all != null) {
            memCache.clear();
            memCache.putAll(all);
            // 将每条元数据写入 redis，key 规则你可以自定义。示例：
            all.forEach((k, v) -> redis.set(buildRedisKey(k), v, redisTtlSeconds, TimeUnit.SECONDS));
        }
    }

    // 读取：优先内存 -> redis -> loader(db)
    public <T> T getOrLoad(String key, Supplier<T> dbLoader) {
        // try memory
        Object v = memCache.get(key);
        if (v != null) {
            return (T) v;
        }
        // try redis
        String redisKey = buildRedisKey(key);
        Object rv = redis.get(redisKey);
        if (rv != null) {
            // put back to memory for fast hit next time
            memCache.put(key, rv);
            return (T) rv;
        }
        // fallback to DB loader
        T loaded = dbLoader.get();
        if (loaded != null) {
            memCache.put(key, loaded);
            redis.set(redisKey, loaded, redisTtlSeconds, TimeUnit.SECONDS);
        }
        return loaded;
    }

    private String buildRedisKey(String k) {
        return "META::" + k;
    }

    private Map<String, Object> fetchAllMetaFromDb() {
        // TODO: 由你把具体 DAO/Repository 的批量获取逻辑放这里
        // 示例返回 null 表示未实现
        return Map.of(); // placeholder 空实现 - 你替换为真实数据
    }
}


4) ResponseCache — 通用业务响应缓存（含“超过 5 分钟异步刷新”策略）
package com.example.cache;

import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * ResponseCache 说明：
 * - 主存放：Redis（AppRedisTemplate）
 * - 本地热点缓存（optional）：ConcurrentHashMap<key, CachedValue>
 * - 刷新阈值（例如 5 分钟）用于判断是否异步刷新
 * - 防击穿：使用 key-level lock map（ConcurrentHashMap<String, CompletableFuture<?>>）
 */
@Component
public class ResponseCache {

    private final AppRedisTemplate redis;
    private final KeyUtil keyUtil;

    // local near-cache (short-living). This is optional; if you prefer avoid local caching, remove it.
    private final ConcurrentHashMap<String, CachedValue> localCache = new ConcurrentHashMap<>();

    // per-key lock to avoid stampede
    private final ConcurrentHashMap<String, CompletableFuture<Object>> loaderFutures = new ConcurrentHashMap<>();

    // executor for optional sync tasks (can be used for synchronous loads)
    private final ExecutorService loaderExecutor = Executors.newCachedThreadPool();

    public ResponseCache(AppRedisTemplate redis, KeyUtil keyUtil) {
        this.redis = redis;
        this.keyUtil = keyUtil;
    }

    public static class CachedValue implements Serializable {
        public final Object value;
        public final long writeTs; // epoch millis when written to cache

        public CachedValue(Object value, long writeTs) {
            this.value = value;
            this.writeTs = writeTs;
        }
    }

    /**
     * Get value from cache with policy:
     * - Try local -> redis
     * - If hit and not stale => return
     * - If hit and stale => return old value and trigger asyncRefresh
     * - If miss => call loader (synchronized per-key) and write cache then return
     *
     * @param key             cache key
     * @param loader          supplier that loads data if cache miss
     * @param cacheTtlSeconds redis ttl
     * @param staleMillis     threshold (e.g. 5 min in millis) to decide background refresh
     */
    public Object getWithRefreshPolicy(String key,
                                       Supplier<Object> loader,
                                       long cacheTtlSeconds,
                                       long staleMillis) {
        long now = System.currentTimeMillis();

        // 1) local cache fast path
        CachedValue lv = localCache.get(key);
        if (lv != null) {
            if (now - lv.writeTs <= staleMillis) {
                return lv.value;
            } else {
                // stale -> return current and async refresh
                triggerAsyncRefreshIfNotRunning(key, loader, cacheTtlSeconds);
                return lv.value;
            }
        }

        // 2) redis
        Object raw = redis.get(key);
        if (raw instanceof CachedValue) {
            CachedValue cv = (CachedValue) raw;
            // put to local for subsequent fast hits
            localCache.put(key, cv);
            if (now - cv.writeTs <= staleMillis) {
                return cv.value;
            } else {
                triggerAsyncRefreshIfNotRunning(key, loader, cacheTtlSeconds);
                return cv.value;
            }
        } else if (raw != null) {
            // legacy plain value from redis without wrapper -> treat as fresh now
            CachedValue cv = new CachedValue(raw, now);
            redis.set(key, cv, cacheTtlSeconds, TimeUnit.SECONDS);
            localCache.put(key, cv);
            return raw;
        }

        // 3) cache miss => do synchronized load (per-key) to avoid stampede
        CompletableFuture<Object> future = loaderFutures.computeIfAbsent(key, k -> {
            CompletableFuture<Object> f = new CompletableFuture<>();
            loaderExecutor.submit(() -> {
                try {
                    Object val = loader.get();
                    if (val != null) {
                        CachedValue cv = new CachedValue(val, System.currentTimeMillis());
                        redis.set(key, cv, cacheTtlSeconds, TimeUnit.SECONDS);
                        localCache.put(key, cv);
                    }
                    f.complete(val);
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                } finally {
                    loaderFutures.remove(key);
                }
            });
            return f;
        });

        try {
            return future.get(); // wait for load
        } catch (Exception e) {
            throw new RuntimeException("loader failed", e);
        }
    }

    private void triggerAsyncRefreshIfNotRunning(String key, Supplier<Object> loader, long cacheTtlSeconds) {
        // use loaderFutures to mark running refresh; if already running, skip
        boolean canStart = loaderFutures.compute(key, (k, old) -> {
            if (old == null) {
                CompletableFuture<Object> f = new CompletableFuture<>();
                // start async via your AsyncUtil if you prefer; here we use loaderExecutor but we'll call AsyncUtil in usage example
                loaderExecutor.submit(() -> {
                    try {
                        Object val = loader.get();
                        if (val != null) {
                            CachedValue cv = new CachedValue(val, System.currentTimeMillis());
                            redis.set(key, cv, cacheTtlSeconds, TimeUnit.SECONDS);
                            localCache.put(key, cv);
                        }
                        f.complete(val);
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    } finally {
                        loaderFutures.remove(k);
                    }
                });
                return f;
            } else {
                return old; // already running
            }
        }) == null;
        // canStart indicates whether we started; not used further here
    }
}


5) CachedServiceInvoker — 把缓存策略封装成可复用方法
package com.example.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 封装“先从缓存取，超时异步刷新 / 不命中同步加载”的调用流程。
 *
 * 使用方式示例见 MeasureDataServiceWrapper 示例。
 */
@Component
public class CachedServiceInvoker {

    private final ResponseCache responseCache;
    private final KeyUtil keyUtil;

    public CachedServiceInvoker(ResponseCache responseCache, KeyUtil keyUtil) {
        this.responseCache = responseCache;
        this.keyUtil = keyUtil;
    }

    /**
     *
     * @param cacheName 系统 cacheName（用于 Key 生成）
     * @param modulePrefix 模块前缀
     * @param fieldA 由参数生成的不变字段 A（例如 serialize(params) 的结果）
     * @param loader 同步加载真实数据（当 cache miss 时）
     * @param cacheTtlSeconds redis 的 TTL
     * @param staleMillis 超过该时间（自写入）则触发异步刷新（例如 5*60*1000）
     * @return 数据对象
     */
    public Object invokeWithCache(String cacheName,
                                  String modulePrefix,
                                  String fieldA,
                                  Supplier<Object> loader,
                                  long cacheTtlSeconds,
                                  long staleMillis) {
        long ts = System.currentTimeMillis();
        String key = keyUtil.generateKey(cacheName, modulePrefix, fieldA, ts); // note: timestamp embedded but not used for grouping
        // 说明：如果你希望同一 fieldA 的 key 是稳定的（便于覆盖），可以把 timestamp 改成 bucket 时间或省略（但你要求 key 包含时间戳，故生成中含 ts）。
        // 为了让不同请求能命中同一 key（而不是每次都带新的 ts），更合理的做法是：用 fieldA + bucketTs（例如按分钟/小时 round）来构造 key。
        // 这里我们给出一个示例：你可以在调用方将 ts 设置为 (now / 60000) * 60000 来按分钟分桶。
        // 为简单起见，假设调用方传入的 fieldA 已包含必要的稳定性策略（例如 fieldA + bucket）。
        // getWithRefreshPolicy 能接受任意 key。
        return responseCache.getWithRefreshPolicy(key, loader, cacheTtlSeconds, staleMillis);
    }
}


关于 Key 的“时间戳”与命中：
你在需求里说 key 包含时间戳。要兼顾“缓存命中率”和“按时间戳区分”，常见做法是对时间戳做分桶（例如以分钟、小时或自定义间隔取整） —— 这样相近请求能命中同一 key，同时又能按时间窗口控制失效/刷新。上面 invokeWithCache 注释里提到了这点：调用方应把 fieldA 与 bucketTs 一起生成，从而保证 key 稳定而非每次都不同。

6) MeasureDataService 与示例包装器 MeasureDataServiceWrapper

示例：原始耗时 Service（你已有），我们用 wrapper 在外层做缓存。也可用 AOP 自动织入。
package com.example.service;

import com.example.cache.CachedServiceInvoker;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class MeasureDataService {
    // 你已有的耗时方法（伪代码）
    public List<Metric> getMetrics(CodeVO codeVO) {
        // 很多业务逻辑，调用其他 Service/DAO，耗时几秒
        return heavyQuery(codeVO);
    }

    private List<Metric> heavyQuery(CodeVO codeVO) {
        // TODO: 实际实现
        return List.of(); // placeholder
    }
}

// wrapper（或替代你的 Controller 层调用）
@Service
public class MeasureDataServiceWrapper {

    private final MeasureDataService delegate;
    private final CachedServiceInvoker invoker;

    // config
    private final String cacheName = "MySystem";
    private final String modulePrefix = "MeasureData";
    private final long cacheTtlSeconds = TimeUnit.MINUTES.toSeconds(30); // 示例缓存 30 分钟
    private final long staleMillis = TimeUnit.MINUTES.toMillis(5); // 5 分钟阈值

    public MeasureDataServiceWrapper(MeasureDataService delegate, CachedServiceInvoker invoker) {
        this.delegate = delegate;
        this.invoker = invoker;
    }

    public Object getMetricsWithCache(CodeVO codeVO) {
        // 生成 fieldA：对关键参数做稳定序列化（必须确保相同参数得到相同 fieldA）
        String fieldA = buildFieldA(codeVO);

        // 采用分钟桶：例如按每分钟分桶，避免每次带精确到毫秒的时间戳导致缓存不命中
        long bucket = (System.currentTimeMillis() / 60000L) * 60000L;
        String fieldAWithBucket = fieldA + "::" + bucket;

        Supplier<Object> loader = () -> delegate.getMetrics(codeVO);

        // 交给 invoker
        return invoker.invokeWithCache(cacheName, modulePrefix, fieldAWithBucket, loader, cacheTtlSeconds, staleMillis);
    }

    private String buildFieldA(CodeVO codeVO) {
        // 将一组决定查询结果的参数稳定化（例如 JSON 序列化并按照键排序，或拼接关键字段）
        // 示例：如果 CodeVO 有 codeId 和 includeChildren 两个字段：
        return "codeId=" + codeVO.getCodeId() + "|incChildren=" + codeVO.isIncludeChildren();
    }
}

7) 使用项目已有异步框架（把后台刷新交给 AsyncUtil）
你提到已有 IAsyncHandler 与 AsyncUtil.invokeSync("BeanName", context). 在 ResponseCache.triggerAsyncRefreshIfNotRunning 中我用了 loaderExecutor 作为示例。最好把实际刷新任务改成调用你们公司异步框架以统一管理：

示例（在 ResponseCache 中替换 triggerAsyncRefreshIfNotRunning 的执行逻辑）：

// pseudo
Context ctx = new Context();
ctx.put("key", key);
ctx.put("loader", loader); // 注意：不能直接序列化 lambda；你可以改用一个可注入的 bean 来做刷新，或在 context 中放置 fieldA，service bean name 等
AsyncUtil.invokeSync("MyBackgroundRefreshBean", ctx);
实际实现时请把异步 handler 做成 Spring Bean，handler 能从 Context 中取得 key 或 fieldA，然后调用真实 loader 并写入 redis。

8) 其他安全 & 性能注意点（摘要）

签名密钥：KeyUtil 的 secret 必须保存在安全配置（例如 Vault 或 Kubernetes Secret），不要硬编码。服务集群内同一 secret 保持一致以能解析 key。

签名字段：签名的是 cacheName::modulePrefix::fieldA::ts，保证 key 不被伪造或窜改。

Key 长度：尽量让 fieldA 可控长度（如果是大量请求参数，先对参数做哈希，再作为 fieldA），避免 key 太长造成 Redis 内存浪费。可以 fieldA = SHA256(jsonOfParams)。

本地 near-cache：提高延迟性能，但要注意失效/内存使用及多实例一致性（若多实例，local cache 可能导致短暂读到旧数据）。可以把 local cache TTL 设置短（比如几十秒）。

并发：使用 per-key CompletableFuture map 防止缓存穿透 / 同时多个请求发起重复加载。

序列化：Redis 写入的对象需可序列化；推荐使用 JSON（Jackson）或二进制序列化（更快更小），但注意兼容性。

监控：为 cache 命中率、刷新次数、loader 耗时添加监控指标（Prometheus/Grafana），以便优化 TTL、分桶策略。

错误处理：如果 loader 抛异常，保持旧缓存继续服务；不要在短期内把缓存变成 null 导致雪崩。

缓存版本：如果业务字段结构升级，建议在 key 中引入 cache schema version 字段（例如 modulePrefix 中包含版本号），以便平滑切换。

