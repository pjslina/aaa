我们面临的需求是：原本只有一个下游接口B，现在变成了多个下游接口（B, B1, B2等），这些接口需要并行调用，然后合并结果。每个接口的处理流程类似，但存在一些差异（如入参、出参、过滤器等）。我们希望设计一个优雅的、高性能的、可扩展的解决方案。

设计思路：

抽象公共流程：将每个接口的处理流程抽象为一个任务（Task），每个任务包含以下步骤：

构建该接口的上下文（包括分页参数等）

获取第一页数据，并解析出总记录数（用于计算总页数）

处理第一页数据

使用异步方式获取剩余页数据

合并所有数据并返回

处理差异点：每个接口的以下部分可能不同，因此需要抽象出来，通过配置或策略模式来实现：

构建请求参数的方法

调用接口的方法（包括解析响应）

前置过滤器和后置过滤器的实现

并行调用：使用CompletableFuture并行调用多个接口的任务，然后合并结果。

可扩展性：考虑未来可能增加更多的接口，设计应便于扩展。

实现方案：

步骤1：定义任务接口

我们可以定义一个泛型接口，表示一个处理流程任务。每个任务负责处理一个接口（如B, B1, B2）的数据获取和处理。

步骤2：抽象公共部分

将公共的流程提取到一个抽象类或模板方法中，差异部分通过抽象方法或接口由具体子类实现。

步骤3：合并结果

使用CompletableFuture.allOf等待所有任务完成，然后合并每个任务返回的Map。

步骤4：处理异常

考虑在异步任务中处理异常，避免因为一个接口失败导致整个任务失败。

详细设计：

定义任务接口（PageDataTask）：

每个任务返回一个CompletableFuture<Map<String, G>>，即异步计算的结果Map。

任务内部实现上述流程。

创建抽象类AbstractPageDataTask，实现PageDataTask接口，封装公共流程：

定义模板方法，包括以下步骤：

a. 构建第一页请求参数

b. 调用接口获取第一页数据

c. 解析第一页数据，得到总页数

d. 处理第一页数据（调用前置过滤器和后置过滤器）

e. 异步获取剩余页数据

f. 合并第一页和剩余页的数据，返回Map

抽象方法（由子类实现）：

构建请求参数的方法（构建第一页和指定页的参数可能不同，但通常第一页和后续页只有页码不同，所以可以设计一个构建参数的方法，传入页码）

调用接口的方法（传入参数，返回响应结果）

解析响应结果的方法（从响应中提取数据列表和总记录数）

前置过滤器的方法（过滤数据）

后置过滤器的方法（将过滤后的数据转换为G对象）

为每个接口（B, B1, B2）创建具体的任务类，继承AbstractPageDataTask，并实现抽象方法。

创建一个任务执行器（TaskExecutor），用于并行执行多个PageDataTask，并合并结果。

在主流程中，创建每个接口对应的任务实例，提交给任务执行器。

代码结构示例：

定义任务接口：

public interface PageDataTask {
    CompletableFuture<Map<String, G>> execute();
}
抽象类AbstractPageDataTask：

public abstract class AbstractPageDataTask implements PageDataTask {
    // 上下文和元数据
    protected Context context;
    protected Metadata metadata;

    public AbstractPageDataTask(Context context, Metadata metadata) {
        this.context = context;
        this.metadata = metadata;
    }

    @Override
    public CompletableFuture<Map<String, G>> execute() {
        // 1. 构建第一页请求参数
        Object firstPageRequest = buildRequest(1);
        // 2. 调用接口获取第一页数据
        Object firstPageResponse = callApi(firstPageRequest);
        // 3. 解析第一页数据，得到总记录数和数据列表
        PageData firstPageData = parseResponse(firstPageResponse);
        int totalRecords = firstPageData.getTotalRecords();
        int totalPages = calculateTotalPages(totalRecords);

        // 4. 处理第一页数据
        Map<String, G> resultMap = processPageData(firstPageData.getData());

        // 5. 如果总页数大于1，则异步获取剩余页
        if (totalPages > 1) {
            List<CompletableFuture<Map<String, G>>> futureList = new ArrayList<>();
            for (int page = 2; page <= totalPages; page++) {
                int finalPage = page;
                CompletableFuture<Map<String, G>> future = CompletableFuture.supplyAsync(() -> {
                    Object request = buildRequest(finalPage);
                    Object response = callApi(request);
                    PageData pageData = parseResponse(response);
                    return processPageData(pageData.getData());
                }, executorService); // 使用线程池执行
                futureList.add(future);
            }

            // 等待所有异步任务完成，合并结果
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futureList.toArray(new CompletableFuture[0]));
            CompletableFuture<Map<String, G>> totalFuture = allFutures.thenApply(v -> {
                Map<String, G> finalResult = new ConcurrentHashMap<>(resultMap);
                futureList.forEach(f -> {
                    try {
                        finalResult.putAll(f.get());
                    } catch (InterruptedException | ExecutionException e) {
                        // 处理异常，可以根据需要决定是抛出运行时异常还是记录日志
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

    // 处理一页数据的方法
    private Map<String, G> processPageData(List<F> dataList) {
        // 前置过滤器
        List<E> filteredList = preFilter(dataList);
        // 后置过滤器：转换为G对象，并生成Map
        Map<String, G> pageResult = new HashMap<>();
        for (E item : filteredList) {
            G g = postFilter(item, context, metadata);
            String key = generateKey(item); // 根据item生成key，如metricCode:::domainCode:::orgCode
            pageResult.put(key, g);
        }
        return pageResult;
    }

    // 生成key的方法
    private String generateKey(E item) {
        return item.getMetricCode() + ":::" + item.getDomainCode() + ":::" + item.getOrgCode();
    }

    // 计算总页数
    private int calculateTotalPages(int totalRecords) {
        int pageSize = getPageSize(); // 每页大小，可以从上下文或元数据中获取，或者由子类实现
        return (totalRecords + pageSize - 1) / pageSize;
    }

    // 抽象方法，由子类实现
    protected abstract Object buildRequest(int page);
    protected abstract Object callApi(Object request);
    protected abstract PageData parseResponse(Object response);
    protected abstract List<E> preFilter(List<F> dataList);
    protected abstract G postFilter(E item, Context context, Metadata metadata);
    protected abstract int getPageSize();
}
具体任务类，例如ForBTask：

public class ForBTask extends AbstractPageDataTask {
    public ForBTask(Context context, Metadata metadata) {
        super(context, metadata);
    }

    @Override
    protected Object buildRequest(int page) {
        // 构建接口B的请求参数
    }

    @Override
    protected Object callApi(Object request) {
        // 调用接口B
    }

    @Override
    protected PageData parseResponse(Object response) {
        // 解析接口B的响应
    }

    @Override
    protected List<E> preFilter(List<F> dataList) {
        // 接口B的前置过滤器
    }

    @Override
    protected G postFilter(E item, Context context, Metadata metadata) {
        // 接口B的后置过滤器
    }

    @Override
    protected int getPageSize() {
        // 返回接口B的每页大小
    }
}
类似地，创建ForB1Task和ForB2Task。

任务执行器：

@Component
public class TaskExecutor {
    @Autowired
    private ExecutorService executorService; // 可以自定义线程池

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
                    // 异常处理
                    throw new RuntimeException(e);
                }
            });
            return result;
        });

        try {
            return overallResult.get();
        } catch (InterruptedException | ExecutionException e) {
            // 异常处理
            throw new RuntimeException(e);
        }
    }
}
在主服务中调用：

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
注意：

异常处理：根据业务需求，可能需要对每个任务的异常进行单独处理，避免一个任务失败影响其他任务。

线程池：需要根据实际情况配置线程池，避免资源浪费和过度竞争。

优化点：

可以将线程池配置为可配置的，根据接口数量调整。

可以考虑使用更高效的合并结果的方式，例如使用ConcurrentHashMap来合并结果，避免在合并时出现性能瓶颈。

这个设计满足了并行调用多个接口的需求，每个接口的处理流程独立，且易于扩展。当新增接口时，只需要新增一个任务类即可。

针对你的需求变化，我建议使用策略模式 + 模板方法模式 + 异步编排来实现优雅的解决方案。以下是详细设计方案：

1. 核心接口设计
// 分页数据处理器策略接口
public interface PageDataProcessor<T, R> {
    
    // 构建请求参数
    T buildRequestParam(Context context, Metadata metadata, int page);
    
    // 调用下游接口
    PageResult<R> callApi(T request);
    
    // 前置过滤器
    List<?> preFilter(List<R> data, Context context, Metadata metadata);
    
    // 后置处理：生成最终结果
    Map<String, ?> postProcess(List<?> filteredData, Context context, Metadata metadata);
    
    // 获取每页大小
    default int getPageSize() {
        return 100;
    }
}

// 分页结果包装类
@Data
public class PageResult<T> {
    private List<T> data;
    private int total;
    private int page;
    private int pageSize;
}

2. 模板方法抽象类
public abstract class AbstractPageDataProcessor<T, R, E, G> 
    implements PageDataProcessor<T, R> {
    
    @Override
    public Map<String, G> execute(Context context, Metadata metadata) {
        // 1. 获取第一页数据
        T firstPageRequest = buildRequestParam(context, metadata, 1);
        PageResult<R> firstPageResult = callApi(firstPageRequest);
        
        // 2. 处理第一页数据
        Map<String, G> resultMap = processSinglePage(
            firstPageResult.getData(), context, metadata);
        
        // 3. 计算总页数并异步处理剩余页
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
        
        // 等待所有任务完成
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
        // 前置过滤
        List<E> filteredData = preFilter(pageData, context, metadata);
        // 后置处理
        return postProcess(filteredData, context, metadata);
    }
    
    private int calculateTotalPages(int totalRecords) {
        return (totalRecords + getPageSize() - 1) / getPageSize();
    }
    
    protected abstract ExecutorService getExecutorService();
}

3. 具体处理器实现
@Component
public class BPageProcessor extends AbstractPageDataProcessor<BRequest, BResponse, BFiltered, G> {
    
    @Autowired
    private ExecutorService taskExecutor;
    
    @Override
    public BRequest buildRequestParam(Context context, Metadata metadata, int page) {
        // B接口特定的参数构建逻辑
        return BRequest.builder()
            .page(page)
            .pageSize(getPageSize())
            // ... 其他参数
            .build();
    }
    
    @Override
    public PageResult<BResponse> callApi(BRequest request) {
        // 调用B接口
        return bApiClient.call(request);
    }
    
    @Override
    public List<BFiltered> preFilter(List<BResponse> data, Context context, Metadata metadata) {
        // B接口特定的前置过滤逻辑
        return data.stream()
            .filter(item -> meetBusinessRequirement(item, context))
            .map(this::convertToFiltered)
            .collect(Collectors.toList());
    }
    
    @Override
    public Map<String, G> postProcess(List<BFiltered> filteredData, 
            Context context, Metadata metadata) {
        // B接口特定的后置处理逻辑
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

4. 并行调用协调器
@Component
public class ParallelApiCoordinator {
    
    @Autowired
    private BPageProcessor bProcessor;
    
    @Autowired
    private B1PageProcessor b1Processor;
    
    @Autowired
    private B2PageProcessor b2Processor;
    
    public Map<String, Object> executeAll(Context context, Metadata metadata) {
        // 并行调用所有处理器
        CompletableFuture<Map<String, G>> bFuture = CompletableFuture
            .supplyAsync(() -> bProcessor.execute(context, metadata));
        
        CompletableFuture<Map<String, G>> b1Future = CompletableFuture
            .supplyAsync(() -> b1Processor.execute(context, metadata));
            
        CompletableFuture<Map<String, G>> b2Future = CompletableFuture
            .supplyAsync(() -> b2Processor.execute(context, metadata));
        
        // 等待所有结果并合并
        return CompletableFuture.allOf(bFuture, b1Future, b2Future)
            .thenApply(v -> {
                Map<String, Object> finalResult = new ConcurrentHashMap<>();
                try {
                    finalResult.putAll(bFuture.get());
                    finalResult.putAll(b1Future.get());
                    finalResult.putAll(b2Future.get());
                } catch (Exception e) {
                    throw new RuntimeException("合并结果失败", e);
                }
                return finalResult;
            }).join();
    }
}

5. 配置和线程池管理

@Configuration
public class AsyncConfig {
    
    @Bean("pageTaskExecutor")
    public ExecutorService pageTaskExecutor() {
        return new ThreadPoolExecutor(
            10,  // 核心线程数
            50,  // 最大线程数  
            60L, // 空闲线程存活时间
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}

6. 使用方式
@Service
public class YourService {
    
    @Autowired
    private ParallelApiCoordinator coordinator;
    
    public Map<String, Object> process(Context context, Metadata metadata) {
        return coordinator.executeAll(context, metadata);
    }
}

设计优势：
高扩展性：新增接口只需实现PageDataProcessor接口

高性能：接口间并行 + 分页数据并行处理

可维护性：职责分离，每个处理器只关注自己的逻辑

复用性：模板方法封装了公共的分页处理逻辑

灵活性：可以轻松调整并行策略和错误处理机制

这种设计既满足了当前需求，也为未来可能的扩展（如新增接口B3、B4等）提供了良好的支持。


--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

我们面临的需求是：原本只有一个下游接口B，现在有多个下游接口（B、B1、B2等），这些接口需要并行调用，然后合并结果。每个接口的处理流程类似，但存在差异（如分页方式、过滤逻辑等）。同时，有些接口可能不需要分页，有些可能不需要前置过滤器。我们还需要确保Context和Metadata只加载一次，并在多个处理器中复用。

设计思路：

使用模板方法模式定义整体流程，但允许子类定制某些步骤（如分页逻辑、过滤逻辑等）。

使用策略模式来封装每个接口的处理策略（B、B1、B2等分别对应不同的策略）。

使用工厂模式来创建不同的策略实例。

使用组合模式来统一处理分页和非分页的接口（将它们抽象为同一个流程，但分页的步骤可以不同）。

使用异步编程（CompletableFuture）来并行调用多个接口，并合并结果。

由于每个子流程有部分相同，但也有差异，我们可以将流程分解为以下步骤：

步骤1：构建请求参数（可能不同）

步骤2：获取数据（可能分页或一次性获取）

步骤3：前置过滤（可能不同，有些可能不需要）

步骤4：后置处理（可能不同）

我们可以定义一个抽象类，将整个流程模板化，但允许子类重写特定的步骤。同时，对于分页和非分页的处理，我们可以通过模板方法中的循环来处理分页，而非分页的可以重写为只处理一页。

具体设计：

定义处理器接口，每个处理器负责处理一个下游接口（B、B1、B2等）。

定义一个抽象模板类，实现公共流程，如：先获取第一页，然后计算总页数，然后异步获取剩余页，然后合并数据，然后进行前置过滤和后置处理。

对于不需要分页的处理器，我们可以重写分页相关方法，使其变为只获取一页（即一次性获取所有数据）。

对于不需要前置过滤的处理器，我们可以重写前置过滤方法，使其直接返回原始数据（或者通过一个空过滤器）。

同时，我们需要一个协调器，来并行调用这些处理器，并合并结果。

注意：Context和Metadata只加载一次，所以我们在协调器中先加载好，然后传递给每个处理器。

详细设计：

步骤一：定义处理器接口

步骤二：定义抽象模板类，实现主要流程

步骤三：定义具体处理器，继承抽象模板类，并重写需要定制的方法

步骤四：定义协调器，用于并行调用多个处理器，并合并结果

步骤五：定义结果合并策略（这里简单合并Map即可，因为每个处理器返回的都是Map，合并时直接putAll，注意key的冲突问题，如果可能冲突，需要考虑命名空间隔离，但根据需求key是由特定字段拼接，应该不会冲突）

下面我们开始代码实现：

注意：我们使用Spring Boot，所以可以使用注解来管理Bean。

由于代码较长，我分部分展示：

处理器接口

基于你的需求，我设计一个结合模板方法模式、策略模式、工厂模式、责任链模式的优雅解决方案。这个设计满足开闭原则、单一职责原则，并且具有高度可扩展性。

1. 核心接口定义
// 处理器接口
public interface ApiProcessor {
    String getProcessorType();
    Map<String, Object> process(Context context, Metadata metadata);
}

// 分页策略接口
public interface PaginationStrategy<T> {
    boolean supportsPagination();
    PageResult<T> fetchFirstPage(Context context, Metadata metadata);
    PageResult<T> fetchPage(Context context, Metadata metadata, int page);
    int calculateTotalPages(int totalRecords, int pageSize);
}

// 过滤器链接口
public interface FilterChain<T, R> {
    List<R> doFilter(List<T> data, Context context, Metadata metadata);
}

// 结果组装器接口
public interface ResultAssembler<T, R> {
    Map<String, R> assemble(List<T> filteredData, Context context, Metadata metadata);
}

2. 上下文和元数据管理器（单例）

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

3. 抽象的模板处理器

public abstract class AbstractApiProcessor<T, R, E, G> implements ApiProcessor {
    
    @Autowired
    protected ExecutorService taskExecutor;
    
    @Autowired
    protected ContextMetadataManager contextMetadataManager;
    
    // 模板方法 - 定义整体流程
    @Override
    public Map<String, Object> process(Context context, Metadata metadata) {
        // 1. 参数验证
        validateParameters(context, metadata);
        
        // 2. 获取数据（分页或非分页）
        List<T> allData = fetchAllData(context, metadata);
        
        // 3. 前置过滤
        List<E> filteredData = applyPreFilters(allData, context, metadata);
        
        // 4. 后置处理组装结果
        return assembleResult(filteredData, context, metadata);
    }
    
    // 钩子方法 - 参数验证
    protected void validateParameters(Context context, Metadata metadata) {
        // 默认实现，子类可重写
    }
    
    // 抽象方法 - 数据获取策略
    protected List<T> fetchAllData(Context context, Metadata metadata) {
        PaginationStrategy<T> paginationStrategy = getPaginationStrategy();
        
        if (paginationStrategy != null && paginationStrategy.supportsPagination()) {
            return fetchDataWithPagination(context, metadata, paginationStrategy);
        } else {
            return fetchDataWithoutPagination(context, metadata);
        }
    }
    
    // 分页数据获取
    private List<T> fetchDataWithPagination(Context context, Metadata metadata, 
                                          PaginationStrategy<T> strategy) {
        // 获取第一页
        PageResult<T> firstPage = strategy.fetchFirstPage(context, metadata);
        List<T> allData = new ArrayList<>(firstPage.getData());
        
        // 计算总页数
        int totalPages = strategy.calculateTotalPages(firstPage.getTotal(), firstPage.getPageSize());
        
        if (totalPages > 1) {
            // 异步获取剩余页
            List<CompletableFuture<PageResult<T>>> futures = new ArrayList<>();
            
            for (int page = 2; page <= totalPages; page++) {
                final int currentPage = page;
                CompletableFuture<PageResult<T>> future = CompletableFuture.supplyAsync(
                    () -> strategy.fetchPage(context, metadata, currentPage), taskExecutor);
                futures.add(future);
            }
            
            // 等待并合并结果
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
    
    // 非分页数据获取
    protected abstract List<T> fetchDataWithoutPagination(Context context, Metadata metadata);
    
    // 过滤器应用
    protected List<E> applyPreFilters(List<T> data, Context context, Metadata metadata) {
        FilterChain<T, E> filterChain = getFilterChain();
        return filterChain != null ? 
            filterChain.doFilter(data, context, metadata) : 
            (List<E>) data; // 如果没有过滤器，直接返回
    }
    
    // 结果组装
    protected Map<String, Object> assembleResult(List<E> filteredData, Context context, Metadata metadata) {
        ResultAssembler<E, G> assembler = getResultAssembler();
        if (assembler != null) {
            return (Map<String, Object>) assembler.assemble(filteredData, context, metadata);
        }
        return Collections.emptyMap();
    }
    
    // 抽象方法 - 由子类实现
    protected abstract PaginationStrategy<T> getPaginationStrategy();
    protected abstract FilterChain<T, E> getFilterChain();
    protected abstract ResultAssembler<E, G> getResultAssembler();
}

4. 具体处理器实现
// B接口处理器
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
        // B接口不需要非分页实现
        throw new UnsupportedOperationException("B接口只支持分页");
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
    
    // B接口分页策略
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
            // 复杂的参数构建逻辑
            return BRequest.builder()
                .page(page)
                .pageSize(100)
                // ... 其他参数
                .build();
        }
    }
    
    // B接口过滤器链
    private class BFilterChain implements FilterChain<BResponse, BFiltered> {
        private final List<Filter<BResponse, BFiltered>> filters = Arrays.asList(
            new BusinessFilter(),
            new ValidationFilter()
            // 可以动态添加更多过滤器
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
    
    // B接口结果组装器
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
            // 复杂的结果计算逻辑
            return BResult.builder()
                // ... 计算逻辑
                .build();
        }
    }
}

// B1接口处理器（不需要分页）
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
        return null; // B1接口不需要分页
    }
    
    @Override
    protected FilterChain<B1Response, B1Filtered> getFilterChain() {
        return null; // B1接口不需要前置过滤
    }
    
    @Override
    protected ResultAssembler<B1Filtered, B1Result> getResultAssembler() {
        return new B1ResultAssembler();
    }
    
    // ... 类似B接口的实现
}

5. 处理器工厂和协调器
// 处理器工厂
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
            throw new IllegalArgumentException("未知的处理器类型: " + type);
        }
        return processor;
    }
    
    public List<ApiProcessor> getProcessors(List<String> types) {
        return types.stream()
            .map(this::getProcessor)
            .collect(Collectors.toList());
    }
}

// 并行协调器
@Component
public class ParallelProcessorCoordinator {
    
    @Autowired
    private ApiProcessorFactory processorFactory;
    
    @Autowired
    private ContextMetadataManager contextMetadataManager;
    
    public Map<String, Object> process(List<String> processorTypes, 
                                      Supplier<Context> contextSupplier,
                                      Supplier<Metadata> metadataSupplier) {
        // 确保Context和Metadata只加载一次
        Context context = contextMetadataManager.getOrCreateContext(contextSupplier);
        Metadata metadata = contextMetadataManager.getOrCreateMetadata(metadataSupplier);
        
        // 获取所有处理器
        List<ApiProcessor> processors = processorFactory.getProcessors(processorTypes);
        
        // 并行处理
        List<CompletableFuture<Map<String, Object>>> futures = processors.stream()
            .map(processor -> CompletableFuture.supplyAsync(
                () -> processor.process(context, metadata)))
            .collect(Collectors.toList());
        
        // 合并结果
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (v1, v2) -> v2 // 处理key重复的策略
                )))
            .join();
    }
}

6. 使用示例

@Service
public class YourBusinessService {
    
    @Autowired
    private ParallelProcessorCoordinator coordinator;
    
    public Map<String, Object> processData() {
        return coordinator.process(
            Arrays.asList("B", "B1", "B2"),
            this::buildContext,    // Context构建逻辑
            this::buildMetadata    // Metadata构建逻辑
        );
    }
    
    private Context buildContext() {
        // 复杂的Context构建逻辑
        return new Context();
    }
    
    private Metadata buildMetadata() {
        // 复杂的Metadata构建逻辑
        return new Metadata();
    }
}

设计优势：
开闭原则：新增处理器只需实现接口，无需修改现有代码

单一职责：每个类只负责特定功能

高性能：Context/Metadata单例 + 并行处理

灵活性：支持分页/非分页、有过滤/无过滤等多种场景

可扩展性：易于添加新的处理器类型

可维护性：清晰的职责分离和模板方法

这个设计能够优雅地处理你的需求变化，同时为未来的扩展提供了良好的基础。
