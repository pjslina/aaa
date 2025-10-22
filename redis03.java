�� �� �һ������������������Ҫ������һ�� ��ֱ�Ӹ��á������Ϲ棨�������� �� 3 �����������ܡ�����չ�Ұ�ȫ ��ʵ�֡�
�ؼ���˵������˵���ۣ��ٸ����룩��

Redis �洢���ԣ�������Ҫ�������ܣ���
�Ұ�������ȷҪ��� ʱ����ŵ�ʵ������ Key��prefix:md5:ts������Ϊ������ KEYS �� SCAN �������������⣬����ά��һ�� �̶�ָ�� Key��prefix:md5 -> prefix:md5:ts��ָ��ǰ��Ч������ key����������ȡʱֻ��һ�� GET prefix:md5 ��һ�� GET prefix:md5:ts��û�� KEYS/SCAN�����ܸ���������ġ�key ����ʱ�����Ҫ��
д��ʱд�� prefix:md5:ts�����ݣ�TTL=8Сʱ����ͬʱ���� prefix:md5 ָ�루ͬ������ TTL����Ԫ���ݿ�ѡ��д�벻���� TTL��ÿ��ˢ�¸���ָ�������ݣ���

MD5 ���㣺ѡȡ��ҵ������ֶΣ�fieldsToUse������ TreeMap ��֤�ȶ�˳���� JSON ���л��� MD5��ȷ��һ���ԣ������ٸĶ��Ͳ��䣩��������װ�� CacheKeyUtil��

�����д���ԣ�

��ȡ���� GET pointerKey��prefix:md5������������ GET dataKey��prefix:md5:ts�������� ts�������ݲ��������Դ��д�롣

������ڣ����� key TTL=8Сʱ�������ã���ָ�� key TTL ͬ����������ָ�볤��ָ���ѹ������ݡ�

ˢ����ֵ������ 5 ���ӣ����� pointer ָ�������ʱ���������ֵʱ���������ؾ����ݣ��� �첽ˢ�»��棨���������󣩡�

�첽ˢ�� & ͨ�õ��ã��ṩ AsyncUtil��Spring bean���ܰ� beanName+methodName �첽�������� Service ������֧�ִ���һ������Map<String,Object>����Ϊ���������ǹ�˾���첽����������ڡ���Լ�����Ұ� AsyncUtil ��ʵ��д�ɿɱ��滻/ί�е���ʽ������������й����첽ʵ�֣�ֻ��ʵ�� AsyncInvoker �ӿڲ�ע�뼴�ɣ���

��������Լ�������й������� ���� �� 3 �����������ö��ŵ���װ���� CacheRequest��һ���������� RefreshContext��һ���������У�������չ�ҷ��ϴ���淶��

�������������루��ģ�������ֱ�ӿɸ��Ƶ���Ŀ������Ұ�ע��д�ñȽ���ϸ��������ֱ��ʹ�û��滻��˾����ʵ�ֲ��֡�

1) RedisConfig��ֻʾ�� RedisTemplate �� TaskExecutor������˾��ͳһ���滻��
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

    // ���̳߳������첽ִ�У�����˾�������滻ע��
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


2) �����ࣺMD5��Key ����
// CacheKeyUtil.java
package com.example.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class CacheKeyUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * ����ҵ������� md5��fields Ϊ null ��ʹ��ȫ�� params �� key�����ֵ���
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
     * �� dataKey ������ ts�������ʽ prefix:md5:ts��
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


3) CacheRequest����װ�������������� ��1��
// CacheRequest.java
package com.example.cache;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ��װһ�λ�������Ĳ��������ڷ����������ڵ���3����
 */
public class CacheRequest {
    private final String keyPrefix;          // e.g. "api:example"
    private final Map<String, Object> params;// ������������� MD5��
    private final List<String> md5Fields;    // ���� MD5 ���ֶΣ��� null ��ʾȫ����
    private final Supplier<Object> loader;   // ��Դ���غ������޲� Supplier��

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


4) CacheWrapper�������� dataKey ��ֵ���������ݣ������Ժ���չ��

���ǰ�ʵ��ҵ������ֱ��д�� dataKey �� value �У��������κζ��󣩣�ͬʱ pointer ָ��� dataKey �ַ�����CacheWrapper ������������ʾ/��չ��Ҳ����ֱ�Ӵ�ҵ����󣩡�

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


5) �ӿڣ�AsyncInvoker�����滻/ί�й�˾����ʵ�֣�

// AsyncInvoker.java
package com.example.async;

import java.util.Map;

/**
 * �����첽���ýӿڡ��������й����첽��ܣ�ʵ�ִ˽ӿڲ�ע���������ɡ�
 * �������� �� 3 ��������� 2 ����
 */
public interface AsyncInvoker {
    /**
     * �첽����ָ�� beanName �� methodName�������ݲ��� map��ͨ������ʵ�ְ� Map ���գ���
     */
    void invokeAsync(String beanName, String methodName, Map<String, Object> arg);
}


6) Ĭ�� AsyncUtil ʵ�֣�����˾���й���ʵ�ֿ��滻��
// DefaultAsyncInvoker.java
package com.example.async;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Ĭ��ʵ�֣�ʹ�� TaskExecutor �첽��ͨ��������� bean method(Map)
 * ����˾���й���ʵ�֣���ʵ�� AsyncInvoker ������ע�롣
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
                // ���Ȳ��ҵ����� Map ��������û���ٳ����޲η���
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
                // ��¼��־���滻Ϊ���ǹ�˾�� logger��
                e.printStackTrace();
            }
        });
    }
}


7) CacheService�������߼����������� �� 3 ����

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
 * ���Ļ�����񣺸����/д�߼����첽ˢ�´���
 * ���ⷽ������ ��3 ����������Ҫ��һ�� CacheRequest��
 */
@Service
public class CacheService {

    private final RedisTemplate<String, Object> redis;
    private final AsyncInvoker asyncInvoker;

    // TTL for data keys and pointer keys
    private final Duration dataTtl = Duration.ofHours(8);

    // ˢ����ֵ�����룩����������ֵ���첽ˢ��
    private final long refreshThresholdMillis = 5 * 60 * 1000L;

    @Autowired
    public CacheService(RedisTemplate<String, Object> redis, AsyncInvoker asyncInvoker) {
        this.redis = redis;
        this.asyncInvoker = asyncInvoker;
    }

    /**
     * ��Ҫ�������������� 1 ����CacheRequest��
     *
     * ��ȡ���̣�
     * 1) ���� md5 -> pointerKey
     * 2) GET pointerKey -> dataKey
     * 3) GET dataKey -> �������ݣ�������ֵ�����첽ˢ�£�
     * 4) �� pointerKey �� dataKey ȱʧ -> ��Դ��д�� dataKey + ���� pointerKey
     */
    public Object getOrLoad(CacheRequest request, String asyncBeanName, String asyncMethodName) {
        String md5 = CacheKeyUtil.md5OfParams(request.getParams(), request.getMd5Fields());
        String pointerKey = CacheKeyUtil.pointerKey(request.getKeyPrefix(), md5);

        // 1) ��ȡ pointer -> ָ�� dataKey����ʽ prefix:md5:ts��
        Object pointerObj = redis.opsForValue().get(pointerKey);
        if (pointerObj instanceof String dataKey) {
            // ����������
            Object dataObj = redis.opsForValue().get(dataKey);
            if (dataObj != null) {
                long ts = CacheKeyUtil.extractTsFromDataKey(dataKey);
                long age = System.currentTimeMillis() - ts;
                // ��������ֵ�����첽ˢ�£����������ؾ����ݣ�
                if (age > refreshThresholdMillis) {
                    triggerAsyncRefresh(asyncBeanName, asyncMethodName, request, md5);
                }
                return dataObj;
            }
            // pointer ָ������� key ����/��ʧ -> �߻�Դ�߼�
        }

        // pointer �����ڻ� dataKey ������ -> ͬ����Դ��д��
        Object loaded = request.getLoader().get(); // ��Դ��DB / ���㣩
        long now = System.currentTimeMillis();
        String dataKey = CacheKeyUtil.dataKey(request.getKeyPrefix(), md5, now);

        // д�� dataKey �� pointerKey�������� TTL=8Сʱ��
        redis.opsForValue().set(dataKey, loaded, dataTtl);
        redis.opsForValue().set(pointerKey, dataKey, dataTtl);

        return loaded;
    }

    /**
     * �����첽ˢ�£�ֻ�ύ��Ҫ��Ϣ�� async service������ ��3��beanName, methodName, context��
     * �����ɵ��÷����� asyncBeanName �� asyncMethodName�������ã���
     * �첽���Ϊ Map������ keyPrefix��md5 �ȣ�������ҵ��ʵ�����õ������ġ�
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
            // ��¼��־����Ӱ��������
            e.printStackTrace();
        }
    }

    /**
     * ���첽ˢ����/�ⲿ Service ���õ�д�ط��������� ��3��
     * �첽�����������ɺ�ֱ�ӵ��ô˷���������ʹ�� cacheService �ṩ�ķ������á�
     *
     * @param keyPrefix ǰ׺
     * @param md5       md5
     * @param data      ˢ�º�����ݣ���д���µ� dataKey ������ pointerKey��
     */
    public void writeRefreshedData(String keyPrefix, String md5, Object data) {
        long now = System.currentTimeMillis();
        String dataKey = CacheKeyUtil.dataKey(keyPrefix, md5, now);
        String pointerKey = CacheKeyUtil.pointerKey(keyPrefix, md5);
        redis.opsForValue().set(dataKey, data, dataTtl);
        redis.opsForValue().set(pointerKey, dataKey, dataTtl);
    }
}


8) ʾ�� SchusterService���첽ˢ��ʵ��ʾ����

// SchusterService.java
package com.example.service;

import java.util.Map;

/**
 * ����첽ҵ��ӿڣ�processData ����ǩ�������򵥣�����Ϊ Map��
 * ע���������� ��3 ����������ֻ�� 1 �� Map ������
 */
public interface SchusterService {
    void processData(Map<String, Object> context);
}


9) Controller ʹ��ʾ����������ڣ�

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
     * ��ڷ��������� ��3��path/body/query ������ controller �㣩
     */
    @PostMapping("/data")
    public Object getData(@RequestBody Map<String, Object> params) {
        List<String> md5Fields = List.of("userId", "orgId", "metricId"); // ��ҵ��ѡ���ֶ�

        CacheRequest req = new CacheRequest(
                "api:example",     // keyPrefix
                params,
                md5Fields,
                () -> loadFromDb(params) // ��Դ loader��Supplier��
        );

        // �첽ˢ���� schusterService.processData ����beanName �� methodName��
        return cacheService.getOrLoad(req, "schusterService", "processData");
    }

    private Object loadFromDb(Map<String, Object> params) {
        // ʵ��ҵ���Դ��ͬ��ִ�У���һ�λ���δ����ʱ���ߴ��߼���
        return Map.of("data", "sync-loaded", "time", System.currentTimeMillis(), "params", params);
    }
}


10) ���������붨ʱˢ�£�ʾ��������ʾ�������ǹ�˾�Ĺ���ʵ���滻��

�����һ��˵���첽�Ͷ�ʱ�����ʵ�֣��㲻�ùܣ�����ϵͳ�й������롱��
��������ֻ��ʾ��������������й��� @Scheduled ���첽��ܣ���������ѵ����߼��������ǵĹ��������С�

// StartupLoader.java
package com.example.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ����ʱ����Ԫ���ݺ����ã�ʾ����
 */
@Component
public class StartupLoader implements ApplicationRunner {

    private final RedisTemplate<String, Object> redis;

    public StartupLoader(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Ԫ���ݣ�д�벻���ù��ڣ���ѡ��
        Map<String, Object> meta = loadMetaFromDb();
        redis.opsForValue().set("meta:all", meta);
        // �������ݣ�д�루Ҳ������ TTL��
        Map<String, Object> cfg = loadConfigFromDb();
        redis.opsForValue().set("config:all", cfg);
    }

    private Map<String, Object> loadMetaFromDb() {
        // TODO: DB ��ѯ
        return Map.of("meta", "value", "loadedAt", System.currentTimeMillis());
    }

    private Map<String, Object> loadConfigFromDb() {
        // TODO
        return Map.of("config", "value", "loadedAt", System.currentTimeMillis());
    }
}


��Ҫ��ȫ�����ܽ��飨�����Ķ���

����������û�����ֱ�ӵ��� key ��һ���֣����� md5 �Բ�����ժҪ���������豣֤ keyPrefix �� md5 ��ϲ��ᱻ���⹹��ɹ��� key��md5 �̶����ȣ����԰�ȫ����

��ֹ���洩͸ / �ȵ������������ getOrLoad �ġ���Դ��·���ϼ��벢���������絥���� ConcurrentHashMap + FutureTask ���仯�� Redis �ֲ�ʽ�������ҿ��Ը��㲹����飨����/�ֲ�ʽ����ʵ�֣���

�����첽ʧ�ܣ�AsyncInvoker �ڲ�Ӧ��ʧ�����Բ���/��Ƶ��澯��ʾ���������ӡ�쳣����������������빫˾��������ԡ�

���л���RedisTemplate ʹ�� GenericJackson2JsonRedisSerializer���������л�����������������л���ȫ��������и���Ҫ����ͳһ ObjectMapper ���ò����ƿ������л������͡�

Key TTL ��һ���ԣ�����ͬʱ�� dataKey �� pointerKey ����ͬ TTL������ pointer ָ����� dataKey �ĳ������ң������Ҫ���ϸ񣬿��ڶ�ʱ��Ⲣ��Դ����

��־��ʾ���� e.printStackTrace() ��Ϊ��ʾ��������滻Ϊ�㹫˾ͳһ logger �����쳣����ʱ��������� hash ���ϱ��� APM/���ϵͳ��