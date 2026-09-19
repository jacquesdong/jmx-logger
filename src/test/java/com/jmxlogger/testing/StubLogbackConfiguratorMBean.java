package com.jmxlogger.testing;

import java.util.List;

/**
 * 测试桩 MBean 接口，方法签名与 logback 1.1.x / 1.2.x 的
 * {@code ch.qos.logback.classic.jmx.JMXConfigurator} 保持一致。
 *
 * <p>命名遵循标准 MBean 约定（实现类 + {@code MBean} 后缀），
 * 因此可以直接 {@code registerMBean}，无需 {@code StandardMBean} 包装。
 */
public interface StubLogbackConfiguratorMBean {

    /** 对应属性 {@code LoggerList}，真实 logback 也是以属性而非操作暴露。 */
    List<String> getLoggerList();

    String getLoggerLevel(String loggerName);

    String getLoggerEffectiveLevel(String loggerName);

    void setLoggerLevel(String loggerName, String level);

    void reloadDefaultConfiguration();

    void reloadByFileName(String fileName);
}
