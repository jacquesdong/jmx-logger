package com.jmxlogger.provider;

import com.jmxlogger.transport.TargetConnector;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 通过 logback 自带的 {@code JMXConfigurator} MBean 读写 Logger 级别并重载配置。
 *
 * <p>适用 logback 1.1.x / 1.2.x（含 Spring Boot 1.5.6 / 1.5.20 自带的 1.1.11 与 Spring Boot 2.7.18 受管的 1.2.12）。
 * logback ≥ 1.3 已彻底移除 {@code JMXConfigurator}，届时走 P3 的 actuator 兜底通道。
 *
 * <p>客户端不引入 Logback 依赖，全部通过 {@link MBeanServerConnection#invoke} 反射式调用。
 */
public class LogbackJmxProvider implements LoggerProvider {

    public static final String ID = "logback";

    private static final String CONFIGURATOR_TYPE = "ch.qos.logback.classic.jmx.JMXConfigurator";

    private final TargetConnector connector;
    private final MBeanServerConnection mbsc;
    private final ObjectName configuratorName;

    /**
     * @throws IllegalStateException 目标 JVM 中不存在 JMXConfigurator MBean（多半是没配 {@code <jmxConfigurator/>}）
     * @throws IOException           连接本身不可用
     */
    public LogbackJmxProvider(TargetConnector connector) throws IOException {
        this.connector = connector;
        this.mbsc = connector.getMBeanServerConnection();
        this.configuratorName = findConfiguratorObjectName();
    }

    /** 目标侧实际解析到的 ObjectName（多 LoggerContext 时可用于诊断）。 */
    public ObjectName getConfiguratorName() {
        return configuratorName;
    }

    private ObjectName findConfiguratorObjectName() throws IOException {
        ObjectName pattern;
        try {
            pattern = new ObjectName("ch.qos.logback.classic:Type=" + CONFIGURATOR_TYPE + ",*");
        } catch (Exception e) {
            throw new IllegalStateException("ObjectName 模式非法", e);
        }

        Set<ObjectName> names = mbsc.queryNames(pattern, null);
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "目标 JVM 中未找到 Logback JMXConfigurator MBean。\n" +
                    "请确认目标应用的 logback.xml 中已启用 <jmxConfigurator/>：\n" +
                    "用 -p/--pid 本地 attach 时只需这一项（无需开 JMX 端口）；\n" +
                    "用 -s/--server 连接时还需目标以 -Dcom.sun.management.jmxremote.port 暴露 JMX。");
        }
        return names.iterator().next();
    }

    private Object invoke(String operation, Object[] params, String[] signature) throws Exception {
        try {
            return mbsc.invoke(configuratorName, operation, params, signature);
        } catch (InstanceNotFoundException e) {
            throw new IllegalStateException(
                    "Logback JMXConfigurator MBean 不存在，请确认目标应用已启用 <jmxConfigurator/>。", e);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.full();
    }

    @Override
    public List<String> listLoggerNames() throws Exception {
        // Logback 将 logger 列表以 Attribute "LoggerList" (java.util.List) 形式暴露，
        // 而非 Operation。
        Object result = mbsc.getAttribute(configuratorName, "LoggerList");
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            List<String> names = new ArrayList<>(list.size());
            for (Object item : list) {
                names.add(item == null ? null : item.toString());
            }
            return names;
        }
        return new ArrayList<>(0);
    }

    @Override
    public String getLoggerLevel(String loggerName) throws Exception {
        Object result = invoke("getLoggerLevel", new Object[]{loggerName},
                new String[]{String.class.getName()});
        return result == null ? null : result.toString();
    }

    @Override
    public String getLoggerEffectiveLevel(String loggerName) throws Exception {
        Object result = invoke("getLoggerEffectiveLevel", new Object[]{loggerName},
                new String[]{String.class.getName()});
        return result == null ? null : result.toString();
    }

    @Override
    public void setLoggerLevel(String loggerName, String level) throws Exception {
        invoke("setLoggerLevel", new Object[]{loggerName, level},
                new String[]{String.class.getName(), String.class.getName()});
    }

    @Override
    public void reloadDefaultConfiguration() throws Exception {
        invoke("reloadDefaultConfiguration", new Object[]{}, new String[]{});
    }

    /**
     * 按文件路径重新加载配置。
     * 注意：Logback 的 reloadByFileName 接受的是文件路径（内部会 new File(path) 校验存在性，
     * 再自行转 URL），因此这里直接传原始路径，由目标 JVM 解析读取。
     */
    @Override
    public void reloadByFileName(String filePath) throws Exception {
        invoke("reloadByFileName", new Object[]{filePath},
                new String[]{String.class.getName()});
    }

    @Override
    public void close() {
        // Provider 持有 connector 的所有权：谁创建谁关闭，避免调用方忘记关连接
        connector.close();
    }
}
