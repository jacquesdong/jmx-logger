package com.jmxlogger.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StubLogbackConfiguratorMBean} 的实现，并额外记录所有被调用的操作，
 * 便于断言客户端确实通过 JMX 触发了对应的动作。
 */
public class StubLogbackConfigurator implements StubLogbackConfiguratorMBean {

    private final Map<String, String> configuredLevels = new LinkedHashMap<String, String>();
    private final Map<String, String> effectiveLevels = new LinkedHashMap<String, String>();
    private final List<String> invocations = new ArrayList<String>();

    /**
     * @param configuredLevel 配置级别，{@code null} 表示继承父 logger
     * @param effectiveLevel  生效级别
     */
    public void put(String loggerName, String configuredLevel, String effectiveLevel) {
        configuredLevels.put(loggerName, configuredLevel);
        effectiveLevels.put(loggerName, effectiveLevel);
    }

    @Override
    public List<String> getLoggerList() {
        return new ArrayList<String>(configuredLevels.keySet());
    }

    @Override
    public String getLoggerLevel(String loggerName) {
        return configuredLevels.get(loggerName);
    }

    @Override
    public String getLoggerEffectiveLevel(String loggerName) {
        return effectiveLevels.get(loggerName);
    }

    @Override
    public void setLoggerLevel(String loggerName, String level) {
        invocations.add("setLoggerLevel(" + loggerName + "," + level + ")");
        if (level == null) {
            // logback 语义：置空即恢复继承父 logger 的级别
            configuredLevels.put(loggerName, null);
            if (!effectiveLevels.containsKey(loggerName)) {
                effectiveLevels.put(loggerName, "INFO");
            }
            return;
        }
        configuredLevels.put(loggerName, level);
        effectiveLevels.put(loggerName, level);
    }

    @Override
    public void reloadDefaultConfiguration() {
        invocations.add("reloadDefaultConfiguration()");
    }

    @Override
    public void reloadByFileName(String fileName) {
        invocations.add("reloadByFileName(" + fileName + ")");
    }

    /** 返回调用记录（按调用顺序），测试用于断言操作确实被下发到目标 JVM。 */
    public List<String> getInvocations() {
        return new ArrayList<String>(invocations);
    }

    public void clearInvocations() {
        invocations.clear();
    }
}
