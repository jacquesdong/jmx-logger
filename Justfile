run *args:
    ./mvnw exec:java -Dexec.mainClass="com.jmxlogger.JmxLoggerCli" -Dexec.args="{{args}}"

# 运行单元测试
test *args:
    ./mvnw test {{args}}

# 打成可执行 fat jar
build:
    ./mvnw clean package

