? 推荐的架构设计方案：基于 Redis Pub/Sub 的分布式缓存刷新
一、整体思路

当数据被修改（新增/更新/删除）时：

更新数据库；

同时刷新 Redis；

再通过 Redis 的 Pub/Sub 发布一个缓存刷新消息；

所有微服务实例都订阅这个刷新频道；

收到消息后，各实例执行：

删除或重建自己的 LocalCache；

（可选）刷新 Redis 的最新数据。

这样就能在分布式多实例环境下做到自动刷新。

          ┌──────────────────┐
          │   数据库(DB)     │
          └────────┬─────────┘
                   │
                   ▼
          ┌──────────────────┐
          │      Redis        │
          │ (缓存+消息通道)   │
          └────────┬─────────┘
                   │
   ┌───────────────┴────────────────┐
   │                                │
┌───────┐                     ┌───────┐
│微服务A│                     │微服务B│
│LocalCache│ ← Pub/Sub → │LocalCache│
└───────┘                     └───────┘


三、核心实现步骤
1? 定义缓存管理接口

public interface CacheService<T> {
    T get(String key);
    void refresh(String key);
    void refreshAll();
}


2? 实现 LocalCache + Redis 双层缓存逻辑
@Service
public class MetadataCacheService implements CacheService<Metadata> {

    private final Map<String, Metadata> localCache = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, Metadata> redisTemplate;

    @Autowired
    private MetadataMapper metadataMapper; // MyBatis DAO

    private static final String REDIS_KEY_PREFIX = "meta:";

    @Override
    public Metadata get(String key) {
        // 1. local
        Metadata meta = localCache.get(key);
        if (meta != null) return meta;

        // 2. redis
        meta = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + key);
        if (meta != null) {
            localCache.put(key, meta);
            return meta;
        }

        // 3. db
        meta = metadataMapper.selectByKey(key);
        if (meta != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + key, meta);
            localCache.put(key, meta);
        }

        return meta;
    }

    @Override
    public void refresh(String key) {
        Metadata meta = metadataMapper.selectByKey(key);
        if (meta != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + key, meta);
            localCache.put(key, meta);
        } else {
            redisTemplate.delete(REDIS_KEY_PREFIX + key);
            localCache.remove(key);
        }
    }

    @Override
    public void refreshAll() {
        localCache.clear();
    }
}


3? 发布刷新消息

在修改元数据时（例如在 MetadataController 的更新方法中）：
@Autowired
private StringRedisTemplate stringRedisTemplate;

@PostMapping("/update")
public ResponseEntity<?> update(@RequestBody Metadata metadata) {
    metadataMapper.update(metadata);
    redisTemplate.opsForValue().set("meta:" + metadata.getKey(), metadata);

    // 关键：发布刷新消息
    stringRedisTemplate.convertAndSend("CACHE_REFRESH_CHANNEL", metadata.getKey());
    return ResponseEntity.ok("Updated and cache refresh broadcasted");
}


4? 各实例订阅刷新频道

创建一个 CacheRefreshListener：
@Component
public class CacheRefreshListener implements MessageListener {

    @Autowired
    private MetadataCacheService metadataCacheService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = message.toString();
        metadataCacheService.refresh(key);
        System.out.println("Refreshed local cache for key: " + key);
    }
}


注册订阅关系：
@Configuration
public class RedisSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheRefreshListener listener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new PatternTopic("CACHE_REFRESH_CHANNEL"));
        return container;
    }
}


优雅的特性

? 分布式一致性：通过 Redis 通道通知所有实例。
? 零停机：修改元数据立即通知，无需重启。
? 可扩展性：适用于任意缓存类型（不仅是元数据）。
? 可定制：你可以扩展为 refreshAll 或批量刷新。

? 可选增强（进阶）

带版本号的缓存刷新机制，避免重复刷新；

消息体包含操作类型（新增/更新/删除）；

异步刷新：使用线程池异步更新本地缓存；

本地缓存使用 Caffeine，可设过期时间、权重等。

总结一句话

最优雅的方案是在修改数据后，通过 Redis Pub/Sub 机制向所有微服务实例广播刷新事件，让各实例的 LocalCache 自动同步更新，从而实现“Redis + LocalCache” 双层缓存的一致性，而无需重启服务。

--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

? 推荐方案：基于 Redis + Guava Cache + 定时版本感知刷新机制

这个方案的关键思想是：

“不用消息广播，而是用 版本号检测机制 + Guava 缓存失效策略，让每个实例自动发现并刷新 LocalCache。”

? 核心设计思想

Redis 作为缓存数据的权威层，每次更新数据后，写入最新版本号；

每个服务实例的 Guava LocalCache 都带有一个本地版本号；

每隔 N 秒（例如 30 秒），实例会去 Redis 查询最新的全局版本号；

如果发现 Redis 的版本号比本地的大，则清空或更新 LocalCache；

这样即使 Redis 不支持 Pub/Sub，也能在短时间内实现近实时同步。

          ┌──────────────┐
          │   Database    │
          └──────┬───────┘
                 │
                 ▼
          ┌──────────────┐
          │    Redis      │
          │ meta:data:*   │
          │ meta:version  │ ← 全局版本号
          └──────┬───────┘
                 │(poll)
     ┌───────────┴─────────────┐
     │                         │
┌────────────┐          ┌────────────┐
│  实例A     │          │  实例B     │
│ GuavaCache │          │ GuavaCache │
│ version=5  │          │ version=5  │
└────────────┘          └────────────┘


当有更新时：

修改 DB

刷新 Redis 数据

递增 Redis 的 meta:version

各实例下次检测到版本号变化后，刷新本地 GuavaCache。

? 实现步骤
① 定义缓存服务接口

public interface MetadataCacheService {
    Metadata get(String key);
    void refresh(String key);
    void refreshAll();
}


② 使用 Guava 实现本地缓存

@Service
public class MetadataCacheServiceImpl implements MetadataCacheService {

    private final Cache<String, Metadata> localCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES) // 可选本地缓存过期
            .build();

    @Autowired
    private RedisTemplate<String, Metadata> redisTemplate;
    @Autowired
    private MetadataMapper metadataMapper;

    private static final String REDIS_KEY_PREFIX = "meta:data:";
    private static final String REDIS_VERSION_KEY = "meta:version";

    // 当前实例缓存版本号
    private final AtomicLong localVersion = new AtomicLong(0);

    @Override
    public Metadata get(String key) {
        Metadata meta = localCache.getIfPresent(key);
        if (meta != null) return meta;

        meta = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + key);
        if (meta != null) {
            localCache.put(key, meta);
            return meta;
        }

        meta = metadataMapper.selectByKey(key);
        if (meta != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + key, meta);
            localCache.put(key, meta);
        }
        return meta;
    }

    @Override
    public void refresh(String key) {
        Metadata meta = metadataMapper.selectByKey(key);
        if (meta != null) {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + key, meta);
            localCache.put(key, meta);
        } else {
            redisTemplate.delete(REDIS_KEY_PREFIX + key);
            localCache.invalidate(key);
        }
    }

    @Override
    public void refreshAll() {
        localCache.invalidateAll();
    }

    /** 获取当前实例的缓存版本号 */
    public long getLocalVersion() {
        return localVersion.get();
    }

    /** 更新本地版本号 */
    public void updateLocalVersion(long version) {
        this.localVersion.set(version);
    }
}


③ 数据更新时递增版本号
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    @Autowired
    private MetadataMapper metadataMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/update")
    public ResponseEntity<?> update(@RequestBody Metadata metadata) {
        metadataMapper.update(metadata);
        redisTemplate.opsForValue().set("meta:data:" + metadata.getKey(), metadata);

        // 关键：递增全局版本号
        redisTemplate.opsForValue().increment("meta:version", 1);
        return ResponseEntity.ok("Updated metadata and version incremented");
    }
}


④ 定时任务检测版本号变化

@Component
public class CacheVersionMonitor {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private MetadataCacheServiceImpl metadataCacheService;

    @Scheduled(fixedDelay = 30000) // 每30秒检查一次
    public void checkVersion() {
        Object versionObj = redisTemplate.opsForValue().get("meta:version");
        if (versionObj == null) return;

        long remoteVersion = Long.parseLong(versionObj.toString());
        long localVersion = metadataCacheService.getLocalVersion();

        if (remoteVersion > localVersion) {
            // Redis版本比本地新 -> 触发刷新
            metadataCacheService.refreshAll();
            metadataCacheService.updateLocalVersion(remoteVersion);
            System.out.println("[CacheSync] Local cache refreshed to version " + remoteVersion);
        }
    }
}

? 优点总结
特性	说明
? 无需 Redis Pub/Sub	用版本号检测替代广播机制
? 无需重启服务	每个实例定时检测自动刷新
? 实现简单	全部基于 Spring + Redis + Guava
? 性能稳定	本地读性能优越，版本检测代价极小
? 可控同步频率	通过 @Scheduled 控制延迟（30秒可调）

? 可选优化

使用 Spring @EventListener 或自定义 ApplicationEvent 机制，让实例内部刷新解耦；

用 Redis setnx 初始化版本号；

支持局部刷新（如只刷新特定 key）；

用 Caffeine 替代 Guava Cache，性能更高。

? 总结一句话

当 Redis 不支持 Pub/Sub 时，可以用 “全局版本号 + 定时检测机制” 来让每个实例自动感知数据变更，触发 LocalCache 刷新。
这样无需重启服务，又能在数十秒内实现全局缓存一致性，是一种稳定且优雅的替代方案。

-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
? 背景回顾

当前方案中：

当 Redis 版本号变化时，CacheVersionMonitor 直接调用了
metadataCacheService.refreshAll()；

这样虽然能工作，但耦合度高；

我们可以用 Spring 事件机制 来优雅地解耦刷新触发与处理逻辑。

? 新目标

当检测到版本号变化时，不直接调用刷新方法；

而是发布一个 Spring 事件 CacheRefreshEvent；

由一个专门的监听器 CacheRefreshListener 响应这个事件；

监听器再调用 MetadataCacheService.refreshAll()；

这样整个系统就实现了：

定时任务 → 发布事件 → 监听器 → 刷新缓存


? 实现步骤（示例代码）

下面是在之前示例项目基础上添加的部分。
文件位置：com.example.cache.event 包内。

① 定义事件类：CacheRefreshEvent
package com.example.cache.event;

import org.springframework.context.ApplicationEvent;

/**
 * 缓存刷新事件
 * 用于触发当前实例的 LocalCache 刷新
 */
public class CacheRefreshEvent extends ApplicationEvent {

    private final long newVersion;

    public CacheRefreshEvent(Object source, long newVersion) {
        super(source);
        this.newVersion = newVersion;
    }

    public long getNewVersion() {
        return newVersion;
    }
}


② 定义监听器：CacheRefreshListener
package com.example.cache.event;

import com.example.cache.MetadataCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 缓存刷新监听器
 * 响应 CacheRefreshEvent 事件
 */
@Component
public class CacheRefreshListener {

    @Autowired
    private MetadataCacheService metadataCacheService;

    @EventListener
    public void handleCacheRefresh(CacheRefreshEvent event) {
        System.out.println("[CacheEvent] Received refresh event, version=" + event.getNewVersion());
        metadataCacheService.refreshAll();
        metadataCacheService.updateLocalVersion(event.getNewVersion());
    }
}


③ 修改定时检测任务：CacheVersionMonitor

将原来直接调用 refreshAll() 的部分改成发布事件。

package com.example.cache;

import com.example.cache.event.CacheRefreshEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期检查 Redis 版本号
 * 若发现远程版本更新，则发布本地刷新事件
 */
@Component
public class CacheVersionMonitor {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private MetadataCacheService metadataCacheService;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 30000) // 每30秒检测一次
    public void checkVersion() {
        Object versionObj = redisTemplate.opsForValue().get("meta:version");
        if (versionObj == null) return;

        long remoteVersion = Long.parseLong(versionObj.toString());
        long localVersion = metadataCacheService.getLocalVersion();

        if (remoteVersion > localVersion) {
            // 不直接调用 refreshAll，而是发布事件
            eventPublisher.publishEvent(new CacheRefreshEvent(this, remoteVersion));
            System.out.println("[CacheMonitor] Detected version change → publish refresh event");
        }
    }
}


④ 事件传播示意
┌───────────────────────┐
│ @Scheduled 任务检测版本 │
└──────────────┬────────┘
               │
               ▼
     ApplicationEventPublisher
               │
               ▼
┌───────────────────────┐
│ @EventListener 监听器 │
│ CacheRefreshListener  │
│ 调用 refreshAll()     │
└───────────────────────┘


? 优势分析
优点	说明
? 完全解耦	版本检测与缓存刷新不再相互依赖
? 可扩展	未来可新增更多监听器（如刷新指标缓存、配置缓存）
? 线程安全	Spring 事件在同一实例中按顺序调用，天然线程安全
? 低侵入性	无需改动核心业务逻辑，只增加事件层
? 支持异步监听	未来可用 @Async 异步执行刷新逻辑

? ⑤ （可选）支持异步事件监听

如果你希望刷新缓存不阻塞主线程，可以这样加上：

@EventListener
@Async
public void handleCacheRefresh(CacheRefreshEvent event) {
    metadataCacheService.refreshAll();
}


别忘了在主启动类加上：
@EnableAsync

com.example.cache
├── CacheDemoApplication.java
├── MetadataCacheService.java
├── MetadataCacheServiceImpl.java
├── CacheVersionMonitor.java     ← 定时检测 + 发布事件
├── event/
│   ├── CacheRefreshEvent.java   ← 自定义事件
│   └── CacheRefreshListener.java← 监听器

? 可拓展设计建议

如果未来你有多个不同类型的缓存（例如：

元数据缓存

指标缓存

组织结构缓存
……）

你可以定义一个更通用的事件结构，例如：

public class CacheRefreshEvent extends ApplicationEvent {
    private final String cacheType; // e.g. "METADATA", "METRIC"
    private final long newVersion;
}


然后让不同的监听器按类型分流处理：
@EventListener
public void handleCacheRefresh(CacheRefreshEvent event) {
    if ("METADATA".equals(event.getCacheType())) {
        metadataCacheService.refreshAll();
    }
}


这样就实现了多缓存类型的动态刷新解耦机制。
