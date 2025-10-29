�á�������һ�����͵Ĳ������ݲɼ� + ������/���� + �ϲ��ۺ����⡣Ŀ���Ǽ�Ҫ���ſ���չ���׼��µ� B-type �ӿڡ���ͬ��ǰ/���ù������ͺϲ����ԣ�����Ҫ�����ܡ���ȫ��������ά����
�����Ҹ���һ���������˼· + �ؼ���/�ӿ� + Java8��JDK1.8 + Spring Boot������Ǽ�������ʵ��ϸ���빤�̻����飨��ʱ�����ԡ�Ԫ�����ȸ��¡������ȣ����Ҳ����ʶ������⣬
�����������ĳ���ֱ�Ӹ���һ�����������ķ�����ʾ������Ƭ�Σ�����ֱ����������չ��

����˼�루һ�仰��

�ѡ���ͬ B �ӿڡ�����Ϊͳһ�� DataFetcher���� FetchTask�� �ӿڣ���ǰ��/���ô����ɿ���ϵ�����Chain of Responsibility / Strategy�����Ѻϲ��߼������ Merger/ValueCombiner��
�� CompletableFuture ������������ Fetchers��������̰߳�ȫ�� ConcurrentHashMap �� Key �ϲ���Key ����ƴ�ַ�����������Key���Ա�֤���Ͱ�ȫ����ȷ�� equals/hashCode��

��Ҫ�ӿ���ְ�����ԭ�򣺵�һְ�𡢿��ա��������ã�
// ���ɱ������������
public final class Context { /* ���󼶲�����getter-only */ }

// ���ɱ��Ԫ����
public final class Metadata { /* ֻ�����汾�ŵ� */ }

// ��� Key - �Ƽ��������ƴ�ַ���
public final class ResultKey {
    private final String metricCode;
    private final String domainCode;
    private final String orgCode;
    // equals/hashCode/toString
}

// ��� Value �ĳ������ G��
public final class ResultValue {
    // ҵ���ֶ�
}

// ���� DataFetcher��ÿ�� B��B1��B2 ��ʵ����
public interface DataFetcher {
    /**
     * @return CompletableFuture of Map<ResultKey, ResultValue> (thread-safe map or immutable)
     */
    CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor);
}

// ǰ�ù������ӿڣ��������ɷ��� E��
public interface PreFilter {
    List<Group> apply(List<RawRecord> records, Context ctx, Metadata md);
}

// ���ô��������� Group -> Map<ResultKey, ResultValue>��
public interface PostProcessor {
    Map<ResultKey, ResultValue> process(Group group, Context ctx, Metadata md);
}

// Value �ϲ�����
public interface ValueMerger {
    ResultValue merge(ResultValue a, ResultValue b);
}


�����Է�ҳ/�Ƿ�ҳ/��ͬ����ʵ�� DataFetcher
�ṩһ�� AbstractPaginatedFetcher��ģ�巽������ʵ�ַ�ҳ�߼����������һҳ�� total���ٲ�������ʣ��ҳ����

�ṩ NonPaginatedFetcher ֱ��һ����ȡ�ꡣ

ÿ������ Bx ֻ��ʵ�֣�fetchPage(pageIndex,pageSize,ctx,md) -> ���� List<RawRecord> ���Ѿ�ת����� Map<ResultKey, ResultValue>��

�ڳ����������ǰ�ù�����������ô�������ʵ��ת����������ʵ�ֻ����ͨ�ô�������

ʾ����α��Ǽܣ���

public abstract class AbstractPaginatedFetcher implements DataFetcher {
    protected final List<PreFilter> preFilters;
    protected final List<PostProcessor> postProcessors;
    protected final ValueMerger merger;
    protected final int pageSize = 500;

    public CompletableFuture<Map<ResultKey, ResultValue>> fetch(Context ctx, Metadata md, Executor executor) {
        // �����1ҳ��ͬ�����첽�����õ� totalPages
        CompletableFuture<PageResponse> first = fetchPageAsync(1, pageSize, ctx, md, executor);
        return first.thenCompose(pageResp -> {
            int totalPages = calcTotalPages(pageResp.getTotal(), pageSize);
            // �����һҳ������ -> map
            Map<ResultKey, ResultValue> map = processPageRecords(pageResp.getRecords(), ctx, md);

            // ���û�и���ҳ��ֱ�ӷ���
            if (totalPages <= 1) {
                return CompletableFuture.completedFuture(map);
            }

            // ��������ҳ�� futures
            List<CompletableFuture<Map<ResultKey, ResultValue>>> futures = IntStream.rangeClosed(2, totalPages)
                .mapToObj(p -> fetchPageAsync(p, pageSize, ctx, md, executor)
                    .thenApply(page -> processPageRecords(page.getRecords(), ctx, md)))
                .collect(Collectors.toList());

            // �ϲ����� futures
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


�ϲ����� B������ + �ϲ���
���� Orchestrator ����һ�� DataFetcher������ B, B1, B2�������д������� fetch��Ȼ��ѽ���ϲ���һ��ȫ�� ConcurrentHashMap<ResultKey, ResultValue>���ϲ�ʱ��ע��� ValueMerger���� fetcher ���Ͳ��ò�ͬ�ϲ����򣩡�

public class FetchOrchestrator {
    private final ExecutorService executor;
    private final ValueMerger globalMerger;

    public CompletableFuture<Map<ResultKey, ResultValue>> fetchAll(Context ctx, Metadata md, List<DataFetcher> fetchers) {
        List<CompletableFuture<Map<ResultKey, ResultValue>>> futures = fetchers.stream()
           .map(f -> f.fetch(ctx, md, executor)
                      .exceptionally(ex -> {
                          // ��¼��־�������ԣ����ؿ� map �� fallback��
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
                // ���ز��ɱ���ͼ
                return Collections.unmodifiableMap(result);
            });
    }
}

���� Key �ĳ�ͻ��ϲ�����

��Ҫ���ַ���ƴ����ΪΨһ key���ᵼ�¸�ʽ/�ָ������⣩�������� ResultKey ֵ�����ڲ�ʵ�� equals/hashCode��

�ϲ�����Ҫ���󻯣�ValueMerger�����������ԣ�

��ֵ��ָ���� sum ��ȡ max/min

ʱ����ȡ latest

�б����� set union ��ϲ�ΨһԪ��

������ֵ���Բ�ͬԴ�������ͻ���ɷ��� ResultValue ��ά�� sourceBuckets �� originFlags

������Դ��fetcher���ͣ�ָ����ͬ�� ValueMerger���� orchestrator �п���ά�� Map<FetcherType, ValueMerger> ����ÿ DataFetcher �ṩ���� ValueMerger��

ʾ�� merge��

public ResultValue merge(ResultValue a, ResultValue b) {
    // �����ϲ���ֵ�ֶ����б��ֶ�
    ResultValue out = new ResultValue();
    out.setNum(a.getNum() + b.getNum());
    out.setList(Stream.concat(a.getList().stream(), b.getList().stream()).distinct().collect(Collectors.toList()));
    // �����ϲ�...
    return out;
}


Ԫ���ݣ�Metadata���ȸ��£���ʵ����

���ᵽ��Ҫ�ڲ�����΢����������ܸ���Ԫ���ݣ�

�Ƽ����������Ļ��洢 + �汾�� + ����/����֪ͨ

�� Metadata �� DB/��������/Redis������Я�� version �� updatedAt��

ÿ��ʵ�����ػ��� Metadata��ֻ�����ɱ���󣩣������ı��֪ͨ������ Redis pub/sub��NATS��Kafka���� Spring Cloud Config ��ˢ�¶˵㣩��

���յ�֪ͨ��ʱ��鵽�°汾ʱ��ԭ���滻���� Metadata��volatile ���û�ʹ�� AtomicReference<Metadata>����

ʾ�����򻯣���

public class MetadataHolder {
    private final AtomicReference<Metadata> ref = new AtomicReference<>();

    public Metadata get() { return ref.get(); }
    public void update(Metadata newMd) { ref.set(newMd); }
}

���� Redis pub/sub��payload ��ֻ���� newVersion -> ʵ���õ���ȥ DB/Cache ���µ� Metadata�������ܱ�֤���ʵ��ͬ�����¡�

����/����ע���

ʹ�� ExecutorService���������̳߳ش�С������ CPU ������ IO ���ԣ�������IO�ܼ� -> �߳������� > CPU �������� CPU2 ~ CPU10����ʹ�ÿ���չ�� ThreadPoolExecutor����

���Ʋ���ҳ������ҳ���в�Ҫ�����ƿ���̫������ʹ�� Semaphore ���Զ����̳߳�/�������ơ�

���ú���ʱ�����Ժ��۶ϣ�Resilience4j ����д������/���ԣ���

��� B �� API ����������������ȡ / �˲໺�� / ��̨Ԥ���㡣

�ɼ��׶ξ������м� Map �� ConcurrentHashMap ���� merge��������������

�Դ������������򼶣�ע���ڴ��ֵ��������ʽ�����д�ݴ浽�ⲿ����ʱ�ļ���DB����

����/��ʱ/���˲��ԣ����̻���

ÿ�� fetch �� CompletableFuture Ӧ�ô��ϳ�ʱ���ơ����磺
public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long timeout, TimeUnit unit, ScheduledExecutorService ses) {
    CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
    ses.schedule(() -> timeoutFuture.completeExceptionally(new TimeoutException()), timeout, unit);
    return CompletableFuture.anyOf(future, timeoutFuture).thenApply(o -> (T)o);
}

��ʧ�ܵ� fetch����¼��־��Ӧ�� fallback�����ؿ� Map �򱾵ػ������ݣ�����Ҫ��Ϊ����ʧ���������巵�أ�����ҵ��ǿ��������
С�᣺��/ʵ�ֽ��飨������չ��

ResultKey �� ���ɱ䣬���� equals/hashCode��

ResultValue �� ���ɱ��ֻ�ڹ����ڱ����Builder����

DataFetcher �ӿ� + AbstractPaginatedFetcher��NonPaginatedFetcher ʵ�֡�

PreFilter ������ʽ���б�˳����ã���

PostProcessor �б�ÿ������һ��ת���߼���

ValueMerger �����볣��ʵ�֣�SumMerger, LatestMerger, ListUnionMerger, CompositeMerger����

FetchOrchestrator �����У��ϲ��뷵�ز��ɱ� Map��

MetadataHolder + pub/sub ˢ�»��ơ�

ȫ�� Executor + ScheduledExecutor������ timeout wrapper��+ metrics��

����ʾ������ֱ�Ӹ��ơ����������չ��

�������һ��������������� Java ����ʾ�������ļ����ȥ��ϸ���Ա��Ķ���������԰�����ɶ���ļ�/bean������ Spring ��ע�� Executor/MetadataHolder/RedisListener �ȡ�

// ʡ�� package/imports for brevity
// ����ʵ�ʹ����в�ֳɶ�����ļ�

// ResultKey, ResultValue ��ǰ�������ԣ�

// �򻯵� ValueMerger ʵ�֣���ֵ�ۼ� + �б�ϲ�ʾ����
public class DefaultValueMerger implements ValueMerger {
    @Override
    public ResultValue merge(ResultValue a, ResultValue b) {
        if (a == null) return b;
        if (b == null) return a;
        ResultValue out = new ResultValue();
        out.setNumField((a.getNumField()==null?0: a.getNumField()) + (b.getNumField()==null?0:b.getNumField()));
        // �ϲ��б�����Ψһ
        List<String> mergedList = Stream.concat(
            Optional.ofNullable(a.getTags()).orElse(Collections.emptyList()).stream(),
            Optional.ofNullable(b.getTags()).orElse(Collections.emptyList()).stream()
        ).distinct().collect(Collectors.toList());
        out.setTags(mergedList);
        // copy other fields with precedence, etc.
        return out;
    }
}

// һ���ǳ����յ� PaginatedFetcher ����
public abstract class SimplePaginatedFetcher extends AbstractPaginatedFetcher {
    protected CompletableFuture<PageResponse> fetchPageAsync(int page, int pageSize, Context ctx, Metadata md, Executor executor) {
        // ����ʵ��Ӧ�� HTTP �����첽��ʹ�� CompletableFuture.supplyAsync��
        return CompletableFuture.supplyAsync(() -> {
            // ����Զ�� API��ת���� PageResponse
        }, executor);
    }

    protected Map<ResultKey, ResultValue> processPageRecords(List<RawRecord> recs, Context ctx, Metadata md) {
        // 1) Ӧ�� preFilters -> groups
        List<Group> groups = recs;
        for (PreFilter pf : preFilters) {
            groups = pf.apply(groups, ctx, md);
        }
        // 2) ��ÿ�� group ִ�� postProcessors
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


������ʡ���� PageResponse, RawRecord, Group ��ҵ������ʵ�֣�����ϵͳ����ģ�ͺ����ײ��ϡ���
�������̽��飨ʵ�����飩

д��Ԫ���Ը��Ǹ��� ValueMerger �� DataFetcher �ĺϲ���ͻ�����

�������߷�����ѹ�⣨ע���̳߳������� API ��������

�� Metadata �浽���ģ�DB/�������ģ������� Redis pub/sub ֪ͨʵ�����¡�

��㣨Prometheus + Micrometer������¼ÿ�� fetcher ���ӳ١��ɹ��ʡ����¡��ϲ���ͻ�ʵȡ�

�� B �ӿںܶࡢ��δ���ᰴ������ӣ�����ʹ�� SPI ��� Fetcher implementations ����ģ�黯�İ����̬���ء�


// Project template: parallel-fetcher-template
// JDK: 1.8
// This single-file presentation contains multiple classes separated by comments.
// In a real project, split each top-level public class into its own file under a package.

parallel-fetcher-template/
������ pom.xml
������ src
������ main
�� ������ java
�� ������ com
�� ������ example
�� ������ fetcher
�� ������ Context.java
�� ������ Metadata.java
�� ������ ResultKey.java
�� ������ ResultValue.java
�� ������ ValueMerger.java
�� ������ DefaultValueMerger.java
�� ������ PreFilter.java
�� ������ PostProcessor.java
�� ������ DataFetcher.java
�� ������ PageResponse.java
�� ������ AbstractPaginatedFetcher.java
�� ������ NonPaginatedFetcher.java
�� ������ FetchOrchestrator.java
�� ������ MetadataHolder.java
�� ������ mock
�� �� ������ MockPaginatedFetcher.java
�� �� ������ MockNonPaginatedFetcher.java
�� �� ������ SamplePreFilter.java
�� �� ������ SamplePostProcessor.java
�� ������ Main.java
������ test
������ java
������ com
������ example
������ fetcher
������ FetchOrchestratorTest.java
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


����Ϊ�����ɲ�������һ���ɱ���� Java ģ�壨�����������ʾ��ʵ�֣����ļ�����Parallel-fetcher-template���������Ҳ�Ĵ�����壨�����ֱ�Ӳ鿴/���ƣ���������

������/Ԫ����ģ�ͣ�Context, Metadata

�������ResultKey, ResultValue��Builder ģʽ��

���������� SPI��DataFetcher, PreFilter, PostProcessor, ValueMerger

��ҳ��Ƿ�ҳ����AbstractPaginatedFetcher, NonPaginatedFetcher

�ϲ� Orchestrator��FetchOrchestrator

Ԫ�����ȸ��� holder��MetadataHolder

Mock ʾ��ʵ�֣�MockPaginatedFetcher, MockNonPaginatedFetcher, ʾ�� PreFilter �� PostProcessor

Main ����ʾ������ʾ���е��á��ϲ�����ʱ��װ��

�������ҿ��ԣ���ѡ��һ����

��������ģ���ֳ���ʵ�� Maven/Gradle ��Ŀ�ṹ��pom.xml �� build.gradle��������������� IDE �����еĲ��裻

���������е� C/D/F ��ʵ���룬�𲽽������滻��ģ���Ӧ��λ�ã��һ������ϸ���ع�����/�Ķ��㣩��

Ϊģ����� Resilience������/�۶ϣ�����ʾ����ʹ�� Resilience4j�����߼��� Micrometer ָ�ꡣ

����Ҫ�ҽ�������һ�

