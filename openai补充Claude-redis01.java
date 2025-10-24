1) CacheConstants.java
package com.example.cache;

public interface CacheConstants {
    // Ĭ�Ϲ���ʱ�䣨�룩��0 = ������
    long DEFAULT_EXPIRE_SECONDS = 24 * 60 * 60; // 1 ��

    // ����ǰ׺ʾ��
    String MODULE_PREFIX = "app:";
    String CACHE_PREFIX = MODULE_PREFIX + "cache:";

    // ˢ��ʱʹ�õ�Ĭ�� ttl���ɱ� loader ���ǣ�
    long DEFAULT_REFRESH_TTL_SECONDS = DEFAULT_EXPIRE_SECONDS;
}


2) CacheKeyGenerator.java
package com.example.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CacheKeyGenerator {

    private CacheKeyGenerator() {}

    // �򵥸�Ч�� key ���ɣ�prefix + ":" + sha1(json)
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


3) CacheDataLoader.java (�ӿ�)

package com.example.cache;

/**
 * ���ͻ���������ӿڣ�
 * - load(cacheKey) ������δ���л���Ҫˢ��ʱ������ͬ��/�첽�ؼ�������
 * - getTtlSeconds() ����Ӧ��д�뻺��Ĺ���ʱ�䣨�룩���� <= 0 ���ʾ�����ù���
 *
 * ҵ�����Ҫʵ�ִ˽ӿڡ�
 */
public interface CacheDataLoader<T> {
    /**
     * ���� cacheKey �������ݣ����ܵ��ú��API/DB��
     * @param cacheKey �����Ļ��� key������ǰ׺��
     * @return ���ص������ݣ�����������ݿɷ��� null
     * @throws Exception �����׳��쳣�������߻Ჶ�񲢴���
     */
    T load(String cacheKey) throws Exception;

    /**
     * loader ����Ļ���TTL���룩��������� <= 0��CacheManager ��ʹ��Ĭ��TTL��CacheConstants.DEFAULT_EXPIRE_SECONDS��
     */
    default long getTtlSeconds() {
        return CacheConstants.DEFAULT_REFRESH_TTL_SECONDS;
    }
}


4) CacheManager.java�����ͺ����ࣩ

package com.example.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * CacheManager: ���ͻ��������
 *
 * ʹ�ò��ԣ�
 * - �����棺����ֵ -> �����л������أ�ͬʱ�첽����ˢ�£��첽ˢ��ʹ�� loader��
 * - ����δ���У��Ը� key ���������Ⲣ����������ͬ������ loader.load()�������д�뻺�沢����
 *
 * �̰߳�ȫ��ʹ�� per-key ReentrantLock
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
     * ��Ҫ��������ȡ���棬����ͨ�� clazz ָ����
     *
     * @param key �������� key������ʹ�� CacheKeyGenerator��
     * @param loader ҵ��ʵ�ֵ� CacheDataLoader
     * @param clazz ����������
     * @param <T> ����
     * @return T or null
     */
    public <T> T get(String key, CacheDataLoader<T> loader, Class<T> clazz) {
        Objects.requireNonNull(key, "key required");
        Objects.requireNonNull(loader, "loader required");

        // try get from redis
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(json)) {
                // ���У������л������أ�ͬʱ�첽ˢ�£��������û�����
                T value = objectMapper.readValue(json, clazz);
                asyncRefresh(key, loader, clazz);
                return value;
            }
        } catch (Exception e) {
            // ��ȡ����ʧ��ʱ����¼�쳣����������һ������ֹ�������ܲ����ã�
            // ����򵥴�ӡ��������ʹ�� logger
            e.printStackTrace();
        }

        // ����δ���У��Ը� key ������ͬ������ loader
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

            // ���� loader ͬ����ȡ
            T loaded = loader.load(key);
            if (loaded != null) {
                writeToCache(key, loaded, loader.getTtlSeconds());
            } else {
                // Optionally cache a null placeholder to avoid hot-loop (��ѡ)
                // writeNullPlaceholder(key);
            }
            return loaded;
        } catch (Exception e) {
            // loader �쳣��ֱ���׳�����ʱ�쳣�򷵻� null���ɵ��÷�������
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
            // д����ʧ�� -> log and ignore
            e.printStackTrace();
        }
    }
}


5) RedisConfig.java��Spring ���ã�

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
        // ʾ��ʹ�� JedisConnectionFactory�������滻 Lettuce / ����
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
     * �첽ˢ���̳߳أ��ɸ��ݲ������������С
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


6) DefaultCacheDataLoader.java��ʾ��ҵ��ʵ�� �� ����˽ӿڣ�
package com.example.loader;

import com.example.cache.CacheDataLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * ʾ������ key �Ӻ�� REST API ��ȡ���ݲ����أ����Ա� MetricService / ����ҵ����
 * ���� cacheKey �а����ɽ����Ĳ�����������Ҳ������ loader ��ά�����������ģ�
 */
public class DefaultCacheDataLoader<T> implements CacheDataLoader<T> {

    private final RestTemplate restTemplate;
    private final Class<T> clazz;
    private final String backendUrlTemplate;
    private final long ttlSeconds;

    /**
     * @param restTemplate Spring ע��
     * @param clazz ���ض�������
     * @param backendUrlTemplate ���ڵ��ú�˵� URL ģ�壨���� "http://api.company.local/metric/{id}"��
     * @param ttlSeconds ����TTL
     */
    public DefaultCacheDataLoader(RestTemplate restTemplate, Class<T> clazz, String backendUrlTemplate, long ttlSeconds) {
        this.restTemplate = restTemplate;
        this.clazz = clazz;
        this.backendUrlTemplate = backendUrlTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public T load(String cacheKey) throws Exception {
        // cacheKey ���ܰ���ǰ׺���ϣ��ʵ��ϵͳ�У���Ӧ�����ܽ����������� key �����ڹ��캯���д���������
        // Ϊ��ʾ�������Ǽ��� cacheKey �����һ������Դ id����ɰ��������
        String resourceId = parseIdFromKey(cacheKey);
        String url = backendUrlTemplate.replace("{id}", resourceId);
        ResponseEntity<T> resp = restTemplate.getForEntity(url, clazz);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        return resp.getBody();
    }

    private String parseIdFromKey(String key) {
        // ��ʾ����key �� ":" �ָ������һ��Ϊ id
        String[] parts = key.split(":");
        return parts[parts.length - 1];
    }

    @Override
    public long getTtlSeconds() {
        return ttlSeconds;
    }
}


˵��������� DefaultCacheDataLoader ��Ϊʾ����ʵ��ҵ��ͨ���������ؽ����������� id������ loader �Ĺ��캯�������Ǵ� cacheKey ������������
7) MetricService.java��ҵ�����ʾ����

package com.example.service;

import com.example.cache.CacheConstants;
import com.example.cache.CacheDataLoader;
import com.example.cache.CacheKeyGenerator;
import com.example.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * ʾ��ҵ�񣺲�ѯָ����� MetricVO��ʹ�÷��� CacheManager
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
     * ���� metricId ��ȡ MetricVO�������߻���
     */
    public MetricVO getMetricById(String metricId) {
        // ����ԭʼ keyData�������� JSON��id����ϲ����ȣ�
        String raw = metricId; // ������
        String prefix = CacheConstants.CACHE_PREFIX + "metric";
        String cacheKey = CacheKeyGenerator.genKey(prefix, raw) + ":" + metricId; // �� key ĩβ���� id ���� loader parse

        // ���� loader������ Spring �����и��� bean��
        CacheDataLoader<MetricVO> loader = new CacheDataLoader<MetricVO>() {
            @Override
            public MetricVO load(String key) throws Exception {
                // ֱ���� restTemplate ���ӿڣ�ʾ����
                String url = "http://backend.internal/api/metrics/{id}";
                return restTemplate.getForObject(url, MetricVO.class, metricId);
            }

            @Override
            public long getTtlSeconds() {
                return 60 * 60; // 1 hour
            }
        };

        // ���� cacheManager ��ȡ
        return cacheManager.get(cacheKey, loader, MetricVO.class);
    }
}
8) RestTemplate Bean�����㻹û�У�
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

9) ��Ҫʵ��ϸ���뽨�飨���˵��

�첽ˢ�£�����������ʱ�����ǲ��������û����󣬶��� refreshExecutor.submit(...) �첽ˢ�¡��߳�����ҵ�������趨��ʾ��Ϊ 8���������ϣ����ǿ���������ƣ����� BoundedExecutor�����оܾ����Ի�������

��������������ڻ���δ����ʱ�Ե��� key ������ReentrantLock��������������ͬʱ�䵽��ˡ�

���洩͸������Loader ���� null ʱ�Ƿ�д���ռλ���� TTL����������������Ƶ������ null������д�����ռλ�Լ��ٶԺ�˵�ѹ����

���л��������� Jackson ���������л�Ϊ JSON ���� Redis��RedisTemplate<String,String>���������Բ�ͬ����/�����Ѻá�����ʹ�ö��������л����� JDK ���л��� fastjson��������Ӧ�滻��

�쳣����ʾ���ж��쳣��ȡ�򵥴�ӡ/��װ��ʽ������ϵͳ��ʹ�� SLF4J ��¼�����ں��ʵĳ������Ի��۶ϡ�

����չ�ԣ�CacheManager ����״̬�ģ������ڶ�� service ����CacheDataLoader ��ҵ��ʵ�֣�����ע�벻ͬ���ԣ�DB��HTTP���ļ��ȣ���

�������ã�CacheDataLoader#getTtlSeconds() ����Ϊ��ͬ���͵����ݷ��ز�ͬTTL����ǿ����ԡ�

Key ��ƣ�CacheKeyGenerator.genKey(prefix, raw) �����˹�ϣ���֣�ʾ�����Ұ� id ׷�ӵ� key ĩβ�Ա� loader �ܽ�������Ҳ����ѡ�����в������ JSON �ٹ�ϣ������ø��ɶ��� key ��ʽ�����Գ��Ⱥ� redis �ڴ�/����Ҫע�⣩��

