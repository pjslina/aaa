一、即时诊断 - 确定问题性质
1. 判断是 Java 堆内存还是容器内存问题

# 查看 Java 进程
ps aux | grep java

# 查看 JVM 堆内存使用情况
jstat -gc <pid> 1000 10

# 查看详细的 GC 情况
jstat -gcutil <pid> 1000 10

# 查看堆内存配置
jps -v | grep <your-app>

关键指标解读:

OU (Old Gen Used): 老年代使用率持续在 80% 以上 → 内存泄漏嫌疑
FGC (Full GC Count): 频繁 Full GC → 堆内存不足
FGCT (Full GC Time): Full GC 耗时长 → 性能瓶颈

2. 检查容器内存分配
# 查看容器内存限制 (cgroup)
cat /sys/fs/cgroup/memory/memory.limit_in_bytes

# 查看当前内存使用
cat /sys/fs/cgroup/memory/memory.usage_in_bytes

# 计算使用率
free -h

二、核心排查步骤
步骤 1: 生成堆转储文件

# 找到 Java 进程 PID
jps -l

# 生成 heap dump (手动触发)
jmap -dump:format=b,file=/tmp/heapdump_$(date +%Y%m%d_%H%M%S).hprof <pid>

# 如果 jmap 不可用,使用 kill 信号触发
kill -3 <pid>  # 生成 thread dump

注意: 在 Spring Boot 中可以提前配置自动 dump:
# application.properties
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/app/logs/heapdump.hprof

步骤 2: 分析线程状态
# 生成线程快照
jstack <pid> > /tmp/thread_dump_$(date +%Y%m%d_%H%M%S).txt

# 查看线程数
ps -eLf | grep <pid> | wc -l

# 实时监控线程创建
watch -n 1 'ps -eLf | grep <pid> | wc -l'

步骤 3: 监控 GC 日志
如果应用启动时已配置 GC 日志:

# 查看 GC 日志位置
ps aux | grep java | grep gc.log

# 实时监控 GC
tail -f /app/logs/gc.log

# 分析 GC 频率
grep "Full GC" /app/logs/gc.log | tail -20

如果未配置,建议添加 JVM 参数:
-XX:+PrintGCDetails 
-XX:+PrintGCDateStamps 
-XX:+PrintGCTimeStamps 
-Xloggc:/app/logs/gc_%p.log
-XX:+UseGCLogFileRotation 
-XX:NumberOfGCLogFiles=5 
-XX:GCLogFileSize=20M

三、常见内存问题定位
场景 1: Java 堆内存泄漏
特征: Old Gen 持续增长,Full GC 后无法回收

# 对比两次 heap dump
jmap -histo:live <pid> | head -20

# 查看占用最多的对象
jmap -histo <pid> | sort -k 2 -g -r | head -20

常见原因:
ThreadLocal 未清理: 使用线程池时,ThreadLocal 对象未 remove
静态集合持有对象: static Map/List 持续添加对象
缓存未设置过期: 本地缓存(如 Caffeine、Guava Cache)无限增长
监听器未注销: Spring Event Listener、MQ Consumer 重复注册
数据库连接池泄漏: HikariCP 连接未正确释放

场景 2: 堆外内存(Direct Memory)泄漏
特征: 容器内存高,但 Java 堆内存正常
# 查看 NIO Direct Buffer 使用
jcmd <pid> VM.native_memory summary

# 如果没有 jcmd,查看进程内存映射
cat /proc/<pid>/status | grep -i vm
pmap -x <pid> | sort -k 3 -n -r | head -20

常见原因:

Netty: DirectBuffer 未释放
Elasticsearch/Lucene: 使用了大量 MMap
RocketMQ/Kafka: Zero-Copy 导致堆外内存占用

解决方案:
# 限制 Direct Memory
-XX:MaxDirectMemorySize=512M

场景 3: 元空间(Metaspace)泄漏
# 查看 Metaspace 使用
jstat -gc <pid> | awk '{print "Metaspace: " $8 "KB"}'

# 详细信息
jstat -gcmetacapacity <pid>

常见原因:

动态生成类(CGLIB、动态代理)过多
Groovy 脚本动态加载
热部署导致类加载器泄漏

解决方案:
-XX:MetaspaceSize=256M
-XX:MaxMetaspaceSize=512M

四、临时应急措施
方案 1: 重启容器释放内存
# 如果有滚动发布能力
kubectl rollout restart deployment <your-app>

# 或联系运维重启

方案 2: 手动触发 Full GC
jcmd <pid> GC.run

# 或使用 jmap
jmap -histo:live <pid>

方案 3: 调整 JVM 参数
# 减少堆内存,给容器留足够空间
-Xms2g -Xmx2g

# 如果容器是 4G,建议 JVM 最多 2.5-3G
# 公式: 容器内存 = JVM Heap + Metaspace + Direct Memory + Native Memory + OS

# 启用 G1GC (JDK8u40+)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m

五、长期解决方案
1. 代码层面优化
排查工具集成 (下次发版添加):

<!-- pom.xml 添加 Spring Boot Actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

# application.yml
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
      
访问端点获取内存信息:
curl http://localhost:8080/actuator/metrics/jvm.memory.used
curl http://localhost:8080/actuator/heapdump > heapdump.hprof
```

### **2. 监控告警增强**

在 Grafana 配置告警:
```
- 堆内存使用率 > 80% 持续 5 分钟
- Full GC 次数 > 10次/分钟
- GC 耗时 > 1秒
- 容器内存使用率 > 75%
```

### **3. 分析工具推荐**

将导出的 heap dump 文件下载到本地分析:

1. **Eclipse MAT** (Memory Analyzer Tool)
   - 查看内存泄漏疑点
   - Dominator Tree 分析最大对象

2. **JProfiler** / **YourKit**
   - 实时监控内存分配
   - 分析对象创建热点

3. **GCeasy.io** / **GCViewer**
   - 上传 GC 日志在线分析
   - 可视化 GC 趋势

## 六、排查流程总结
```
1. jstat -gcutil 查看 GC 情况 (1 分钟)
   ↓
2. 如果 Old Gen > 80%,生成 heap dump (5 分钟)
   ↓
3. jstack 检查线程是否异常增长 (2 分钟)
   ↓
4. pmap/cat /proc/<pid>/status 检查堆外内存 (2 分钟)
   ↓
5. 下载 dump 文件到本地分析 (30 分钟)
   ↓
6. 根据分析结果修改代码或调整 JVM 参数
   ↓
7. 发版验证,持续监控 Grafana 指标


七、你现在应该立即执行
# 1. 先收集现场数据
jstat -gcutil <pid> 1000 5 > /tmp/jstat.log
jmap -histo:live <pid> > /tmp/histo.log
jstack <pid> > /tmp/jstack.log
ps aux | grep java > /tmp/java_process.log
cat /proc/<pid>/status > /tmp/process_status.log

# 2. 打包下载分析
tar -czf memory_analysis_$(date +%Y%m%d_%H%M%S).tar.gz /tmp/*.log

# 3. 如果内存持续升高,考虑重启容器
# 4. 分析日志文件找出根因

如果您能提供 jstat 或 jmap 的输出结果,我可以帮您进一步分析具体问题!

-----------------------------------------------------------------------------------------------------------------------
一、jstat 命令详细介绍1. jstat 基本语法
jstat [option] <pid> [interval] [count]

option: 统计选项
pid: Java 进程 ID
interval: 采样间隔(毫秒)
count: 采样次数(不指定则持续输出)
2. 核心选项详解-gc (最常用,查看 GC 堆状态)
jstat -gc <pid> 1000 10

输出列说明:
列名     含义                     单位               判断标准
S0C      Survivor0 容量           KB                 -
S1C      Survivor1 容量           KB                 -
S0U      Survivor0 使用量         KB                 -
S1U      Survivor1 使用量         KB                 -
EC       Eden 区容量              KB                 -
EU       Eden 区使用量            KB                 EU/EC > 80% 频繁则 Minor GC 频繁
OC       Old Gen 容量             KB                 -
OU       Old Gen 使用量           KB                 OU/OC > 80% 持续 → 内存泄漏
MC       Metaspace 容量           KB                 -
MU       Metaspace 使用量         KB                 MU 持续增长 → 类加载泄漏
YGC      Young GC 次数            次                 每秒 > 1 次需优化
YGCT     Young GC 总耗时          秒                 -
FGC      Full GC 次数             次                 持续增加 → 严重问题
FGCT     Full GC 总耗时           秒                 单次 > 1秒 → 影响业务
GCT      GC 总耗时                秒                 -

实战示例:
# 每秒采集一次,共采集 60 次
jstat -gc 12345 1000 60

# 典型输出
 S0C    S1C    S0U    S1U      EC       EU        OC         OU       MC     MU    YGC  YGCT   FGC  FGCT   GCT   
10240  10240  0.0    8192.0  81920.0  65536.0  204800.0  163840.0  51200  45056  125  1.234   8   2.456  3.690

问题判断:
# 计算老年代使用率
OU / OC = 163840 / 204800 = 80%  # ? 危险

# 计算 Full GC 频率
# 如果 60 秒内 FGC 从 8 增加到 15 → 频繁 Full GC

-gcutil (显示百分比,更直观)
jstat -gcutil <pid> 1000 10

输出列说明:
列名               含义                            危险阈值
S0                 Survivor0 使用率                -
S1                 Survivor1 使用率                -
E                  Eden 使用率                     > 90% 频繁
O                  Old Gen 使用率                  > 80% 持续
M                  Metaspace 使用率                > 90%
CCS                压缩类空间使用率                > 90%
YGC                Young GC 次数                   增长过快
YGCT               Young GC 耗时                   -
FGC                Full GC 次数                    持续增加
FGCT               Full GC 耗时                    -
GCT                总 GC 耗时                      -

实战示例:
jstat -gcutil 12345 1000 10

# 输出
  S0     S1     E      O      M     CCS    YGC   YGCT    FGC  FGCT    GCT   
  0.00  80.00  45.67  82.34  87.89  89.12  125   1.234    8   2.456  3.690
  
关键指标判断:
O = 82.34%  # ? 老年代使用率过高
M = 87.89%  # ? 元空间使用率高
FGC = 8     # 如果 10 秒后变成 9,说明发生了 Full GC

-gccause (显示 GC 原因)
jstat -gccause <pid> 1000 5

额外列:

LGCC: 上次 GC 原因
GCC: 当前 GC 原因

常见 GC 原因:

Allocation Failure: Eden 区满了,正常的 Minor GC
Ergonomics: JVM 自动调优触发
System.gc(): 代码显式调用(需排查代码)
Metadata GC Threshold: 元空间不足
Heap Inspection Initiated GC: 诊断工具触发(如 jmap)

-gcnew (Young Gen 详情)

jstat -gcnew <pid> 1000 5

关注指标:

TT: 对象在 Young Gen 晋升到 Old Gen 的年龄阈值
MTT: 最大晋升阈值
DSS: 期望的 Survivor 大小

-gcold (Old Gen 详情)

jstat -gcold <pid> 1000 5

显示老年代和永久代(JDK8 是 Metaspace)的详细信息
-gcmetacapacity (元空间容量变化)

jstat -gcmetacapacity <pid>


输出:

MCMN: 最小元空间容量
MCMX: 最大元空间容量
MC: 当前元空间容量
YGC/FGC: GC 次数

3. 实战排查场景
场景 1: 判断是否内存泄漏

# 每 5 秒采集一次,持续观察
jstat -gcutil <pid> 5000 100 > gc_monitor.log

# 分析老年代趋势
awk '{print $4}' gc_monitor.log | tail -50
```

**判断标准:**
```
时间    O (老年代使用率)
0s      45.67%
5s      48.23%
10s     52.89%
15s     58.34%
20s     65.78%
...
100s    82.45%  # ? 持续增长且 Full GC 后不降低 → 内存泄漏

场景 2: 计算 GC 吞吐量
# 采集 60 秒数据
jstat -gc <pid> 1000 60 > gc_60s.log

# 计算吞吐量
# 吞吐量 = (总时间 - GC时间) / 总时间
head -1 gc_60s.log > header.txt
tail -1 gc_60s.log > end.txt

# GCT(最后) - GCT(最初) = 60秒内总GC时间
# 吞吐量 = (60 - GC时间) / 60 * 100%

标准:

吞吐量 > 95%: 优秀
吞吐量 90-95%: 良好
吞吐量 < 90%: 需要优化

场景 3: 监控 Full GC 频率

# 持续监控并输出 Full GC 发生时间
jstat -gcutil <pid> 1000 | awk '{if(NR>1 && $8>prev) print strftime("%Y-%m-%d %H:%M:%S"), "Full GC occurred, count:", $8; prev=$8}'

4. jstat 组合脚本(无需权限)

#!/bin/bash
# 一键诊断脚本

PID=$1
DURATION=${2:-60}  # 默认监控 60 秒

echo "开始诊断 PID: $PID, 持续时间: ${DURATION}秒"
echo "======================================"

# 1. 基本信息
echo "=== 进程信息 ==="
ps -p $PID -o pid,cmd,rss,vsz

# 2. 初始快照
echo -e "\n=== 初始 GC 状态 ==="
jstat -gcutil $PID 1000 1

# 3. 持续监控
echo -e "\n=== 开始监控 ${DURATION}秒 ==="
jstat -gcutil $PID 1000 $DURATION | tee /tmp/gc_monitor_${PID}.log

# 4. 分析结果
echo -e "\n=== 分析结果 ==="
OLD_MAX=$(awk 'NR>1 {print $4}' /tmp/gc_monitor_${PID}.log | sort -n | tail -1)
OLD_MIN=$(awk 'NR>1 {print $4}' /tmp/gc_monitor_${PID}.log | sort -n | head -1)
FGC_START=$(awk 'NR==2 {print $8}' /tmp/gc_monitor_${PID}.log)
FGC_END=$(awk 'END {print $8}' /tmp/gc_monitor_${PID}.log)

echo "老年代使用率: 最低 ${OLD_MIN}%, 最高 ${OLD_MAX}%"
echo "Full GC 次数: 从 $FGC_START 到 $FGC_END, 增加 $((FGC_END - FGC_START)) 次"

if (( $(echo "$OLD_MAX > 80" | bc -l) )); then
    echo "?  警告: 老年代使用率超过 80%"
fi

if (( $(echo "$FGC_END - $FGC_START > 5" | bc -l) )); then
    echo "?  警告: ${DURATION}秒内发生了 $((FGC_END - FGC_START)) 次 Full GC"
fi

使用方式:
chmod +x diagnose_gc.sh
./diagnose_gc.sh <pid> 120  # 监控 120 秒

二、JVM 参数无权限配置的替代方案
1. 查看当前 JVM 参数

# 方法 1: 使用 jps
jps -v

# 方法 2: 使用 ps
ps aux | grep java | grep -v grep

# 方法 3: 查看 JVM 启动参数(最详细)
cat /proc/<pid>/cmdline | tr '\0' '\n'

# 方法 4: jcmd (如果有)
jcmd <pid> VM.flags

2. 临时调整(无需重启)
虽然不能修改启动参数,但可以使用 jinfo 动态调整部分参数:
# 查看所有可修改的参数
jinfo -flag +PrintGC <pid>           # 开启 GC 日志打印
jinfo -flag +PrintGCDetails <pid>    # 开启详细 GC 日志

# 但注意: 大部分核心参数(如 -Xmx)无法动态修改

3. 通过环境变量影响 JVM
如果部署平台支持环境变量配置:

# 在部署平台设置环境变量
JAVA_OPTS="-Xms2g -Xmx2g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

4. 修改 Spring Boot 配置文件
即使不能改 JVM 参数,也可以从应用层优化:

# application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20      # 降低连接池大小
      minimum-idle: 5
  
  cache:
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m  # 限制缓存大小

server:
  tomcat:
    threads:
      max: 200                    # 限制线程数
      min-spare: 10
    max-connections: 10000        # 限制连接数
```

### 5. 申请权限的理由模板

向运维/平台团队申请 JVM 参数配置权限的邮件:
```
主题: 申请配置 JVM 参数以解决生产内存问题

目前生产环境出现内存使用率持续增长问题,通过 jstat 排查发现老年代使用率达 80%+,
需要配置以下 JVM 参数进行诊断和优化:

【诊断类参数】(临时添加,问题解决后可移除)
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/app/logs/heapdump.hprof
-XX:+PrintGCDetails
-Xloggc:/app/logs/gc.log

【优化类参数】(长期保留)
-Xms2g -Xmx2g              # 明确堆内存大小
-XX:+UseG1GC                # 使用 G1 垃圾收集器
-XX:MaxGCPauseMillis=200    # 限制 GC 停顿时间

【风险评估】
- 以上参数不会降低现有性能
- 仅增加日志输出和 OOM 时的诊断能力
- 容器内存 4G,JVM 设置 2G 符合最佳实践

请协助配置,谢谢!

三、jmap 命令详解
1. jmap 基本语法

jmap [option] <pid>

2. 核心选项详解
-heap (查看堆配置和使用情况)

jmap -heap <pid>
```

**输出示例:**
```
Heap Configuration:
   MinHeapFreeRatio         = 40          # 最小空闲堆比例
   MaxHeapFreeRatio         = 70          # 最大空闲堆比例
   MaxHeapSize              = 2147483648 (2048.0MB)  # -Xmx
   NewSize                  = 715653120 (682.5MB)    # Young Gen 大小
   MaxNewSize               = 715653120 (682.5MB)
   OldSize                  = 1431830528 (1365.5MB)  # Old Gen 大小
   NewRatio                 = 2
   SurvivorRatio            = 8
   MetaspaceSize            = 21807104 (20.796875MB)
   MaxMetaspaceSize         = 17592186044415 MB

Heap Usage:
Eden Space:
   capacity = 572522496 (546.0MB)
   used     = 458342400 (437.0MB)          # Eden 使用量
   free     = 114180096 (109.0MB)
   80.03%                                   # 使用率

From Space (Survivor):
   capacity = 71565312 (68.25MB)
   used     = 0 (0.0MB)
   free     = 71565312 (68.25MB)

Old Generation:
   capacity = 1431830528 (1365.5MB)
   used     = 1145830528 (1092.5MB)        # ? Old Gen 使用量
   free     = 286000000 (273.0MB)
   80.01%                                   # ? 使用率过高
   
关键判断:

Old Gen 使用率 > 80% → 可能内存泄漏
Eden 频繁满 → Young GC 频繁

-histo[:live] (查看对象统计)
# 查看所有对象(包括垃圾对象)
jmap -histo <pid> | head -20

# 只查看存活对象(会触发 Full GC)
jmap -histo:live <pid> | head -20
```

**输出示例:**
```
 num     #instances         #bytes  class name
----------------------------------------------
   1:        123456      987654320  [B          # byte数组
   2:         98765      234567890  java.lang.String
   3:         45678      156789012  com.example.User  # ? 自定义对象过多
   4:         34567      123456789  java.util.HashMap$Node
   5:         23456       98765432  java.lang.Object[]
   
列说明:

#instances: 对象实例数量
#bytes: 占用字节数
class name: 类名

[B = byte[]
[C = char[]
[I = int[]
[L<className> = 对象数组



排查技巧:

# 对比两次快照,找出增长的对象
jmap -histo <pid> > /tmp/histo1.txt
sleep 60
jmap -histo <pid> > /tmp/histo2.txt

# 对比差异
diff /tmp/histo1.txt /tmp/histo2.txt

-dump (生成堆转储文件)

# 生成完整堆快照
jmap -dump:format=b,file=/tmp/heapdump.hprof <pid>

# 只 dump 存活对象(会触发 Full GC,文件更小)
jmap -dump:live,format=b,file=/tmp/heapdump_live.hprof <pid>

注意事项:

会阻塞应用: dump 期间 JVM 会停止响应(STW)
文件很大: 通常等于堆内存大小(如 2G 堆 → 2G 文件)
磁盘空间: 确保 /tmp 有足够空间

安全生成方式:

# 1. 检查磁盘空间
df -h /tmp

# 2. 查看堆大小
jmap -heap <pid> | grep MaxHeapSize

# 3. 如果是生产环境,建议在流量低峰期执行
# 4. 生成后立即压缩
jmap -dump:live,format=b,file=/tmp/heapdump.hprof <pid>
gzip /tmp/heapdump.hprof  # 可压缩到 30-50% 大小

-finalizerinfo (显示 Finalizer 队列)

jmap -finalizerinfo <pid>
```

**输出:**
```
Number of objects pending for finalization: 0

问题判断:

如果数字很大(几千,几万)→ Finalizer 队列积压
可能原因: 覆写了 finalize() 方法的对象太多

-clstats (类加载统计)

jmap -clstats <pid>

查看类加载器统计信息,排查元空间泄漏
3. jmap 实战场景
场景 1: 快速定位内存占用最多的对象

# 生成对象统计并排序
jmap -histo <pid> | sort -k 3 -n -r | head -30 > /tmp/top_objects.txt

# 关注自定义类(com.yourcompany.*)
grep "com\.example\." /tmp/top_objects.txt

场景 2: 对比内存快照找泄漏点
#!/bin/bash
PID=$1

echo "生成第一次快照..."
jmap -histo:live $PID > /tmp/snapshot1.txt

echo "等待 5 分钟..."
sleep 300

echo "生成第二次快照..."
jmap -histo:live $PID > /tmp/snapshot2.txt

echo "对比增长最快的对象:"
paste <(awk 'NR>3 {print $2, $4}' /tmp/snapshot1.txt) \
      <(awk 'NR>3 {print $2, $4}' /tmp/snapshot2.txt) | \
awk '{if($1==$3) print $4, $2-$1, $2}' | sort -k2 -n -r | head -20

场景 3: 定时自动 dump(防止 OOM 没抓到现场)

#!/bin/bash
PID=$1
THRESHOLD=80  # 老年代阈值

while true; do
    OLD_USAGE=$(jstat -gcutil $PID 1000 1 | tail -1 | awk '{print $4}' | cut -d. -f1)
    
    if [ "$OLD_USAGE" -gt "$THRESHOLD" ]; then
        echo "[$(date)] 老年代使用率 ${OLD_USAGE}%, 开始生成 heap dump..."
        jmap -dump:live,format=b,file=/tmp/heapdump_$(date +%Y%m%d_%H%M%S).hprof $PID
        echo "Dump 完成,退出监控"
        break
    fi
    
    sleep 30
done

使用方式:
chmod +x auto_dump.sh
nohup ./auto_dump.sh <pid> &

4. jmap 替代方案(如果 jmap 不可用)
# 方法 1: 使用 jcmd
jcmd <pid> GC.heap_dump /tmp/heapdump.hprof

# 方法 2: 使用 kill 信号 + JVM 参数(如果有配置)
# -XX:+HeapDumpOnOutOfMemoryError 会在 OOM 时自动 dump

# 方法 3: 通过 jattach (第三方工具)
./jattach <pid> dumpheap /tmp/heapdump.hprof

# 方法 4: gcore (生成 core dump,更底层)
gcore -o /tmp/core <pid>

四、Actuator Endpoint 远程访问
1. 配置说明

# application.yml
management:
  endpoints:
    web:
      exposure:
        include: "*"
      base-path: /actuator
  endpoint:
    health:
      show-details: always
  server:
    port: 8081  # ? 建议使用独立端口,避免暴露给外网
    
2. 远程访问方式
方式 1: 内网直接访问(最常见)

# 在自己电脑上执行(需要能访问到容器网络)
curl http://<容器IP>:8080/actuator/heapdump > heapdump.hprof

# 或者通过 Ingress/Service 暴露的域名
curl https://your-app.example.com/actuator/heapdump > heapdump.hprof

前提条件:

容器的 8080 端口能从你的电脑访问到
通常需要 VPN 或在同一内网

方式 2: 通过跳板机(最安全)

# 1. SSH 登录到跳板机
ssh user@jump-server

# 2. 在跳板机上执行
curl http://your-app:8080/actuator/heapdump > /tmp/heapdump.hprof

# 3. 从跳板机下载到本地
scp user@jump-server:/tmp/heapdump.hprof ./

方式 3: 端口转发(推荐)

# 1. 建立 SSH 隧道
ssh -L 8080:your-app-pod-ip:8080 user@jump-server

# 2. 在本地访问
curl http://localhost:8080/actuator/heapdump > heapdump.hprof

方式 4: kubectl port-forward (K8s 环境)

# 1. 找到 Pod
kubectl get pods -n your-namespace

# 2. 端口转发
kubectl port-forward -n your-namespace pod/your-app-pod-xxx 8080:8080

# 3. 本地访问
curl http://localhost:8080/actuator/heapdump > heapdump.hprof


3. 安全注意事项
? Actuator 暴露的风险:

heap dump 包含内存中的所有数据(密码、token、敏感信息)
可能被攻击者利用获取敏感信息

安全配置:

# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,heapdump  # 只暴露必要的端点
  endpoint:
    heapdump:
      enabled: true
  server:
    port: 8081              # 使用独立端口
    address: 127.0.0.1      # ? 只监听本地,外部无法访问

spring:
  security:
    user:
      name: admin
      password: ${ACTUATOR_PASSWORD}  # 添加认证
      
认证访问:
curl -u admin:password http://localhost:8081/actuator/heapdump > heapdump.hprof

4. 完整操作流程
# ========== 在容器内 ==========
# 1. 确认 Actuator 已启用
curl http://localhost:8080/actuator | jq .

# 2. 查看可用端点
curl http://localhost:8080/actuator | jq '._links | keys'

# 3. 生成 heap dump (? 会阻塞应用几秒到几分钟)
curl http://localhost:8080/actuator/heapdump -o /tmp/heapdump.hprof

# 4. 检查文件大小
ls -lh /tmp/heapdump.hprof

# 5. 压缩文件(可选,加快传输)
gzip /tmp/heapdump.hprof

# ========== 传输到本地 ==========
# 方法 1: scp
scp user@server:/tmp/heapdump.hprof.gz ./

# 方法 2: 通过部署平台的文件下载功能

# ========== 本地分析 ==========
gunzip heapdump.hprof.gz
# 使用 Eclipse MAT / JProfiler / VisualVM 打开分析

5. 其他有用的 Actuator 端点
# 查看 JVM 内存使用
curl http://localhost:8080/actuator/metrics/jvm.memory.used | jq .

# 查看 GC 统计
curl http://localhost:8080/actuator/metrics/jvm.gc.pause | jq .

# 查看线程信息
curl http://localhost:8080/actuator/threaddump > threaddump.txt

# 查看环境变量
curl http://localhost:8080/actuator/env | jq .

# 查看所有 Bean
curl http://localhost:8080/actuator/beans | jq .

# 实时日志级别调整
curl -X POST http://localhost:8080/actuator/loggers/com.example -H "Content-Type: application/json" -d '{"configuredLevel":"DEBUG"}'


五、综合排查方案(无需任何权限)
如果你完全没有配置权限,只能使用现有命令:

#!/bin/bash
# 零权限诊断脚本

PID=$(ps aux | grep java | grep -v grep | awk '{print $2}' | head -1)

echo "==== Java 内存诊断报告 ===="
echo "时间: $(date)"
echo "PID: $PID"
echo

echo "=== 1. 进程基本信息 ==="
ps -p $PID -o pid,rss,vsz,cmd

echo -e "\n=== 2. 容器内存限制 ==="
cat /sys/fs/cgroup/memory/memory.limit_in_bytes | awk '{print $1/1024/1024 " MB"}'
cat /sys/fs/cgroup/memory/memory.usage_in_bytes | awk '{print $1/1024/1024 " MB"}'

echo -e "\n=== 3. JVM 堆内存状态 ==="
jstat -gcutil $PID 1000 3

echo -e "\n=== 4. 对象统计 TOP 20 ==="
jmap -histo:live $PID | head -25

echo -e "\n=== 5. 线程