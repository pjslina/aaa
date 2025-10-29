�������ٵ������ǣ�ԭ��ֻ��һ�����νӿ�B�����ڱ���˶�����νӿڣ�B, B1, B2�ȣ�����Щ�ӿ���Ҫ���е��ã�Ȼ��ϲ������ÿ���ӿڵĴ����������ƣ�������һЩ���죨����Ρ����Ρ��������ȣ�������ϣ�����һ�����ŵġ������ܵġ�����չ�Ľ��������

���˼·��

���󹫹����̣���ÿ���ӿڵĴ������̳���Ϊһ������Task����ÿ������������²��裺

�����ýӿڵ������ģ�������ҳ�����ȣ�

��ȡ��һҳ���ݣ����������ܼ�¼�������ڼ�����ҳ����

�����һҳ����

ʹ���첽��ʽ��ȡʣ��ҳ����

�ϲ��������ݲ�����

�������㣺ÿ���ӿڵ����²��ֿ��ܲ�ͬ�������Ҫ���������ͨ�����û����ģʽ��ʵ�֣�

������������ķ���

���ýӿڵķ���������������Ӧ��

ǰ�ù������ͺ��ù�������ʵ��

���е��ã�ʹ��CompletableFuture���е��ö���ӿڵ�����Ȼ��ϲ������

����չ�ԣ�����δ���������Ӹ���Ľӿڣ����Ӧ������չ��

ʵ�ַ�����

����1����������ӿ�

���ǿ��Զ���һ�����ͽӿڣ���ʾһ��������������ÿ����������һ���ӿڣ���B, B1, B2�������ݻ�ȡ�ʹ���

����2�����󹫹�����

��������������ȡ��һ���������ģ�巽���У����첿��ͨ�����󷽷���ӿ��ɾ�������ʵ�֡�

����3���ϲ����

ʹ��CompletableFuture.allOf�ȴ�����������ɣ�Ȼ��ϲ�ÿ�����񷵻ص�Map��

����4�������쳣

�������첽�����д����쳣��������Ϊһ���ӿ�ʧ�ܵ�����������ʧ�ܡ�

��ϸ��ƣ�

��������ӿڣ�PageDataTask����

ÿ�����񷵻�һ��CompletableFuture<Map<String, G>>�����첽����Ľ��Map��

�����ڲ�ʵ���������̡�

����������AbstractPageDataTask��ʵ��PageDataTask�ӿڣ���װ�������̣�

����ģ�巽�����������²��裺

a. ������һҳ�������

b. ���ýӿڻ�ȡ��һҳ����

c. ������һҳ���ݣ��õ���ҳ��

d. �����һҳ���ݣ�����ǰ�ù������ͺ��ù�������

e. �첽��ȡʣ��ҳ����

f. �ϲ���һҳ��ʣ��ҳ�����ݣ�����Map

���󷽷���������ʵ�֣���

������������ķ�����������һҳ��ָ��ҳ�Ĳ������ܲ�ͬ����ͨ����һҳ�ͺ���ҳֻ��ҳ�벻ͬ�����Կ������һ�����������ķ���������ҳ�룩

���ýӿڵķ��������������������Ӧ�����

������Ӧ����ķ���������Ӧ����ȡ�����б���ܼ�¼����

ǰ�ù������ķ������������ݣ�

���ù������ķ����������˺������ת��ΪG����

Ϊÿ���ӿڣ�B, B1, B2����������������࣬�̳�AbstractPageDataTask����ʵ�ֳ��󷽷���

����һ������ִ������TaskExecutor�������ڲ���ִ�ж��PageDataTask�����ϲ������

���������У�����ÿ���ӿڶ�Ӧ������ʵ�����ύ������ִ������

����ṹʾ����

��������ӿڣ�

public interface PageDataTask {
    CompletableFuture<Map<String, G>> execute();
}
������AbstractPageDataTask��

public abstract class AbstractPageDataTask implements PageDataTask {
    // �����ĺ�Ԫ����
    protected Context context;
    protected Metadata metadata;

    public AbstractPageDataTask(Context context, Metadata metadata) {
        this.context = context;
        this.metadata = metadata;
    }

    @Override
    public CompletableFuture<Map<String, G>> execute() {
        // 1. ������һҳ�������
        Object firstPageRequest = buildRequest(1);
        // 2. ���ýӿڻ�ȡ��һҳ����
        Object firstPageResponse = callApi(firstPageRequest);
        // 3. ������һҳ���ݣ��õ��ܼ�¼���������б�
        PageData firstPageData = parseResponse(firstPageResponse);
        int totalRecords = firstPageData.getTotalRecords();
        int totalPages = calculateTotalPages(totalRecords);

        // 4. �����һҳ����
        Map<String, G> resultMap = processPageData(firstPageData.getData());

        // 5. �����ҳ������1�����첽��ȡʣ��ҳ
        if (totalPages > 1) {
            List<CompletableFuture<Map<String, G>>> futureList = new ArrayList<>();
            for (int page = 2; page <= totalPages; page++) {
                int finalPage = page;
                CompletableFuture<Map<String, G>> future = CompletableFuture.supplyAsync(() -> {
                    Object request = buildRequest(finalPage);
                    Object response = callApi(request);
                    PageData pageData = parseResponse(response);
                    return processPageData(pageData.getData());
                }, executorService); // ʹ���̳߳�ִ��
                futureList.add(future);
            }

            // �ȴ������첽������ɣ��ϲ����
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futureList.toArray(new CompletableFuture[0]));
            CompletableFuture<Map<String, G>> totalFuture = allFutures.thenApply(v -> {
                Map<String, G> finalResult = new ConcurrentHashMap<>(resultMap);
                futureList.forEach(f -> {
                    try {
                        finalResult.putAll(f.get());
                    } catch (InterruptedException | ExecutionException e) {
                        // �����쳣�����Ը�����Ҫ�������׳�����ʱ�쳣���Ǽ�¼��־
                        throw new RuntimeException(e);
                    }
                });
                return finalResult;
            });
            return totalFuture;
        } else {
            return CompletableFuture.completedFuture(resultMap);
        }
    }

    // ����һҳ���ݵķ���
    private Map<String, G> processPageData(List<F> dataList) {
        // ǰ�ù�����
        List<E> filteredList = preFilter(dataList);
        // ���ù�������ת��ΪG���󣬲�����Map
        Map<String, G> pageResult = new HashMap<>();
        for (E item : filteredList) {
            G g = postFilter(item, context, metadata);
            String key = generateKey(item); // ����item����key����metricCode:::domainCode:::orgCode
            pageResult.put(key, g);
        }
        return pageResult;
    }

    // ����key�ķ���
    private String generateKey(E item) {
        return item.getMetricCode() + ":::" + item.getDomainCode() + ":::" + item.getOrgCode();
    }

    // ������ҳ��
    private int calculateTotalPages(int totalRecords) {
        int pageSize = getPageSize(); // ÿҳ��С�����Դ������Ļ�Ԫ�����л�ȡ������������ʵ��
        return (totalRecords + pageSize - 1) / pageSize;
    }

    // ���󷽷���������ʵ��
    protected abstract Object buildRequest(int page);
    protected abstract Object callApi(Object request);
    protected abstract PageData parseResponse(Object response);
    protected abstract List<E> preFilter(List<F> dataList);
    protected abstract G postFilter(E item, Context context, Metadata metadata);
    protected abstract int getPageSize();
}
���������࣬����ForBTask��

public class ForBTask extends AbstractPageDataTask {
    public ForBTask(Context context, Metadata metadata) {
        super(context, metadata);
    }

    @Override
    protected Object buildRequest(int page) {
        // �����ӿ�B���������
    }

    @Override
    protected Object callApi(Object request) {
        // ���ýӿ�B
    }

    @Override
    protected PageData parseResponse(Object response) {
        // �����ӿ�B����Ӧ
    }

    @Override
    protected List<E> preFilter(List<F> dataList) {
        // �ӿ�B��ǰ�ù�����
    }

    @Override
    protected G postFilter(E item, Context context, Metadata metadata) {
        // �ӿ�B�ĺ��ù�����
    }

    @Override
    protected int getPageSize() {
        // ���ؽӿ�B��ÿҳ��С
    }
}
���Ƶأ�����ForB1Task��ForB2Task��

����ִ������

@Component
public class TaskExecutor {
    @Autowired
    private ExecutorService executorService; // �����Զ����̳߳�

    public Map<String, G> executeTasks(List<PageDataTask> tasks) {
        List<CompletableFuture<Map<String, G>>> futures = tasks.stream()
                .map(task -> task.execute())
                .collect(Collectors.toList());

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));

        CompletableFuture<Map<String, G>> overallResult = allFutures.thenApply(v -> {
            Map<String, G> result = new HashMap<>();
            futures.forEach(f -> {
                try {
                    result.putAll(f.get());
                } catch (InterruptedException | ExecutionException e) {
                    // �쳣����
                    throw new RuntimeException(e);
                }
            });
            return result;
        });

        try {
            return overallResult.get();
        } catch (InterruptedException | ExecutionException e) {
            // �쳣����
            throw new RuntimeException(e);
        }
    }
}
���������е��ã�

@Service
public class MainService {
    @Autowired
    private TaskExecutor taskExecutor;

    public Map<String, G> getData(Context context, Metadata metadata) {
        List<PageDataTask> tasks = Arrays.asList(
                new ForBTask(context, metadata),
                new ForB1Task(context, metadata),
                new ForB2Task(context, metadata)
        );

        return taskExecutor.executeTasks(tasks);
    }
}
ע�⣺

�쳣��������ҵ�����󣬿�����Ҫ��ÿ��������쳣���е�����������һ������ʧ��Ӱ����������

�̳߳أ���Ҫ����ʵ����������̳߳أ�������Դ�˷Ѻ͹��Ⱦ�����

�Ż��㣺

���Խ��̳߳�����Ϊ�����õģ����ݽӿ�����������

���Կ���ʹ�ø���Ч�ĺϲ�����ķ�ʽ������ʹ��ConcurrentHashMap���ϲ�����������ںϲ�ʱ��������ƿ����

�����������˲��е��ö���ӿڵ�����ÿ���ӿڵĴ������̶�������������չ���������ӿ�ʱ��ֻ��Ҫ����һ�������༴�ɡ�

����������仯���ҽ���ʹ�ò���ģʽ + ģ�巽��ģʽ + �첽������ʵ�����ŵĽ����������������ϸ��Ʒ�����

1. ���Ľӿ����
// ��ҳ���ݴ��������Խӿ�
public interface PageDataProcessor<T, R> {
    
    // �����������
    T buildRequestParam(Context context, Metadata metadata, int page);
    
    // �������νӿ�
    PageResult<R> callApi(T request);
    
    // ǰ�ù�����
    List<?> preFilter(List<R> data, Context context, Metadata metadata);
    
    // ���ô����������ս��
    Map<String, ?> postProcess(List<?> filteredData, Context context, Metadata metadata);
    
    // ��ȡÿҳ��С
    default int getPageSize() {
        return 100;
    }
}

// ��ҳ�����װ��
@Data
public class PageResult<T> {
    private List<T> data;
    private int total;
    private int page;
    private int pageSize;
}

2. ģ�巽��������
public abstract class AbstractPageDataProcessor<T, R, E, G> 
    implements PageDataProcessor<T, R> {
    
    @Override
    public Map<String, G> execute(Context context, Metadata metadata) {
        // 1. ��ȡ��һҳ����
        T firstPageRequest = buildRequestParam(context, metadata, 1);
        PageResult<R> firstPageResult = callApi(firstPageRequest);
        
        // 2. �����һҳ����
        Map<String, G> resultMap = processSinglePage(
            firstPageResult.getData(), context, metadata);
        
        // 3. ������ҳ�����첽����ʣ��ҳ
        int totalPages = calculateTotalPages(firstPageResult.getTotal());
        if (totalPages > 1) {
            Map<String, G> remainingResults = processRemainingPages(
                context, metadata, totalPages);
            resultMap.putAll(remainingResults);
        }
        
        return resultMap;
    }
    
    private Map<String, G> processRemainingPages(Context context, 
            Metadata metadata, int totalPages) {
        
        List<CompletableFuture<Map<String, G>>> futures = new ArrayList<>();
        
        for (int page = 2; page <= totalPages; page++) {
            final int currentPage = page;
            CompletableFuture<Map<String, G>> future = CompletableFuture.supplyAsync(() -> {
                T request = buildRequestParam(context, metadata, currentPage);
                PageResult<R> pageResult = callApi(request);
                return processSinglePage(pageResult.getData(), context, metadata);
            }, getExecutorService());
            
            futures.add(future);
        }
        
        // �ȴ������������
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        return allFutures.thenApply(v -> 
            futures.stream()
                .map(CompletableFuture::join)
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        ).join();
    }
    
    private Map<String, G> processSinglePage(List<R> pageData, 
            Context context, Metadata metadata) {
        // ǰ�ù���
        List<E> filteredData = preFilter(pageData, context, metadata);
        // ���ô���
        return postProcess(filteredData, context, metadata);
    }
    
    private int calculateTotalPages(int totalRecords) {
        return (totalRecords + getPageSize() - 1) / getPageSize();
    }
    
    protected abstract ExecutorService getExecutorService();
}

3. ���崦����ʵ��
@Component
public class BPageProcessor extends AbstractPageDataProcessor<BRequest, BResponse, BFiltered, G> {
    
    @Autowired
    private ExecutorService taskExecutor;
    
    @Override
    public BRequest buildRequestParam(Context context, Metadata metadata, int page) {
        // B�ӿ��ض��Ĳ��������߼�
        return BRequest.builder()
            .page(page)
            .pageSize(getPageSize())
            // ... ��������
            .build();
    }
    
    @Override
    public PageResult<BResponse> callApi(BRequest request) {
        // ����B�ӿ�
        return bApiClient.call(request);
    }
    
    @Override
    public List<BFiltered> preFilter(List<BResponse> data, Context context, Metadata metadata) {
        // B�ӿ��ض���ǰ�ù����߼�
        return data.stream()
            .filter(item -> meetBusinessRequirement(item, context))
            .map(this::convertToFiltered)
            .collect(Collectors.toList());
    }
    
    @Override
    public Map<String, G> postProcess(List<BFiltered> filteredData, 
            Context context, Metadata metadata) {
        // B�ӿ��ض��ĺ��ô����߼�
        return filteredData.stream()
            .collect(Collectors.toMap(
                this::generateKey,
                item -> calculateG(item, context, metadata)
            ));
    }
    
    private String generateKey(BFiltered item) {
        return item.getMetricCode() + ":::" + 
               item.getDomainCode() + ":::" + 
               item.getOrgCode();
    }
    
    @Override
    protected ExecutorService getExecutorService() {
        return taskExecutor;
    }
}

4. ���е���Э����
@Component
public class ParallelApiCoordinator {
    
    @Autowired
    private BPageProcessor bProcessor;
    
    @Autowired
    private B1PageProcessor b1Processor;
    
    @Autowired
    private B2PageProcessor b2Processor;
    
    public Map<String, Object> executeAll(Context context, Metadata metadata) {
        // ���е������д�����
        CompletableFuture<Map<String, G>> bFuture = CompletableFuture
            .supplyAsync(() -> bProcessor.execute(context, metadata));
        
        CompletableFuture<Map<String, G>> b1Future = CompletableFuture
            .supplyAsync(() -> b1Processor.execute(context, metadata));
            
        CompletableFuture<Map<String, G>> b2Future = CompletableFuture
            .supplyAsync(() -> b2Processor.execute(context, metadata));
        
        // �ȴ����н�����ϲ�
        return CompletableFuture.allOf(bFuture, b1Future, b2Future)
            .thenApply(v -> {
                Map<String, Object> finalResult = new ConcurrentHashMap<>();
                try {
                    finalResult.putAll(bFuture.get());
                    finalResult.putAll(b1Future.get());
                    finalResult.putAll(b2Future.get());
                } catch (Exception e) {
                    throw new RuntimeException("�ϲ����ʧ��", e);
                }
                return finalResult;
            }).join();
    }
}

5. ���ú��̳߳ع���

@Configuration
public class AsyncConfig {
    
    @Bean("pageTaskExecutor")
    public ExecutorService pageTaskExecutor() {
        return new ThreadPoolExecutor(
            10,  // �����߳���
            50,  // ����߳���  
            60L, // �����̴߳��ʱ��
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}

6. ʹ�÷�ʽ
@Service
public class YourService {
    
    @Autowired
    private ParallelApiCoordinator coordinator;
    
    public Map<String, Object> process(Context context, Metadata metadata) {
        return coordinator.executeAll(context, metadata);
    }
}

������ƣ�
����չ�ԣ������ӿ�ֻ��ʵ��PageDataProcessor�ӿ�

�����ܣ��ӿڼ䲢�� + ��ҳ���ݲ��д���

��ά���ԣ�ְ����룬ÿ��������ֻ��ע�Լ����߼�

�����ԣ�ģ�巽����װ�˹����ķ�ҳ�����߼�

����ԣ��������ɵ������в��Ժʹ��������

������Ƽ������˵�ǰ����ҲΪδ�����ܵ���չ���������ӿ�B3��B4�ȣ��ṩ�����õ�֧�֡�


--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

�������ٵ������ǣ�ԭ��ֻ��һ�����νӿ�B�������ж�����νӿڣ�B��B1��B2�ȣ�����Щ�ӿ���Ҫ���е��ã�Ȼ��ϲ������ÿ���ӿڵĴ����������ƣ������ڲ��죨���ҳ��ʽ�������߼��ȣ���ͬʱ����Щ�ӿڿ��ܲ���Ҫ��ҳ����Щ���ܲ���Ҫǰ�ù����������ǻ���Ҫȷ��Context��Metadataֻ����һ�Σ����ڶ���������и��á�

���˼·��

ʹ��ģ�巽��ģʽ�����������̣����������ඨ��ĳЩ���裨���ҳ�߼��������߼��ȣ���

ʹ�ò���ģʽ����װÿ���ӿڵĴ�����ԣ�B��B1��B2�ȷֱ��Ӧ��ͬ�Ĳ��ԣ���

ʹ�ù���ģʽ��������ͬ�Ĳ���ʵ����

ʹ�����ģʽ��ͳһ�����ҳ�ͷǷ�ҳ�Ľӿڣ������ǳ���Ϊͬһ�����̣�����ҳ�Ĳ�����Բ�ͬ����

ʹ���첽��̣�CompletableFuture�������е��ö���ӿڣ����ϲ������

����ÿ���������в�����ͬ����Ҳ�в��죬���ǿ��Խ����̷ֽ�Ϊ���²��裺

����1������������������ܲ�ͬ��

����2����ȡ���ݣ����ܷ�ҳ��һ���Ի�ȡ��

����3��ǰ�ù��ˣ����ܲ�ͬ����Щ���ܲ���Ҫ��

����4�����ô������ܲ�ͬ��

���ǿ��Զ���һ�������࣬����������ģ�廯��������������д�ض��Ĳ��衣ͬʱ�����ڷ�ҳ�ͷǷ�ҳ�Ĵ������ǿ���ͨ��ģ�巽���е�ѭ���������ҳ�����Ƿ�ҳ�Ŀ�����дΪֻ����һҳ��

������ƣ�

���崦�����ӿڣ�ÿ��������������һ�����νӿڣ�B��B1��B2�ȣ���

����һ������ģ���࣬ʵ�ֹ������̣��磺�Ȼ�ȡ��һҳ��Ȼ�������ҳ����Ȼ���첽��ȡʣ��ҳ��Ȼ��ϲ����ݣ�Ȼ�����ǰ�ù��˺ͺ��ô���

���ڲ���Ҫ��ҳ�Ĵ����������ǿ�����д��ҳ��ط�����ʹ���Ϊֻ��ȡһҳ����һ���Ի�ȡ�������ݣ���

���ڲ���Ҫǰ�ù��˵Ĵ����������ǿ�����дǰ�ù��˷�����ʹ��ֱ�ӷ���ԭʼ���ݣ�����ͨ��һ���չ���������

ͬʱ��������Ҫһ��Э�����������е�����Щ�����������ϲ������

ע�⣺Context��Metadataֻ����һ�Σ�����������Э�������ȼ��غã�Ȼ�󴫵ݸ�ÿ����������

��ϸ��ƣ�

����һ�����崦�����ӿ�

��������������ģ���࣬ʵ����Ҫ����

��������������崦�������̳г���ģ���࣬����д��Ҫ���Ƶķ���

�����ģ�����Э���������ڲ��е��ö�������������ϲ����

�����壺�������ϲ����ԣ�����򵥺ϲ�Map���ɣ���Ϊÿ�����������صĶ���Map���ϲ�ʱֱ��putAll��ע��key�ĳ�ͻ���⣬������ܳ�ͻ����Ҫ���������ռ���룬����������key�����ض��ֶ�ƴ�ӣ�Ӧ�ò����ͻ��

�������ǿ�ʼ����ʵ�֣�

ע�⣺����ʹ��Spring Boot�����Կ���ʹ��ע��������Bean��

���ڴ���ϳ����ҷֲ���չʾ��

�������ӿ�

����������������һ�����ģ�巽��ģʽ������ģʽ������ģʽ��������ģʽ�����Ž�����������������㿪��ԭ�򡢵�һְ��ԭ�򣬲��Ҿ��и߶ȿ���չ�ԡ�

1. ���Ľӿڶ���
// �������ӿ�
public interface ApiProcessor {
    String getProcessorType();
    Map<String, Object> process(Context context, Metadata metadata);
}

// ��ҳ���Խӿ�
public interface PaginationStrategy<T> {
    boolean supportsPagination();
    PageResult<T> fetchFirstPage(Context context, Metadata metadata);
    PageResult<T> fetchPage(Context context, Metadata metadata, int page);
    int calculateTotalPages(int totalRecords, int pageSize);
}

// ���������ӿ�
public interface FilterChain<T, R> {
    List<R> doFilter(List<T> data, Context context, Metadata metadata);
}

// �����װ���ӿ�
public interface ResultAssembler<T, R> {
    Map<String, R> assemble(List<T> filteredData, Context context, Metadata metadata);
}

2. �����ĺ�Ԫ���ݹ�������������

@Component
public class ContextMetadataManager {
    private volatile Context context;
    private volatile Metadata metadata;
    private final Object contextLock = new Object();
    private final Object metadataLock = new Object();
    
    public Context getOrCreateContext(Supplier<Context> supplier) {
        if (context == null) {
            synchronized (contextLock) {
                if (context == null) {
                    context = supplier.get();
                }
            }
        }
        return context;
    }
    
    public Metadata getOrCreateMetadata(Supplier<Metadata> supplier) {
        if (metadata == null) {
            synchronized (metadataLock) {
                if (metadata == null) {
                    metadata = supplier.get();
                }
            }
        }
        return metadata;
    }
    
    public void clear() {
        synchronized (contextLock) {
            context = null;
        }
        synchronized (metadataLock) {
            metadata = null;
        }
    }
}

3. �����ģ�崦����

public abstract class AbstractApiProcessor<T, R, E, G> implements ApiProcessor {
    
    @Autowired
    protected ExecutorService taskExecutor;
    
    @Autowired
    protected ContextMetadataManager contextMetadataManager;
    
    // ģ�巽�� - ������������
    @Override
    public Map<String, Object> process(Context context, Metadata metadata) {
        // 1. ������֤
        validateParameters(context, metadata);
        
        // 2. ��ȡ���ݣ���ҳ��Ƿ�ҳ��
        List<T> allData = fetchAllData(context, metadata);
        
        // 3. ǰ�ù���
        List<E> filteredData = applyPreFilters(allData, context, metadata);
        
        // 4. ���ô�����װ���
        return assembleResult(filteredData, context, metadata);
    }
    
    // ���ӷ��� - ������֤
    protected void validateParameters(Context context, Metadata metadata) {
        // Ĭ��ʵ�֣��������д
    }
    
    // ���󷽷� - ���ݻ�ȡ����
    protected List<T> fetchAllData(Context context, Metadata metadata) {
        PaginationStrategy<T> paginationStrategy = getPaginationStrategy();
        
        if (paginationStrategy != null && paginationStrategy.supportsPagination()) {
            return fetchDataWithPagination(context, metadata, paginationStrategy);
        } else {
            return fetchDataWithoutPagination(context, metadata);
        }
    }
    
    // ��ҳ���ݻ�ȡ
    private List<T> fetchDataWithPagination(Context context, Metadata metadata, 
                                          PaginationStrategy<T> strategy) {
        // ��ȡ��һҳ
        PageResult<T> firstPage = strategy.fetchFirstPage(context, metadata);
        List<T> allData = new ArrayList<>(firstPage.getData());
        
        // ������ҳ��
        int totalPages = strategy.calculateTotalPages(firstPage.getTotal(), firstPage.getPageSize());
        
        if (totalPages > 1) {
            // �첽��ȡʣ��ҳ
            List<CompletableFuture<PageResult<T>>> futures = new ArrayList<>();
            
            for (int page = 2; page <= totalPages; page++) {
                final int currentPage = page;
                CompletableFuture<PageResult<T>> future = CompletableFuture.supplyAsync(
                    () -> strategy.fetchPage(context, metadata, currentPage), taskExecutor);
                futures.add(future);
            }
            
            // �ȴ����ϲ����
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                    .map(CompletableFuture::join)
                    .map(PageResult::getData)
                    .flatMap(List::stream)
                    .collect(Collectors.toList()))
                .join()
                .forEach(allData::add);
        }
        
        return allData;
    }
    
    // �Ƿ�ҳ���ݻ�ȡ
    protected abstract List<T> fetchDataWithoutPagination(Context context, Metadata metadata);
    
    // ������Ӧ��
    protected List<E> applyPreFilters(List<T> data, Context context, Metadata metadata) {
        FilterChain<T, E> filterChain = getFilterChain();
        return filterChain != null ? 
            filterChain.doFilter(data, context, metadata) : 
            (List<E>) data; // ���û�й�������ֱ�ӷ���
    }
    
    // �����װ
    protected Map<String, Object> assembleResult(List<E> filteredData, Context context, Metadata metadata) {
        ResultAssembler<E, G> assembler = getResultAssembler();
        if (assembler != null) {
            return (Map<String, Object>) assembler.assemble(filteredData, context, metadata);
        }
        return Collections.emptyMap();
    }
    
    // ���󷽷� - ������ʵ��
    protected abstract PaginationStrategy<T> getPaginationStrategy();
    protected abstract FilterChain<T, E> getFilterChain();
    protected abstract ResultAssembler<E, G> getResultAssembler();
}

4. ���崦����ʵ��
// B�ӿڴ�����
@Component
public class BApiProcessor extends AbstractApiProcessor<BResponse, BFiltered, BResult> {
    
    @Autowired
    private BApiClient bApiClient;
    
    @Override
    public String getProcessorType() {
        return "B";
    }
    
    @Override
    protected List<BResponse> fetchDataWithoutPagination(Context context, Metadata metadata) {
        // B�ӿڲ���Ҫ�Ƿ�ҳʵ��
        throw new UnsupportedOperationException("B�ӿ�ֻ֧�ַ�ҳ");
    }
    
    @Override
    protected PaginationStrategy<BResponse> getPaginationStrategy() {
        return new BPaginationStrategy();
    }
    
    @Override
    protected FilterChain<BResponse, BFiltered> getFilterChain() {
        return new BFilterChain();
    }
    
    @Override
    protected ResultAssembler<BFiltered, BResult> getResultAssembler() {
        return new BResultAssembler();
    }
    
    // B�ӿڷ�ҳ����
    private class BPaginationStrategy implements PaginationStrategy<BResponse> {
        @Override
        public boolean supportsPagination() {
            return true;
        }
        
        @Override
        public PageResult<BResponse> fetchFirstPage(Context context, Metadata metadata) {
            BRequest request = buildBRequest(context, metadata, 1);
            return bApiClient.callWithPagination(request);
        }
        
        @Override
        public PageResult<BResponse> fetchPage(Context context, Metadata metadata, int page) {
            BRequest request = buildBRequest(context, metadata, page);
            return bApiClient.callWithPagination(request);
        }
        
        @Override
        public int calculateTotalPages(int totalRecords, int pageSize) {
            return (int) Math.ceil((double) totalRecords / pageSize);
        }
        
        private BRequest buildBRequest(Context context, Metadata metadata, int page) {
            // ���ӵĲ��������߼�
            return BRequest.builder()
                .page(page)
                .pageSize(100)
                // ... ��������
                .build();
        }
    }
    
    // B�ӿڹ�������
    private class BFilterChain implements FilterChain<BResponse, BFiltered> {
        private final List<Filter<BResponse, BFiltered>> filters = Arrays.asList(
            new BusinessFilter(),
            new ValidationFilter()
            // ���Զ�̬��Ӹ��������
        );
        
        @Override
        public List<BFiltered> doFilter(List<BResponse> data, Context context, Metadata metadata) {
            List<BFiltered> currentData = data.stream()
                .map(BFiltered::new)
                .collect(Collectors.toList());
                
            for (Filter<BResponse, BFiltered> filter : filters) {
                currentData = filter.doFilter(currentData, context, metadata);
            }
            
            return currentData;
        }
    }
    
    // B�ӿڽ����װ��
    private class BResultAssembler implements ResultAssembler<BFiltered, BResult> {
        @Override
        public Map<String, BResult> assemble(List<BFiltered> filteredData, Context context, Metadata metadata) {
            return filteredData.stream()
                .collect(Collectors.toMap(
                    this::generateKey,
                    item -> calculateResult(item, context, metadata)
                ));
        }
        
        private String generateKey(BFiltered item) {
            return item.getMetricCode() + ":::" + 
                   item.getDomainCode() + ":::" + 
                   item.getOrgCode();
        }
        
        private BResult calculateResult(BFiltered item, Context context, Metadata metadata) {
            // ���ӵĽ�������߼�
            return BResult.builder()
                // ... �����߼�
                .build();
        }
    }
}

// B1�ӿڴ�����������Ҫ��ҳ��
@Component
public class B1ApiProcessor extends AbstractApiProcessor<B1Response, B1Filtered, B1Result> {
    
    @Autowired
    private B1ApiClient b1ApiClient;
    
    @Override
    public String getProcessorType() {
        return "B1";
    }
    
    @Override
    protected List<B1Response> fetchDataWithoutPagination(Context context, Metadata metadata) {
        B1Request request = buildB1Request(context, metadata);
        return b1ApiClient.callWithoutPagination(request);
    }
    
    @Override
    protected PaginationStrategy<B1Response> getPaginationStrategy() {
        return null; // B1�ӿڲ���Ҫ��ҳ
    }
    
    @Override
    protected FilterChain<B1Response, B1Filtered> getFilterChain() {
        return null; // B1�ӿڲ���Ҫǰ�ù���
    }
    
    @Override
    protected ResultAssembler<B1Filtered, B1Result> getResultAssembler() {
        return new B1ResultAssembler();
    }
    
    // ... ����B�ӿڵ�ʵ��
}

5. ������������Э����
// ����������
@Component
public class ApiProcessorFactory {
    
    @Autowired
    private List<ApiProcessor> processors;
    
    private final Map<String, ApiProcessor> processorMap = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        processors.forEach(processor -> 
            processorMap.put(processor.getProcessorType(), processor));
    }
    
    public ApiProcessor getProcessor(String type) {
        ApiProcessor processor = processorMap.get(type);
        if (processor == null) {
            throw new IllegalArgumentException("δ֪�Ĵ���������: " + type);
        }
        return processor;
    }
    
    public List<ApiProcessor> getProcessors(List<String> types) {
        return types.stream()
            .map(this::getProcessor)
            .collect(Collectors.toList());
    }
}

// ����Э����
@Component
public class ParallelProcessorCoordinator {
    
    @Autowired
    private ApiProcessorFactory processorFactory;
    
    @Autowired
    private ContextMetadataManager contextMetadataManager;
    
    public Map<String, Object> process(List<String> processorTypes, 
                                      Supplier<Context> contextSupplier,
                                      Supplier<Metadata> metadataSupplier) {
        // ȷ��Context��Metadataֻ����һ��
        Context context = contextMetadataManager.getOrCreateContext(contextSupplier);
        Metadata metadata = contextMetadataManager.getOrCreateMetadata(metadataSupplier);
        
        // ��ȡ���д�����
        List<ApiProcessor> processors = processorFactory.getProcessors(processorTypes);
        
        // ���д���
        List<CompletableFuture<Map<String, Object>>> futures = processors.stream()
            .map(processor -> CompletableFuture.supplyAsync(
                () -> processor.process(context, metadata)))
            .collect(Collectors.toList());
        
        // �ϲ����
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (v1, v2) -> v2 // ����key�ظ��Ĳ���
                )))
            .join();
    }
}

6. ʹ��ʾ��

@Service
public class YourBusinessService {
    
    @Autowired
    private ParallelProcessorCoordinator coordinator;
    
    public Map<String, Object> processData() {
        return coordinator.process(
            Arrays.asList("B", "B1", "B2"),
            this::buildContext,    // Context�����߼�
            this::buildMetadata    // Metadata�����߼�
        );
    }
    
    private Context buildContext() {
        // ���ӵ�Context�����߼�
        return new Context();
    }
    
    private Metadata buildMetadata() {
        // ���ӵ�Metadata�����߼�
        return new Metadata();
    }
}

������ƣ�
����ԭ������������ֻ��ʵ�ֽӿڣ������޸����д���

��һְ��ÿ����ֻ�����ض�����

�����ܣ�Context/Metadata���� + ���д���

����ԣ�֧�ַ�ҳ/�Ƿ�ҳ���й���/�޹��˵ȶ��ֳ���

����չ�ԣ���������µĴ���������

��ά���ԣ�������ְ������ģ�巽��

�������ܹ����ŵش����������仯��ͬʱΪδ������չ�ṩ�����õĻ�����
