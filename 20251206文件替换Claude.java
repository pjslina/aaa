完美！这个思路更优雅！使用 Maven Replacer Plugin 在构建时动态替换 XML 文件中的特定行，本地保持 dev 配置不变。
最优方案：Maven Replacer Plugin 行级替换
1. POM.xml 完整配置
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.company</groupId>
    <artifactId>your-project</artifactId>
    <version>1.0.0</version>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <!-- 默认dev环境 -->
        <mq.profile>dev</mq.profile>
    </properties>

    <profiles>
        <!-- 本地开发环境（默认） -->
        <profile>
            <id>dev</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <mq.profile>dev</mq.profile>
                <spring.profiles.active>dev</spring.profiles.active>
            </properties>
        </profile>

        <!-- SIT环境 -->
        <profile>
            <id>sit</id>
            <properties>
                <mq.profile>sit</mq.profile>
                <spring.profiles.active>sit</spring.profiles.active>
            </properties>
        </profile>

        <!-- UAT环境 -->
        <profile>
            <id>uat</id>
            <properties>
                <mq.profile>uat</mq.profile>
                <spring.profiles.active>uat</spring.profiles.active>
            </properties>
        </profile>

        <!-- 生产环境 -->
        <profile>
            <id>prod</id>
            <properties>
                <mq.profile>prod</mq.profile>
                <spring.profiles.active>prod</spring.profiles.active>
            </properties>
        </profile>
    </profiles>

    <build>
        <resources>
            <!-- 标准资源处理 -->
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
                <includes>
                    <include>**/*.yml</include>
                    <include>**/*.yaml</include>
                    <include>**/*.properties</include>
                </includes>
            </resource>
            <!-- XML文件不过滤，后续特殊处理 -->
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
                <includes>
                    <include>**/*.xml</include>
                </includes>
            </resource>
        </resources>

        <plugins>
            <!-- 编译插件 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
                <configuration>
                    <source>1.8</source>
                    <target>1.8</target>
                    <encoding>UTF-8</encoding>
                </configuration>
            </plugin>

            <!-- 资源插件 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <version>3.2.0</version>
                <configuration>
                    <encoding>UTF-8</encoding>
                    <delimiters>
                        <delimiter>@</delimiter>
                    </delimiters>
                    <useDefaultDelimiters>false</useDefaultDelimiters>
                </configuration>
            </plugin>

            <!-- Replacer插件 - 核心配置 -->
            <plugin>
                <groupId>com.google.code.maven-replacer-plugin</groupId>
                <artifactId>replacer</artifactId>
                <version>1.5.3</version>
                <executions>
                    <execution>
                        <id>replace-mq-profile</id>
                        <phase>process-resources</phase>
                        <goals>
                            <goal>replace</goal>
                        </goals>
                        <configuration>
                            <!-- 目标文件 -->
                            <file>${project.build.outputDirectory}/web.mqs.beans.xml</file>
                            
                            <!-- 替换规则 -->
                            <replacements>
                                <!-- 替换 profile 属性 -->
                                <replacement>
                                    <token><![CDATA[profile="dev"]]></token>
                                    <value>profile="${mq.profile}"</value>
                                </replacement>
                                
                                <!-- 如果使用 profiles 属性（多个环境） -->
                                <replacement>
                                    <token><![CDATA[profiles="dev"]]></token>
                                    <value>profiles="${mq.profile}"</value>
                                </replacement>
                            </replacements>
                            
                            <!-- 输出详细日志 -->
                            <quiet>false</quiet>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Spring Boot打包插件 -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>2.1.6.RELEASE</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <!-- Spring Boot依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>2.1.6.RELEASE</version>
        </dependency>
        
        <!-- 其他依赖 -->
    </dependencies>
</project>

2. 源文件配置（提交到Git）
<!-- src/main/resources/web.mqs.beans.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
       http://www.springframework.org/schema/beans/spring-beans.xsd"
       profile="dev">
    
    <!-- 公司MQ框架的Bean配置 -->
    <bean id="mqConnectionFactory" class="com.company.mq.MQConnectionFactory">
        <property name="brokerUrl" value="${mq.broker.url}"/>
        <property name="username" value="${mq.username}"/>
        <property name="password" value="${mq.password}"/>
    </bean>
    
    <bean id="mqConsumer" class="com.company.mq.MQConsumer">
        <property name="connectionFactory" ref="mqConnectionFactory"/>
        <property name="queueName" value="${mq.queue.name}"/>
    </bean>
</beans>

3. application.yml 配置
# src/main/resources/application.yml
spring:
  profiles:
    active: @spring.profiles.active@

---
# DEV环境配置
spring:
  profiles: dev

mq:
  broker:
    url: tcp://dev-mq.company.com:61616
  username: dev_user
  password: dev_pass
  queue:
    name: dev.queue

---
# SIT环境配置
spring:
  profiles: sit

mq:
  broker:
    url: tcp://sit-mq.company.com:61616
  username: sit_user
  password: sit_pass
  queue:
    name: sit.queue

---
# UAT环境配置
spring:
  profiles: uat

mq:
  broker:
    url: tcp://uat-mq.company.com:61616
  username: uat_user
  password: uat_pass
  queue:
    name: uat.queue

---
# PROD环境配置
spring:
  profiles: prod

mq:
  broker:
    url: tcp://prod-mq.company.com:61616
  username: prod_user
  password: prod_pass
  queue:
    name: prod.queue
    
4. 使用方式
本地开发（默认dev）
# 不修改任何文件，直接运行
mvn clean install
mvn spring-boot:run

# 或者手动修改 web.mqs.beans.xml 测试其他环境
# 将 profile="dev" 改为 profile="sit"
# 同时在 application.yml 设置 spring.profiles.active=sit

打包不同环境

# SIT环境打包
mvn clean package -Psit
# 打包后 target/classes/web.mqs.beans.xml 中 profile="sit"

# UAT环境打包
mvn clean package -Puat
# 打包后 target/classes/web.mqs.beans.xml 中 profile="uat"

# 生产环境打包
mvn clean package -Pprod
# 打包后 target/classes/web.mqs.beans.xml 中 profile="prod"

验证替换结果
# 查看打包后的文件
cat target/classes/web.mqs.beans.xml | grep profile

# 或解压jar包查看
unzip -p target/your-project-1.0.0.jar BOOT-INF/classes/web.mqs.beans.xml | grep profile

5. 进阶：支持更复杂的替换场景
如果您的 XML 文件中有多个需要替换的地方：
<!-- pom.xml 中的 replacer 配置 -->
<plugin>
    <groupId>com.google.code.maven-replacer-plugin</groupId>
    <artifactId>replacer</artifactId>
    <version>1.5.3</version>
    <executions>
        <execution>
            <id>replace-mq-profile</id>
            <phase>process-resources</phase>
            <goals>
                <goal>replace</goal>
            </goals>
            <configuration>
                <file>${project.build.outputDirectory}/web.mqs.beans.xml</file>
                <replacements>
                    <!-- 替换 profile 属性 -->
                    <replacement>
                        <token><![CDATA[profile="dev"]]></token>
                        <value>profile="${mq.profile}"</value>
                    </replacement>
                    
                    <!-- 替换 profiles 属性 -->
                    <replacement>
                        <token><![CDATA[profiles="dev"]]></token>
                        <value>profiles="${mq.profile}"</value>
                    </replacement>
                    
                    <!-- 替换注释中的环境标识（可选） -->
                    <replacement>
                        <token><![CDATA[<!-- Environment: dev -->]]></token>
                        <value><![CDATA[<!-- Environment: ${mq.profile} -->]]></value>
                    </replacement>
                    
                    <!-- 使用正则表达式替换（更灵活） -->
                    <replacement>
                        <token><![CDATA[profile(s)?="dev"]]></token>
                        <value>profile$1="${mq.profile}"</value>
                        <regex>true</regex>
                    </replacement>
                </replacements>
                
                <!-- 配置选项 -->
                <quiet>false</quiet>
                <outputFile>${project.build.outputDirectory}/web.mqs.beans.xml</outputFile>
                <encoding>UTF-8</encoding>
            </configuration>
        </execution>
    </executions>
</plugin>

6. 如果 XML 文件格式更复杂
假设您的文件格式是这样的：
<!-- src/main/resources/web.mqs.beans.xml -->
<beans xmlns="..." profiles="dev,sit,uat,prod">
    <bean id="mqConfig" class="...">
        <property name="env" value="dev"/>
    </bean>
</beans>

使用正则表达式替换：
<plugin>
    <groupId>com.google.code.maven-replacer-plugin</groupId>
    <artifactId>replacer</artifactId>
    <version>1.5.3</version>
    <executions>
        <execution>
            <id>replace-mq-profile</id>
            <phase>process-resources</phase>
            <goals>
                <goal>replace</goal>
            </goals>
            <configuration>
                <file>${project.build.outputDirectory}/web.mqs.beans.xml</file>
                <replacements>
                    <!-- 替换 profiles 属性，只保留当前环境 -->
                    <replacement>
                        <token><![CDATA[profiles="dev,sit,uat,prod"]]></token>
                        <value>profiles="${mq.profile}"</value>
                    </replacement>
                    
                    <!-- 替换 property 中的 env 值 -->
                    <replacement>
                        <token><![CDATA[<property name="env" value="dev"/>]]></token>
                        <value><![CDATA[<property name="env" value="${mq.profile}"/>]]></value>
                    </replacement>
                </replacements>
                <quiet>false</quiet>
            </configuration>
        </execution>
    </executions>
</plugin>

7. 自动化验证脚本
创建验证脚本确保替换成功：
#!/bin/bash
# verify-build.sh

ENV=$1

if [ -z "$ENV" ]; then
    echo "Usage: ./verify-build.sh [sit|uat|prod]"
    exit 1
fi

echo "=========================================="
echo "验证 $ENV 环境构建..."
echo "=========================================="

# 构建
mvn clean package -P$ENV -DskipTests

# 检查替换结果
if unzip -p target/*.jar BOOT-INF/classes/web.mqs.beans.xml | grep -q "profile=\"$ENV\""; then
    echo "? 成功: profile 已替换为 $ENV"
else
    echo "? 失败: profile 未正确替换"
    exit 1
fi

# 检查 application.yml
if unzip -p target/*.jar BOOT-INF/classes/application.yml | grep -q "active: $ENV"; then
    echo "? 成功: spring.profiles.active 已设置为 $ENV"
else
    echo "? 失败: spring.profiles.active 未正确设置"
    exit 1
fi

echo "=========================================="
echo "? 构建验证通过!"
echo "=========================================="

使用方式：
chmod +x verify-build.sh
./verify-build.sh sit

8. IDE 开发提示配置
为了让开发人员更容易理解，在项目根目录添加 README-MQ-CONFIG.md：

# MQ配置说明

## 本地开发

默认连接 **DEV** 环境MQ，无需任何配置。

如需临时测试其他环境：
1. 修改 `web.mqs.beans.xml` 中的 `profile="dev"` 为目标环境
2. 修改 `application.yml` 中的 `spring.profiles.active` 为目标环境
3. ? **测试完成后请撤销修改，不要提交！**

## 打包部署
```bash
# SIT环境
mvn clean package -Psit

# UAT环境
mvn clean package -Puat

# 生产环境
mvn clean package -Pprod
```

## 工作原理

打包时，Maven会自动将 `web.mqs.beans.xml` 中的 `profile="dev"` 替换为对应环境。

**源文件（Git仓库）：**
```xml
<beans profile="dev">
```

**打包后（SIT环境）：**
```xml
<beans profile="sit">
```

## 验证构建
```bash
# 验证SIT环境构建
unzip -p target/*.jar BOOT-INF/classes/web.mqs.beans.xml | grep profile
```
```

---

## 方案总结

### ? 核心优势

1. **源文件保持dev配置**：Git仓库中始终是 `profile="dev"`
2. **本地环境隔离**：开发人员默认只能消费dev环境消息
3. **灵活手动测试**：需要时可手动修改本地文件测试其他环境
4. **自动化打包替换**：构建时自动替换为目标环境
5. **不侵入框架**：公司框架无感知，仍然读取 `web.mqs.beans.xml`
6. **配置简单**：只需要在pom.xml添加一个插件

### ? 替换流程
```
源代码（Git）           编译时                  打包结果
profile="dev"    →   replacer插件替换    →   profile="sit"

? 适用场景

? 不能修改公司框架
? 不能修改CICD流程
? 需要本地环境隔离
? 需要灵活手动测试
? 需要自动化打包

这个方案完美满足您的所有需求！

