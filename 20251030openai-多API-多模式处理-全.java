好——这是一个典型的并行数据采集 + 责任链/过滤 + 合并聚合问题。目标是既要优雅可扩展（易加新的 B-type 接口、不同的前/后置过滤器和合并策略），又要高性能、安全并发、易维护。
下面我给出一个整体设计思路 + 关键类/接口 + Java8（JDK1.8 + Spring Boot）代码骨架与若干实现细节与工程化建议（超时、重试、元数据热更新、度量等）。我不会问额外问题，
按照你描述的场景直接给出一个尽量完整的方案和示例代码片段，供你直接套用与扩展。

核心思想（一句话）

把「不同 B 接口」抽象为统一的 DataFetcher（或 FetchTask） 接口；把前置/后置处理拆成可组合的链（Chain of Responsibility / Strategy）；把合并逻辑抽象成 Merger/ValueCombiner；
用 CompletableFuture 并发调用所有 Fetchers，结果用线程安全的 ConcurrentHashMap 按 Key 合并；Key 不用拼字符串，建议用Key类以保证类型安全与正确的 equals/hashCode。

主要接口与职责（设计原则：单一职责、开闭、依赖倒置）
// 不可变的请求上下文
public final class Context { /* 请求级参数，getter-only */ }

// 不可变的元数据
public final class Metadata { /* 只读，版本号等 */ }

// 结果 Key - 推荐对象而非拼字符串
public final class ResultKey {
    private final String metricCode;
    private final String domainCode;
    private final String orgCode;
    // equals/hashCode/toString
}

// 结果 Value 的抽象（你的 G）
public final class ResultValue {
    // 业务字段
}

// 抽象 DataFetcher：每个 B、B1、B2 都实现它
public interface DataFetcher {
    /**
     * @return CompletableFuture of Map<ResultKey, ResultValue> (thread-safe map or immutable)
     */
    CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor);
}

// 前置过滤器接口（用于生成分组 E）
public interface PreFilter {
    List<Group> apply(List<RawRecord> records, Context ctx, Metadata md);
}

// 后置处理器（将 Group -> Map<ResultKey, ResultValue>）
public interface PostProcessor {
    Map<ResultKey, ResultValue> process(Group group, Context ctx, Metadata md);
}

// Value 合并策略
public interface ValueMerger {
    ResultValue merge(ResultValue a, ResultValue b);
}


如何针对分页/非分页/不同差异实现 DataFetcher
提供一个 AbstractPaginatedFetcher（模板方法），实现分页逻辑（先请求第一页拿 total，再并行请求剩余页）。

提供 NonPaginatedFetcher 直接一次性取完。

每个具体 Bx 只需实现：fetchPage(pageIndex,pageSize,ctx,md) -> 返回 List<RawRecord> 或已经转换后的 Map<ResultKey, ResultValue>。

在抽象类里组合前置过滤器链与后置处理器，实际转换交给具体实现或共享的通用处理器。

示例（伪码骨架）：

public abstract class AbstractPaginatedFetcher implements DataFetcher {
    protected final List<PreFilter> preFilters;
    protected final List<PostProcessor> postProcessors;
    protected final ValueMerger merger;
    protected final int pageSize = 500;

    public CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor) {
        // 请求第1页（同步或异步），拿到 totalPages
        CompletableFuture<PageResponse> first = fetchPageAsync(1, pageSize, ctx, md, executor);
        return first.thenCompose(pageResp -> {
            int totalPages = calcTotalPages(pageResp.getTotal(), pageSize);
            // 处理第一页的数据 -> map
            Map<ResultKey, ResultValue> map = processPageRecords(pageResp.getRecords(), ctx, md);

            // 如果没有更多页，直接返回
            if (totalPages <= 1) {
                return CompletableFuture.completedFuture(map);
            }

            // 创建其它页的 futures
            List<CompletableFuture<Map<ResultKey, ResultValue>>> futures = IntStream.rangeClosed(2, totalPages)
                .mapToObj(p -> fetchPageAsync(p, pageSize, ctx, md, executor)
                    .thenApply(page -> processPageRecords(page.getRecords(), ctx, md)))
                .collect(Collectors.toList());

            // 合并所有 futures
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            return all.thenApply(v -> {
                Map<ResultKey, ResultValue> combined = new ConcurrentHashMap<>();
                // put first
                map.forEach((k, val) -> combined.merge(k, val, merger::merge));
                for (CompletableFuture<Map<ResultKey, ResultValue>> f : futures) {
                    Map<ResultKey, ResultValue> pageMap = f.join();
                    pageMap.forEach((k, val) -> combined.merge(k, val, merger::merge));
                }
                return combined;
            });
        });
    }

    protected abstract CompletableFuture<PageResponse> fetchPageAsync(int page, int pageSize, Context ctx, Metadata md, Executor executor);
    protected Map<ResultKey, ResultValue> processPageRecords(List<RawRecord> recs, Context ctx, Metadata md) {
        // apply preFilters -> groups -> apply postProcessors -> produce map
    }
}


合并多种 B（并行 + 合并）
主控 Orchestrator 接受一组 DataFetcher（例如 B, B1, B2），并行触发所有 fetch，然后把结果合并到一个全局 ConcurrentHashMap<ResultKey, ResultValue>，合并时用注入的 ValueMerger（或按 fetcher 类型采用不同合并规则）。

public class FetchOrchestrator {
    private final ExecutorService executor;
    private final ValueMerger globalMerger;

    public CompletableFuture<Map<ResultKey, ResultValue>> fetchAll(Context ctx, Metadata md, List<DataFetcher> fetchers) {
        List<CompletableFuture<Map<ResultKey, ResultValue>>> futures = fetchers.stream()
           .map(f -> f.fetch(ctx, md, executor)
                      .exceptionally(ex -> {
                          // 记录日志并做策略（返回空 map 或 fallback）
                          return Collections.emptyMap();
                      }))
           .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<ResultKey, ResultValue> result = new ConcurrentHashMap<>();
                for (CompletableFuture<Map<ResultKey, ResultValue>> f : futures) {
                    Map<ResultKey, ResultValue> m = f.join();
                    m.forEach((k, val) -> result.merge(k, val, globalMerger::merge));
                }
                // 返回不可变视图
                return Collections.unmodifiableMap(result);
            });
    }
}

关于 Key 的冲突与合并策略

不要用字符串拼接作为唯一 key（会导致格式/分隔符问题）。建议用 ResultKey 值对象，内部实现 equals/hashCode。

合并规则要抽象化（ValueMerger），常见策略：

数值类指标做 sum 或取 max/min

时间类取 latest

列表类做 set union 或合并唯一元素

若两个值来自不同源且语义冲突，可放入 ResultValue 内维护 sourceBuckets 或 originFlags

允许按来源（fetcher类型）指定不同的 ValueMerger：在 orchestrator 中可以维护 Map<FetcherType, ValueMerger> 或让每 DataFetcher 提供它的 ValueMerger。

示例 merge：

public ResultValue merge(ResultValue a, ResultValue b) {
    // 例：合并数值字段与列表字段
    ResultValue out = new ResultValue();
    out.setNum(a.getNum() + b.getNum());
    out.setList(Stream.concat(a.getList().stream(), b.getList().stream()).distinct().collect(Collectors.toList()));
    // 其他合并...
    return out;
}


元数据（Metadata）热更新（多实例）

你提到需要在不重启微服务情况下能更新元数据：

推荐做法：中心化存储 + 版本号 + 发布/订阅通知

把 Metadata 存 DB/配置中心/Redis，并且携带 version 或 updatedAt。

每个实例本地缓存 Metadata（只读不可变对象），并订阅变更通知（例如 Redis pub/sub、NATS、Kafka，或 Spring Cloud Config 的刷新端点）。

当收到通知或定时检查到新版本时，原子替换本地 Metadata（volatile 引用或使用 AtomicReference<Metadata>）。

示例（简化）：

public class MetadataHolder {
    private final AtomicReference<Metadata> ref = new AtomicReference<>();

    public Metadata get() { return ref.get(); }
    public void update(Metadata newMd) { ref.set(newMd); }
}

若用 Redis pub/sub，payload 可只包含 newVersion -> 实例拿到后去 DB/Cache 拉新的 Metadata。这样能保证多个实例同步更新。

并发/性能注意点

使用 ExecutorService（可配置线程池大小，基于 CPU 核数和 IO 特性）：网络IO密集 -> 线程数可以 > CPU 数（例如 CPU2 ~ CPU10，或使用可扩展的 ThreadPoolExecutor）。

控制并发页数：分页并行不要无限制开启太多请求，使用 Semaphore 或自定义线程池/并发限制。

设置合理超时、重试和熔断（Resilience4j 或自写简单限流/重试）。

如果 B 的 API 很慢，考虑批量拉取 / 端侧缓存 / 后台预计算。

采集阶段尽量将中间 Map 用 ConcurrentHashMap 并用 merge，减少锁竞争。

对大数据量（百万级）注意内存峰值：考虑流式处理或写暂存到外部（临时文件、DB）。

错误/超时/回退策略（工程化）

每个 fetch 的 CompletableFuture 应该带上超时控制。例如：
public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long timeout, TimeUnit unit, ScheduledExecutorService ses) {
    CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
    ses.schedule(() -> timeoutFuture.completeExceptionally(new TimeoutException()), timeout, unit);
    return CompletableFuture.anyOf(future, timeoutFuture).thenApply(o -> (T)o);
}

对失败的 fetch，记录日志并应用 fallback（返回空 Map 或本地缓存数据），不要因为单个失败阻塞整体返回（除非业务强依赖）。
小结：类/实现建议（便于扩展）

ResultKey — 不可变，定义 equals/hashCode。

ResultValue — 不可变或只在构建期变更（Builder）。

DataFetcher 接口 + AbstractPaginatedFetcher、NonPaginatedFetcher 实现。

PreFilter 链（链式或列表顺序调用）。

PostProcessor 列表，每个负责一块转换逻辑。

ValueMerger 抽象与常用实现（SumMerger, LatestMerger, ListUnionMerger, CompositeMerger）。

FetchOrchestrator 负责并行，合并与返回不可变 Map。

MetadataHolder + pub/sub 刷新机制。

全局 Executor + ScheduledExecutor（用于 timeout wrapper）+ metrics。

完整示例（可直接复制、按需继续扩展）

下面给出一个较完整但精简的 Java 代码示例（单文件风格，去掉细节以便阅读）。你可以把它拆成多个文件/bean，并在 Spring 中注入 Executor/MetadataHolder/RedisListener 等。

// 省略 package/imports for brevity
// 请在实际工程中拆分成多个类文件

// ResultKey, ResultValue 如前所述（略）

// 简化的 ValueMerger 实现（数值累加 + 列表合并示例）
public class DefaultValueMerger implements ValueMerger {
    @Override
    public ResultValue merge(ResultValue a, ResultValue b) {
        if (a == null) return b;
        if (b == null) return a;
        ResultValue out = new ResultValue();
        out.setNumField((a.getNumField()==null?0: a.getNumField()) + (b.getNumField()==null?0:b.getNumField()));
        // 合并列表，保持唯一
        List<String> mergedList = Stream.concat(
            Optional.ofNullable(a.getTags()).orElse(Collections.emptyList()).stream(),
            Optional.ofNullable(b.getTags()).orElse(Collections.emptyList()).stream()
        ).distinct().collect(Collectors.toList());
        out.setTags(mergedList);
        // copy other fields with precedence, etc.
        return out;
    }
}

// 一个非常紧凑的 PaginatedFetcher 例子
public abstract class SimplePaginatedFetcher extends AbstractPaginatedFetcher {
    protected CompletableFuture<PageResponse> fetchPageAsync(int page, int pageSize, Context ctx, Metadata md, Executor executor) {
        // 这里实际应发 HTTP 请求（异步或使用 CompletableFuture.supplyAsync）
        return CompletableFuture.supplyAsync(() -> {
            // 调用远端 API，转换成 PageResponse
        }, executor);
    }

    protected Map<ResultKey, ResultValue> processPageRecords(List<RawRecord> recs, Context ctx, Metadata md) {
        // 1) 应用 preFilters -> groups
        List<Group> groups = recs;
        for (PreFilter pf : preFilters) {
            groups = pf.apply(groups, ctx, md);
        }
        // 2) 对每个 group 执行 postProcessors
        Map<ResultKey, ResultValue> map = new HashMap<>();
        for (Group g : groups) {
            for (PostProcessor pp : postProcessors) {
                Map<ResultKey, ResultValue> produced = pp.process(g, ctx, md);
                produced.forEach((k, val) -> map.merge(k, val, merger::merge));
            }
        }
        return map;
    }
}


（上面省略了 PageResponse, RawRecord, Group 等业务类型实现，按你系统现有模型很容易补上。）
后续工程建议（实践经验）

写单元测试覆盖各种 ValueMerger 和 DataFetcher 的合并冲突情况。

在流量高峰下做压测（注意线程池配置与 API 限流）。

把 Metadata 存到中心（DB/配置中心），并用 Redis pub/sub 通知实例更新。

打点（Prometheus + Micrometer）：记录每个 fetcher 的延迟、成功率、吞吐、合并冲突率等。

若 B 接口很多、或未来会按插件增加，考虑使用 SPI 或把 Fetcher implementations 放在模块化的包里，动态加载。


// Project template: parallel-fetcher-template
// JDK: 1.8
// This single-file presentation contains multiple classes separated by comments.
// In a real project, split each top-level public class into its own file under a package.

parallel-fetcher-template/
├── pom.xml
└── src
├── main
│ └── java
│ └── com
│ └── example
│ └── fetcher
│ ├── Context.java
│ ├── Metadata.java
│ ├── ResultKey.java
│ ├── ResultValue.java
│ ├── ValueMerger.java
│ ├── DefaultValueMerger.java
│ ├── PreFilter.java
│ ├── PostProcessor.java
│ ├── DataFetcher.java
│ ├── PageResponse.java
│ ├── AbstractPaginatedFetcher.java
│ ├── NonPaginatedFetcher.java
│ ├── FetchOrchestrator.java
│ ├── MetadataHolder.java
│ ├── mock
│ │ ├── MockPaginatedFetcher.java
│ │ ├── MockNonPaginatedFetcher.java
│ │ ├── SamplePreFilter.java
│ │ └── SamplePostProcessor.java
│ └── Main.java
└── test
└── java
└── com
└── example
└── fetcher
└── FetchOrchestratorTest.java
```

// ------------------------
// File: com.example.fetcher.model.Context.java
// ------------------------
package com.example.fetcher.model;

import java.io.Serializable;

public final class Context implements Serializable {
    private final String requestId;
    private final String userId;

    public Context(String requestId, String userId) {
        this.requestId = requestId;
        this.userId = userId;
    }

    public String getRequestId() { return requestId; }
    public String getUserId() { return userId; }
}

// ------------------------
// File: com.example.fetcher.model.Metadata.java
// ------------------------
package com.example.fetcher.model;

import java.io.Serializable;
import java.time.Instant;

public final class Metadata implements Serializable {
    private final long version;
    private final Instant updatedAt;

    public Metadata(long version, Instant updatedAt) {
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}

// ------------------------
// File: com.example.fetcher.model.ResultKey.java
// ------------------------
package com.example.fetcher.model;

import java.io.Serializable;
import java.util.Objects;

public final class ResultKey implements Serializable {
    private final String metricCode;
    private final String domainCode;
    private final String orgCode;

    public ResultKey(String metricCode, String domainCode, String orgCode) {
        this.metricCode = metricCode;
        this.domainCode = domainCode;
        this.orgCode = orgCode;
    }

    public String getMetricCode() { return metricCode; }
    public String getDomainCode() { return domainCode; }
    public String getOrgCode() { return orgCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResultKey that = (ResultKey) o;
        return Objects.equals(metricCode, that.metricCode) &&
                Objects.equals(domainCode, that.domainCode) &&
                Objects.equals(orgCode, that.orgCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metricCode, domainCode, orgCode);
    }

    @Override
    public String toString() {
        return metricCode + ":" + domainCode + ":" + orgCode;
    }
}

// ------------------------
// File: com.example.fetcher.model.ResultValue.java
// ------------------------
package com.example.fetcher.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ResultValue implements Serializable {
    private final Double numericValue; // example numeric field
    private final List<String> tags; // example list field
    private final String origin; // optional origin information

    private ResultValue(Double numericValue, List<String> tags, String origin) {
        this.numericValue = numericValue;
        this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags));
        this.origin = origin;
    }

    public static Builder builder() { return new Builder(); }

    public Double getNumericValue() { return numericValue; }
    public List<String> getTags() { return tags; }
    public String getOrigin() { return origin; }

    public static final class Builder {
        private Double numericValue;
        private List<String> tags = new ArrayList<>();
        private String origin;

        public Builder numericValue(Double v) { this.numericValue = v; return this; }
        public Builder tags(List<String> t) { this.tags = t == null ? new ArrayList<>() : new ArrayList<>(t); return this; }
        public Builder origin(String o) { this.origin = o; return this; }
        public ResultValue build() { return new ResultValue(numericValue, tags, origin); }
    }
}

// ------------------------
// File: com.example.fetcher.spi.ValueMerger.java
// ------------------------
package com.example.fetcher.spi;

import com.example.fetcher.model.ResultValue;

public interface ValueMerger {
    /**
     * Merge two ResultValue instances into one. a or b may be null.
     */
    ResultValue merge(ResultValue a, ResultValue b);
}

// ------------------------
// File: com.example.fetcher.spi.DefaultValueMerger.java
// ------------------------
package com.example.fetcher.spi;

import com.example.fetcher.model.ResultValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DefaultValueMerger implements ValueMerger {
    @Override
    public ResultValue merge(ResultValue a, ResultValue b) {
        if (a == null) return b;
        if (b == null) return a;

        // numeric: sum if present
        Double av = a.getNumericValue();
        Double bv = b.getNumericValue();
        Double sum = null;
        if (av != null || bv != null) sum = (av == null ? 0D : av) + (bv == null ? 0D : bv);

        // tags: union preserving order
        Set<String> union = new LinkedHashSet<>();
        if (a.getTags() != null) union.addAll(a.getTags());
        if (b.getTags() != null) union.addAll(b.getTags());
        List<String> tags = new ArrayList<>(union);

        // origin: combine
        String origin = String.format("(%s)+(%s)", a.getOrigin(), b.getOrigin());

        return ResultValue.builder().numericValue(sum).tags(tags).origin(origin).build();
    }
}

// ------------------------
// File: com.example.fetcher.spi.PreFilter.java
// ------------------------
package com.example.fetcher.spi;

import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;

import java.util.List;

/**
 * Transforms raw records into groups (domain-specific). For demo we keep raw record as String.
 */
public interface PreFilter {
    java.util.List<RawRecord> apply(java.util.List<RawRecord> records, Context ctx, Metadata md);
}

// ------------------------
// File: com.example.fetcher.spi.PostProcessor.java
// ------------------------
package com.example.fetcher.spi;

import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;

import java.util.Map;

public interface PostProcessor {
    /**
     * Transform a single group/raw record into a (possibly empty) Map of ResultKey->ResultValue
     */
    java.util.Map<ResultKey, ResultValue> process(RawRecord group, Context ctx, Metadata md);
}

// ------------------------
// File: com.example.fetcher.spi.RawRecord.java
// ------------------------
package com.example.fetcher.spi;

import java.io.Serializable;

/** Very small placeholder for a raw record. Replace with your domain object. */
public class RawRecord implements Serializable {
    private final String id;
    private final double value;
    private final String metric;
    private final String domain;
    private final String org;

    public RawRecord(String id, double value, String metric, String domain, String org) {
        this.id = id; this.value = value; this.metric = metric; this.domain = domain; this.org = org;
    }

    public String getId() { return id; }
    public double getValue() { return value; }
    public String getMetric() { return metric; }
    public String getDomain() { return domain; }
    public String getOrg() { return org; }
}

// ------------------------
// File: com.example.fetcher.spi.PageResponse.java
// ------------------------
package com.example.fetcher.spi;

import java.util.List;

public class PageResponse {
    private final List<RawRecord> records;
    private final long total;

    public PageResponse(List<RawRecord> records, long total) {
        this.records = records;
        this.total = total;
    }

    public List<RawRecord> getRecords() { return records; }
    public long getTotal() { return total; }
}

// ------------------------
// File: com.example.fetcher.spi.DataFetcher.java
// ------------------------
package com.example.fetcher.spi;

import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fetcher that will return a map of ResultKey->ResultValue. Implementations may be paginated or one-shot.
 */
public interface DataFetcher {
    CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor);
}

// ------------------------
// File: com.example.fetcher.fetcher.AbstractPaginatedFetcher.java
// ------------------------
package com.example.fetcher.fetcher;

import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;
import com.example.fetcher.spi.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class AbstractPaginatedFetcher implements DataFetcher {
    protected final List<PreFilter> preFilters;
    protected final List<PostProcessor> postProcessors;
    protected final com.example.fetcher.spi.ValueMerger merger;
    protected final int pageSize;

    public AbstractPaginatedFetcher(List<PreFilter> preFilters,
                                    List<PostProcessor> postProcessors,
                                    com.example.fetcher.spi.ValueMerger merger,
                                    int pageSize) {
        this.preFilters = preFilters == null ? Collections.emptyList() : preFilters;
        this.postProcessors = postProcessors == null ? Collections.emptyList() : postProcessors;
        this.merger = merger;
        this.pageSize = pageSize;
    }

    @Override
    public CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor) {
        CompletableFuture<PageResponse> first = fetchPageAsync(1, pageSize, ctx, md, executor);

        return first.thenCompose(pageResp -> {
            int totalPages = calcTotalPages(pageResp.getTotal(), pageSize);
            Map<ResultKey, ResultValue> firstMap = processPageRecords(pageResp.getRecords(), ctx, md);
            if (totalPages <= 1) return CompletableFuture.completedFuture(firstMap);

            List<CompletableFuture<Map<ResultKey, ResultValue>>> futures = IntStream.rangeClosed(2, totalPages)
                    .mapToObj(p -> fetchPageAsync(p, pageSize, ctx, md, executor)
                            .thenApply(page -> processPageRecords(page.getRecords(), ctx, md)))
                    .collect(Collectors.toList());

            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            return all.thenApply(v -> {
                Map<ResultKey, ResultValue> combined = new ConcurrentHashMap<>();
                // merge first
                firstMap.forEach((k, val) -> combined.merge(k, val, merger::merge));
                for (CompletableFuture<Map<ResultKey, ResultValue>> f : futures) {
                    Map<ResultKey, ResultValue> mp = f.join();
                    mp.forEach((k, val) -> combined.merge(k, val, merger::merge));
                }
                return combined;
            });
        });
    }

    protected int calcTotalPages(long total, int pageSize) {
        if (pageSize <= 0) return 1;
        return (int) ((total + pageSize - 1) / pageSize);
    }

    protected Map<ResultKey, ResultValue> processPageRecords(List<RawRecord> recs, Context ctx, Metadata md) {
        if (recs == null || recs.isEmpty()) return Collections.emptyMap();
        List<RawRecord> groups = recs;
        for (PreFilter pf : preFilters) {
            groups = pf.apply(groups, ctx, md);
            if (groups == null || groups.isEmpty()) break;
        }

        Map<ResultKey, ResultValue> out = new HashMap<>();
        for (RawRecord g : groups) {
            for (PostProcessor pp : postProcessors) {
                Map<ResultKey, ResultValue> produced = pp.process(g, ctx, md);
                if (produced != null) {
                    produced.forEach((k, v) -> out.merge(k, v, merger::merge));
                }
            }
        }
        return out;
    }

    protected abstract CompletableFuture<PageResponse> fetchPageAsync(int page, int pageSize, Context ctx, Metadata md, Executor executor);
}

// ------------------------
// File: com.example.fetcher.fetcher.NonPaginatedFetcher.java
// ------------------------
package com.example.fetcher.fetcher;

import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;
import com.example.fetcher.spi.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** Simple one-shot fetcher: fetchAll then process without pagination */
public abstract class NonPaginatedFetcher implements DataFetcher {
    protected final List<PreFilter> preFilters;
    protected final List<PostProcessor> postProcessors;
    protected final com.example.fetcher.spi.ValueMerger merger;

    public NonPaginatedFetcher(List<PreFilter> preFilters, List<PostProcessor> postProcessors, com.example.fetcher.spi.ValueMerger merger) {
        this.preFilters = preFilters == null ? Collections.emptyList() : preFilters;
        this.postProcessors = postProcessors == null ? Collections.emptyList() : postProcessors;
        this.merger = merger;
    }

    @Override
    public CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor) {
        return fetchAllAsync(ctx, md, executor).thenApply(records -> {
            List<RawRecord> groups = records;
            for (PreFilter pf : preFilters) {
                groups = pf.apply(groups, ctx, md);
                if (groups == null || groups.isEmpty()) break;
            }

            return groups.stream()
                    .map(g -> {
                        Map<ResultKey, ResultValue> combined = postProcessors.stream()
                                .map(pp -> pp.process(g, ctx, md))
                                .filter(m -> m != null)
                                .flatMap(m -> m.entrySet().stream())
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                        (a, b) -> merger.merge(a, b)));
                        return combined;
                    })
                    .flatMap(m -> m.entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> merger.merge(a, b)));
        });
    }

    protected abstract CompletableFuture<List<RawRecord>> fetchAllAsync(Context ctx, Metadata md, Executor executor);
}

// ------------------------
// File: com.example.fetcher.orch.FetchOrchestrator.java
// ------------------------
package com.example.fetcher.orch;

import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;
import com.example.fetcher.spi.DataFetcher;
import com.example.fetcher.spi.ValueMerger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

public class FetchOrchestrator {
    private final Executor executor;
    private final ValueMerger globalMerger;

    public FetchOrchestrator(Executor executor, ValueMerger merger) {
        this.executor = executor;
        this.globalMerger = merger;
    }

    public CompletableFuture<Map<ResultKey, ResultValue>> fetchAll(Context ctx, Metadata md, List<DataFetcher> fetchers) {
        if (fetchers == null || fetchers.isEmpty()) return CompletableFuture.completedFuture(Collections.emptyMap());

        List<CompletableFuture<Map<ResultKey, ResultValue>>> futures = fetchers.stream()
                .map(f -> f.fetch(ctx, md, executor)
                        .exceptionally(ex -> {
                            // log & fallback
                            ex.printStackTrace();
                            return Collections.emptyMap();
                        }))
                .collect(Collectors.toList());

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.thenApply(v -> {
            Map<ResultKey, ResultValue> combined = new ConcurrentHashMap<>();
            for (CompletableFuture<Map<ResultKey, ResultValue>> f : futures) {
                Map<ResultKey, ResultValue> m = f.join();
                m.forEach((k, val) -> combined.merge(k, val, globalMerger::merge));
            }
            return combined;
        });
    }
}

// ------------------------
// File: com.example.fetcher.meta.MetadataHolder.java
// ------------------------
package com.example.fetcher.meta;

import com.example.fetcher.model.Metadata;

import java.util.concurrent.atomic.AtomicReference;

public class MetadataHolder {
    private final AtomicReference<Metadata> ref = new AtomicReference<>();

    public Metadata get() { return ref.get(); }
    public void update(Metadata md) { ref.set(md); }
}

// ------------------------
// File: com.example.fetcher.examples.MockComponents.java
// ------------------------
package com.example.fetcher.examples;

import com.example.fetcher.fetcher.AbstractPaginatedFetcher;
import com.example.fetcher.fetcher.NonPaginatedFetcher;
import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;
import com.example.fetcher.spi.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Demo mock implementations used to simulate upstream APIs.
 */
public class MockComponents {

    public static class SimplePreFilter implements PreFilter {
        @Override
        public List<RawRecord> apply(List<RawRecord> records, Context ctx, Metadata md) {
            // example: filter out negative values
            return records.stream().filter(r -> r.getValue() >= 0).collect(Collectors.toList());
        }
    }

    public static class SimplePostProcessor implements PostProcessor {
        @Override
        public Map<ResultKey, ResultValue> process(RawRecord group, Context ctx, Metadata md) {
            ResultKey key = new ResultKey(group.getMetric(), group.getDomain(), group.getOrg());
            ResultValue value = ResultValue.builder().numericValue(group.getValue()).tags(Arrays.asList(group.getId())).origin("mock").build();
            Map<ResultKey, ResultValue> m = new HashMap<>(); m.put(key, value); return m;
        }
    }

    // Mock paginated fetcher simulating B-like API
    public static class MockPaginatedFetcher extends AbstractPaginatedFetcher {
        private final String name;
        private final int simulatedTotal;

        public MockPaginatedFetcher(String name, int simulatedTotal, List<PreFilter> preFilters, List<PostProcessor> postProcessors, com.example.fetcher.spi.ValueMerger merger, int pageSize) {
            super(preFilters, postProcessors, merger, pageSize);
            this.name = name;
            this.simulatedTotal = simulatedTotal;
        }

        @Override
        protected CompletableFuture<PageResponse> fetchPageAsync(int page, int pageSize, Context ctx, Metadata md, Executor executor) {
            return CompletableFuture.supplyAsync(() -> {
                // simulate latency
                try { Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200)); } catch (InterruptedException e) { }
                int start = (page - 1) * pageSize;
                int end = Math.min(simulatedTotal, start + pageSize);
                List<RawRecord> recs = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    // create deterministic-ish data
                    RawRecord r = new RawRecord(name + "-" + i, i * 1.0, "m_" + (i % 3), "d_" + (i % 2), "org_" + (i % 4));
                    recs.add(r);
                }
                return new PageResponse(recs, simulatedTotal);
            }, executor);
        }
    }

    // Mock one-shot fetcher simulating B2-like API
    public static class MockNonPaginatedFetcher extends NonPaginatedFetcher {
        private final String name;
        private final int count;

        public MockNonPaginatedFetcher(String name, int count, List<PreFilter> preFilters, List<PostProcessor> postProcessors, com.example.fetcher.spi.ValueMerger merger) {
            super(preFilters, postProcessors, merger);
            this.name = name; this.count = count;
        }

        @Override
        protected CompletableFuture<List<RawRecord>> fetchAllAsync(Context ctx, Metadata md, Executor executor) {
            return CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(ThreadLocalRandom.current().nextInt(30, 150)); } catch (InterruptedException e) { }
                List<RawRecord> recs = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    RawRecord r = new RawRecord(name + "-" + i, i * 2.0, "m_" + (i % 3), "d_" + (i % 2), "org_" + (i % 4));
                    recs.add(r);
                }
                return recs;
            }, executor);
        }
    }
}

// ------------------------
// File: com.example.fetcher.Main.java
// ------------------------
package com.example.fetcher;

import com.example.fetcher.examples.MockComponents;
import com.example.fetcher.fetcher.AbstractPaginatedFetcher;
import com.example.fetcher.fetcher.NonPaginatedFetcher;
import com.example.fetcher.meta.MetadataHolder;
import com.example.fetcher.model.Context;
import com.example.fetcher.model.Metadata;
import com.example.fetcher.model.ResultKey;
import com.example.fetcher.model.ResultValue;
import com.example.fetcher.orch.FetchOrchestrator;
import com.example.fetcher.spi.DataFetcher;
import com.example.fetcher.spi.PostProcessor;
import com.example.fetcher.spi.PreFilter;
import com.example.fetcher.spi.ValueMerger;
import com.example.fetcher.spi.RawRecord;
import com.example.fetcher.spi.PageResponse;
import com.example.fetcher.spi.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {
        // Thread pool tuned for IO-bound tasks
        ExecutorService executor = Executors.newFixedThreadPool(16);
        ScheduledExecutorService ses = Executors.newScheduledThreadPool(2);

        // Metadata holder demo
        MetadataHolder metadataHolder = new MetadataHolder();
        metadataHolder.update(new Metadata(1, Instant.now()));

        Context ctx = new Context("req-1", "user-42");
        Metadata md = metadataHolder.get();

        ValueMerger merger = new com.example.fetcher.spi.DefaultValueMerger();

        PreFilter pf = new MockComponents.SimplePreFilter();
        PostProcessor pp = new MockComponents.SimplePostProcessor();

        // Create fetchers: B (paginated), B1 (paginated), B2 (non-paginated)
        DataFetcher fetchB = new MockComponents.MockPaginatedFetcher("B", 1200, Arrays.asList(pf), Arrays.asList(pp), merger, 200);
        DataFetcher fetchB1 = new MockComponents.MockPaginatedFetcher("B1", 800, Arrays.asList(pf), Arrays.asList(pp), merger, 200);
        DataFetcher fetchB2 = new MockComponents.MockNonPaginatedFetcher("B2", 500, null, Arrays.asList(pp), merger);

        FetchOrchestrator orch = new FetchOrchestrator(executor, merger);

        CompletableFuture<Map<ResultKey, ResultValue>> resultFuture = orch.fetchAll(ctx, md, Arrays.asList(fetchB, fetchB1, fetchB2));

        // Example: set a global timeout for whole orchestration
        CompletableFuture<Map<ResultKey, ResultValue>> resultWithTimeout = withTimeout(resultFuture, 30, TimeUnit.SECONDS, ses);

        Map<ResultKey, ResultValue> result = resultWithTimeout.get();

        System.out.println("Combined result size: " + result.size());

        // print a few entries
        result.entrySet().stream().limit(20).forEach(e -> {
            System.out.println(e.getKey() + " => num=" + e.getValue().getNumericValue() + ", tags=" + e.getValue().getTags() + ", origin=" + e.getValue().getOrigin());
        });

        executor.shutdown(); ses.shutdown();
    }

    // utility: wrap CompletableFuture with timeout
    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long timeout, TimeUnit unit, ScheduledExecutorService ses) {
        final CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
        ses.schedule(() -> timeoutFuture.completeExceptionally(new TimeoutException("Timeout after " + timeout + " " + unit)), timeout, unit);
        return CompletableFuture.anyOf(future, timeoutFuture).thenApply(o -> (T) o);
    }
}


我已为你生成并保存了一个可编译的 Java 模板（包含多个类与示例实现），文件名：Parallel-fetcher-template。它放在右侧的代码面板（你可以直接查看/复制），包含：

上下文/元数据模型：Context, Metadata

结果对象：ResultKey, ResultValue（Builder 模式）

抽象能力与 SPI：DataFetcher, PreFilter, PostProcessor, ValueMerger

分页与非分页抽象：AbstractPaginatedFetcher, NonPaginatedFetcher

合并 Orchestrator：FetchOrchestrator

元数据热更新 holder：MetadataHolder

Mock 示例实现：MockPaginatedFetcher, MockNonPaginatedFetcher, 示例 PreFilter 与 PostProcessor

Main 启动示例（演示并行调用、合并、超时包装）

接下来我可以（任选其一）：

帮你把这个模板拆分成真实的 Maven/Gradle 项目结构（pom.xml 或 build.gradle），并给出如何在 IDE 中运行的步骤；

根据你现有的 C/D/F 真实代码，逐步将它们替换到模板对应的位置（我会给出详细的重构补丁/改动点）；

为模板添加 Resilience（重试/熔断）集成示例（使用 Resilience4j）或者集成 Micrometer 指标。

你想要我接着做哪一项？

