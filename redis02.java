? 设计思想总览

我们按「分层 + 封装参数对象 + 策略接口」三原则重构：

层	作用	特点
CacheRequest	封装所有缓存操作参数（避免方法参数过多）	高可扩展
CacheService	统一缓存读写逻辑（RedisTemplate操作）	单一职责
AsyncRefresher	异步刷新触发（通用执行器）	解耦异步执行
StartupLoader / Scheduler	启动 & 定时刷新	简单任务
Controller	业务层用法演示	优雅调用

一、参数封装类（核心解耦）
// CacheRequest.java
package com.example.cache;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 缓存操作请求对象，用于封装缓存相关参数，减少方法参数数量。
 */
public class CacheRequest {
    private final String keyPrefix;
    private final Map<String, Object> params;
    private final List<String> md5Fields;     // 参与MD5计算的字段
    private final Supplier<Object> loader;    // 缓存未命中时的加载逻辑

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



二、CacheService（核心缓存逻辑）
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
     * 通用缓存获取逻辑：支持异步刷新机制
     * @param request CacheRequest 封装请求
     * @param asyncRefresher 异步刷新器
     */
    public Object getOrLoad(CacheRequest request, AsyncRefresher asyncRefresher) {
        String md5 = CacheKeyUtil.md5OfParams(request.getParams(), request.getMd5Fields());
        String key = request.getKeyPrefix() + ":" + md5;

        Object obj = redisTemplate.opsForValue().get(key);
        long now = System.currentTimeMillis();

        if (obj == null) {
            // 未命中，回源
            Object data = request.getLoader().get();
            redisTemplate.opsForValue()
                    .set(key, new CacheWrapper(now, data), Duration.ofHours(8));
            return data;
        }

        if (!(obj instanceof CacheWrapper wrapper)) {
            // 异常结构
            Object data = request.getLoader().get();
            redisTemplate.opsForValue()
                    .set(key, new CacheWrapper(now, data), Duration.ofHours(8));
            return data;
        }

        long age = now - wrapper.getTs();
        if (age > asyncRefresher.getRefreshThreshold()) {
            // 触发异步刷新，不阻塞主线程
            asyncRefresher.refreshAsync(key, request);
        }
        return wrapper.getData();
    }

    /**
     * 用于异步线程直接刷新
     */
    public void refresh(CacheRequest request, String key) {
        Object data = request.getLoader().get();
        redisTemplate.opsForValue()
                .set(key, new CacheWrapper(System.currentTimeMillis(), data), Duration.ofHours(8));
    }
}


? 三、AsyncRefresher（通用异步刷新器）

// AsyncRefresher.java
package com.example.cache;

import com.example.util.AsyncExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 通用异步刷新器
 */
@Component
public class AsyncRefresher {

    private final AsyncExecutor asyncExecutor;
    private final CacheService cacheService;

    private final long refreshThreshold = 5 * 60 * 1000L; // 5分钟阈值

    @Autowired
    public AsyncRefresher(AsyncExecutor asyncExecutor, CacheService cacheService) {
        this.asyncExecutor = asyncExecutor;
        this.cacheService = cacheService;
    }

    public long getRefreshThreshold() {
        return refreshThreshold;
    }

    /**
     * 异步刷新缓存，不阻塞主线程
     */
    public void refreshAsync(String key, CacheRequest request) {
        asyncExecutor.submit(() -> cacheService.refresh(request, key));
    }
}


四、通用异步执行器（反射 + 异步任务）

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
     * 通用：按beanName+methodName异步调用
     */
    public void invokeAsync(String beanName, String methodName, Object... args) {
        taskExecutor.execute(() -> {
            try {
                Object bean = context.getBean(beanName);
                Method m = bean.getClass().getMethod(methodName, Map.class);
                m.invoke(bean, args);
            } catch (Exception e) {
                // 记录日志
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
        // 模拟真实数据库查询
        return Map.of("data", "loaded from DB", "time", System.currentTimeMillis());
    }
}


六、启动加载与定时刷新（每日 / 5分钟）

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


七、工具类（MD5与CacheWrapper）

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


