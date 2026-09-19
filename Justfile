run *args:
    ./mvnw exec:java -Dexec.mainClass="com.jmxlogger.JmxLoggerCli" -Dexec.args="{{args}}"
