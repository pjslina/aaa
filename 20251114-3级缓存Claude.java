Java 元数据三级缓存体系最佳实践方案
作为资深 Java 架构师,我将为您设计一套完整的、生产级的三级缓存解决方案。
一、架构设计
1.1 三级缓存架构图(文字描述)
┌─────────────────────────────────────────────────────────┐
│                    应用层(Service)                        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              MetadataCacheManager                        │
│          (统一缓存管理门面,透明封装)                       │
└─┬───────────────────────────────────────────────────────┘
  │
  ├──? ① LocalCache (Guava)  ──? 命中则返回
  │         │ 未命中
  │         ▼
  ├──? ② Redis (二级缓存)    ──? 命中则回填 LocalCache
  │         │ 未命中
  │         ▼
  └──? ③ Database (OpenGauss) ──? 回填 LocalCache + Redis
           │
           └──? 通过 MyBatis 访问

┌─────────────────────────────────────────────────────────┐
│                  缓存同步机制                             │
├─────────────────────────────────────────────────────────┤
│  方案A: MQ 广播(推荐)                                     │
│    ├─ 写操作后发送 MQ 消息                                │
│    └─ 所有实例订阅并刷新 LocalCache                       │
│                                                          │
│  方案B: Redis Pub/Sub(无MQ场景)                          │
│    ├─ 写操作后发布 Redis 消息                             │
│    └─ 所有实例订阅并刷新 LocalCache                       │
└─────────────────────────────────────────────────────────┘

1.2 缓存读写流程图
读流程:
查询请求
  │
  ▼
┌────────────────┐
│ 查询 LocalCache │ ──命中──? 返回数据
└────────┬───────┘
         │未命中
         ▼
┌────────────────┐
│  查询 Redis    │ ──命中──? 回填LocalCache ──? 返回数据
└────────┬───────┘
         │未命中
         ▼
┌────────────────┐
│  查询 Database │ ──? 回填Redis ──? 回填LocalCache ──? 返回数据
└────────────────┘

写流程(方案A-有MQ):
写请求(新增/修改/删除)
  │
  ▼
┌────────────────┐
│  更新 Database │
└────────┬───────┘
         │
         ▼
┌────────────────┐
│  更新 Redis    │
└────────┬───────┘
         │
         ▼
┌────────────────┐
│ 发送 MQ 消息   │
└────────┬───────┘
         │
         ▼
┌────────────────────────────────┐
│ 所有实例接收消息并刷新LocalCache │
└────────────────────────────────┘

二、完整代码实现
// ===================== 1. 配置类 =====================

/**
 * 缓存配置
 */
@Configuration
@EnableScheduling
public class CacheConfiguration {
    
    @Value("${cache.local.max-size:10000}")
    private int localCacheMaxSize;
    
    @Value("${cache.local.expire-minutes:30}")
    private int localCacheExpireMinutes;
    
    @Value("${cache.redis.expire-hours:24}")
    private int redisCacheExpireHours;
    
    /**
     * Guava LocalCache 配置
     */
    @Bean
    public LoadingCache<String, String> metadataLocalCache() {
        return CacheBuilder.newBuilder()
                .maximumSize(localCacheMaxSize)
                .expireAfterWrite(localCacheExpireMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build(new CacheLoader<String, String>() {
                    @Override
                    public String load(String key) throws Exception {
                        // 不会被调用,因为我们使用 getIfPresent
                        return null;
                    }
                });
    }
    
    /**
     * Redis Template 配置
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // 使用 Jackson2JsonRedisSerializer 序列化
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
        serializer.setObjectMapper(mapper);
        
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        
        return template;
    }
    
    public int getRedisCacheExpireHours() {
        return redisCacheExpireHours;
    }
}

// ===================== 2. 实体类 =====================

/**
 * 元数据实体基类
 */
public class MetadataEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long id;
    private String code;
    private String name;
    private Integer status;
    private Long version; // 乐观锁版本号
    private Date createTime;
    private Date updateTime;
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}

// ===================== 3. 缓存事件消息 =====================

/**
 * 缓存变更事件
 */
public class CacheChangeEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum EventType {
        INSERT, UPDATE, DELETE, REFRESH_ALL
    }
    
    private String cacheType; // 元数据类型
    private EventType eventType;
    private String key; // 缓存key
    private Long id; // 数据ID
    private Long version; // 数据版本号
    private String sourceInstanceId; // 发起变更的实例ID
    private long timestamp; // 事件时间戳
    
    public CacheChangeEvent() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public CacheChangeEvent(String cacheType, EventType eventType, String key, Long id) {
        this();
        this.cacheType = cacheType;
        this.eventType = eventType;
        this.key = key;
        this.id = id;
    }
    
    // Getters and Setters
    public String getCacheType() { return cacheType; }
    public void setCacheType(String cacheType) { this.cacheType = cacheType; }
    
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    
    public String getSourceInstanceId() { return sourceInstanceId; }
    public void setSourceInstanceId(String sourceInstanceId) { this.sourceInstanceId = sourceInstanceId; }
    
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}

// ===================== 4. 核心缓存管理器 =====================

/**
 * 三级缓存管理器(核心类)
 */
@Component
@Slf4j
public class MetadataCacheManager {
    
    @Autowired
    private LoadingCache<String, String> localCache;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private CacheConfiguration cacheConfiguration;
    
    @Autowired
    private CacheSyncService cacheSyncService;
    
    private static final String CACHE_PREFIX = "metadata:";
    private static final String NULL_VALUE = "NULL";
    
    // 实例ID,用于识别缓存变更来源
    private final String instanceId = UUID.randomUUID().toString();
    
    /**
     * 三级缓存读取(透明封装)
     * 
     * @param cacheType 缓存类型(表名)
     * @param key 缓存key
     * @param dbLoader 数据库加载函数
     * @param clazz 实体类类型
     * @return 查询结果
     */
    public <T> T get(String cacheType, String key, Supplier<T> dbLoader, Class<T> clazz) {
        String fullKey = buildKey(cacheType, key);
        
        // 1. 查询 LocalCache
        String localValue = localCache.getIfPresent(fullKey);
        if (localValue != null) {
            log.debug("LocalCache命中: {}", fullKey);
            return deserialize(localValue, clazz);
        }
        
        // 2. 查询 Redis
        Object redisValue = redisTemplate.opsForValue().get(fullKey);
        if (redisValue != null) {
            log.debug("Redis命中: {}", fullKey);
            String jsonValue = (String) redisValue;
            localCache.put(fullKey, jsonValue); // 回填LocalCache
            return deserialize(jsonValue, clazz);
        }
        
        // 3. 查询数据库(防止缓存穿透)
        T dbValue = null;
        try {
            dbValue = dbLoader.get();
        } catch (Exception e) {
            log.error("数据库查询失败: {}", fullKey, e);
            throw new RuntimeException("数据库查询失败", e);
        }
        
        // 4. 回填缓存
        String jsonValue = serialize(dbValue);
        
        // 回填Redis
        redisTemplate.opsForValue().set(
                fullKey, 
                jsonValue, 
                cacheConfiguration.getRedisCacheExpireHours(), 
                TimeUnit.HOURS
        );
        
        // 回填LocalCache
        localCache.put(fullKey, jsonValue);
        
        log.debug("从DB加载并缓存: {}", fullKey);
        return dbValue;
    }
    
    /**
     * 批量查询(支持多key)
     */
    public <T> Map<String, T> multiGet(String cacheType, List<String> keys, 
                                        Function<List<String>, Map<String, T>> dbLoader, 
                                        Class<T> clazz) {
        Map<String, T> result = new HashMap<>();
        List<String> missedKeys = new ArrayList<>();
        
        // 1. 批量查询LocalCache
        for (String key : keys) {
            String fullKey = buildKey(cacheType, key);
            String localValue = localCache.getIfPresent(fullKey);
            if (localValue != null) {
                result.put(key, deserialize(localValue, clazz));
            } else {
                missedKeys.add(key);
            }
        }
        
        if (missedKeys.isEmpty()) {
            return result;
        }
        
        // 2. 批量查询Redis
        List<String> fullMissedKeys = missedKeys.stream()
                .map(k -> buildKey(cacheType, k))
                .collect(Collectors.toList());
        
        List<Object> redisValues = redisTemplate.opsForValue().multiGet(fullMissedKeys);
        List<String> dbMissedKeys = new ArrayList<>();
        
        for (int i = 0; i < missedKeys.size(); i++) {
            Object redisValue = redisValues.get(i);
            if (redisValue != null) {
                String jsonValue = (String) redisValue;
                T entity = deserialize(jsonValue, clazz);
                result.put(missedKeys.get(i), entity);
                localCache.put(fullMissedKeys.get(i), jsonValue);
            } else {
                dbMissedKeys.add(missedKeys.get(i));
            }
        }
        
        if (dbMissedKeys.isEmpty()) {
            return result;
        }
        
        // 3. 查询数据库
        Map<String, T> dbResult = dbLoader.apply(dbMissedKeys);
        
        // 4. 回填缓存
        for (Map.Entry<String, T> entry : dbResult.entrySet()) {
            String key = entry.getKey();
            T value = entry.getValue();
            String fullKey = buildKey(cacheType, key);
            String jsonValue = serialize(value);
            
            redisTemplate.opsForValue().set(
                    fullKey, 
                    jsonValue, 
                    cacheConfiguration.getRedisCacheExpireHours(), 
                    TimeUnit.HOURS
            );
            localCache.put(fullKey, jsonValue);
            result.put(key, value);
        }
        
        return result;
    }
    
    /**
     * 更新缓存(写入场景)
     */
    public <T> void put(String cacheType, String key, T value) {
        String fullKey = buildKey(cacheType, key);
        String jsonValue = serialize(value);
        
        // 1. 更新Redis
        redisTemplate.opsForValue().set(
                fullKey, 
                jsonValue, 
                cacheConfiguration.getRedisCacheExpireHours(), 
                TimeUnit.HOURS
        );
        
        // 2. 更新LocalCache
        localCache.put(fullKey, jsonValue);
        
        // 3. 通知其他实例刷新LocalCache
        Long id = null;
        Long version = null;
        if (value instanceof MetadataEntity) {
            MetadataEntity entity = (MetadataEntity) value;
            id = entity.getId();
            version = entity.getVersion();
        }
        
        CacheChangeEvent event = new CacheChangeEvent(cacheType, 
                CacheChangeEvent.EventType.UPDATE, key, id);
        event.setVersion(version);
        event.setSourceInstanceId(instanceId);
        
        cacheSyncService.publishCacheChange(event);
        
        log.info("缓存已更新: {}", fullKey);
    }
    
    /**
     * 删除缓存
     */
    public void evict(String cacheType, String key) {
        String fullKey = buildKey(cacheType, key);
        
        // 1. 删除Redis
        redisTemplate.delete(fullKey);
        
        // 2. 删除LocalCache
        localCache.invalidate(fullKey);
        
        // 3. 通知其他实例
        CacheChangeEvent event = new CacheChangeEvent(cacheType, 
                CacheChangeEvent.EventType.DELETE, key, null);
        event.setSourceInstanceId(instanceId);
        
        cacheSyncService.publishCacheChange(event);
        
        log.info("缓存已删除: {}", fullKey);
    }
    
    /**
     * 刷新所有缓存(全量刷新)
     */
    public void refreshAll(String cacheType) {
        // 1. 清空LocalCache
        localCache.invalidateAll();
        
        // 2. 清空Redis(根据前缀批量删除)
        String pattern = buildKey(cacheType, "*");
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        
        // 3. 通知其他实例
        CacheChangeEvent event = new CacheChangeEvent(cacheType, 
                CacheChangeEvent.EventType.REFRESH_ALL, null, null);
        event.setSourceInstanceId(instanceId);
        
        cacheSyncService.publishCacheChange(event);
        
        log.info("所有缓存已刷新: {}", cacheType);
    }
    
    /**
     * 处理缓存变更事件(接收其他实例通知)
     */
    public void handleCacheChangeEvent(CacheChangeEvent event) {
        // 忽略自己发出的事件
        if (instanceId.equals(event.getSourceInstanceId())) {
            return;
        }
        
        log.info("收到缓存变更事件: type={}, eventType={}, key={}", 
                event.getCacheType(), event.getEventType(), event.getKey());
        
        switch (event.getEventType()) {
            case INSERT:
            case UPDATE:
                // 只删除LocalCache,下次查询时会从Redis加载
                if (event.getKey() != null) {
                    String fullKey = buildKey(event.getCacheType(), event.getKey());
                    localCache.invalidate(fullKey);
                }
                break;
                
            case DELETE:
                if (event.getKey() != null) {
                    String fullKey = buildKey(event.getCacheType(), event.getKey());
                    localCache.invalidate(fullKey);
                }
                break;
                
            case REFRESH_ALL:
                localCache.invalidateAll();
                break;
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        CacheStats stats = localCache.stats();
        return String.format("LocalCache统计 - 命中率:%.2f%%, 请求数:%d, 命中数:%d, 未命中数:%d, 加载成功数:%d",
                stats.hitRate() * 100,
                stats.requestCount(),
                stats.hitCount(),
                stats.missCount(),
                stats.loadSuccessCount());
    }
    
    // ========== 辅助方法 ==========
    
    private String buildKey(String cacheType, String key) {
        return CACHE_PREFIX + cacheType + ":" + key;
    }
    
    private <T> String serialize(T value) {
        if (value == null) {
            return NULL_VALUE;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("序列化失败", e);
            throw new RuntimeException("序列化失败", e);
        }
    }
    
    private <T> T deserialize(String json, Class<T> clazz) {
        if (NULL_VALUE.equals(json)) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("反序列化失败: {}", json, e);
            return null;
        }
    }
}

// ===================== 5. 缓存同步服务 =====================

/**
 * 缓存同步服务接口
 */
public interface CacheSyncService {
    /**
     * 发布缓存变更事件
     */
    void publishCacheChange(CacheChangeEvent event);
    
    /**
     * 初始化(启动监听)
     */
    void init();
}

// ===================== 方案A: 基于MQ的实现 =====================

/**
 * 基于MQ的缓存同步实现(以RocketMQ为例)
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "cache.sync.type", havingValue = "mq")
public class MqCacheSyncServiceImpl implements CacheSyncService, InitializingBean {
    
    @Autowired
    private MetadataCacheManager cacheManager;
    
    // 注入MQ生产者和消费者(根据实际MQ框架调整)
    // @Autowired
    // private RocketMQTemplate rocketMQTemplate;
    
    private static final String TOPIC = "CACHE_SYNC_TOPIC";
    
    @Override
    public void publishCacheChange(CacheChangeEvent event) {
        try {
            // 发送MQ消息(示例)
            // rocketMQTemplate.convertAndSend(TOPIC, event);
            
            // 模拟发送
            log.info("发送MQ缓存变更消息: {}", event.getCacheType());
            
            // 实际项目中的实现示例:
            /*
            Message<CacheChangeEvent> message = MessageBuilder
                    .withPayload(event)
                    .setHeader("cacheType", event.getCacheType())
                    .build();
            rocketMQTemplate.send(TOPIC, message);
            */
            
        } catch (Exception e) {
            log.error("发送MQ消息失败", e);
            // 考虑重试或降级方案
        }
    }
    
    @Override
    public void init() {
        // 订阅MQ消息(示例)
        log.info("初始化MQ监听器...");
        
        // 实际项目中使用 @RocketMQMessageListener 注解
        /*
        @RocketMQMessageListener(
            topic = TOPIC,
            consumerGroup = "cache-sync-consumer-group"
        )
        public class CacheSyncConsumer implements RocketMQListener<CacheChangeEvent> {
            @Override
            public void onMessage(CacheChangeEvent event) {
                cacheManager.handleCacheChangeEvent(event);
            }
        }
        */
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }
    
    /**
     * MQ消息消费者(实际使用时单独定义)
     */
    // @Component
    // @RocketMQMessageListener(topic = TOPIC, consumerGroup = "cache-sync-group")
    public class CacheSyncMessageListener { // implements RocketMQListener<CacheChangeEvent>
        
        @Autowired
        private MetadataCacheManager cacheManager;
        
        // @Override
        public void onMessage(CacheChangeEvent event) {
            try {
                log.info("收到MQ缓存同步消息: {}", event);
                
                // 幂等性处理:使用version字段或timestamp判断
                cacheManager.handleCacheChangeEvent(event);
                
            } catch (Exception e) {
                log.error("处理MQ消息失败", e);
                // 消息会重试,注意幂等性
            }
        }
    }
}

// ===================== 方案B: 基于Redis Pub/Sub的实现 =====================

/**
 * 基于Redis Pub/Sub的缓存同步实现
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "cache.sync.type", havingValue = "redis", matchIfMissing = true)
public class RedisPubSubCacheSyncServiceImpl implements CacheSyncService, InitializingBean {
    
    @Autowired
    private MetadataCacheManager cacheManager;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    private static final String CHANNEL = "cache:sync:channel";
    
    @Override
    public void publishCacheChange(CacheChangeEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, event);
            log.debug("发布Redis Pub/Sub消息: {}", event.getCacheType());
        } catch (Exception e) {
            log.error("发布Redis消息失败", e);
        }
    }
    
    @Override
    public void init() {
        // 订阅Redis频道
        RedisMessageListenerContainer container = createRedisMessageListenerContainer();
        container.addMessageListener(new MessageListener() {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                try {
                    String body = new String(message.getBody());
                    ObjectMapper mapper = new ObjectMapper();
                    CacheChangeEvent event = mapper.readValue(body, CacheChangeEvent.class);
                    
                    log.info("收到Redis Pub/Sub消息: {}", event);
                    cacheManager.handleCacheChangeEvent(event);
                    
                } catch (Exception e) {
                    log.error("处理Redis消息失败", e);
                }
            }
        }, new ChannelTopic(CHANNEL));
        
        container.start();
        log.info("Redis Pub/Sub监听器已启动");
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }
    
    private RedisMessageListenerContainer createRedisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisTemplate.getConnectionFactory());
        return container;
    }
}

// ===================== 6. Mapper接口 =====================

/**
 * 元数据Mapper接口
 */
@Mapper
public interface MetadataMapper {
    
    MetadataEntity selectById(@Param("id") Long id);
    
    MetadataEntity selectByCode(@Param("code") String code);
    
    List<MetadataEntity> selectByIds(@Param("ids") List<Long> ids);
    
    List<MetadataEntity> selectByCodes(@Param("codes") List<String> codes);
    
    List<MetadataEntity> selectAll();
    
    int insert(MetadataEntity entity);
    
    int updateById(MetadataEntity entity);
    
    int deleteById(@Param("id") Long id);
}

// ===================== 7. Service层 =====================

/**
 * 元数据Service(业务层)
 */
@Service
@Slf4j
public class MetadataService {
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    @Autowired
    private MetadataCacheManager cacheManager;
    
    private static final String CACHE_TYPE = "metadata";
    
    /**
     * 根据ID查询(走三级缓存)
     */
    public MetadataEntity getById(Long id) {
        return cacheManager.get(
                CACHE_TYPE,
                "id:" + id,
                () -> metadataMapper.selectById(id),
                MetadataEntity.class
        );
    }
    
    /**
     * 根据Code查询(走三级缓存)
     */
    public MetadataEntity getByCode(String code) {
        return cacheManager.get(
                CACHE_TYPE,
                "code:" + code,
                () -> metadataMapper.selectByCode(code),
                MetadataEntity.class
        );
    }
    
    /**
     * 批量查询
     */
    public Map<Long, MetadataEntity> getByIds(List<Long> ids) {
        List<String> keys = ids.stream()
                .map(id -> "id:" + id)
                .collect(Collectors.toList());
        
        return cacheManager.multiGet(
                CACHE_TYPE,
                keys,
                missedKeys -> {
                    List<Long> missedIds = missedKeys.stream()
                            .map(k -> Long.parseLong(k.substring(3)))
                            .collect(Collectors.toList());
                    
                    List<MetadataEntity> entities = metadataMapper.selectByIds(missedIds);
                    return entities.stream()
                            .collect(Collectors.toMap(
                                    e -> "id:" + e.getId(),
                                    e -> e
                            ));
                },
                MetadataEntity.class
        );
    }
    
    /**
     * 新增(更新缓存)
     */
    @Transactional(rollbackFor = Exception.class)
    public void insert(MetadataEntity entity) {
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        entity.setVersion(1L);
        
        // 1. 插入数据库
        metadataMapper.insert(entity);
        
        // 2. 更新缓存
        cacheManager.put(CACHE_TYPE, "id:" + entity.getId(), entity);
        if (entity.getCode() != null) {
            cacheManager.put(CACHE_TYPE, "code:" + entity.getCode(), entity);
        }
        
        log.info("新增元数据成功: id={}", entity.getId());
    }
    
    /**
     * 更新(更新缓存)
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(MetadataEntity entity) {
        entity.setUpdateTime(new Date());
        entity.setVersion(entity.getVersion() + 1); // 乐观锁
        
        // 1. 更新数据库
        int rows = metadataMapper.updateById(entity);
        if (rows == 0) {
            throw new RuntimeException("更新失败,数据可能已被修改");
        }
        
        // 2. 更新缓存
        cacheManager.put(CACHE_TYPE, "id:" + entity.getId(), entity);
        if (entity.getCode() != null) {
            cacheManager.put(CACHE_TYPE, "code:" + entity.getCode(), entity);
        }
        
        log.info("更新元数据成功: id={}", entity.getId());
    }
    
    /**
     * 删除(删除缓存)
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 先查询,获取code用于删除缓存
        MetadataEntity entity = metadataMapper.selectById(id);
        if (entity == null) {
            return;
        }
        
        // 1. 删除数据库
        metadataMapper.deleteById(id);
        
        // 2. 删除缓存
        cacheManager.evict(CACHE_TYPE, "id:" + id);
        if (entity.getCode() != null) {
            cacheManager.evict(CACHE_TYPE, "code:" + entity.getCode());
        }
        
        log.info("删除元数据成功: id={}", id);
    }
    
    /**
     * 全量刷新缓存
     */
    public void refreshAllCache() {
        log.info("开始全量刷新元数据缓存...");
        
        // 1. 清空所有缓存
        cacheManager.refreshAll(CACHE_TYPE);
        
        // 2. 预热缓存(可选)
        List<MetadataEntity> allEntities = metadataMapper.selectAll();
        for (MetadataEntity entity : allEntities) {
            cacheManager.put(CACHE_TYPE, "id:" + entity.getId(), entity);
            if (entity.getCode() != null) {
                cacheManager.put(CACHE_TYPE, "code:" + entity.getCode(), entity);
            }
        }
        
        log.info("全量刷新缓存完成,共预热{}条数据", allEntities.size());
    }
}

// ===================== 8. 定时任务 =====================

/**
 * 缓存定时刷新任务
 */
@Component
@Slf4j
public class CacheRefreshScheduler {
    
    @Autowired
    private MetadataService metadataService;
    
    /**
     * 每天凌晨3点全量刷新缓存
     * 使用分布式锁确保只有一个实例执行
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledRefreshCache() {
        log.info("定时任务触发:开始刷新缓存");
        
        // 使用分布式锁(基于Redis)
        String lockKey = "cache:refresh:lock";
        String lockValue = UUID.randomUUID().toString();
        
        try {
            // 尝试获取锁,30秒超时
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, 30, TimeUnit.SECONDS);
            
            if (Boolean.TRUE.equals(locked)) {
                log.info("获取分布式锁成功,执行缓存刷新");
                metadataService.refreshAllCache();
            } else {
                log.info("其他实例正在执行缓存刷新,跳过");
            }
        } catch (Exception e) {
            log.error("定时刷新缓存失败", e);
        } finally {
            // 释放锁(Lua脚本保证原子性)
            releaseLock(lockKey, lockValue);
        }
    }
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    private void releaseLock(String lockKey, String lockValue) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) else return 0 end";
        
        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(lockKey), lockValue);
    }
}

// ===================== 9. 手工刷新接口 =====================

/**
 * 缓存管理Controller
 */
@RestController
@RequestMapping("/api/cache")
@Slf4j
public class CacheManagementController {
    
    @Autowired
    private MetadataService metadataService;
    
    @Autowired
    private MetadataCacheManager cacheManager;
    
    /**
     * 手工触发全量刷新
     */
    @PostMapping("/refresh")
    public ResponseEntity<String> refreshCache() {
        try {
            log.info("手工触发缓存刷新");
            metadataService.refreshAllCache();
            return ResponseEntity.ok("缓存刷新成功");
        } catch (Exception e) {
            log.error("手工刷新缓存失败", e);
            return ResponseEntity.status(500).body("刷新失败: " + e.getMessage());
        }
    }
    
    /**
     * 刷新指定key的缓存
     */
    @PostMapping("/refresh/{key}")
    public ResponseEntity<String> refreshByKey(@PathVariable String key) {
        try {
            cacheManager.evict("metadata", key);
            return ResponseEntity.ok("缓存项已清除: " + key);
        } catch (Exception e) {
            log.error("清除缓存失败", e);
            return ResponseEntity.status(500).body("清除失败: " + e.getMessage());
        }
    }
    
    /**
     * 查看缓存统计
     */
    @GetMapping("/stats")
    public ResponseEntity<String> getCacheStats() {
        String stats = cacheManager.getCacheStats();
        return ResponseEntity.ok(stats);
    }
}

// ===================== 10. 并发问题解决方案 =====================

/**
 * 缓存并发问题处理工具
 */
@Component
@Slf4j
public class CacheConcurrencyHandler {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // 防止缓存击穿的布隆过滤器(可选)
    private BloomFilter<String> bloomFilter;
    
    @PostConstruct
    public void init() {
        // 初始化布隆过滤器(预估10000条数据,误判率0.01)
        bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(Charset.defaultCharset()),
                10000,
                0.01
        );
    }
    
    /**
     * 防止缓存击穿:分布式锁
     * 当大量并发请求同时查询一个不存在的key时,使用锁保证只有一个线程查询DB
     */
    public <T> T getWithLock(String key, Supplier<T> dbLoader, 
                             Class<T> clazz, MetadataCacheManager cacheManager) {
        String lockKey = "lock:" + key;
        String lockValue = UUID.randomUUID().toString();
        
        try {
            // 尝试获取锁
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
            
            if (Boolean.TRUE.equals(locked)) {
                // 获取锁成功,查询数据库
                T result = dbLoader.get();
                return result;
            } else {
                // 获取锁失败,等待后重试
                Thread.sleep(50);
                // 重新从缓存查询
                return null; // 实际应递归调用或重试逻辑
            }
        } catch (Exception e) {
            log.error("分布式锁处理失败", e);
            return dbLoader.get();
        } finally {
            // 释放锁
            releaseLock(lockKey, lockValue);
        }
    }
    
    /**
     * 防止缓存穿透:布隆过滤器
     * 在查询前先判断key是否可能存在
     */
    public boolean mightContain(String key) {
        return bloomFilter.mightContain(key);
    }
    
    /**
     * 添加key到布隆过滤器
     */
    public void addToBloomFilter(String key) {
        bloomFilter.put(key);
    }
    
    /**
     * 防止缓存雪崩:随机过期时间
     */
    public long getRandomExpireTime(long baseSeconds) {
        // 在基础时间上随机增加0-20%的偏移
        long offset = (long) (baseSeconds * 0.2 * Math.random());
        return baseSeconds + offset;
    }
    
    private void releaseLock(String lockKey, String lockValue) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) else return 0 end";
        
        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(lockKey), lockValue);
    }
}

// ===================== 11. 增强版缓存管理器(带并发控制) =====================

/**
 * 增强版缓存管理器,加入并发控制
 */
@Component
@Slf4j
public class EnhancedMetadataCacheManager {
    
    @Autowired
    private MetadataCacheManager cacheManager;
    
    @Autowired
    private CacheConcurrencyHandler concurrencyHandler;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // 本地锁(防止同一JVM内的重复查询)
    private final ConcurrentHashMap<String, Object> localLocks = new ConcurrentHashMap<>();
    
    /**
     * 防击穿、防穿透的查询方法
     */
    public <T> T getWithProtection(String cacheType, String key, 
                                    Supplier<T> dbLoader, Class<T> clazz) {
        String fullKey = cacheType + ":" + key;
        
        // 1. 布隆过滤器检查(防穿透)
        if (!concurrencyHandler.mightContain(fullKey)) {
            log.warn("布隆过滤器判断key不存在: {}", fullKey);
            return null;
        }
        
        // 2. 正常查询流程
        T result = cacheManager.get(cacheType, key, () -> {
            // 3. 使用分布式锁防止缓存击穿
            return getFromDbWithLock(fullKey, dbLoader);
        }, clazz);
        
        return result;
    }
    
    /**
     * 使用分布式锁从数据库加载
     */
    private <T> T getFromDbWithLock(String fullKey, Supplier<T> dbLoader) {
        // 先尝试本地锁
        Object localLock = localLocks.computeIfAbsent(fullKey, k -> new Object());
        
        synchronized (localLock) {
            // 再尝试分布式锁
            String lockKey = "lock:" + fullKey;
            String lockValue = UUID.randomUUID().toString();
            
            try {
                Boolean locked = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
                
                if (Boolean.TRUE.equals(locked)) {
                    // 获取锁成功,查询数据库
                    log.info("获取分布式锁成功,查询DB: {}", fullKey);
                    T result = dbLoader.get();
                    
                    // 添加到布隆过滤器
                    if (result != null) {
                        concurrencyHandler.addToBloomFilter(fullKey);
                    }
                    
                    return result;
                } else {
                    // 获取锁失败,等待后重试
                    log.info("等待其他线程查询DB: {}", fullKey);
                    Thread.sleep(100);
                    
                    // 再次尝试从Redis读取(可能已被其他线程写入)
                    Object cached = redisTemplate.opsForValue().get("metadata:" + fullKey);
                    if (cached != null) {
                        return (T) cached;
                    }
                    
                    // 仍未命中,直接查询DB
                    return dbLoader.get();
                }
            } catch (Exception e) {
                log.error("分布式锁处理失败", e);
                return dbLoader.get();
            } finally {
                // 释放分布式锁
                releaseLock(lockKey, lockValue);
                // 清理本地锁
                localLocks.remove(fullKey);
            }
        }
    }
    
    private void releaseLock(String lockKey, String lockValue) {
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) else return 0 end";
        
        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(lockKey), lockValue);
    }
}

// ===================== 12. 配置文件示例 =====================

/**
 * application.yml 配置示例
 * 
 * spring:
 *   redis:
 *     host: localhost
 *     port: 6379
 *     password: 
 *     database: 0
 *     lettuce:
 *       pool:
 *         max-active: 8
 *         max-idle: 8
 *         min-idle: 0
 * 
 * cache:
 *   sync:
 *     type: redis  # 可选: mq, redis
 *   local:
 *     max-size: 10000
 *     expire-minutes: 30
 *   redis:
 *     expire-hours: 24
 * 
 * # MQ配置(如果使用MQ方案)
 * rocketmq:
 *   name-server: localhost:9876
 *   producer:
 *     group: cache-sync-producer
 */

// ===================== 13. 使用示例 =====================

/**
 * 使用示例
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
    
    @Autowired
    private MetadataService metadataService;
    
    /**
     * 查询元数据(自动走三级缓存)
     */
    @GetMapping("/{id}")
    public ResponseEntity<MetadataEntity> getById(@PathVariable Long id) {
        MetadataEntity entity = metadataService.getById(id);
        return ResponseEntity.ok(entity);
    }
    
    @GetMapping("/code/{code}")
    public ResponseEntity<MetadataEntity> getByCode(@PathVariable String code) {
        MetadataEntity entity = metadataService.getByCode(code);
        return ResponseEntity.ok(entity);
    }
    
    /**
     * 新增元数据(自动更新缓存)
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestBody MetadataEntity entity) {
        metadataService.insert(entity);
        return ResponseEntity.ok("创建成功");
    }
    
    /**
     * 更新元数据(自动更新缓存)
     */
    @PutMapping
    public ResponseEntity<String> update(@RequestBody MetadataEntity entity) {
        metadataService.update(entity);
        return ResponseEntity.ok("更新成功");
    }
    
    /**
     * 删除元数据(自动删除缓存)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable Long id) {
        metadataService.delete(id);
        return ResponseEntity.ok("删除成功");
    }
}

三、方案对比与选择建议
3.1 方案 A: 基于 MQ 的实现
优点:

? 可靠性高: MQ 自带持久化、重试机制
? 顺序保证: 可通过消息顺序保证缓存更新顺序
? 解耦性好: 各实例独立消费,互不影响
? 扩展性强: 易于添加其他消费者(如审计、监控)
? 削峰填谷: MQ 可缓冲突发流量

缺点:

? 依赖中间件: 需要部署和维护 MQ 集群
? 复杂度高: 需要处理消息堆积、死信等问题
? 延迟较高: 网络传输 + 消息堆积导致延迟增加

适用场景:

系统已有 MQ 基础设施
对一致性要求极高
需要审计缓存变更记录
高并发写入场景


3.2 方案 B: 基于 Redis Pub/Sub 的实现
优点:

? 轻量级: 无需额外中间件,复用 Redis
? 低延迟: 消息传递快,通常 < 10ms
? 实现简单: 代码量少,易于维护
? 成本低: 不增加运维成本

缺点:

? 可靠性差: 消息不持久化,订阅者离线会丢消息
? 无重试机制: 消费失败不会自动重试
? 顺序无保证: 多实例场景下消息顺序可能错乱

适用场景:

小型系统,无 MQ 基础设施
对一致性要求不极端严格
低并发写入场景
预算有限的项目

推荐选择:

? 首选方案 A (MQ): 适用于中大型系统
? 备选方案 B (Redis Pub/Sub): 适用于小型系统或过渡方案

四、并发问题处理方案
4.1 缓存穿透
问题: 查询不存在的数据,导致请求直达数据库
解决方案:
// 1. 布隆过滤器(已在代码中实现)
concurrencyHandler.mightContain(key);

// 2. 缓存空值(设置较短过期时间)
if (dbValue == null) {
    redisTemplate.opsForValue().set(key, "NULL", 5, TimeUnit.MINUTES);
}

4.2 缓存击穿
问题: 热点 key 过期瞬间,大量请求击穿到数据库
解决方案:
// 1. 分布式锁(已在代码中实现)
getFromDbWithLock(fullKey, dbLoader);

// 2. 热点数据永不过期
redisTemplate.persist(hotKey);

// 3. 互斥更新(Guava LoadingCache 自带)

4.3 缓存雪崩
问题: 大量 key 同时过期,导致数据库瞬时压力过大
解决方案:

// 1. 随机过期时间(已在代码中实现)
long expireTime = concurrencyHandler.getRandomExpireTime(3600);

// 2. Redis 集群高可用
// 3. 限流降级

4.4 缓存一致性问题
问题: 多实例更新缓存,可能出现数据不一致
解决方案:

// 1. 乐观锁(基于 version 字段)
entity.setVersion(entity.getVersion() + 1);

// 2. 先更新 DB,再删除缓存(推荐)
metadataMapper.updateById(entity);
cacheManager.evict(cacheType, key);

// 3. 延迟双删(避免脏数据)
cacheManager.evict(cacheType, key);
metadataMapper.updateById(entity);
Thread.sleep(500);
cacheManager.evict(cacheType, key);
```

---

## 五、部署与监控建议

### 5.1 部署架构
```
┌─────────────────────────────────────────┐
│          Nginx / API Gateway            │
└──────────────────┬──────────────────────┘
                   │
        ┌──────────┼──────────┐
        ▼          ▼          ▼
   ┌────────┐ ┌────────┐ ┌────────┐
   │ App-1  │ │ App-2  │ │ App-3  │  (多实例)
   │LocalCache LocalCache LocalCache│
   └───┬────┘ └───┬────┘ └───┬────┘
       │          │          │
       └──────────┼──────────┘
                  ▼
          ┌───────────────┐
          │  Redis Cluster│  (主从 + 哨兵)
          └───────┬───────┘
                  │
          ┌───────────────┐
          │  OpenGauss DB │  (主备)
          └───────────────┘
          
5.2 监控指标
关键指标:

LocalCache 命中率 (目标 > 90%)
Redis 命中率 (目标 > 80%)
平均查询耗时 (目标 < 50ms)
缓存更新延迟 (目标 < 100ms)
MQ 消息堆积数量 (告警阈值 < 1000)

监控代码示例:
@Scheduled(fixedRate = 60000)
public void reportCacheMetrics() {
    CacheStats stats = localCache.stats();
    log.info("缓存统计: 命中率={}, 请求数={}, 驱逐数={}", 
            stats.hitRate(), 
            stats.requestCount(), 
            stats.evictionCount());
}

六、最佳实践总结
? Do's (推荐做法)

统一封装: 使用 MetadataCacheManager 统一管理缓存访问
先更新 DB: 写操作先更新数据库,保证数据持久化
异步通知: 使用 MQ/Pub-Sub 异步通知缓存更新,避免阻塞
幂等设计: 消息处理必须幂等,通过 version 字段去重
监控告警: 监控缓存命中率、延迟等关键指标
预热策略: 系统启动时预热热点数据

? Don'ts (避免做法)

禁止同步调用: 写操作不要同步刷新其他实例缓存(会阻塞)
避免大 value: 单个缓存 value 不超过 1MB
避免长过期: LocalCache 过期时间不宜过长(推荐 30 分钟)
避免热 key: 通过分片或本地缓存分散热点
避免循环依赖: Service 层不要直接依赖 CacheManager


七、生产环境检查清单

 Redis 配置主从 + 哨兵/集群模式
 开启 Redis 持久化 (AOF + RDB)
 配置分布式锁超时时间
 设置 MQ 消息重试次数和死信队列
 配置 LocalCache 最大容量和过期策略
 实现缓存监控和告警
 压测验证缓存命中率和性能
 准备缓存降级预案(如 Redis 宕机)
 配置日志级别和审计日志
 文档化缓存 key 设计规范

