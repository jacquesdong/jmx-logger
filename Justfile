# fat jar 路径：pom.xml 里用 <finalName>jmx-logger</finalName> 固定了产物名，
# 因此这里不含版本号，升级 <version> 后无需同步修改
jar_file := "target/jmx-logger.jar"

run *args:
    ./mvnw exec:java -Dexec.mainClass="com.jmxlogger.JmxLoggerCli" -Dexec.args="{{args}}"

# 运行单元测试
test *args:
    ./mvnw test {{args}}

# 打成可执行 fat jar
build:
    ./mvnw clean package

# 用 fat jar 运行，例：just jar -s 127.0.0.1:19000 get
jar *args: (build)
    java -jar {{jar_file}} {{args}}
