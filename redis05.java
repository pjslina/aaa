�ؼ����Ҫ�㣨�ȶ�һ�飬���㿴���룩

Ԫ���ݣ�Metadata��

����ʱ�� DB ���ص��ڴ� MetadataCache��ConcurrentHashMap������д�� Redis������/�� TTL����

ÿ�춨ʱˢ�£��������ж�ʱ��ܣ�ֻ����� MetadataCache.refreshFromDb()����

��ȡʱ���Ȳ��ڴ棻���û���У��� Redis��Redis û�����ٲ� DB ����д Redis ���ڴ档��·�������̣�д·���첽�����������ɰ�����չ����

������Ӧ���棨ResponseCache��

Redis ��Ϊ�����棬�ڴ汣��һ�������ȵ㻺�棨ConcurrentHashMap���Լ��������ӳ٣���ѡ����

����ֵ��װ�� value��writeTs��д��ʱ�䣩�����ж��Ƿ񡰳��� 5 ���ӡ���

������ now - writeTs > 5min ʱ�����̷��ؾ�ֵ��ͬʱ�����첽ˢ�£������ǵ� AsyncUtil.invokeSync������ʵ�֡��ȷ��أ���̨���¡�ģʽ��

δ����ʱ���� key-level �������Ⲣ����͸����ͬ���������ݲ�д�� Redis��Ȼ���ͷ�������

Key ����밲ȫ

Key �ṹʾ���� <cacheName>::<modulePrefix>::<fieldA>::<tsMillis>::<base64url(HMAC)>

fieldA �����ǡ����ɱ��ֶΡ���ҵ���죨����ṩ����ǩ��ʹ�� HMAC-SHA256������� secret �������ã�������ʱ����֤ǩ���ٽ��������ֶΣ���ֹα��/�۸ġ�

�������� KeyUtil.parseKey(...) ���� KeyInfo{cacheName,modulePrefix,fieldA,timestamp} �����׳��쳣�򷵻� null ��ǩ�����ԡ�

�� MeasureDataService �Ľ���

���� CachedServiceInvoker#invokeWithCache(...) �����ӿڣ��������ÿ����ʱ Service ��ֱ�ӵ��û�ͨ�� AOP ֯�롣

invokeWithCache �������ɻ��� key �ĺ�����ʵ�ʲ�ѯ�������������ʱ���롰�Ƿ񳬹� 5 �������̨ˢ�¡�����ֵ�ȡ�


�������ఴ�ļ��ֿ���ÿ����Ϊһ�� .java�������� com.example.cache ������İ�����ע�⣺AppRedisTemplate ���ڴ����ﰴ�������ǩ��������һ�棨set(String key, Object value, long timeout, TimeUnit unit)����������ǵ���ʵʵ�ֲ�ͬ�������滻����ǩ�����ɣ�ע��������ʾ����

1) AppRedisTemplate����˾�ṩ - ��д�˳���ǩ���Ľӿڣ�����ʵ��ͬ���滻ʵ�֣�
package com.example.cache;

import java.util.concurrent.TimeUnit;

public interface AppRedisTemplate {
    Object get(String key);
    // NOTE: ���ƶ� set ��Ҫ value, timeout, unit�����������ʵǩ����ͬ�����滻����ͺ������ô���
    void set(String key, Object value, long timeout, TimeUnit unit);
    void remove(String key);
    void clear(String cacheName);
}

2) KeyUtil �� ����������� HMAC �İ�ȫ key
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


3) MetadataCache �� �������� + �ڴ� + Redis ����
package com.example.cache;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Ԫ���ݻ��棨ʾ������
 * - �ڴ滺�棨ConcurrentHashMap��
 * - Redis ��Ϊ����/�־ã�ʹ�� AppRedisTemplate��
 * - �ṩ getOrLoad(key, loader) �����������ڴ� -> redis -> db(loader)��
 */
@Component
public class MetadataCache implements InitializingBean {

    private final AppRedisTemplate redis;
    private final KeyUtil keyUtil;
    // �ڴ滺�棨�����ù��ڣ���ʱ�����ˢ�£�
    private final ConcurrentHashMap<String, Object> memCache = new ConcurrentHashMap<>();

    // redis TTL for metadata (long but finite)
    private final long redisTtlSeconds = TimeUnit.DAYS.toSeconds(7); // ʾ����7�죨����������Ҫ������

    public MetadataCache(AppRedisTemplate redis, KeyUtil keyUtil) {
        this.redis = redis;
        this.keyUtil = keyUtil;
    }

    // called on app start
    @Override
    public void afterPropertiesSet() throws Exception {
        // ���ﴥ��һ��ȫ�����أ��������ǵ� loader��
        refreshFromDb();
    }

    // ����ʱ����ÿ�����
    public void refreshFromDb() {
        // TODO: ���� DB �� DAO ���������Ԫ���ݲ����� memCache & redis
        // �������ʾ��α���룬����Ҫ����ʵ�� DAO �滻 fetchAllMeta()
        Map<String, Object> all = fetchAllMetaFromDb();
        if (all != null) {
            memCache.clear();
            memCache.putAll(all);
            // ��ÿ��Ԫ����д�� redis��key ����������Զ��塣ʾ����
            all.forEach((k, v) -> redis.set(buildRedisKey(k), v, redisTtlSeconds, TimeUnit.SECONDS));
        }
    }

    // ��ȡ�������ڴ� -> redis -> loader(db)
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
        // TODO: ����Ѿ��� DAO/Repository ��������ȡ�߼�������
        // ʾ������ null ��ʾδʵ��
        return Map.of(); // placeholder ��ʵ�� - ���滻Ϊ��ʵ����
    }
}


4) ResponseCache �� ͨ��ҵ����Ӧ���棨�������� 5 �����첽ˢ�¡����ԣ�
package com.example.cache;

import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * ResponseCache ˵����
 * - ����ţ�Redis��AppRedisTemplate��
 * - �����ȵ㻺�棨optional����ConcurrentHashMap<key, CachedValue>
 * - ˢ����ֵ������ 5 ���ӣ������ж��Ƿ��첽ˢ��
 * - ��������ʹ�� key-level lock map��ConcurrentHashMap<String, CompletableFuture<?>>��
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


5) CachedServiceInvoker �� �ѻ�����Է�װ�ɿɸ��÷���
package com.example.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ��װ���ȴӻ���ȡ����ʱ�첽ˢ�� / ������ͬ�����ء��ĵ������̡�
 *
 * ʹ�÷�ʽʾ���� MeasureDataServiceWrapper ʾ����
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
     * @param cacheName ϵͳ cacheName������ Key ���ɣ�
     * @param modulePrefix ģ��ǰ׺
     * @param fieldA �ɲ������ɵĲ����ֶ� A������ serialize(params) �Ľ����
     * @param loader ͬ��������ʵ���ݣ��� cache miss ʱ��
     * @param cacheTtlSeconds redis �� TTL
     * @param staleMillis ������ʱ�䣨��д�룩�򴥷��첽ˢ�£����� 5*60*1000��
     * @return ���ݶ���
     */
    public Object invokeWithCache(String cacheName,
                                  String modulePrefix,
                                  String fieldA,
                                  Supplier<Object> loader,
                                  long cacheTtlSeconds,
                                  long staleMillis) {
        long ts = System.currentTimeMillis();
        String key = keyUtil.generateKey(cacheName, modulePrefix, fieldA, ts); // note: timestamp embedded but not used for grouping
        // ˵���������ϣ��ͬһ fieldA �� key ���ȶ��ģ����ڸ��ǣ������԰� timestamp �ĳ� bucket ʱ���ʡ�ԣ�����Ҫ�� key ����ʱ������������к� ts����
        // Ϊ���ò�ͬ����������ͬһ key��������ÿ�ζ����µ� ts����������������ǣ��� fieldA + bucketTs�����簴����/Сʱ round�������� key��
        // �������Ǹ���һ��ʾ����������ڵ��÷��� ts ����Ϊ (now / 60000) * 60000 �������ӷ�Ͱ��
        // Ϊ�������������÷������ fieldA �Ѱ�����Ҫ���ȶ��Բ��ԣ����� fieldA + bucket����
        // getWithRefreshPolicy �ܽ������� key��
        return responseCache.getWithRefreshPolicy(key, loader, cacheTtlSeconds, staleMillis);
    }
}


���� Key �ġ�ʱ����������У�
����������˵ key ����ʱ�����Ҫ��ˡ����������ʡ��͡���ʱ������֡������������Ƕ�ʱ�������Ͱ�������Է��ӡ�Сʱ���Զ�����ȡ���� ���� �����������������ͬһ key��ͬʱ���ܰ�ʱ�䴰�ڿ���ʧЧ/ˢ�¡����� invokeWithCache ע�����ᵽ����㣺���÷�Ӧ�� fieldA �� bucketTs һ�����ɣ��Ӷ���֤ key �ȶ�����ÿ�ζ���ͬ��

6) MeasureDataService ��ʾ����װ�� MeasureDataServiceWrapper

ʾ����ԭʼ��ʱ Service�������У��������� wrapper ����������档Ҳ���� AOP �Զ�֯�롣
package com.example.service;

import com.example.cache.CachedServiceInvoker;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class MeasureDataService {
    // �����еĺ�ʱ������α���룩
    public List<Metric> getMetrics(CodeVO codeVO) {
        // �ܶ�ҵ���߼����������� Service/DAO����ʱ����
        return heavyQuery(codeVO);
    }

    private List<Metric> heavyQuery(CodeVO codeVO) {
        // TODO: ʵ��ʵ��
        return List.of(); // placeholder
    }
}

// wrapper���������� Controller ����ã�
@Service
public class MeasureDataServiceWrapper {

    private final MeasureDataService delegate;
    private final CachedServiceInvoker invoker;

    // config
    private final String cacheName = "MySystem";
    private final String modulePrefix = "MeasureData";
    private final long cacheTtlSeconds = TimeUnit.MINUTES.toSeconds(30); // ʾ������ 30 ����
    private final long staleMillis = TimeUnit.MINUTES.toMillis(5); // 5 ������ֵ

    public MeasureDataServiceWrapper(MeasureDataService delegate, CachedServiceInvoker invoker) {
        this.delegate = delegate;
        this.invoker = invoker;
    }

    public Object getMetricsWithCache(CodeVO codeVO) {
        // ���� fieldA���Թؼ��������ȶ����л�������ȷ����ͬ�����õ���ͬ fieldA��
        String fieldA = buildFieldA(codeVO);

        // ���÷���Ͱ�����簴ÿ���ӷ�Ͱ������ÿ�δ���ȷ�������ʱ������»��治����
        long bucket = (System.currentTimeMillis() / 60000L) * 60000L;
        String fieldAWithBucket = fieldA + "::" + bucket;

        Supplier<Object> loader = () -> delegate.getMetrics(codeVO);

        // ���� invoker
        return invoker.invokeWithCache(cacheName, modulePrefix, fieldAWithBucket, loader, cacheTtlSeconds, staleMillis);
    }

    private String buildFieldA(CodeVO codeVO) {
        // ��һ�������ѯ����Ĳ����ȶ��������� JSON ���л������ռ����򣬻�ƴ�ӹؼ��ֶΣ�
        // ʾ������� CodeVO �� codeId �� includeChildren �����ֶΣ�
        return "codeId=" + codeVO.getCodeId() + "|incChildren=" + codeVO.isIncludeChildren();
    }
}

7) ʹ����Ŀ�����첽��ܣ��Ѻ�̨ˢ�½��� AsyncUtil��
���ᵽ���� IAsyncHandler �� AsyncUtil.invokeSync("BeanName", context). �� ResponseCache.triggerAsyncRefreshIfNotRunning �������� loaderExecutor ��Ϊʾ������ð�ʵ��ˢ������ĳɵ������ǹ�˾�첽�����ͳһ����

ʾ������ ResponseCache ���滻 triggerAsyncRefreshIfNotRunning ��ִ���߼�����

// pseudo
Context ctx = new Context();
ctx.put("key", key);
ctx.put("loader", loader); // ע�⣺����ֱ�����л� lambda������Ը���һ����ע��� bean ����ˢ�£����� context �з��� fieldA��service bean name ��
AsyncUtil.invokeSync("MyBackgroundRefreshBean", ctx);
ʵ��ʵ��ʱ����첽 handler ���� Spring Bean��handler �ܴ� Context ��ȡ�� key �� fieldA��Ȼ�������ʵ loader ��д�� redis��

8) ������ȫ & ����ע��㣨ժҪ��

ǩ����Կ��KeyUtil �� secret ���뱣���ڰ�ȫ���ã����� Vault �� Kubernetes Secret������ҪӲ���롣����Ⱥ��ͬһ secret ����һ�����ܽ��� key��

ǩ���ֶΣ�ǩ������ cacheName::modulePrefix::fieldA::ts����֤ key ����α���ܸġ�

Key ���ȣ������� fieldA �ɿس��ȣ�����Ǵ�������������ȶԲ�������ϣ������Ϊ fieldA�������� key ̫����� Redis �ڴ��˷ѡ����� fieldA = SHA256(jsonOfParams)��

���� near-cache������ӳ����ܣ���Ҫע��ʧЧ/�ڴ�ʹ�ü���ʵ��һ���ԣ�����ʵ����local cache ���ܵ��¶��ݶ��������ݣ������԰� local cache TTL ���ọ̈����缸ʮ�룩��

������ʹ�� per-key CompletableFuture map ��ֹ���洩͸ / ͬʱ����������ظ����ء�

���л���Redis д��Ķ���������л����Ƽ�ʹ�� JSON��Jackson������������л��������С������ע������ԡ�

��أ�Ϊ cache �����ʡ�ˢ�´�����loader ��ʱ��Ӽ��ָ�꣨Prometheus/Grafana�����Ա��Ż� TTL����Ͱ���ԡ�

��������� loader ���쳣�����־ɻ���������񣻲�Ҫ�ڶ����ڰѻ����� null ����ѩ����

����汾�����ҵ���ֶνṹ������������ key ������ cache schema version �ֶΣ����� modulePrefix �а����汾�ţ����Ա�ƽ���л���

