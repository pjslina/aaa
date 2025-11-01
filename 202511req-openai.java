�����ܲ����ۺϷ�����������������������Spring Boot + JDK8 + �Լ��첽/�̳߳�/��ʱ�ӿڣ�

��������˵��˵����˼·���ؼ��㡢��������ֱ����ص�ʾ������Ƭ�Σ�����Ϊ�Ǽܣ���Ŀ�����㣺�����ܡ�����չ����ά�����̰߳�ȫ���ڴ��Ѻá�����Ӧ��
1. ����ܹ�����������λ��֣�

Controller

���� MetricQueryVO��������У�飬���� Service �㣨ͬ���������� List<MeasureDataVO>����

MetricAggregationService�����ģ�

���� Context���� MetricQueryVO��Metadata������ʱ�����ȷŽ�ȥ������ Redis ���� Metadata��

���� metadata + query ����Ҫ���õ����� API �б�ÿ�� API/Դ��Ӧһ�� ApiCallDescriptor�������Ƿ��ҳ��page-size������������������������������ȣ���

Ϊ��ҳ�ӿڣ���ͬ�������һҳ�õ���ҳ�����ٲ����ύʣ��ҳ���ܲ����������ƣ���

��ÿ�������ҳ����һ���ԷǷ�ҳ��Ӧ����ʽ�����ȹ��� -> ת����ͳһ�� domain model -> �����ۺϵ������ṹ����Ҫ�Ȱ�����ԭʼ�����ռ����ڴ棩��

�ȴ�����������ɣ�CompletableFuture.allOf(...) ����� ItaskExecutorService �ķ��� Future�������ؾۺϽ����

ApiClient �㣨ÿ���ⲿAPIһ��ʵ�֣�

��������ⲿ API��������ӦΪʵ�� T�������Ƿ��ͣ����������ҳ�߼�����С�������� page info��totalPages/nextCursor �ȣ���

Filter/Transformer �����

ÿ�� API/Դ��ע�� Filter<T>��Transformer<T,StandardMeasure>��������չ/ά����

Aggregator

������ȫ�ذ� StandardMeasure �ۺϳ� Map<periodId, Map<metricCode+domainCode+measureCode, List<MeasureObject>>>�����չ��� List<MeasureDataVO>����

ʹ�� ConcurrentHashMap + ConcurrentLinkedQueue �� compute ԭ�Ӳ����Ա�֤�̰߳�ȫ����������

����/�۶�/����/�����������ر���

ʹ�� Resilience���� resilience4j�������м�����/���ԣ�������/ʧ�ܵ����������������ʧ�ܲ��ԣ������̳߳غľ���

���/���

ÿ�� API ���ú�ʱ���ɹ�/ʧ���ʡ����ش�С�����г��ȡ��̳߳������ʡ��ڴ�ռ�õȡ�

2. �������ڴ���ԣ��ؼ�Ҫ�㣩

��ҳ���в���

��ͬ�������һҳ�õ���ҳ��/totalCount���� pages>1�����ɿز�������������ʣ��ҳ������ maxParallelPages = min(totalPages-1, configuredParallelism)����

�Էǳ���ҳ��������ǧҳ������Ҫͬʱ�ύ����ҳ��ʹ�� Semaphore ���̳߳ض��������ڣ������������Կ����ڴ�������ѹǿ��

��ʽ�������������ԭʼ���ݱ�����

ÿҳ���󵽺����������� -> ת�� -> �ۺ��벢�� Map����Ҫ��ԭʼ List<T> �ŵ�ȫ�ּ����

�����ۺ����ݽṹ

ʹ�� ConcurrentHashMap<Key, ConcurrentLinkedQueue<Value>>������ʹ�� computeIfAbsent + offer������������

��ʹ�� ConcurrentHashMap.compute() ���ϲ�����Ҫע�� compute �ĺ����ᱻ�������õ�����ԭ���ԣ���

�̰߳�ȫ����

����ÿ�� key �� List��ʹ�� ConcurrentLinkedQueue �����ת��Ϊ List ����ǰ��������/ȥ�ء�

���Ҫ��ÿ�� key �����Ӻϲ����ۼӡ�ƽ���ȣ���ʹ�� LongAdder/DoubleAdder�����Զ����̰߳�ȫ�ۺ�������ԭ���ֶΣ���

�ڴ��ͷ�

��ʱ�����м���󣨾����������ʱ���ϣ�����ҳ������� GC ����ա�����ȫ���ϲ�ǰ�Ȱ����� page ��ʵ��ȫ�����ڶ��ϡ�

�̳߳�����

�̳߳ش�С����� I/O / CPU �������������� I/O��use larger pool but bounded queue����ȷ��ʹ�����ǵ� ItaskExecutorService�������ú������/�ܾ����ԡ�

3. �������볬ʱ

ÿ���ⲿ�������ó�ʱ������/�����������첽 Future ������ȫ�ֳ�ʱ�������������

�Թؼ� API �����ԣ�ָ���˱ܣ����Էǹؼ������������ؿջ򻺴����ݣ���

����һ�ؼ� API����ʧ�ܴﵽ��ֵ�������۶ϣ�����ʧ�ܲ���¼�澯��

4. ����չ�㣨�������

ApiCallDescriptor������ Filter��Transformer��PagingStrategy��Parser���� �� API ֻ������ descriptor �� client ʵ�֡�

Filter<T> �ӿڣ�boolean accept(MetricQueryVO, Metadata, Context, T)��

Transformer<T, StandardMeasure>���Ѳ�ͬ T ͳһ�� StandardMeasure������ periodId��metricCode��domainCode��measureCode��value��currency��unit �ȣ���

5. �ؼ�����Ƭ�Σ��Ǽܣ�������أ�

����ʾ���Լ��ɶ�Ϊ����ʡ�Բ����쳣���빤����ʵ�֡�ע�⣺�õ��Ĺ�˾�ӿ� ItaskExecutorService��asyncTaskUtil �Ȱ���������ʵ���滻����㡣

// domain classes (��)
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


// Filter �ӿ�
public interface IDataFilter<T> {
    boolean accept(MetricQueryVO query, Metadata meta, Context ctx, T item);
}

// Transformer �ӿ�
public interface ITransformer<T> {
    StandardMeasure transform(MetricQueryVO query, Metadata meta, Context ctx, T item);
}


// ApiCallDescriptor������һ���ⲿ����Դ
public class ApiCallDescriptor<T> {
    String name;
    boolean paged;
    Function<Integer, Object> buildRequestForPage; // or more complex builder
    BiFunction<Object, String, List<T>> callApi; // (request,pageToken)->List<T> or page wrapper
    IDataFilter<T> filter;
    ITransformer<T> transformer;
    // plus timeouts, rateLimit info, etc
}


// Aggregator��������ȫ�ۺ�
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


// Service �����̣�α���룩
@Service
public class MetricAggregationService {

    @Autowired ItaskExecutorService taskExecutorService;
    @Autowired RedisTemplate redisTemplate; // for metadata
    @Autowired List<ApiCallDescriptor<?>> apiDescriptors; // ÿ��descriptor����һ������source

    public List<MeasureDataVO> query(MetricQueryVO query) {
        Context ctx = buildContext(query);
        // load metadata from redis once
        ctx.setMetadata(loadMetadataFromRedis());

        Aggregator aggregator = new Aggregator();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (ApiCallDescriptor desc : apiDescriptors) {
            if (!shouldCall(desc, query, ctx)) continue;

            if (desc.isPaged()) {
                // 1) �����һҳ��ͬ����̳�ʱ������ȡҳ��Ϣ��totalPages��
                PageResult first = callPage(desc, 1, ctx);
                processPageItems(first.getItems(), desc, query, ctx, aggregator);

                int total = first.getTotalPages();
                int maxParallel = Math.min(total - 1, configuredParallelPages());
                // ʹ��forѭ���ύʣ��ҳ�����Ʋ�����ʹ�� Semaphore��
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


ע�������õ��� ItaskExecutorService.submitTask(Supplier<T>,String)���ýӿڷ��� CompletableFuture<T>��������Ľӿ���ƴд CompletableFutrue �Ұ���׼ CompletableFuture ʹ�ã���

6. ���ܵ�����ʵս����

�����ȿ��ƣ���ҪäĿ��������ҳ����ÿ������Դ���ò������ޣ��������γ������������������

������ѹ�����������֧�ִ�ҳ������ʹ�ýϴ� pageSize ��������������ڿɽ����ڴ淶Χ�ڣ���

HTTP �ͻ��ˣ�ʹ�����ӳأ�Apache HttpClient / OkHttp�����������ӣ��̳�ʱ���á�

���������ʱ���ֵ����ĳЩ����᷵�ؾ޴� payload����������ȡժҪ��ֻȡ��Ҫ�ֶΣ����̨Ԥ�ۺϻ��档

�����ȵ㣺�Գ�����ϣ�metric+period+org���� Redis ���������棬������ֱ�ӷ��أ���ʱˢ��/�첽ˢ�¡�

�������ݣ���¼ÿ�� page �� size/time/error�����㶨λ���ӿ�/�� payload ������ƿ����

�������գ�GC�����ţ���������������ÿҳitems��ʱ��ѡ���ʵ��Ѵ�С��GC���ԣ�JDK8�ϳ��� CMS / G1������ѡ�����á�

�ڴ���㣺�Ե��� StandardMeasure ��С���㣬���Բ���������������ֵ�ڴ棬ȷ�������� JVM �ѡ�

7. ��ά��������չ

����������Դ��ֻ������ ApiCallDescriptor + client + transformer + filter��

����䶯���ѹ���/��ϴ�߼����ڲ�����/�ű�������Ȳ���� Groovy/JS�����������Ķ�Ƶ��������

��Ԫ/���ɲ��ԣ���ÿ�� ApiClient/Transformer/Filter �����⣻�� Aggregator ������ѹ�����ԣ�ģ�Ⲣ��������֤�޾�̬����

��־��ʾ�٣�ÿ�ξۺϼ�¼ traceId��������ͷ���룩���� traceId ���ݵ������첽���񣬱�����·׷�١�

8. С�ᣨִ��Ҫ�㣩

�ȹ��� Context + �� Redis ��ȡ Metadata��һ���ԣ���

�� data-source �������񣨷�ҳ��ȡ��һҳ���ٲ���ʣ��ҳ���ܲ������ڿ��ƣ���

ÿҳ���ֺ����������� -> transformer -> �ۺ� �벢����ȫ�ṹ�����Ᵽ��ԭʼ�󼯺ϣ���

ʹ�� ItaskExecutorService.submitTask(Supplier, info) ���ص� CompletableFuture �ռ����� allOf �ȴ�������ʱ����

�����۶�/����/���Բ��ԣ������澯��

�����Ը�⣬�ҿ��ԣ���ѡһ�����̸�����

һ���������� MetricAggregationService �ɱ���ʾ��������ҳ���е�����ʵ�֡���ʱ/�쳣����Semaphore �������ڣ�������

Ϊ����ĳ����ʵ���� API��������ӿ�������дһ�� ApiCallDescriptor + Transformer + Filter �ľ���ʵ�ֺ͵�Ԫ����������

��ѡ��һ�ҾͰѿ�ֱ�ӿ��������̵���������д����������Ҫ���ٻش��κ����⣩��

�ظ� 1
�������һ����ֱ�ӿ��������̡��ɱ������е������Ǽ�ʵ�֣������㹫˾����������ItaskExecutorService.submitTask(Supplier<T>, String) ���� CompletableFuture<T>��ʹ�� JDK8 + Spring Boot����ʵ��Ҫ�㣺

֧�֣���ҳ��ȡ��һҳ -> ����ץȡʣ��ҳ���������� Semaphore ���ƣ�

ÿҳ���־ʹ������� -> ת�� -> ������ȫ�ۺϣ����Ᵽ��ԭʼ�󼯺ϣ�

ȫ�ֵȴ���CompletableFuture.allOf(...).get(globalTimeout, TimeUnit.SECONDS)��JDK8��ȫ������

����/��ʱ�����ͷ� Semaphore����־ռλ������չ�� ApiCallDescriptor/IDataFilter/ITransformer �����

���뾡������������ʾ�� ApiClient / Descriptor / Aggregator / DTOs

˵��������Ĵ���Ϊ���Ǽ�ʵ��/�ο�ʵ�֡�������Ҫ����ʵ�� HTTP ���á�Redis Ԫ���ݼ��ء��쳣�ࡢ��־����滻Ϊ�������������е�ʵ�֣��������������̰߳�ȫ����������ʾ��������ֱ�ӷ��빤����������֤����չ��

�����嵥�����ļ�չʾ�Ա㸴�ơ�����ʵ�ʹ��̰�����֣�
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
 * Standard measure (ͳһ�м�ģ��)
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
 * ���ظ�ǰ�˵Ľṹ
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
 * PageResult - ��ҳ��Ӧ��ͳһ��װ
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
 * ������/ת�����ӿڣ��������
 */
interface IDataFilter<T> {
    boolean accept(MetricQueryVO query, Metadata meta, Context ctx, T item);
}

interface ITransformer<T> {
    StandardMeasure transform(MetricQueryVO query, Metadata meta, Context ctx, T item);
}

/**
 * ApiCallDescriptor - ����һ������Դ
 */
class ApiCallDescriptor<T> {
    private String name;
    private boolean paged;
    private ApiClient<T> client; // ��������ⲿAPI������ PageResult<T> �� List<T>

    private IDataFilter<T> filter;
    private ITransformer<T> transformer;

    private int pageSize = 100; // Ĭ��page size���ɸ���
    private int maxParallelPages = 10; // descriptor ����Ĳ������ڣ������ã�

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
 * ApiClient - ��������ߣ���ͬ����ʵ�ָ��Խ�����
 */
interface ApiClient<T> {
    /**
     * �� paged == true ʱ�����ô�ҳ��Ľӿڲ����� PageResult<T>.
     * �� paged == false ʱ������ PageResult with totalPages=1 and items filled.
     */
    PageResult<T> fetchPage(Context ctx, int page, int pageSize) throws Exception;
}

/**
 * Aggregator - ������ȫ�ۺ�ʵ��
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
 * -------------- �ؼ�����MetricAggregationService������Ϊ Spring @Service�� --------------
 */
class ApplicationException extends Exception {
    public ApplicationException(String msg) { super(msg); }
    public ApplicationException(String msg, Throwable t) { super(msg, t); }
}

/**
 * ���������ṩ���̳߳�/ִ�����ӿڣ������������������
 */
interface ItaskExecutorService {
    ThreadPoolExecutor findExecutorService();
    void submitTask(TaskRun taskRun);
    <T> CompletableFuture<T> submitTask(Supplier<T> supplier, String taskinfo);
    void RequestContextHolderInit(Object current);
}

/**
 * ��� TaskRun ����������İ汾�б��� runable -> Runable�����ﰴ��׼ Runnable��
 */
abstract class TaskRun implements Runnable {
    public abstract String getTaskInfo();
    public abstract void doRun();
    @Override
    public void run() {
        // ���Լ��� try/catch/trace
        doRun();
    }
}

/**
 * MetricAggregationService - ������ʵ��
 */
public class MetricAggregationService {

    private final ItaskExecutorService taskExecutorService;
    // ģ�� Redis ��Ԫ���ݼ���
    // ����ʵ��Ŀ��ע�� RedisTemplate ���ӻ����ȡMetadata
    public MetricAggregationService(ItaskExecutorService taskExecutorService) {
        this.taskExecutorService = taskExecutorService;
    }

    /**
     * ȫ����ڣ�query -> ���� List<MeasureDataVO>
     *   - apiDescriptors: Ҫ���õ���������Դ�б��� spring ע����죩
     *   - globalTimeoutSeconds: ȫ�������ȴ���ʱ
     */
    public List<MeasureDataVO> query(MetricQueryVO query,
                                     List<ApiCallDescriptor<?>> apiDescriptors,
                                     long globalTimeoutSeconds) throws ApplicationException {

        // 1. ���� Context������ Metadata��ʾ����Ϊ�� new��
        Metadata metadata = loadMetadataFromRedisOrCache();
        Context ctx = new Context(query, metadata);

        Aggregator aggregator = new Aggregator();
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // Executor service ���̳߳ؿ���������Ҫ schedule/timeout ʱ����������ƣ��˴���ѡ��
        ThreadPoolExecutor executor = taskExecutorService.findExecutorService();

        for (ApiCallDescriptor<?> descriptor : apiDescriptors) {
            // ����������·��/�����жϣ�shouldCall(descriptor, query, ctx)
            if (descriptor.isPaged()) {
                handlePagedDescriptor(descriptor, ctx, aggregator, futures);
            } else {
                handleNonPagedDescriptor(descriptor, ctx, aggregator, futures);
            }
        }

        // �ȴ�����������ɣ���ȫ�ֳ�ʱ��
        try {
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            all.get(globalTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // ��������δ��� -> ��¼/�澯/�������ص�ǰ������׳��쳣
            // ����ѡ���׳��쳣��Ҳ��ѡ�񷵻ص�ǰ�ۺϽ����
            throw new ApplicationException("Global timeout reached while waiting for downstream tasks", te);
        } catch (ExecutionException ee) {
            throw new ApplicationException("Execution error during aggregation", ee.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApplicationException("Interrupted while waiting for tasks", ie);
        }

        // �������ս��
        return aggregator.toResult();
    }

    private Metadata loadMetadataFromRedisOrCache() {
        // TODO: �� Redis �򻺴���� Metadata��ʾ��ֱ�� new��
        return new Metadata();
    }

    /**
     * paged �� descriptor ����������һҳ -> ������ʣ��ҳ��Semaphore ���Ʋ������ڣ�
     */
    @SuppressWarnings("unchecked")
    private <T> void handlePagedDescriptor(ApiCallDescriptor<T> descriptor, Context ctx,
                                           Aggregator aggregator, List<CompletableFuture<?>> futures) throws ApplicationException {

        ApiClient<T> client = descriptor.getClient();
        int pageSize = descriptor.getPageSize();

        // 1) ȡ��һҳ��ͬ���̳�ʱ����ȫ��
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
        // �����һҳ���ݣ�ͬ���ڵ�ǰ�̴߳������ⲻ��Ҫ���첽������
        processItems(firstPage.getItems(), descriptor, ctx, aggregator);

        int totalPages = Math.max(1, firstPage.getTotalPages());
        if (totalPages <= 1) return;

        // ���Ʋ������ڣ�descriptor ����
        int window = Math.min(descriptor.getMaxParallelPages(),  descriptor.getMaxParallelPages());
        // Ҳ���Ի��� system-config ������������������
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
     * �Ƿ�ҳ�� descriptor ����һ�������󲢴����첽��
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
                // per-item�쳣������ԣ���¼�����������ⵥ�������ݵ���ȫ����ʧ��
                // TODO: replace with logging framework
                System.err.println("Error processing item from " + descriptor.getName() + ": " + ex.getMessage());
            }
        }
    }
}

/**
 * ------------------- ʾ�� ApiClient ʵ�֣�ģ�����Σ����ڱ��ص�Ԫ���ԣ� -------------------
 * ��ʵʵ���У����� HTTP ���󡢽��� JSON������ʵ�� List<T>������� PageResult
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
 * ʾ������ʵ��
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
 * ʾ�� Filter �� Transformer
 */
class ExampleFilter implements IDataFilter<ExampleRecord> {
    @Override
    public boolean accept(MetricQueryVO query, Metadata meta, Context ctx, ExampleRecord item) {
        // ʾ�����ˣ�ֻ���ܷǿ�ֵ��Ҳ�ɰ� metricCodes/periodIds/orgCodes ����
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
 * ------------------- �򵥱��ز���ʾ����main�� -------------------
 * �������ڲ�������������֤���̣��㹤������ Spring ע�� ItaskExecutorService��
 */
class LocalTest {
    public static void main(String[] args) throws Exception {

        // 1) ����һ���򵥵� ItaskExecutorService ʵ�֣����ڱ��ز��ԣ�
        ItaskExecutorService executorService = new SimpleItaskExecutorServiceImpl();

        // 2) ���� MetricAggregationService
        MetricAggregationService svc = new MetricAggregationService(executorService);

        // 3) ����һ����ҳ�� ApiDescriptor
        ExampleApiClient client = new ExampleApiClient(true, 1050); // 1050�� -> ��ҳ
        ApiCallDescriptor<ExampleRecord> desc = new ApiCallDescriptor<>(
                "examplePagedSource",
                true,
                client,
                new ExampleFilter(),
                new ExampleTransformer()
        );
        desc.setPageSize(200); // ÿҳ200
        desc.setMaxParallelPages(5); // ���д���5���ɵ�����

        // 4) ��������
        MetricQueryVO q = new MetricQueryVO();
        // TODO ���þ��� query �ֶ�

        // 5) ִ��
        List<ApiCallDescriptor<?>> descriptors = Arrays.asList(desc);
        List<MeasureDataVO> result = svc.query(q, descriptors, 60); // ȫ���̳�ʱ60��

        System.out.println("Periods returned: " + result.size());
        for (MeasureDataVO vo : result) {
            System.out.println("Period: " + vo.getPeriodId() + ", keys: " + vo.getDataMap().keySet());
        }
        // 6) �رձ����̳߳�
        ((SimpleItaskExecutorServiceImpl) executorService).shutdown();
    }
}

/**
 * ------------------- ���ؼ� ItaskExecutorService ʵ�֣��������ԣ� -------------------
 * ����ʵ������ʹ���������е�ʵ�֣��˴�����ʾ��
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


����˵������ΰ���������������ǹ��̣�

�Ѹ��ఴ����֣�dto��client��service��aggregator �ȣ���ʾ����Ϊ�����Ķ����ŵ����ļ���

�� Spring �а� MetricAggregationService ��ǳ� @Service����ͨ��������ע�� ItaskExecutorService����������ʵ�֣���

����ʵ ApiClient �滻 ExampleApiClient��ʵ�� HTTP ���ã����ӳء��̳�ʱ������/�˱ܣ���JSON -> ʵ�������

�� IDataFilter �� ITransformer ��ʵ�ֱ�� Spring Bean���ɰ� ApiCallDescriptor ע�룬ʵ�ֿ����û����Ȳ�Ρ�

���� pageSize��maxParallelPages���Լ� globalTimeoutSeconds �������������� JVM �ڴ�ѹ����

��������㣨ÿҳ��С����ʱ��ʧ���ʣ������쳣������־�ϱ������ϵͳ��

�Ծۺ� key��metric|domain|measure�����������ۺϣ����/ƽ�������� StandardMeasure �ľۺ��߼��ŵ� Aggregator��ʹ�� LongAdder/DoubleAdder �� AtomicReference �Ա�֤��Ч�����ۺϡ�



�ظ� 2
̫���� �� ��ֱ�Ӹ���һ���ɱ��롢��ֱ�ӿ��������̵ĹǼ�ʵ�֣����� JDK8 + Spring Boot�����������з�ҳ���ԡ�Semaphore �������ڡ���ʱ/�쳣������ʽ��������ռ�ô����ڴ棩��������ȫ�ۺϣ��Լ���������ǵ� ItaskExecutorService �ύ���񲢵ȴ���������뾡����������ע�ͣ�������ֱ���滻/��չ ApiClient��Filter��Transformer �ȡ�

����Ĵ��밴�� com.company.metrics ��֯������԰�ģ�����ļ������ؼ��㣺

���� ItaskExecutorService.submitTask(Supplier<T>, String) ���� CompletableFuture<T>��������ǵķ�������������ͬ���滻���ɣ���

Redis �� MetadataService ��ȡ���������� Redis �����滻���ɣ���

ApiCallDescriptor �ǿ����õģ�ÿ������Դ�ṩ callPage / callAll �ķ�����

ʹ�� Semaphore ����ÿ������Դ�Ĳ���ҳ���󴰿ڣ�����˲����̳߳غľ�������ѹ������

����ҳ�浽�ֺ��������� -> transform -> aggregate����ʽ����������м伯�ϡ�

����ܳ�����ÿ���඼��ע�͡���������Ŀ�к�����ʵ�� ApiClient ʵ�֡�MetadataService���Լ������ڲ����쳣/��־���߼��ɡ�

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
 * �����Ǽ�ʵ�֣��߲�����ҳ���� + ����/ת��/�����ۺ�
 *
 * ˵����
 * - ����Ҫ�����ֳɵ����ļ�������һ�������д�������Ķ���
 * - �滻 TODO �Ĳ���Ϊ�㹫˾����ʵ�֣�HTTP client��Redis metadata����־���쳣��ȣ�
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
    // TODO: ʵ���ֶδ� Redis ����
    // ���磺Map metric->measures, domain mapping, org level mapping, etc
}

// ȫ����������
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

// ͳһ�ۺ϶���ģ��
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

// ���շ��ض���
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

// ------------------------ ����ӿڣ�Filter / Transformer ------------------------
interface IDataFilter<T> {
    boolean accept(MetricQueryVO query, Metadata metadata, Context ctx, T item);
}

interface ITransformer<T> {
    StandardMeasure transform(MetricQueryVO query, Metadata metadata, Context ctx, T item);
}

// ------------------------ ApiCallDescriptor�������ⲿ����Դ ------------------------
class ApiCallDescriptor<T> {
    private String name;
    private boolean paged;
    private int pageSize = 200; // default
    private int maxParallelPages = 10; // default�������ڣ��ɸ��ǣ�
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

// ------------------------ ������ȫ�ۺ��� ------------------------
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

// ------------------------ �ٶ��ⲿ����ӿڣ��������еģ� ------------------------
interface ItaskExecutorService {
    ThreadPoolExecutor findExecutorService();
    void submitTask(TaskRun taskRun);
    <T> CompletableFuture<T> submitTask(java.util.function.Supplier<T> supplier, String taskinfo);
    void RequestContextHolderInit(Object current);
}

// ���� TaskRun���㹫˾���У�
abstract class TaskRun implements Runnable {
    public abstract String getTaskInfo();
    public abstract void doRun();
    @Override
    public void run() {
        getTaskInfo();
        doRun();
    }
}

// ------------------------ MetadataService���� Redis ���� metadata��ʾ���� ------------------------
@Component
class MetadataService {
    // TODO: ע�� RedisTemplate / Cache client
    public Metadata loadMetadata() {
        // TODO: �� Redis ��ȡ������ Metadata
        return new Metadata();
    }
}

// ------------------------ MetricAggregationService������ʵ�֣� ------------------------
@Service
public class MetricAggregationService {

    @Autowired
    private ItaskExecutorService taskExecutorService;

    @Autowired
    private MetadataService metadataService;

    // ����ע�������е� ApiCallDescriptor ʵ����ÿ������Դһ����
    @Autowired(required = false)
    private List<ApiCallDescriptor<?>> apiDescriptors = Collections.emptyList();

    // ȫ�ֵȴ���ʱ���룩���������ۺ����̵�Ӳ��ʱ
    private final long globalTimeoutSeconds = 30L;

    // ȫ���̳߳�����ĳЩtimeout��װ��Ҳ����ʹ��taskExecutorService.findExecutorService��
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * ����ڣ����е��ø� data-source����ҳ���У���ʽ�ۺ�
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
     * �Ƿ���Ҫ����ĳ������Դ�����Ի��� query + metadata + ctx ������
     */
    private boolean shouldCall(ApiCallDescriptor<?> desc, MetricQueryVO query, Context ctx) {
        // TODO: add more sophisticated logic
        return true;
    }

    /**
     * ���÷�ҳ�ӿڲ��� per-call ��ʱ
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
     * ����һҳ��һ���Է��ص����ݣ����� -> ת�� -> �ۺ�
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

// ------------------------ ʾ�����������һ�� ApiCallDescriptor��αʵ�֣� ------------------------
@Component
class DemoDescriptors {

    @Autowired
    private SomeApiClient someApiClient; // TODO: your HTTP client bean

    // ���� Spring ������ descriptor ע�뵽 MetricAggregationService �� apiDescriptors �б�
    // �����ʾ����ι��� descriptor���� @Configuration ��ע��Ҳ���ԣ�
    public ApiCallDescriptor<DemoApiEntity> createDemoPagedDescriptor() {
        ApiCallDescriptor<DemoApiEntity> desc = new ApiCallDescriptor<>("demoSource", true);
        desc.setPageSize(500);
        desc.setMaxParallelPages(8);
        desc.setPerCallTimeoutSec(15);

        // callPageFunc: pageIndex -> PageResult<DemoApiEntity>
        desc.setCallPageFunc((pageIndex, ctx) -> {
            // ʹ����ʵ�ʵ� HTTP client ȥ�������νӿ�
            // e.g. someApiClient.query(pageIndex, desc.getPageSize(), ctx.getQuery(), ctx.getMetadata())
            // ���� SomeApiClient ���� PageResult<DemoApiEntity>
            return someApiClient.queryPage(pageIndex, desc.getPageSize(), ctx.getQuery());
        });

        // filter
        desc.setFilter((q, metadata, context, item) -> {
            // ҵ������߼���������� orgCodes/periodIds �Ƿ�ƥ��
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

// ------------------------ DemoApiEntity & SomeApiClient��ʾ���� ------------------------
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
    // TODO: ʵ�� HTTP client ע�벢ʵ�� queryPage
    public PageResult<DemoApiEntity> queryPage(int pageIndex, int pageSize, MetricQueryVO query) {
        // TODO: �������Σ�������Ӧ������ PageResult
        // ������α����ʾ��
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


��ΰ�����Ǽ���ص����ǹ��̣�ʵ���嵥��

���������ֳɵ��� java �ļ�����������˾��׼����

ʵ�� SomeApiClient����Ϊÿ������ʵ�� callPageFunc / callAllFunc��HTTP �ͻ���ʹ�����ӳء��̳�ʱ��������ʵ�壩��

�� Spring �����а�ÿ�� ApiCallDescriptor ע��Ϊ Bean������ @Configuration �в��� List<ApiCallDescriptor<?>> ע�� MetricAggregationService����

�����ǵ� ItaskExecutorService �� submitTask(supplier, info) ȥ�ύ�첽��������ʾ����ʹ�ô˽ӿڣ���

���� perCallTimeoutSec��maxParallelPages��globalTimeoutSeconds �����������Ժ�ҵ�� SLA ���š�

Ϊÿ�� API �������� / �۶ϲ��ԣ�resilience4j �����У������� callPageWithTimeout �ﲶ��ʱ�������Ի򽵼���

�����·׷�٣�traceId���ͼ����㣨ÿҳ��ʱ��ʧ���ʡ������������ۺ�ʱ��ȣ���
С�ᣨ��Ϊ�����ˣ�

�ṩ��һ���������ɱ���Ĳ��з�ҳ�����ۺϹǼ�ʵ�֣�ʹ�������е� ItaskExecutorService �����첽�ύ���������������ʱ���ơ�

���� Semaphore ����ҳ�������ڣ�����˲ʱ�����������̳߳�/����ѹ�塣

ÿҳ���ּ�����->ת��->�ۺϣ���ʽ�������������Դ���ݱ������ڴ棬���� GC ѹ����

��������ڹ�����ע�����չ����ȷ���衣

�����ϣ���������ڿ��ԣ���ѡ��һ����
A. �����ϴ����� ÿ����һ���ļ� ������ Git ��Ŀ�ṹ������ pom.xml��Spring Boot main��������������չʾ�ؼ��ļ����ݣ�
B. ����������� ��ʵ���� API �ӿ�������URL/���/���� JSON����������ʵ��һ����ʵ�� ApiCallDescriptor + Transformer + Filter �ľ���ʵ�֣����ṩ��Ԫ���ԣ�ģ�� HTTP ���أ���

��������Ҫ A ���� B����ֱ�Ӱ�ĳ����ʵ API ������ճ������������Ϊ�� API д������ʵ�֣���