优雅的多级缓存刷新方案(极简Redis版本)
既然Redis只支持基础操作，我为您设计一个基于版本号 + 心跳检测的轻量级方案。
? 核心设计思路

版本号机制：Redis存储每个缓存类型的版本号
定时心跳：各实例定时检查版本号变化
懒加载刷新：版本变化时清空LocalCache，下次访问时自动重新加载

? 完整实现方案
1. 缓存版本信息实体
import java.io.Serializable;

/**
 * 缓存刷新请求
 */
public class CacheRefreshRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /** 缓存类型(表名) */
    private String cacheType;
    
    /** 操作类型: REFRESH_ALL(全量刷新), EVICT(删除单个), UPDATE(更新单个) */
    private String operation;
    
    /** 具体的key(用于单个操作) */
    private String key;
    
    /** 更新的值(用于UPDATE操作) */
    private Object value;
    
    public CacheRefreshRequest() {}
    
    public CacheRefreshRequest(String cacheType, String operation) {
        this.cacheType = cacheType;
        this.operation = operation;
    }
    
    // getter/setter...
}

2. 多级缓存管理器(核心组件)
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存管理器
 */
@Component("multiLevelCacheManager")
public class MultiLevelCacheManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MultiLevelCacheManager.class);
    
    /** Redis中存储版本号的Key前缀 */
    private static final String VERSION_KEY_PREFIX = "cache:version:";
    
    /** Redis中存储数据的Key前缀 */
    private static final String DATA_KEY_PREFIX = "cache:data:";
    
    /** Redis缓存过期时间(小时) */
    private static final int REDIS_EXPIRE_HOURS = 2;
    
    @Autowired
    private RedisClient redisClient; // 你们的Redis客户端
    
    @Autowired
    private MetadataMapper metadataMapper; // 数据库Mapper
    
    /** Guava本地缓存 */
    private Cache<String, Object> localCache;
    
    /** 本地维护的版本号映射 cacheType -> version */
    private final Map<String, Long> localVersionMap = new ConcurrentHashMap<>();
    
    /** 实例启动时间戳(用于日志追踪) */
    private long instanceStartTime;
    
    @PostConstruct
    public void init() {
        // 初始化本地缓存
        localCache = CacheBuilder.newBuilder()
                .maximumSize(1000)                          // 最大1000条
                .expireAfterWrite(30, TimeUnit.MINUTES)     // 写入后30分钟过期
                .recordStats()                              // 记录统计信息
                .build();
        
        instanceStartTime = System.currentTimeMillis();
        logger.info("多级缓存管理器初始化完成, instanceStartTime={}", instanceStartTime);
    }
    
    /**
     * 三级获取缓存数据
     * @param cacheType 缓存类型(表名)
     * @param key 缓存key
     * @return 缓存数据
     */
    public Object get(String cacheType, String key) {
        String fullKey = buildFullKey(cacheType, key);
        
        // Level 1: LocalCache
        Object value = localCache.getIfPresent(fullKey);
        if (value != null) {
            logger.debug("命中LocalCache: {}", fullKey);
            return value;
        }
        
        // Level 2: Redis
        String redisDataKey = DATA_KEY_PREFIX + fullKey;
        value = redisClient.get(redisDataKey);
        if (value != null) {
            logger.debug("命中Redis: {}", fullKey);
            // 回填LocalCache
            localCache.put(fullKey, value);
            return value;
        }
        
        // Level 3: Database
        logger.info("缓存未命中，从数据库加载: {}", fullKey);
        value = loadFromDatabase(cacheType, key);
        
        if (value != null) {
            // 写入LocalCache
            localCache.put(fullKey, value);
            
            // 写入Redis
            redisClient.set(redisDataKey, value, REDIS_EXPIRE_HOURS * 3600);
        }
        
        return value;
    }
    
    /**
     * 批量获取(可选，提升性能)
     */
    public Map<String, Object> batchGet(String cacheType, List<String> keys) {
        Map<String, Object> result = new HashMap<>();
        
        for (String key : keys) {
            Object value = get(cacheType, key);
            if (value != null) {
                result.put(key, value);
            }
        }
        
        return result;
    }
    
    /**
     * 更新缓存(单个)
     * @param cacheType 缓存类型
     * @param key 缓存key
     * @param value 新值
     */
    public void put(String cacheType, String key, Object value) {
        String fullKey = buildFullKey(cacheType, key);
        
        // 1. 更新LocalCache
        localCache.put(fullKey, value);
        
        // 2. 更新Redis
        String redisDataKey = DATA_KEY_PREFIX + fullKey;
        redisClient.set(redisDataKey, value, REDIS_EXPIRE_HOURS * 3600);
        
        // 3. 递增版本号(通知其他实例)
        incrementVersion(cacheType);
        
        logger.info("缓存已更新: {}", fullKey);
    }
    
    /**
     * 删除缓存(单个)
     * @param cacheType 缓存类型
     * @param key 缓存key
     */
    public void evict(String cacheType, String key) {
        String fullKey = buildFullKey(cacheType, key);
        
        // 1. 删除LocalCache
        localCache.invalidate(fullKey);
        
        // 2. 删除Redis
        String redisDataKey = DATA_KEY_PREFIX + fullKey;
        redisClient.delete(redisDataKey);
        
        // 3. 递增版本号
        incrementVersion(cacheType);
        
        logger.info("缓存已删除: {}", fullKey);
    }
    
    /**
     * 刷新指定类型的所有缓存
     * @param cacheType 缓存类型
     */
    public void refreshAll(String cacheType) {
        logger.info("开始全量刷新缓存: {}", cacheType);
        
        // 1. 清空LocalCache中该类型的所有数据
        clearLocalCacheByType(cacheType);
        
        // 2. 清空Redis中该类型的所有数据(可选)
        // 由于Redis只支持基础操作，这里简单处理：
        // 方案A: 不删除Redis，让其自然过期
        // 方案B: 如果key命名规范，可以批量删除
        clearRedisCacheByType(cacheType);
        
        // 3. 递增版本号(关键步骤)
        incrementVersion(cacheType);
        
        logger.info("全量刷新完成: {}", cacheType);
    }
    
    /**
     * 定时检查版本号并同步(核心方法)
     */
    public void checkAndSyncVersion() {
        // 获取所有需要监控的缓存类型(建议配置化)
        String[] cacheTypes = getCacheTypes();
        
        for (String cacheType : cacheTypes) {
            try {
                // 1. 获取Redis中的远程版本号
                Long remoteVersion = getRemoteVersion(cacheType);
                
                // 2. 获取本地版本号
                Long localVersion = localVersionMap.get(cacheType);
                
                // 3. 首次启动，初始化本地版本号
                if (localVersion == null) {
                    localVersionMap.put(cacheType, remoteVersion != null ? remoteVersion : 0L);
                    logger.info("初始化本地版本号: {}={}", cacheType, remoteVersion);
                    continue;
                }
                
                // 4. 版本号不一致，需要刷新本地缓存
                if (remoteVersion != null && !remoteVersion.equals(localVersion)) {
                    logger.warn("检测到版本变化: {} [本地: {} -> 远程: {}]", 
                            cacheType, localVersion, remoteVersion);
                    
                    // 清空本地缓存
                    clearLocalCacheByType(cacheType);
                    
                    // 更新本地版本号
                    localVersionMap.put(cacheType, remoteVersion);
                    
                    logger.info("本地缓存已同步: {}", cacheType);
                }
                
            } catch (Exception e) {
                logger.error("版本同步失败: " + cacheType, e);
            }
        }
    }
    
    /**
     * 递增版本号(修改缓存时调用)
     */
    private void incrementVersion(String cacheType) {
        String versionKey = VERSION_KEY_PREFIX + cacheType;
        
        try {
            // 获取当前版本号
            Long currentVersion = getRemoteVersion(cacheType);
            Long newVersion = (currentVersion != null ? currentVersion : 0L) + 1;
            
            // 设置新版本号(永久保存或设置较长过期时间)
            redisClient.set(versionKey, String.valueOf(newVersion), 7 * 24 * 3600);
            
            // 同步更新本地版本号
            localVersionMap.put(cacheType, newVersion);
            
            logger.info("版本号已递增: {} -> {}", cacheType, newVersion);
            
        } catch (Exception e) {
            logger.error("递增版本号失败: " + cacheType, e);
        }
    }
    
    /**
     * 获取远程版本号
     */
    private Long getRemoteVersion(String cacheType) {
        String versionKey = VERSION_KEY_PREFIX + cacheType;
        Object version = redisClient.get(versionKey);
        
        if (version == null) {
            return 0L;
        }
        
        return Long.parseLong(version.toString());
    }
    
    /**
     * 清空本地缓存中指定类型的数据
     */
    private void clearLocalCacheByType(String cacheType) {
        String prefix = cacheType + ":";
        localCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
        logger.debug("已清空LocalCache: {}", cacheType);
    }
    
    /**
     * 清空Redis中指定类型的数据(可选实现)
     */
    private void clearRedisCacheByType(String cacheType) {
        // 由于Redis功能受限，这里提供两种方案：
        
        // 方案1: 不删除，让数据自然过期(推荐，简单可靠)
        logger.debug("Redis缓存将自然过期: {}", cacheType);
        
        // 方案2: 如果有批量删除能力，可以实现
        // 需要维护一个Set记录所有key，然后批量删除
        // 但这会增加复杂度，不推荐
    }
    
    /**
     * 从数据库加载数据
     */
    private Object loadFromDatabase(String cacheType, String key) {
        try {
            // 根据cacheType路由到不同的Mapper方法
            if ("metadata_config".equals(cacheType)) {
                return metadataMapper.selectConfigByKey(key);
            } else if ("metadata_dict".equals(cacheType)) {
                return metadataMapper.selectDictByKey(key);
            }
            // ... 其他表的处理
            
            logger.warn("未知的?acheType: {}", cacheType);
            return null;
            
        } catch (Exception e) {
            logger.error("从数据库加载失败: {}:{}", cacheType, key, e);
            return null;
        }
    }
    
    /**
     * 构建完整的缓存key
     */
    private String buildFullKey(String cacheType, String key) {
        return cacheType + ":" + key;
    }
    
    /**
     * 获取需要监控的缓存类型列表(建议配置化)
     */
    private String[] getCacheTypes() {
        // 方案1: 硬编码(简单直接)
        return new String[]{"metadata_config", "metadata_dict"};
        
        // 方案2: 从配置读取(更灵活)
        // return cacheConfig.getCacheTypes().toArray(new String[0]);
    }
    
    /**
     * 获取缓存统计信息(用于监控)
     */
    public String getCacheStats() {
        return String.format("LocalCache统计: %s, 版本信息: %s", 
                localCache.stats(), localVersionMap);
    }
    
    /**
     * 获取本地版本信息(用于调试)
     */
    public Map<String, Long> getLocalVersionMap() {
        return new ConcurrentHashMap<>(localVersionMap);
    }
}

3. 定时任务 - 版本同步心跳
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 缓存版本同步定时任务
 * 建议配置: 每30秒执行一次 (cron: 0/30 * * * * ?)
 */
@Component("cacheVersionSyncTask")
public class CacheVersionSyncTask implements ITimerTask {
    
    private static final Logger logger = LoggerFactory.getLogger(CacheVersionSyncTask.class);
    
    @Autowired
    private MultiLevelCacheManager cacheManager;
    
    @Override
    public void executeOnTime(Map<String, String> parameters) throws ApplicationException {
        try {
            cacheManager.checkAndSyncVersion();
        } catch (Exception e) {
            logger.error("缓存版本同步失败", e);
            // 不抛出异常，避免影响定时任务继续执行
        }
    }
}

4. 异步刷新处理器
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * 缓存异步刷新处理器
 */
@Component("cacheAsyncRefreshHandler")
public class CacheAsyncRefreshHandler implements IAsyncProcessHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(CacheAsyncRefreshHandler.class);
    
    @Autowired
    private MultiLevelCacheManager cacheManager;
    
    @Override
    public AjaxMessageVo handlerProcess(Serializable context) throws ApplicationException {
        if (!(context instanceof CacheRefreshRequest)) {
            return AjaxMessageVo.error("无效的请求参数");
        }
        
        CacheRefreshRequest request = (CacheRefreshRequest) context;
        
        try {
            logger.info("开始异步刷新缓存: type={}, operation={}, key={}", 
                    request.getCacheType(), request.getOperation(), request.getKey());
            
            String operation = request.getOperation();
            
            if ("REFRESH_ALL".equals(operation)) {
                // 全量刷新
                cacheManager.refreshAll(request.getCacheType());
                
            } else if ("EVICT".equals(operation)) {
                // 删除单个
                cacheManager.evict(request.getCacheType(), request.getKey());
                
            } else if ("UPDATE".equals(operation)) {
                // 更新单个
                cacheManager.put(request.getCacheType(), request.getKey(), request.getValue());
                
            } else {
                logger.warn("未知的操作类型: {}", operation);
                return AjaxMessageVo.error("未知的操作类型: " + operation);
            }
            
            return AjaxMessageVo.success("缓存刷新成功");
            
        } catch (Exception e) {
            logger.error("异步刷新缓存失败", e);
            return AjaxMessageVo.error("缓存刷新失败: " + e.getMessage());
        }
    }
}

5. 业务服务层(使用示例)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 元数据服务
 */
@Service
public class MetadataService {
    
    private static final String CACHE_TYPE_CONFIG = "metadata_config";
    private static final String CACHE_TYPE_DICT = "metadata_dict";
    
    @Autowired
    private MultiLevelCacheManager cacheManager;
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    @Autowired
    private AsyncTaskUtil asyncTaskUtil;
    
    /**
     * 查询配置(走三级缓存)
     */
    public MetadataConfig getConfig(String configKey) {
        return (MetadataConfig) cacheManager.get(CACHE_TYPE_CONFIG, configKey);
    }
    
    /**
     * 查询字典(走三级缓存)
     */
    public MetadataDict getDict(String dictCode) {
        return (MetadataDict) cacheManager.get(CACHE_TYPE_DICT, dictCode);
    }
    
    /**
     * 更新配置(推荐：异步刷新)
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(MetadataConfig config) throws ApplicationException {
        // 1. 更新数据库
        metadataMapper.updateConfig(config);
        
        // 2. 异步刷新缓存(推荐方式，不阻塞主流程)
        CacheRefreshRequest request = new CacheRefreshRequest(CACHE_TYPE_CONFIG, "UPDATE");
        request.setKey(config.getConfigKey());
        request.setValue(config);
        
        asyncTaskUtil.invokeAsync("cacheAsyncRefreshHandler", request);
    }
    
    /**
     * 更新配置(可选：同步刷新)
     * 适用于对实时性要求极高的场景
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfigSync(MetadataConfig config) {
        // 1. 更新数据库
        metadataMapper.updateConfig(config);
        
        // 2. 同步刷新缓存
        cacheManager.put(CACHE_TYPE_CONFIG, config.getConfigKey(), config);
    }
    
    /**
     * 批量更新后刷新所有缓存
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateConfig(List<MetadataConfig> configs) throws ApplicationException {
        // 1. 批量更新数据库
        metadataMapper.batchUpdateConfig(configs);
        
        // 2. 异步刷新所有缓存
        CacheRefreshRequest request = new CacheRefreshRequest(CACHE_TYPE_CONFIG, "REFRESH_ALL");
        asyncTaskUtil.invokeAsync("cacheAsyncRefreshHandler", request);
    }
    
    /**
     * 删除配置
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(String configKey) throws ApplicationException {
        // 1. 删除数据库
        metadataMapper.deleteConfig(configKey);
        
        // 2. 异步删除缓存
        CacheRefreshRequest request = new CacheRefreshRequest(CACHE_TYPE_CONFIG, "EVICT");
        request.setKey(configKey);
        
        asyncTaskUtil.invokeAsync("cacheAsyncRefreshHandler", request);
    }
    
    /**
     * 手动刷新所有配置缓存(提供给运维或管理界面)
     */
    public void manualRefreshAllConfig() throws ApplicationException {
        CacheRefreshRequest request = new CacheRefreshRequest(CACHE_TYPE_CONFIG, "REFRESH_ALL");
        asyncTaskUtil.invokeAsync("cacheAsyncRefreshHandler", request);
    }
}

6. 管理接口(可选，用于监控和手动刷新)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 缓存管理接口
 */
@RestController
@RequestMapping("/api/cache")
public class CacheManagementController {
    
    @Autowired
    private MultiLevelCacheManager cacheManager;
    
    @Autowired
    private AsyncTaskUtil asyncTaskUtil;
    
    /**
     * 获取缓存统计信息
     */
    @GetMapping("/stats")
    public AjaxMessageVo getCacheStats() {
        String stats = cacheManager.getCacheStats();
        return AjaxMessageVo.success(stats);
    }
    
    /**
     * 获取版本信息
     */
    @GetMapping("/versions")
    public AjaxMessageVo getVersions() {
        Map<String, Long> versions = cacheManager.getLocalVersionMap();
        return AjaxMessageVo.success(versions);
    }
    
    /**
     * 手动刷新指定类型的缓存
     */
    @PostMapping("/refresh/{cacheType}")
    public AjaxMessageVo refreshCache(@PathVariable String cacheType) throws ApplicationException {
        CacheRefreshRequest request = new CacheRefreshRequest(cacheType, "REFRESH_ALL");
        asyncTaskUtil.invokeAsync("cacheAsyncRefreshHandler", request);
        
        return AjaxMessageVo.success("刷新请求已提交");
    }
    
    /**
     * 手动触发版本同步检查
     */
    @PostMapping("/sync")
    public AjaxMessageVo syncVersion() {
        cacheManager.checkAndSyncVersion();
        return AjaxMessageVo.success("版本同步完成");
    }
}
```

---

## ? 部署配置

### 1. **定时任务配置**
在定时任务配置中心添加:
```
Bean名称: cacheVersionSyncTask
Cron表达式: 0/30 * * * * ?  (每30秒执行一次)
说明: 缓存版本心跳检测
状态: 启用

2. application.yml配置(可选)

# 缓存配置
cache:
  local:
    max-size: 1000
    expire-minutes: 30
  redis:
    expire-hours: 2
  sync:
    interval-seconds: 30
  types:
    - metadata_config
    - metadata_dict
```

---

## ? 方案优势对比

| 特性 | 本方案 | Pub/Sub方案 | 说明 |
|------|--------|-------------|------|
| **Redis依赖** | ? 最小化 | ? 需要额外功能 | 仅需get/set |
| **实时性** | ? 30秒内 | ? 秒级 | 可接受的延迟 |
| **实现复杂度** | ? 简单 | ? 中等 | 无订阅机制 |
| **资源消耗** | ? 极低 | ? 需要连接池 | 仅版本号检查 |
| **故障恢复** | ? 自动 | ? 需重连逻辑 | 定时任务保证 |
| **扩展性** | ? 优秀 | ? 优秀 | 都支持水平扩展 |

---

## ? 工作流程图
```
[修改元数据] 
    ↓
[更新数据库] 
    ↓
[异步刷新Handler] → [更新本实例缓存] + [递增Redis版本号]
    ↓
[30秒后其他实例定时任务] → [检测版本号变化] → [清空LocalCache]
    ↓
[下次查询] → [LocalCache未命中] → [从Redis/DB重新加载]

? 核心要点
? 优雅之处

最小化Redis依赖：只用get/set，不需要特殊功能
懒加载机制：清空缓存而非预加载，避免启动风暴
最终一致性：30秒内完成同步，满足低频修改场景
零侵入性：利用现有的定时任务和异步能力

? 注意事项

延迟可接受性：30秒延迟，不适合高实时性场景
版本号持久化：建议设置7天过期，避免Redis重启后丢失
监控告警：关注版本同步失败日志
灰度发布：建议先在单个cacheType测试

? 调优建议

缩短同步间隔：如需更快同步，改为10秒或15秒
增加立即刷新：关键修改后直接调用 cacheManager.refreshAll()
分类管理：不同表设置不同同步频率