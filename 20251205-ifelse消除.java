// 1. 枚举类定义
public enum MqsSceneType {
    META("META"),
    ORG("ORG");
    
    private String code;
    
    MqsSceneType(String code) {
        this.code = code;
    }
    
    public String getCode() {
        return code;
    }
}

// 2. Context 上下文对象
public class Context {
    private String sceneType;
    // 其它字段
    
    public String getSceneType() {
        return sceneType;
    }
    
    public void setSceneType(String sceneType) {
        this.sceneType = sceneType;
    }
}

// 3. 策略接口
public interface SceneProcessStrategy {
    /**
     * 处理业务逻辑
     */
    void process(Context context);
    
    /**
     * 支持的场景类型
     */
    MqsSceneType supportType();
}

// 4. META 场景处理策略
@Component
public class MetaSceneProcessStrategy implements SceneProcessStrategy {
    
    @Override
    public void process(Context context) {
        // META 场景的业务逻辑
        System.out.println("处理 META 场景业务逻辑");
        // 实际业务代码...
    }
    
    @Override
    public MqsSceneType supportType() {
        return MqsSceneType.META;
    }
}

// 5. ORG 场景处理策略
@Component
public class OrgSceneProcessStrategy implements SceneProcessStrategy {
    
    @Override
    public void process(Context context) {
        // ORG 场景的业务逻辑
        System.out.println("处理 ORG 场景业务逻辑");
        // 实际业务代码...
    }
    
    @Override
    public MqsSceneType supportType() {
        return MqsSceneType.ORG;
    }
}

// 6. 策略工厂（核心类）
@Component
public class SceneProcessStrategyFactory implements InitializingBean {
    
    @Autowired
    private List<SceneProcessStrategy> strategies;
    
    private Map<String, SceneProcessStrategy> strategyMap = new HashMap<>();
    
    @Override
    public void afterPropertiesSet() {
        // Spring 容器初始化后，自动注册所有策略
        for (SceneProcessStrategy strategy : strategies) {
            strategyMap.put(strategy.supportType().getCode(), strategy);
        }
    }
    
    /**
     * 根据场景类型获取对应的策略
     */
    public SceneProcessStrategy getStrategy(String sceneType) {
        SceneProcessStrategy strategy = strategyMap.get(sceneType);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的场景类型: " + sceneType);
        }
        return strategy;
    }
}

// 7. 重构后的业务处理类
@Component
public class OpBroadCastProcess {
    
    @Autowired
    private SceneProcessStrategyFactory strategyFactory;
    
    public void process(Context context) {
        // 一行代码搞定，无需 if-else
        SceneProcessStrategy strategy = strategyFactory.getStrategy(context.getSceneType());
        strategy.process(context);
    }
}

// 8. 使用示例
@Service
public class BusinessService {
    
    @Autowired
    private OpBroadCastProcess opBroadCastProcess;
    
    public void handleMessage() {
        Context context = new Context();
        context.setSceneType(MqsSceneType.META.getCode());
        
        // 调用统一处理入口
        opBroadCastProcess.process(context);
    }
}

// 9. 后续扩展新场景（示例）
// 只需新增一个策略类即可，无需修改原有代码
@Component
public class UserSceneProcessStrategy implements SceneProcessStrategy {
    
    @Override
    public void process(Context context) {
        // USER 场景的业务逻辑
        System.out.println("处理 USER 场景业务逻辑");
    }
    
    @Override
    public MqsSceneType supportType() {
        // 需要在枚举中新增 USER("USER")
        return MqsSceneType.USER;
    }
}