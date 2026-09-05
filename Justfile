run *args:
    mvn exec:java -Dexec.mainClass="com.jmxlogger.JmxLoggerCli" -Dexec.args="{{args}}"
