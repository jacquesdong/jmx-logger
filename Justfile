
# 调试
exec *args:
    ./mvnw exec:java -Dexec.mainClass="com.jmxlogger.JmxLoggerCli" -Dexec.args="{{args}}"

# 编译
compile *args:
    ./mvnw compile {{args}}

# 测试
test *args:
    ./mvnw test {{args}}

# 打包
[group('jar')]
package:
    ./mvnw clean package

# 路径
# pom.xml 里用 <finalName>jmx-logger</finalName>
# 升级 <version> 后无需同步修改
jar_file := "target/jmx-logger.jar"
# 运行，just run get com.bayestone.server
[group('jar')]
run *args:
    java -jar {{jar_file}} {{args}}

# 端到端验收：启动 Spring Boot 服务，并执行 e2e 测试
[group('e2e')]
verify version="1.5.6":
    tools/e2e/verify.sh {{version}}

# 前台起一个 e2e 目标供手工验证
[group('e2e')]
target version="1.5.6" mode="jmx":
    tools/e2e/run-target.sh {{version}} {{mode}}
