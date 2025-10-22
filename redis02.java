? ���˼������

���ǰ����ֲ� + ��װ�������� + ���Խӿڡ���ԭ���ع���

��	����	�ص�
CacheRequest	��װ���л���������������ⷽ���������ࣩ	�߿���չ
CacheService	ͳһ�����д�߼���RedisTemplate������	��һְ��
AsyncRefresher	�첽ˢ�´�����ͨ��ִ������	�����첽ִ��
StartupLoader / Scheduler	���� & ��ʱˢ��	������
Controller	ҵ����÷���ʾ	���ŵ���

һ��������װ�ࣨ���Ľ��
// CacheRequest.java
package com.example.cache;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * �����������������ڷ�װ������ز��������ٷ�������������
 */
public class CacheRequest {
    private final String keyPrefix;
    private final Map<String, Object> params;
    private final List<String> md5Fields;     // ����MD5������ֶ�
    private final Supplier<Object> loader;    // ����δ����ʱ�ļ����߼�

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



����CacheService�����Ļ����߼���
// CacheService.java
package com.example.cache;

import com.example.util.CacheKeyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public CacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * ͨ�û����ȡ�߼���֧���첽ˢ�»���
     * @param request CacheRequest ��װ����
     * @param asyncRefresher �첽ˢ����
     */
    public Object getOrLoad(CacheRequest request, AsyncRefresher asyncRefresher) {
        String md5 = CacheKeyUtil.md5OfParams(request.getParams(), request.getMd5Fields());
        String key = request.getKeyPrefix() + ":" + md5;

        Object obj = redisTemplate.opsForValue().get(key);
        long now = System.currentTimeMillis();

        if (obj == null) {
            // δ���У���Դ
            Object data = request.getLoader().get();
            redisTemplate.opsForValue()
                    .set(key, new CacheWrapper(now, data), Duration.ofHours(8));
            return data;
        }

        if (!(obj instanceof CacheWrapper wrapper)) {
            // �쳣�ṹ
            Object data = request.getLoader().get();
            redisTemplate.opsForValue()
                    .set(key, new CacheWrapper(now, data), Duration.ofHours(8));
            return data;
        }

        long age = now - wrapper.getTs();
        if (age > asyncRefresher.getRefreshThreshold()) {
            // �����첽ˢ�£����������߳�
            asyncRefresher.refreshAsync(key, request);
        }
        return wrapper.getData();
    }

    /**
     * �����첽�߳�ֱ��ˢ��
     */
    public void refresh(CacheRequest request, String key) {
        Object data = request.getLoader().get();
        redisTemplate.opsForValue()
                .set(key, new CacheWrapper(System.currentTimeMillis(), data), Duration.ofHours(8));
    }
}


? ����AsyncRefresher��ͨ���첽ˢ������

// AsyncRefresher.java
package com.example.cache;

import com.example.util.AsyncExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ͨ���첽ˢ����
 */
@Component
public class AsyncRefresher {

    private final AsyncExecutor asyncExecutor;
    private final CacheService cacheService;

    private final long refreshThreshold = 5 * 60 * 1000L; // 5������ֵ

    @Autowired
    public AsyncRefresher(AsyncExecutor asyncExecutor, CacheService cacheService) {
        this.asyncExecutor = asyncExecutor;
        this.cacheService = cacheService;
    }

    public long getRefreshThreshold() {
        return refreshThreshold;
    }

    /**
     * �첽ˢ�»��棬���������߳�
     */
    public void refreshAsync(String key, CacheRequest request) {
        asyncExecutor.submit(() -> cacheService.refresh(request, key));
    }
}


�ġ�ͨ���첽ִ���������� + �첽����

// AsyncExecutor.java
package com.example.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
public class AsyncExecutor {

    private final TaskExecutor taskExecutor;
    private final ApplicationContext context;

    @Autowired
    public AsyncExecutor(TaskExecutor taskExecutor, ApplicationContext context) {
        this.taskExecutor = taskExecutor;
        this.context = context;
    }

    public void submit(Runnable task) {
        taskExecutor.execute(task);
    }

    /**
     * ͨ�ã���beanName+methodName�첽����
     */
    public void invokeAsync(String beanName, String methodName, Object... args) {
        taskExecutor.execute(() -> {
            try {
                Object bean = context.getBean(beanName);
                Method m = bean.getClass().getMethod(methodName, Map.class);
                m.invoke(bean, args);
            } catch (Exception e) {
                // ��¼��־
                e.printStackTrace();
            }
        });
    }
}


// ExampleController.java
package com.example.web;

import com.example.cache.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/example")
public class ExampleController {

    private final CacheService cacheService;
    private final AsyncRefresher asyncRefresher;

    @Autowired
    public ExampleController(CacheService cacheService, AsyncRefresher asyncRefresher) {
        this.cacheService = cacheService;
        this.asyncRefresher = asyncRefresher;
    }

    @PostMapping("/data")
    public Object getData(@RequestBody Map<String, Object> params) {
        List<String> md5Fields = List.of("userId", "orgId", "metricId");

        CacheRequest request = new CacheRequest(
                "api:example",
                params,
                md5Fields,
                () -> loadFromDb(params)
        );

        return cacheService.getOrLoad(request, asyncRefresher);
    }

    private Object loadFromDb(Map<String, Object> params) {
        // ģ����ʵ���ݿ��ѯ
        return Map.of("data", "loaded from DB", "time", System.currentTimeMillis());
    }
}


�������������붨ʱˢ�£�ÿ�� / 5���ӣ�

// StartupLoader.java
package com.example.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class StartupLoader implements ApplicationRunner {

    private final RedisTemplate<String, Object> redisTemplate;

    public StartupLoader(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadMetaData();
        loadConfig();
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void loadMetaData() {
        redisTemplate.opsForValue().set("meta:data", Map.of("meta", System.currentTimeMillis()));
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void loadConfig() {
        redisTemplate.opsForValue().set("config:data", Map.of("config", System.currentTimeMillis()));
    }
}


�ߡ������ࣨMD5��CacheWrapper��

// CacheKeyUtil.java
package com.example.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class CacheKeyUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String md5OfParams(Map<String, Object> params, List<String> fields) {
        Map<String, Object> sorted = new TreeMap<>();
        if (fields == null || fields.isEmpty()) sorted.putAll(params);
        else for (String f : fields) if (params.containsKey(f)) sorted.put(f, params.get(f));

        try {
            String json = mapper.writeValueAsString(sorted);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}


// CacheWrapper.java
package com.example.cache;

import java.io.Serializable;

public class CacheWrapper implements Serializable {
    private final long ts;
    private final Object data;

    public CacheWrapper(long ts, Object data) {
        this.ts = ts;
        this.data = data;
    }

    public long getTs() { return ts; }
    public Object getData() { return data; }
}


