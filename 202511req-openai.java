高性能并发聚合方案（基于你们现有能力：Spring Boot + JDK8 + 自家异步/线程池/定时接口）

下面给出端到端的设计思路、关键点、并附带可直接落地的示例代码片段（可作为骨架）。目标满足：高性能、可扩展、易维护、线程安全、内存友好、短响应。
1. 总体架构（组件与责任划分）

Controller

接收 MetricQueryVO，做轻量校验，调用 Service 层（同步返回最终 List<MeasureDataVO>）。

MetricAggregationService（核心）

构造 Context（把 MetricQueryVO、Metadata、运行时参数等放进去），从 Redis 加载 Metadata。

根据 metadata + query 构造要调用的下游 API 列表（每个 API/源对应一个 ApiCallDescriptor，包含是否分页、page-size、构造参数方法、解析器、过滤器等）。

为分页接口：先同步请求第一页得到总页数，再并行提交剩余页（受并发限流控制）。

对每个到达的页（或一次性非分页响应）流式处理：先过滤 -> 转换成统一的 domain model -> 立即聚合到并发结构（不要先把所有原始对象收集到内存）。

等待所有任务完成（CompletableFuture.allOf(...) 或基于 ItaskExecutorService 的返回 Future），返回聚合结果。

ApiClient 层（每种外部API一个实现）

负责调用外部 API、解析响应为实体 T（可以是泛型），并负责分页逻辑的最小化（返回 page info：totalPages/nextCursor 等）。

Filter/Transformer 插件化

每个 API/源可注入 Filter<T>、Transformer<T,StandardMeasure>。便于扩展/维护。

Aggregator

并发安全地把 StandardMeasure 聚合成 Map<periodId, Map<metricCode+domainCode+measureCode, List<MeasureObject>>>（最终构造 List<MeasureDataVO>）。

使用 ConcurrentHashMap + ConcurrentLinkedQueue 或 compute 原子操作以保证线程安全并减少锁。

限流/熔断/重试/降级（生产必备）

使用 Resilience（如 resilience4j，或自研简单限流/重试），对慢/失败的下游做重试与快速失败策略，避免线程池耗尽。

监控/埋点

每个 API 调用耗时、成功/失败率、返回大小、队列长度、线程池利用率、内存占用等。

2. 并发与内存策略（关键要点）

分页并行策略

先同步请求第一页拿到总页数/totalCount；若 pages>1，按可控并发数并行请求剩余页（例如 maxParallelPages = min(totalPages-1, configuredParallelism)）。

对非常多页（比如上千页），不要同时提交所有页，使用 Semaphore 或线程池队列做窗口（滑动并发）以控制内存与下游压强。

流式处理（避免把所有原始数据保留）

每页请求到后立即：过滤 -> 转换 -> 聚合入并发 Map；不要把原始 List<T> 放到全局集合里。

并发聚合数据结构

使用 ConcurrentHashMap<Key, ConcurrentLinkedQueue<Value>>。插入使用 computeIfAbsent + offer（非阻塞）。

或使用 ConcurrentHashMap.compute() 做合并（需要注意 compute 的函数会被并发调用但具有原子性）。

线程安全集合

对于每个 key 的 List，使用 ConcurrentLinkedQueue 在最后转换为 List 返回前再做排序/去重。

如果要对每个 key 做复杂合并（累加、平均等），使用 LongAdder/DoubleAdder，或自定义线程安全聚合容器（原子字段）。

内存释放

及时丢弃中间对象（尽量避免大临时集合），分页处理完就 GC 会回收。避免全量合并前先把所有 page 的实体全部存在堆上。

线程池配置

线程池大小需基于 I/O / CPU 比例（调用网络 I/O，use larger pool but bounded queue）。确保使用你们的 ItaskExecutorService，并配置合理队列/拒绝策略。

3. 错误处理与超时

每次外部调用设置超时（连接/读），并在异步 Future 上设置全局超时（避免挂死）。

对关键 API 做重试（指数退避），对非关键做降级（返回空或缓存数据）。

当任一关键 API连续失败达到阈值，触发熔断，快速失败并记录告警。

4. 可扩展点（插件化）

ApiCallDescriptor（包含 Filter、Transformer、PagingStrategy、Parser）→ 新 API 只需新增 descriptor 和 client 实现。

Filter<T> 接口：boolean accept(MetricQueryVO, Metadata, Context, T)。

Transformer<T, StandardMeasure>：把不同 T 统一成 StandardMeasure（包含 periodId、metricCode、domainCode、measureCode、value、currency、unit 等）。

5. 关键代码片段（骨架，便于落地）

下面示例以简洁可读为主，省略部分异常类与工具类实现。注意：用到的公司接口 ItaskExecutorService、asyncTaskUtil 等按你们已有实现替换接入点。

// domain classes (简化)
public class MetricQueryVO {
    Set<String> metricCodes;
    Set<String> periodIds;
    Set<String> orgCodes;
    Set<String> orgLevels;
    Set<String> domainCodes;
    Integer senceType;
    // getters/setters
}

public class Context {
    private MetricQueryVO query;
    private Metadata metadata;
    private Map<String,Object> runtime = new HashMap<>();
    // getters/setters
}

public class StandardMeasure {
    String periodId;
    String metricCode;
    String domainCode;
    String measureCode;
    BigDecimal value;
    String currency;
    String unit;
    // plus other attrs
}

public class MeasureDataVO {
    String periodId;
    Map<String, List<StandardMeasure>> dataMap; // key: metric+domain+measure
}


// Filter 接口
public interface IDataFilter<T> {
    boolean accept(MetricQueryVO query, Metadata meta, Context ctx, T item);
}

// Transformer 接口
public interface ITransformer<T> {
    StandardMeasure transform(MetricQueryVO query, Metadata meta, Context ctx, T item);
}


// ApiCallDescriptor：描述一个外部数据源
public class ApiCallDescriptor<T> {
    String name;
    boolean paged;
    Function<Integer, Object> buildRequestForPage; // or more complex builder
    BiFunction<Object, String, List<T>> callApi; // (request,pageToken)->List<T> or page wrapper
    IDataFilter<T> filter;
    ITransformer<T> transformer;
    // plus timeouts, rateLimit info, etc
}


// Aggregator：并发安全聚合
public class Aggregator {
    // periodId -> (key -> queue of StandardMeasure)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>>> store = new ConcurrentHashMap<>();

    public void aggregate(StandardMeasure m) {
        // outer map: period
        ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>> byPeriod
            = store.computeIfAbsent(m.getPeriodId(), p -> new ConcurrentHashMap<>());
        String key = m.getMetricCode() + "|" + m.getDomainCode() + "|" + m.getMeasureCode();
        ConcurrentLinkedQueue<StandardMeasure> q = byPeriod.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        q.offer(m);
    }

    public List<MeasureDataVO> toResult() {
        List<MeasureDataVO> out = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>>> e : store.entrySet()) {
            MeasureDataVO vo = new MeasureDataVO();
            vo.setPeriodId(e.getKey());
            Map<String, List<StandardMeasure>> map = new HashMap<>();
            e.getValue().forEach((k, q) -> {
                List<StandardMeasure> list = new ArrayList<>();
                StandardMeasure item;
                while ((item = q.poll()) != null) {
                    list.add(item);
                }
                map.put(k, list);
            });
            vo.setDataMap(map);
            out.add(vo);
        }
        return out;
    }
}


// Service 主流程（伪代码）
@Service
public class MetricAggregationService {

    @Autowired ItaskExecutorService taskExecutorService;
    @Autowired RedisTemplate redisTemplate; // for metadata
    @Autowired List<ApiCallDescriptor<?>> apiDescriptors; // 每个descriptor代表一个下游source

    public List<MeasureDataVO> query(MetricQueryVO query) {
        Context ctx = buildContext(query);
        // load metadata from redis once
        ctx.setMetadata(loadMetadataFromRedis());

        Aggregator aggregator = new Aggregator();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (ApiCallDescriptor desc : apiDescriptors) {
            if (!shouldCall(desc, query, ctx)) continue;

            if (desc.isPaged()) {
                // 1) 请求第一页（同步或短超时），获取页信息（totalPages）
                PageResult first = callPage(desc, 1, ctx);
                processPageItems(first.getItems(), desc, query, ctx, aggregator);

                int total = first.getTotalPages();
                int maxParallel = Math.min(total - 1, configuredParallelPages());
                // 使用for循环提交剩余页但控制并发（使用 Semaphore）
                Semaphore sem = new Semaphore(maxParallel);
                for (int p = 2; p <= total; p++) {
                    final int page = p;
                    sem.acquireUninterruptibly();
                    CompletableFuture<Void> f = taskExecutorService.submitTask(() -> {
                        try {
                            PageResult pr = callPage(desc, page, ctx);
                            processPageItems(pr.getItems(), desc, query, ctx, aggregator);
                        } finally {
                            sem.release();
                        }
                        return null;
                    }, desc.getName() + "-page-" + page);
                    futures.add(f);
                }
            } else {
                // non-paged: submit one async task
                CompletableFuture<Void> f = taskExecutorService.submitTask(() -> {
                    List<?> items = callNonPaged(desc, ctx);
                    processPageItems(items, desc, query, ctx, aggregator);
                    return null;
                }, desc.getName());
                futures.add(f);
            }
        }

        // wait all with timeout
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(globalTimeoutSec(), TimeUnit.SECONDS);

        return aggregator.toResult();
    }

    private void processPageItems(List items, ApiCallDescriptor desc, MetricQueryVO q, Context ctx, Aggregator agg) {
        for (Object t : items) {
            if (!desc.getFilter().accept(q, ctx.getMetadata(), ctx, t)) continue;
            StandardMeasure sm = desc.getTransformer().transform(q, ctx.getMetadata(), ctx, t);
            if (sm == null) continue;
            agg.aggregate(sm);
        }
    }
}


注：上面用到了 ItaskExecutorService.submitTask(Supplier<T>,String)，该接口返回 CompletableFuture<T>（你给出的接口里拼写 CompletableFutrue 我按标准 CompletableFuture 使用）。

6. 性能调优与实战建议

并发度控制：不要盲目并行所有页；对每个数据源设置并发上限（基于下游承受能力与网络带宽）。

批量与压缩：如果下游支持大页，优先使用较大 pageSize 减少请求次数（在可接受内存范围内）。

HTTP 客户端：使用连接池（Apache HttpClient / OkHttp），复用连接，短超时设置。

避免大对象短时间峰值：若某些请求会返回巨大 payload，分流或摄取摘要（只取必要字段）或后台预聚合缓存。

缓存热点：对常见组合（metric+period+org）在 Redis 做二级缓存，命中则直接返回，定时刷新/异步刷新。

监控与回溯：记录每个 page 的 size/time/error，方便定位慢接口/大 payload 带来的瓶颈。

垃圾回收（GC）调优：大量短生命对象（每页items）时，选择适当堆大小与GC策略；JDK8上常见 CMS / G1（若可选）配置。

内存估算：对单条 StandardMeasure 大小估算，乘以并发最大条数估算峰值内存，确保不超出 JVM 堆。

7. 可维护性与扩展

增加新数据源：只需新增 ApiCallDescriptor + client + transformer + filter。

规则变动：把过滤/清洗逻辑放在策略类/脚本（如可热部署的 Groovy/JS），避免代码改动频繁发布。

单元/集成测试：对每个 ApiClient/Transformer/Filter 做单测；对 Aggregator 做并发压力测试（模拟并发插入验证无竞态）。

日志与示踪：每次聚合记录 traceId（从请求头传入），将 traceId 传递到所有异步任务，便于链路追踪。

8. 小结（执行要点）

先构造 Context + 从 Redis 读取 Metadata（一次性）。

按 data-source 构造任务（分页先取第一页，再并行剩余页，受并发窗口控制）。

每页到手后立即：过滤 -> transformer -> 聚合 入并发安全结构（避免保存原始大集合）。

使用 ItaskExecutorService.submitTask(Supplier, info) 返回的 CompletableFuture 收集任务并 allOf 等待（带超时）。

增加熔断/限流/重试策略，监控与告警。

如果你愿意，我可以（二选一）立刻给出：

一个更完整的 MetricAggregationService 可编译示例（含分页并行的完整实现、超时/异常处理、Semaphore 并发窗口）；或者

为你们某个真实下游 API（你给出接口样例）写一个 ApiCallDescriptor + Transformer + Filter 的具体实现和单元测试样例。

你选其一我就把可直接拷贝到工程的完整代码写出来（不需要你再回答任何问题）。

回复 1
下面给你一个可直接拷贝到工程、可编译运行的完整骨架实现（基于你公司现有能力：ItaskExecutorService.submitTask(Supplier<T>, String) 返回 CompletableFuture<T>，使用 JDK8 + Spring Boot）。实现要点：

支持：分页先取第一页 -> 并行抓取剩余页（并发窗口 Semaphore 控制）

每页到手就处理：过滤 -> 转换 -> 并发安全聚合（避免保存原始大集合）

全局等待：CompletableFuture.allOf(...).get(globalTimeout, TimeUnit.SECONDS)（JDK8安全方案）

错误/超时处理、释放 Semaphore、日志占位、可扩展的 ApiCallDescriptor/IDataFilter/ITransformer 插件化

代码尽量完整，包含示例 ApiClient / Descriptor / Aggregator / DTOs

说明：下面的代码为“骨架实现/参考实现”。你需要把真实的 HTTP 调用、Redis 元数据加载、异常类、日志框架替换为你们生产代码中的实现，但整体流程与线程安全控制已完整示例化，可直接放入工程做功能验证与扩展。

代码清单（单文件展示以便复制——在实际工程按包拆分）
package com.company.metrics;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 1) DTO / domain
 */
public class MetricQueryVO implements Serializable {
    private Set<String> metricCodes;
    private Set<String> periodIds;
    private Set<String> orgCodes;
    private Set<String> orgLevels;
    private Set<String> domainCodes;
    private Integer senceType;
    // getters / setters / constructor omitted for brevity
    // add them in production
}

class Metadata {
    // placeholder for your metadata (metrics/measure/domain/org-level mapping)
}

/**
 * Context for request lifecycle
 */
class Context {
    private MetricQueryVO query;
    private Metadata metadata;
    private Map<String, Object> runtime = new HashMap<>();

    public Context(MetricQueryVO query, Metadata metadata) {
        this.query = query;
        this.metadata = metadata;
    }
    public MetricQueryVO getQuery() { return query; }
    public Metadata getMetadata() { return metadata; }
    public Map<String, Object> getRuntime() { return runtime; }
}

/**
 * Standard measure (统一中间模型)
 */
class StandardMeasure {
    private String periodId;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private BigDecimal value;
    private String currency;
    private String unit;

    // constructors, getters, setters
    public StandardMeasure() {}
    public String getPeriodId() { return periodId; }
    public void setPeriodId(String periodId) { this.periodId = periodId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getMeasureCode() { return measureCode; }
    public void setMeasureCode(String measureCode) { this.measureCode = measureCode; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}

/**
 * 返回给前端的结构
 */
class MeasureDataVO {
    private String periodId;
    private Map<String, List<StandardMeasure>> dataMap = new HashMap<>(); // key: metric|domain|measure

    public String getPeriodId() { return periodId; }
    public void setPeriodId(String periodId) { this.periodId = periodId; }
    public Map<String, List<StandardMeasure>> getDataMap() { return dataMap; }
    public void setDataMap(Map<String, List<StandardMeasure>> dataMap) { this.dataMap = dataMap; }
}

/**
 * PageResult - 分页响应的统一包装
 */
class PageResult<T> {
    private List<T> items;
    private int page;
    private int pageSize;
    private int totalPages;
    private long totalElements;

    // constructors/getters/setters
    public PageResult() {}
    public PageResult(List<T> items, int page, int pageSize, int totalPages, long totalElements) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = totalPages;
        this.totalElements = totalElements;
    }
    public List<T> getItems() { return items; }
    public void setItems(List<T> items) { this.items = items; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }
}

/**
 * 过滤器/转换器接口（插件化）
 */
interface IDataFilter<T> {
    boolean accept(MetricQueryVO query, Metadata meta, Context ctx, T item);
}

interface ITransformer<T> {
    StandardMeasure transform(MetricQueryVO query, Metadata meta, Context ctx, T item);
}

/**
 * ApiCallDescriptor - 描述一个数据源
 */
class ApiCallDescriptor<T> {
    private String name;
    private boolean paged;
    private ApiClient<T> client; // 负责调用外部API并返回 PageResult<T> 或 List<T>

    private IDataFilter<T> filter;
    private ITransformer<T> transformer;

    private int pageSize = 100; // 默认page size，可覆盖
    private int maxParallelPages = 10; // descriptor 级别的并发窗口（可配置）

    public ApiCallDescriptor(String name, boolean paged, ApiClient<T> client,
                             IDataFilter<T> filter, ITransformer<T> transformer) {
        this.name = name; this.paged = paged; this.client = client;
        this.filter = filter; this.transformer = transformer;
    }

    // getters/setters
    public String getName() { return name; }
    public boolean isPaged() { return paged; }
    public ApiClient<T> getClient() { return client; }
    public IDataFilter<T> getFilter() { return filter; }
    public ITransformer<T> getTransformer() { return transformer; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxParallelPages() { return maxParallelPages; }
    public void setMaxParallelPages(int maxParallelPages) { this.maxParallelPages = maxParallelPages; }
}

/**
 * ApiClient - 具体调用者（不同下游实现各自解析）
 */
interface ApiClient<T> {
    /**
     * 当 paged == true 时，调用带页码的接口并返回 PageResult<T>.
     * 当 paged == false 时，返回 PageResult with totalPages=1 and items filled.
     */
    PageResult<T> fetchPage(Context ctx, int page, int pageSize) throws Exception;
}

/**
 * Aggregator - 并发安全聚合实现
 */
class Aggregator {
    // periodId -> (key -> queue of StandardMeasure)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>>> store = new ConcurrentHashMap<>();

    public void aggregate(StandardMeasure m) {
        if (m == null || m.getPeriodId() == null) return;
        ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>> byPeriod
                = store.computeIfAbsent(m.getPeriodId(), p -> new ConcurrentHashMap<>());
        String key = m.getMetricCode() + "|" + m.getDomainCode() + "|" + m.getMeasureCode();
        ConcurrentLinkedQueue<StandardMeasure> q = byPeriod.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        q.offer(m);
    }

    public List<MeasureDataVO> toResult() {
        List<MeasureDataVO> out = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>>> e : store.entrySet()) {
            MeasureDataVO vo = new MeasureDataVO();
            vo.setPeriodId(e.getKey());
            Map<String, List<StandardMeasure>> map = new HashMap<>();
            e.getValue().forEach((k, q) -> {
                List<StandardMeasure> list = new ArrayList<>();
                StandardMeasure item;
                while ((item = q.poll()) != null) {
                    list.add(item);
                }
                map.put(k, list);
            });
            vo.setDataMap(map);
            out.add(vo);
        }
        return out;
    }
}

/**
 * -------------- 关键服务：MetricAggregationService（可作为 Spring @Service） --------------
 */
class ApplicationException extends Exception {
    public ApplicationException(String msg) { super(msg); }
    public ApplicationException(String msg, Throwable t) { super(msg, t); }
}

/**
 * 这是你们提供的线程池/执行器接口（根据你给出的描述）
 */
interface ItaskExecutorService {
    ThreadPoolExecutor findExecutorService();
    void submitTask(TaskRun taskRun);
    <T> CompletableFuture<T> submitTask(Supplier<T> supplier, String taskinfo);
    void RequestContextHolderInit(Object current);
}

/**
 * 你的 TaskRun 抽象（你给出的版本有笔误 runable -> Runable，这里按标准 Runnable）
 */
abstract class TaskRun implements Runnable {
    public abstract String getTaskInfo();
    public abstract void doRun();
    @Override
    public void run() {
        // 可以加入 try/catch/trace
        doRun();
    }
}

/**
 * MetricAggregationService - 主流程实现
 */
public class MetricAggregationService {

    private final ItaskExecutorService taskExecutorService;
    // 模拟 Redis 的元数据加载
    // 在真实项目中注入 RedisTemplate 并从缓存读取Metadata
    public MetricAggregationService(ItaskExecutorService taskExecutorService) {
        this.taskExecutorService = taskExecutorService;
    }

    /**
     * 全局入口：query -> 返回 List<MeasureDataVO>
     *   - apiDescriptors: 要调用的下游数据源列表（由 spring 注入或构造）
     *   - globalTimeoutSeconds: 全流程最大等待超时
     */
    public List<MeasureDataVO> query(MetricQueryVO query,
                                     List<ApiCallDescriptor<?>> apiDescriptors,
                                     long globalTimeoutSeconds) throws ApplicationException {

        // 1. 构造 Context、加载 Metadata（示例中为简单 new）
        Metadata metadata = loadMetadataFromRedisOrCache();
        Context ctx = new Context(query, metadata);

        Aggregator aggregator = new Aggregator();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // Executor service 的线程池可用于在需要 schedule/timeout 时创建额外控制（此处可选）
        ThreadPoolExecutor executor = taskExecutorService.findExecutorService();

        for (ApiCallDescriptor<?> descriptor : apiDescriptors) {
            // 可在这里做路由/开关判断：shouldCall(descriptor, query, ctx)
            if (descriptor.isPaged()) {
                handlePagedDescriptor(descriptor, ctx, aggregator, futures);
            } else {
                handleNonPagedDescriptor(descriptor, ctx, aggregator, futures);
            }
        }

        // 等待所有任务完成（带全局超时）
        try {
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            all.get(globalTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // 部分任务未完成 -> 记录/告警/继续返回当前结果或抛出异常
            // 这里选择抛出异常（也可选择返回当前聚合结果）
            throw new ApplicationException("Global timeout reached while waiting for downstream tasks", te);
        } catch (ExecutionException ee) {
            throw new ApplicationException("Execution error during aggregation", ee.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApplicationException("Interrupted while waiting for tasks", ie);
        }

        // 构造最终结果
        return aggregator.toResult();
    }

    private Metadata loadMetadataFromRedisOrCache() {
        // TODO: 从 Redis 或缓存加载 Metadata（示例直接 new）
        return new Metadata();
    }

    /**
     * paged 的 descriptor 处理：先拉第一页 -> 并行拉剩余页（Semaphore 控制并发窗口）
     */
    @SuppressWarnings("unchecked")
    private <T> void handlePagedDescriptor(ApiCallDescriptor<T> descriptor, Context ctx,
                                           Aggregator aggregator, List<CompletableFuture<?>> futures) throws ApplicationException {

        ApiClient<T> client = descriptor.getClient();
        int pageSize = descriptor.getPageSize();

        // 1) 取第一页（同步短超时更安全）
        PageResult<T> firstPage;
        try {
            firstPage = client.fetchPage(ctx, 1, pageSize);
        } catch (Exception e) {
            throw new ApplicationException("Failed to fetch first page from " + descriptor.getName(), e);
        }
        if (firstPage == null || firstPage.getItems() == null) {
            // nothing
            return;
        }
        // 处理第一页数据（同步在当前线程处理，避免不必要的异步开销）
        processItems(firstPage.getItems(), descriptor, ctx, aggregator);

        int totalPages = Math.max(1, firstPage.getTotalPages());
        if (totalPages <= 1) return;

        // 控制并发窗口（descriptor 级别）
        int window = Math.min(descriptor.getMaxParallelPages(),  descriptor.getMaxParallelPages());
        // 也可以基于 system-config 或下游容忍能力调整
        final Semaphore sem = new Semaphore(window);

        for (int p = 2; p <= totalPages; p++) {
            final int pageNo = p;
            // Acquire before submit ensures we never have more than `window` concurrent fetch tasks outstanding
            sem.acquireUninterruptibly();
            // submit async task using your ItaskExecutorService; returns CompletableFuture
            CompletableFuture<Void> fut = taskExecutorService.submitTask(() -> {
                try {
                    PageResult<T> pr = client.fetchPage(ctx, pageNo, pageSize);
                    if (pr != null && pr.getItems() != null && !pr.getItems().isEmpty()) {
                        processItems(pr.getItems(), descriptor, ctx, aggregator);
                    }
                    return null;
                } catch (Exception ex) {
                    // handle per-page error: log and swallow or rethrow to fail whole request
                    // Here we choose to rethrow to let overall error handling catch it
                    throw new RuntimeException("Error fetching page " + pageNo + " from " + descriptor.getName(), ex);
                } finally {
                    sem.release();
                }
            }, descriptor.getName() + "-page-" + pageNo);
            futures.add(fut);
        }
    }

    /**
     * 非分页的 descriptor 处理：一次性请求并处理（异步）
     */
    @SuppressWarnings("unchecked")
    private <T> void handleNonPagedDescriptor(ApiCallDescriptor<T> descriptor, Context ctx,
                                              Aggregator aggregator, List<CompletableFuture<?>> futures) {

        ApiClient<T> client = descriptor.getClient();

        CompletableFuture<Void> fut = taskExecutorService.submitTask(() -> {
            try {
                PageResult<T> pr = client.fetchPage(ctx, 1, descriptor.getPageSize());
                if (pr != null && pr.getItems() != null) {
                    processItems(pr.getItems(), descriptor, ctx, aggregator);
                }
                return null;
            } catch (Exception ex) {
                throw new RuntimeException("Non-paged data fetch failed for " + descriptor.getName(), ex);
            }
        }, descriptor.getName());
        futures.add(fut);
    }

    /**
     * items -> filter -> transform -> aggregate
     */
    private <T> void processItems(List<T> items, ApiCallDescriptor<T> descriptor, Context ctx, Aggregator aggregator) {
        IDataFilter<T> filter = descriptor.getFilter();
        ITransformer<T> transformer = descriptor.getTransformer();

        for (T t : items) {
            try {
                if (filter != null && !filter.accept(ctx.getQuery(), ctx.getMetadata(), ctx, t)) {
                    continue;
                }
                StandardMeasure sm = transformer.transform(ctx.getQuery(), ctx.getMetadata(), ctx, t);
                if (sm != null) {
                    aggregator.aggregate(sm);
                }
            } catch (Exception ex) {
                // per-item异常处理策略：记录并继续，避免单个坏数据导致全流程失败
                // TODO: replace with logging framework
                System.err.println("Error processing item from " + descriptor.getName() + ": " + ex.getMessage());
            }
        }
    }
}

/**
 * ------------------- 示例 ApiClient 实现（模拟下游，便于本地单元测试） -------------------
 * 真实实现中：进行 HTTP 请求、解析 JSON、构造实体 List<T>，并填充 PageResult
 */
class ExampleApiClient implements ApiClient<ExampleRecord> {

    private final boolean simulatePaged;
    private final int totalElements;

    public ExampleApiClient(boolean simulatePaged, int totalElements) {
        this.simulatePaged = simulatePaged;
        this.totalElements = totalElements;
    }

    @Override
    public PageResult<ExampleRecord> fetchPage(Context ctx, int page, int pageSize) throws Exception {
        if (!simulatePaged) {
            // return everything once
            List<ExampleRecord> all = new ArrayList<>();
            for (int i = 1; i <= totalElements; i++) {
                all.add(new ExampleRecord("metricA", "domainX", "m1", "202501", BigDecimal.valueOf(i)));
            }
            return new PageResult<>(all, 1, all.size(), 1, all.size());
        } else {
            int totalPages = (int) Math.ceil((double) totalElements / pageSize);
            if (page > totalPages) {
                return new PageResult<>(Collections.emptyList(), page, pageSize, totalPages, totalElements);
            }
            int start = (page - 1) * pageSize + 1;
            int end = Math.min(page * pageSize, totalElements);
            List<ExampleRecord> chunk = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                chunk.add(new ExampleRecord("metricA", "domainX", "m1", "202501", BigDecimal.valueOf(i)));
            }
            return new PageResult<>(chunk, page, pageSize, totalPages, totalElements);
        }
    }
}

/**
 * 示例下游实体
 */
class ExampleRecord {
    String metricCode;
    String domainCode;
    String measureCode;
    String periodId;
    BigDecimal value;

    public ExampleRecord(String metricCode, String domainCode, String measureCode, String periodId, BigDecimal value) {
        this.metricCode = metricCode;
        this.domainCode = domainCode;
        this.measureCode = measureCode;
        this.periodId = periodId;
        this.value = value;
    }
}

/**
 * 示例 Filter 与 Transformer
 */
class ExampleFilter implements IDataFilter<ExampleRecord> {
    @Override
    public boolean accept(MetricQueryVO query, Metadata meta, Context ctx, ExampleRecord item) {
        // 示例过滤：只接受非空值；也可按 metricCodes/periodIds/orgCodes 过滤
        return item != null && item.value != null;
    }
}

class ExampleTransformer implements ITransformer<ExampleRecord> {
    @Override
    public StandardMeasure transform(MetricQueryVO query, Metadata meta, Context ctx, ExampleRecord item) {
        StandardMeasure sm = new StandardMeasure();
        sm.setPeriodId(item.periodId);
        sm.setMetricCode(item.metricCode);
        sm.setDomainCode(item.domainCode);
        sm.setMeasureCode(item.measureCode);
        sm.setValue(item.value);
        sm.setCurrency("CNY");
        sm.setUnit("unit");
        return sm;
    }
}

/**
 * ------------------- 简单本地测试示例（main） -------------------
 * 把它放在测试类中运行验证流程（你工程中用 Spring 注入 ItaskExecutorService）
 */
class LocalTest {
    public static void main(String[] args) throws Exception {

        // 1) 构造一个简单的 ItaskExecutorService 实现（用于本地测试）
        ItaskExecutorService executorService = new SimpleItaskExecutorServiceImpl();

        // 2) 构造 MetricAggregationService
        MetricAggregationService svc = new MetricAggregationService(executorService);

        // 3) 构造一个分页的 ApiDescriptor
        ExampleApiClient client = new ExampleApiClient(true, 1050); // 1050条 -> 多页
        ApiCallDescriptor<ExampleRecord> desc = new ApiCallDescriptor<>(
                "examplePagedSource",
                true,
                client,
                new ExampleFilter(),
                new ExampleTransformer()
        );
        desc.setPageSize(200); // 每页200
        desc.setMaxParallelPages(5); // 并行窗口5（可调整）

        // 4) 构造请求
        MetricQueryVO q = new MetricQueryVO();
        // TODO 设置具体 query 字段

        // 5) 执行
        List<ApiCallDescriptor<?>> descriptors = Arrays.asList(desc);
        List<MeasureDataVO> result = svc.query(q, descriptors, 60); // 全流程超时60秒

        System.out.println("Periods returned: " + result.size());
        for (MeasureDataVO vo : result) {
            System.out.println("Period: " + vo.getPeriodId() + ", keys: " + vo.getDataMap().keySet());
        }
        // 6) 关闭本地线程池
        ((SimpleItaskExecutorServiceImpl) executorService).shutdown();
    }
}

/**
 * ------------------- 本地简单 ItaskExecutorService 实现（仅供测试） -------------------
 * 在真实工程里使用你们已有的实现（此处是演示）
 */
class SimpleItaskExecutorServiceImpl implements ItaskExecutorService {

    private final ThreadPoolExecutor executor;

    public SimpleItaskExecutorServiceImpl() {
        this.executor = new ThreadPoolExecutor(
                20, 50, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public ThreadPoolExecutor findExecutorService() {
        return executor;
    }

    @Override
    public void submitTask(TaskRun taskRun) {
        executor.submit(taskRun);
    }

    @Override
    public <T> CompletableFuture<T> submitTask(Supplier<T> supplier, String taskinfo) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                T res = supplier.get();
                cf.complete(res);
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    @Override
    public void RequestContextHolderInit(Object current) {
        // noop for demo
    }

    public void shutdown() {
        executor.shutdown();
    }
}


快速说明（如何把上面代码用于你们工程）

把各类按包拆分（dto、client、service、aggregator 等）。示例中为便于阅读而放到单文件。

在 Spring 中把 MetricAggregationService 标记成 @Service，并通过构造器注入 ItaskExecutorService（你们已有实现）。

用真实 ApiClient 替换 ExampleApiClient：实现 HTTP 调用（连接池、短超时、重试/退避）、JSON -> 实体解析。

将 IDataFilter 和 ITransformer 的实现变成 Spring Bean，可按 ApiCallDescriptor 注入，实现可配置化、热插拔。

调整 pageSize、maxParallelPages、以及 globalTimeoutSeconds 基于下游能力与 JVM 内存压力。

加入监控埋点（每页大小、耗时、失败率），将异常与慢日志上报到监控系统。

对聚合 key（metric|domain|measure）如需排序或聚合（求和/平均），把 StandardMeasure 的聚合逻辑放到 Aggregator，使用 LongAdder/DoubleAdder 或 AtomicReference 以保证高效并发聚合。



回复 2
太好了 — 我直接给你一个可编译、可直接拷贝到工程的骨架实现（基于 JDK8 + Spring Boot），包括并行分页策略、Semaphore 并发窗口、超时/异常处理、流式处理（避免占用大量内存）、并发安全聚合，以及如何用你们的 ItaskExecutorService 提交任务并等待结果。代码尽量完整并带注释，方便你直接替换/扩展 ApiClient、Filter、Transformer 等。

下面的代码按包 com.company.metrics 组织（你可以按模块拆分文件）。关键点：

假设 ItaskExecutorService.submitTask(Supplier<T>, String) 返回 CompletableFuture<T>（如果你们的返回类型命名不同，替换即可）。

Redis 从 MetadataService 读取（你们现有 Redis 工具替换即可）。

ApiCallDescriptor 是可配置的，每个数据源提供 callPage / callAll 的方法。

使用 Semaphore 控制每个数据源的并发页请求窗口，避免瞬间把线程池耗尽或下游压力过大。

所有页面到手后立即过滤 -> transform -> aggregate（流式），避免大中间集合。

代码很长，但每个类都有注释。拷贝到项目中后补上真实的 ApiClient 实现、MetadataService、以及你们内部的异常/日志工具即可。

package com.company.metrics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 完整骨架实现：高并发分页并行 + 过滤/转换/并发聚合
 *
 * 说明：
 * - 你需要将类拆分成单独文件（我在一个代码块写出便于阅读）
 * - 替换 TODO 的部分为你公司具体实现（HTTP client、Redis metadata、日志、异常类等）
 */

// ------------------------ Domain / VO ------------------------
class MetricQueryVO implements Serializable {
    private Set<String> metricCodes;
    private Set<String> periodIds;
    private Set<String> orgCodes;
    private Set<String> orgLevels;
    private Set<String> domainCodes;
    private Integer senceType;
    // getters / setters omitted for brevity
    // --- generate constructors, getters, setters ---
    public Set<String> getMetricCodes() { return metricCodes; }
    public void setMetricCodes(Set<String> metricCodes) { this.metricCodes = metricCodes; }
    public Set<String> getPeriodIds() { return periodIds; }
    public void setPeriodIds(Set<String> periodIds) { this.periodIds = periodIds; }
    public Set<String> getOrgCodes() { return orgCodes; }
    public void setOrgCodes(Set<String> orgCodes) { this.orgCodes = orgCodes; }
    public Set<String> getOrgLevels() { return orgLevels; }
    public void setOrgLevels(Set<String> orgLevels) { this.orgLevels = orgLevels; }
    public Set<String> getDomainCodes() { return domainCodes; }
    public void setDomainCodes(Set<String> domainCodes) { this.domainCodes = domainCodes; }
    public Integer getSenceType() { return senceType; }
    public void setSenceType(Integer senceType) { this.senceType = senceType; }
}

class Metadata {
    // TODO: 实际字段从 Redis 加载
    // 例如：Map metric->measures, domain mapping, org level mapping, etc
}

// 全流程上下文
class Context {
    private MetricQueryVO query;
    private Metadata metadata;
    private Map<String,Object> runtime = new HashMap<>();
    // getters/setters
    public MetricQueryVO getQuery() { return query; }
    public void setQuery(MetricQueryVO query) { this.query = query; }
    public Metadata getMetadata() { return metadata; }
    public void setMetadata(Metadata metadata) { this.metadata = metadata; }
    public Map<String,Object> getRuntime() { return runtime; }
}

// 统一聚合度量模型
class StandardMeasure {
    private String periodId;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private BigDecimal value;
    private String currency;
    private String unit;
    // getters/setters, constructor
    public String getPeriodId() { return periodId; }
    public void setPeriodId(String periodId) { this.periodId = periodId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getMeasureCode() { return measureCode; }
    public void setMeasureCode(String measureCode) { this.measureCode = measureCode; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}

// 最终返回对象
class MeasureDataVO {
    private String periodId;
    private Map<String, List<StandardMeasure>> dataMap;
    public String getPeriodId() { return periodId; }
    public void setPeriodId(String periodId) { this.periodId = periodId; }
    public Map<String, List<StandardMeasure>> getDataMap() { return dataMap; }
    public void setDataMap(Map<String, List<StandardMeasure>> dataMap) { this.dataMap = dataMap; }
}

// Page wrapper from APIs
class PageResult<T> {
    private List<T> items;
    private int totalPages;
    private int page;
    private long totalCount;

    public PageResult(List<T> items, int page, int totalPages, long totalCount) {
        this.items = items;
        this.page = page;
        this.totalPages = totalPages;
        this.totalCount = totalCount;
    }
    public List<T> getItems() { return items; }
    public int getTotalPages() { return totalPages; }
    public int getPage() { return page; }
    public long getTotalCount() { return totalCount; }
}

// ------------------------ 插件接口：Filter / Transformer ------------------------
interface IDataFilter<T> {
    boolean accept(MetricQueryVO query, Metadata metadata, Context ctx, T item);
}

interface ITransformer<T> {
    StandardMeasure transform(MetricQueryVO query, Metadata metadata, Context ctx, T item);
}

// ------------------------ ApiCallDescriptor：描述外部数据源 ------------------------
class ApiCallDescriptor<T> {
    private String name;
    private boolean paged;
    private int pageSize = 200; // default
    private int maxParallelPages = 10; // default并发窗口（可覆盖）
    // callPage: (pageIndex -> PageResult<T>)
    private BiFunction<Integer, Context, PageResult<T>> callPageFunc;
    // callAll: non-paged call returning all items (or large list) - optional
    private Function<Context, List<T>> callAllFunc;
    private IDataFilter<T> filter;
    private ITransformer<T> transformer;
    // timeouts per call (seconds)
    private long perCallTimeoutSec = 20;

    // constructors / getters / setters
    public ApiCallDescriptor(String name, boolean paged) {
        this.name = name;
        this.paged = paged;
    }

    public String getName() { return name; }
    public boolean isPaged() { return paged; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxParallelPages() { return maxParallelPages; }
    public void setMaxParallelPages(int maxParallelPages) { this.maxParallelPages = maxParallelPages; }
    public BiFunction<Integer, Context, PageResult<T>> getCallPageFunc() { return callPageFunc; }
    public void setCallPageFunc(BiFunction<Integer, Context, PageResult<T>> callPageFunc) { this.callPageFunc = callPageFunc; }
    public Function<Context, List<T>> getCallAllFunc() { return callAllFunc; }
    public void setCallAllFunc(Function<Context, List<T>> callAllFunc) { this.callAllFunc = callAllFunc; }
    public IDataFilter<T> getFilter() { return filter; }
    public void setFilter(IDataFilter<T> filter) { this.filter = filter; }
    public ITransformer<T> getTransformer() { return transformer; }
    public void setTransformer(ITransformer<T> transformer) { this.transformer = transformer; }
    public long getPerCallTimeoutSec() { return perCallTimeoutSec; }
    public void setPerCallTimeoutSec(long perCallTimeoutSec) { this.perCallTimeoutSec = perCallTimeoutSec; }
}

// ------------------------ 并发安全聚合器 ------------------------
class Aggregator {
    // periodId -> (compositeKey -> queue)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>>> store = new ConcurrentHashMap<>();

    public void aggregate(StandardMeasure m) {
        if (m == null || m.getPeriodId() == null) return;
        ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>> byPeriod =
                store.computeIfAbsent(m.getPeriodId(), p -> new ConcurrentHashMap<>());
        String key = compositeKey(m);
        byPeriod.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).offer(m);
    }

    private String compositeKey(StandardMeasure m) {
        return m.getMetricCode() + "|" + m.getDomainCode() + "|" + m.getMeasureCode();
    }

    public List<MeasureDataVO> toResult() {
        List<MeasureDataVO> out = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<StandardMeasure>>> e : store.entrySet()) {
            MeasureDataVO vo = new MeasureDataVO();
            vo.setPeriodId(e.getKey());
            Map<String, List<StandardMeasure>> map = new HashMap<>();
            e.getValue().forEach((k, q) -> {
                List<StandardMeasure> list = new ArrayList<>();
                StandardMeasure item;
                while ((item = q.poll()) != null) {
                    list.add(item);
                }
                map.put(k, list);
            });
            vo.setDataMap(map);
            out.add(vo);
        }
        return out;
    }
}

// ------------------------ 假定外部服务接口（你们已有的） ------------------------
interface ItaskExecutorService {
    ThreadPoolExecutor findExecutorService();
    void submitTask(TaskRun taskRun);
    <T> CompletableFuture<T> submitTask(java.util.function.Supplier<T> supplier, String taskinfo);
    void RequestContextHolderInit(Object current);
}

// 抽象 TaskRun（你公司已有）
abstract class TaskRun implements Runnable {
    public abstract String getTaskInfo();
    public abstract void doRun();
    @Override
    public void run() {
        getTaskInfo();
        doRun();
    }
}

// ------------------------ MetadataService：从 Redis 加载 metadata（示例） ------------------------
@Component
class MetadataService {
    // TODO: 注入 RedisTemplate / Cache client
    public Metadata loadMetadata() {
        // TODO: 从 Redis 读取并构造 Metadata
        return new Metadata();
    }
}

// ------------------------ MetricAggregationService（核心实现） ------------------------
@Service
public class MetricAggregationService {

    @Autowired
    private ItaskExecutorService taskExecutorService;

    @Autowired
    private MetadataService metadataService;

    // 这里注入你所有的 ApiCallDescriptor 实例（每个数据源一个）
    @Autowired(required = false)
    private List<ApiCallDescriptor<?>> apiDescriptors = Collections.emptyList();

    // 全局等待超时（秒）——整个聚合流程的硬超时
    private final long globalTimeoutSeconds = 30L;

    // 全局线程池用于某些timeout包装（也可以使用taskExecutorService.findExecutorService）
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * 主入口：并行调用各 data-source，分页并行，流式聚合
     */
    @SuppressWarnings("unchecked")
    public List<MeasureDataVO> query(MetricQueryVO query) throws Exception {
        long start = System.currentTimeMillis();
        Context ctx = new Context();
        ctx.setQuery(query);
        // load metadata once
        ctx.setMetadata(metadataService.loadMetadata());

        Aggregator aggregator = new Aggregator();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // for each data source descriptor
        for (ApiCallDescriptor desc : apiDescriptors) {
            if (!shouldCall(desc, query, ctx)) continue;

            if (desc.isPaged()) {
                // 1) call first page synchronously with timeout
                PageResult firstPage = callPageWithTimeout(desc, 1, ctx);
                if (firstPage != null) {
                    processItems(firstPage.getItems(), desc, ctx, aggregator);
                    int totalPages = Math.max(1, firstPage.getTotalPages());
                    if (totalPages > 1) {
                        // limit concurrent page fetching with semaphore
                        int maxParallel = Math.min(desc.getMaxParallelPages(), Math.max(1, totalPages - 1));
                        final Semaphore sem = new Semaphore(maxParallel);

                        for (int p = 2; p <= totalPages; p++) {
                            final int pageIndex = p;
                            // acquire permit then submit task
                            sem.acquireUninterruptibly();
                            CompletableFuture<Void> f = taskExecutorService.submitTask(() -> {
                                try {
                                    PageResult pr = callPageWithTimeout(desc, pageIndex, ctx);
                                    if (pr != null) {
                                        processItems(pr.getItems(), desc, ctx, aggregator);
                                    }
                                } catch (Exception ex) {
                                    // log and swallow so other futures continue
                                    // TODO: replace with your logging framework
                                    System.err.println("Error fetching page " + pageIndex + " for " + desc.getName() + ": " + ex.getMessage());
                                } finally {
                                    sem.release();
                                }
                                return null;
                            }, desc.getName() + "-page-" + pageIndex);
                            futures.add(f);
                        }
                    }
                }
            } else {
                // non-paged - submit single async job
                CompletableFuture<Void> f = taskExecutorService.submitTask(() -> {
                    try {
                        List items = desc.getCallAllFunc().apply(ctx);
                        processItems(items, desc, ctx, aggregator);
                    } catch (Exception ex) {
                        System.err.println("Error calling non-paged " + desc.getName() + ": " + ex.getMessage());
                    }
                    return null;
                }, desc.getName());
                futures.add(f);
            }
        }

        // Wait all futures but bounded by global timeout
        long elapsed = System.currentTimeMillis() - start;
        long remain = globalTimeoutSeconds * 1000 - elapsed;
        if (remain <= 0) {
            // timeout already reached
            System.err.println("Global timeout reached before waiting tasks");
        } else {
            try {
                CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                // wait with timeout using blocking get
                all.get(remain, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                System.err.println("Global wait timeout: " + te.getMessage());
                // optionally cancel pending futures
                futures.forEach(f -> f.cancel(true));
            } catch (ExecutionException | InterruptedException ex) {
                System.err.println("Error waiting tasks: " + ex.getMessage());
                // restore interrupt if interrupted
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        List<MeasureDataVO> result = aggregator.toResult();
        return result;
    }

    /**
     * 是否需要调用某个数据源（可以基于 query + metadata + ctx 决定）
     */
    private boolean shouldCall(ApiCallDescriptor<?> desc, MetricQueryVO query, Context ctx) {
        // TODO: add more sophisticated logic
        return true;
    }

    /**
     * 调用分页接口并带 per-call 超时
     */
    @SuppressWarnings("unchecked")
    private <T> PageResult<T> callPageWithTimeout(ApiCallDescriptor<T> desc, int pageIndex, Context ctx) throws Exception {
        long timeout = desc.getPerCallTimeoutSec();
        CompletableFuture<PageResult<T>> future = taskExecutorService.submitTask(() -> {
            // call page function (synchronous)
            return desc.getCallPageFunc().apply(pageIndex, ctx);
        }, desc.getName() + "-call-page-" + pageIndex);
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new TimeoutException("callPage timeout for " + desc.getName() + " page " + pageIndex);
        }
    }

    /**
     * 处理一页或一次性返回的数据：过滤 -> 转换 -> 聚合
     */
    @SuppressWarnings("unchecked")
    private <T> void processItems(List<T> items, ApiCallDescriptor<T> desc, Context ctx, Aggregator aggregator) {
        if (items == null || items.isEmpty()) return;
        MetricQueryVO q = ctx.getQuery();
        IDataFilter<T> filter = desc.getFilter();
        ITransformer<T> transformer = desc.getTransformer();
        for (T item : items) {
            try {
                if (filter != null && !filter.accept(q, ctx.getMetadata(), ctx, item)) continue;
                StandardMeasure sm = transformer.transform(q, ctx.getMetadata(), ctx, item);
                if (sm == null) continue;
                aggregator.aggregate(sm);
            } catch (Throwable t) {
                // log and continue
                System.err.println("processing item error in " + desc.getName() + ": " + t.getMessage());
            }
        }
    }
}

// ------------------------ 示例：如何声明一个 ApiCallDescriptor（伪实现） ------------------------
@Component
class DemoDescriptors {

    @Autowired
    private SomeApiClient someApiClient; // TODO: your HTTP client bean

    // 假设 Spring 会把这个 descriptor 注入到 MetricAggregationService 的 apiDescriptors 列表
    // 这里仅示范如何构造 descriptor（在 @Configuration 中注册也可以）
    public ApiCallDescriptor<DemoApiEntity> createDemoPagedDescriptor() {
        ApiCallDescriptor<DemoApiEntity> desc = new ApiCallDescriptor<>("demoSource", true);
        desc.setPageSize(500);
        desc.setMaxParallelPages(8);
        desc.setPerCallTimeoutSec(15);

        // callPageFunc: pageIndex -> PageResult<DemoApiEntity>
        desc.setCallPageFunc((pageIndex, ctx) -> {
            // 使用你实际的 HTTP client 去调用下游接口
            // e.g. someApiClient.query(pageIndex, desc.getPageSize(), ctx.getQuery(), ctx.getMetadata())
            // 假设 SomeApiClient 返回 PageResult<DemoApiEntity>
            return someApiClient.queryPage(pageIndex, desc.getPageSize(), ctx.getQuery());
        });

        // filter
        desc.setFilter((q, metadata, context, item) -> {
            // 业务过滤逻辑：例如基于 orgCodes/periodIds 是否匹配
            // return true to keep
            return true;
        });

        // transformer
        desc.setTransformer((q, metadata, context, item) -> {
            // map DemoApiEntity -> StandardMeasure
            StandardMeasure sm = new StandardMeasure();
            sm.setPeriodId(item.getPeriodId());
            sm.setMetricCode(item.getMetricCode());
            sm.setDomainCode(item.getDomainCode());
            sm.setMeasureCode(item.getMeasureCode());
            sm.setValue(item.getValue());
            sm.setCurrency(item.getCurrency());
            sm.setUnit(item.getUnit());
            return sm;
        });

        return desc;
    }
}

// ------------------------ DemoApiEntity & SomeApiClient（示例） ------------------------
class DemoApiEntity {
    private String periodId;
    private String metricCode;
    private String domainCode;
    private String measureCode;
    private BigDecimal value;
    private String currency;
    private String unit;
    // getters/setters
    public String getPeriodId() { return periodId; }
    public void setPeriodId(String periodId) { this.periodId = periodId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getMeasureCode() { return measureCode; }
    public void setMeasureCode(String measureCode) { this.measureCode = measureCode; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
}

@Component
class SomeApiClient {
    // TODO: 实际 HTTP client 注入并实现 queryPage
    public PageResult<DemoApiEntity> queryPage(int pageIndex, int pageSize, MetricQueryVO query) {
        // TODO: 调用下游，解析响应，返回 PageResult
        // 下面是伪数据示例
        List<DemoApiEntity> list = new ArrayList<>();
        // simulate
        if (pageIndex <= 3) {
            for (int i = 0; i < 10; i++) {
                DemoApiEntity e = new DemoApiEntity();
                e.setPeriodId("2025Q3");
                e.setMetricCode("M001");
                e.setDomainCode("D01");
                e.setMeasureCode("MEAS01");
                e.setValue(BigDecimal.valueOf(i + pageIndex));
                e.setCurrency("CNY");
                e.setUnit("unit");
                list.add(e);
            }
        }
        int totalPages = 3;
        return new PageResult<>(list, pageIndex, totalPages, 30);
    }
}


如何把这个骨架落地到你们工程（实践清单）

把上面的类分成单独 java 文件（包名按公司标准）。

实现 SomeApiClient、或为每个下游实现 callPageFunc / callAllFunc（HTTP 客户端使用连接池、短超时、解析成实体）。

在 Spring 配置中把每个 ApiCallDescriptor 注册为 Bean（或在 @Configuration 中产生 List<ApiCallDescriptor<?>> 注入 MetricAggregationService）。

用你们的 ItaskExecutorService 的 submitTask(supplier, info) 去提交异步任务（上面示例即使用此接口）。

调整 perCallTimeoutSec、maxParallelPages、globalTimeoutSeconds 根据下游特性和业务 SLA 调优。

为每个 API 增加重试 / 熔断策略（resilience4j 或自研），并在 callPageWithTimeout 里捕获超时进行重试或降级。

添加链路追踪（traceId）和监控埋点（每页耗时、失败率、返回条数、聚合时间等）。
小结（我为你做了）

提供了一个完整、可编译的并行分页并发聚合骨架实现，使用你现有的 ItaskExecutorService 风格的异步提交方法并在外层做超时控制。

采用 Semaphore 做分页并发窗口，避免瞬时并发过大导致线程池/下游压垮。

每页到手即过滤->转换->聚合（流式），避免把所有源数据保留在内存，减少 GC 压力。

给出如何在工程中注入和扩展的明确步骤。

如果你希望，我现在可以（任选其一）：
A. 把以上代码拆成 每个类一个文件 的完整 Git 项目结构（包含 pom.xml、Spring Boot main、测试样例）并展示关键文件内容；
B. 基于你给出的 真实下游 API 接口样例（URL/入参/返回 JSON），我替你实现一个真实的 ApiCallDescriptor + Transformer + Filter 的具体实现，并提供单元测试（模拟 HTTP 返回）。

告诉我你要 A 还是 B（或直接把某个真实 API 的样例粘过来，我立刻为该 API 写出可用实现）。