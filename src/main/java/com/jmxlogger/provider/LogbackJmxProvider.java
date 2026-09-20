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

    /** logback 配置器 MBean 的 domain（ObjectName 里冒号前的那一段）。 */
    public static final String DOMAIN = "ch.qos.logback.classic";

    /** 配置器的 Type 取值（= 类的全限定名）：ObjectName 里 {@code Type=} 后面就是它。 */
    public static final String CONFIGURATOR_TYPE = "ch.qos.logback.classic.jmx.JMXConfigurator";

    /** 探测配置器的查询模式；报错与 doctor 里给用户看的也是这个形态（只此一处拼）。 */
    public static final String PATTERN = DOMAIN + ":Type=" + CONFIGURATOR_TYPE + ",*";

    private final TargetConnector connector;
    private final MBeanServerConnection mbsc;
    private final ObjectName configuratorName;

    /**
     * @throws IllegalStateException 目标 JVM 中不存在 JMXConfigurator MBean（多半是没配 {@code <jmxConfigurator/>}）
     * @throws IOException           连接本身不可用
     */
    public LogbackJmxProvider(TargetConnector connector) throws IOException {
        this(connector, null);
    }

    /**
     * 直接点名要操作的配置器 MBean（{@code --object-name}）：目标上有多个 LoggerContext
     * （多个 JMXConfigurator）时用它挑一个，而不是默认取查询结果里的第一个。
     *
     * @param explicit 为 {@code null} 时按 {@link #PATTERN} 自动探测
     * @throws IllegalStateException 点名的 MBean 不存在，或它不是 logback 的配置器
     */
    public LogbackJmxProvider(TargetConnector connector, ObjectName explicit) throws IOException {
        this.connector = connector;
        this.mbsc = connector.getMBeanServerConnection();
        this.configuratorName = explicit == null ? findConfiguratorObjectName() : verifyExplicit(explicit);
    }

    /**
     * 校验点名的那条 MBean 确实是 logback 的配置器。不存在或 Type 不对都当场说清楚，
     * 而不是等后面 {@code invoke} 时报一个看不懂的 {@code NoSuchMethod}。
     */
    private ObjectName verifyExplicit(ObjectName name) throws IOException {
        if (!mbsc.isRegistered(name)) {
            throw new IllegalStateException("目标 JVM 上没有注册 " + name + " 这个 MBean。\n"
                    + "用 doctor 可以列出目标上真实存在的候选 MBean。");
        }
        String type = name.getKeyProperty("Type");
        if (!CONFIGURATOR_TYPE.equals(type)) {
            throw new IllegalStateException(name + " 不是 logback 的 JMXConfigurator（Type=" + type + "）；"
                    + "logback 通道要求 Type=" + CONFIGURATOR_TYPE + "。");
        }
        return name;
    }

    /** 目标侧实际解析到的 ObjectName（多 LoggerContext 时可用于诊断）。 */
    public ObjectName getConfiguratorName() {
        return configuratorName;
    }

    private ObjectName findConfiguratorObjectName() throws IOException {
        ObjectName pattern;
        try {
            pattern = new ObjectName(PATTERN);
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
        invoke("setLoggerLevel", new Object[]{loggerName, toTargetLevel(level)},
                new String[]{String.class.getName(), String.class.getName()});
    }

    /**
     * 空串 / {@code null} 表示「恢复继承」，logback 侧只认<b>字符串 {@code "null"}</b>：
     * <ul>
     *   <li>直接下发 Java {@code null} 会被目标侧静默忽略——源码首行就是
     *       {@code if (levelStr == null) return;}；</li>
     *   <li>下发空串同样无效——会落到 {@code Level.toLevel("", null)} 得到 null 后静默返回。</li>
     * </ul>
     * 所以"恢复继承"必须在本地翻译成字符串 {@code "null"}，不能原样下发。
     */
    private static String toTargetLevel(String level) {
        return level == null || level.isEmpty() ? "null" : level;
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
