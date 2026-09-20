package com.jmxlogger.provider;

import com.jmxlogger.support.JmxInvocation;
import com.jmxlogger.transport.TargetConnector;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot Actuator 的 {@code loggers} 端点通道（对外取值 {@code --target actuator}），
 * 读写 Logger 级别，用作<b>目标没有 {@code <jmxConfigurator/>}</b>（或 logback ≥ 1.3 已移除该 MBean）时的兜底通道。
 *
 * <p>端点 MBean 是 {@code DynamicMBean}，形态随版本变化，因此这里<b>不写死任何签名</b>：
 * <ul>
 *   <li>先按 {@code org.springframework.boot:type=Endpoint,*} 查询、再按名字里含
 *       {@code logger} 过滤——Spring Boot 1.5 的 {@code name=loggersEndpoint} 与
 *       Spring Boot 2.7 的 {@code name=Loggers} 都能命中；</li>
 *   <li>操作名两套都认：列表 {@code loggers()}/{@code getLoggers()}、
 *       单查 {@code loggerLevels}/{@code getLogger}、写入 {@code configureLogLevel}/{@code setLogLevel}；</li>
 *   <li>写入的参数类型由 {@link JmxInvocation} 按 {@link MBeanInfo} 现场构造
 *       （Spring Boot 2.7 可能是 {@code String} 也可能是 {@code LogLevel} 枚举）。</li>
 * </ul>
 *
 * <p>已在真实目标（Spring Boot 1.5.6 / 1.5.20 + logback 1.1.11，本机 19000）上实测过的形态：
 * <pre>
 * ATTR Loggers: java.lang.Object → LinkedHashMap{levels=[OFF, ERROR, …],
 *                                              loggers={名字 → {configuredLevel, effectiveLevel}}}
 * OP   getLoggers() → 同上（一次调用拿全量 logger；条数取决于目标应用，不写死）
 * OP   getLogger(String) → LinkedHashMap{configuredLevel, effectiveLevel}
 * OP   setLogLevel(String, String) → void
 * </pre>
 * 注意两点：{@code configuredLevel} 未配置时是 {@code null}（统一收敛成空串）；
 * <b>查不存在的 logger 也会返回一个带 {@code effectiveLevel} 的 Map</b>，
 * 所以"logger 是否存在"只能以全量列表为准，不能看单查的返回值。
 *
 * <p>能力：{@code get} / {@code set} 可用，<b>不支持配置重载</b>（{@link Capabilities#withoutReload()}），
 * {@code reload} 类命令应先看能力声明再决定给什么建议。
 */
public class ActuatorJmxProvider implements LoggerProvider {

    public static final String ID = "actuator";

    /** Spring Boot 端点 MBean 的 domain（ObjectName 里冒号前的那一段）。 */
    public static final String DOMAIN = "org.springframework.boot";

    /** Spring Boot 端点统一挂在这个 domain + type 下，具体 {@code name} 各版本不同。 */
    public static final String ENDPOINT_PATTERN = DOMAIN + ":type=Endpoint,*";

    /** 全量列表的属性名：Spring Boot 1.5 与 2.x 实测都是 {@code Loggers}，小写是保险。 */
    private static final String[] LIST_ATTRIBUTES = {"Loggers", "loggers"};

    /** 全量列表的操作名：Spring Boot 2.x 是 {@code loggers()}，Spring Boot 1.5 实测是 {@code getLoggers()}。 */
    private static final String[] LIST_OPERATIONS = {"loggers", "getLoggers"};

    /** 单查操作：Spring Boot 2.x 是 {@code loggerLevels(name)}，Spring Boot 1.5 实测是 {@code getLogger(name)}。 */
    private static final String[] SINGLE_OPERATIONS = {"loggerLevels", "getLogger"};

    /** 写入操作：Spring Boot 2.x 是 {@code configureLogLevel}，Spring Boot 1.5 实测是 {@code setLogLevel}。 */
    private static final String[] SET_OPERATIONS = {"configureLogLevel", "setLogLevel"};

    private final TargetConnector connector;
    private final MBeanServerConnection mbsc;
    private final ObjectName endpointName;

    /** 全量列表只需一次 RMI 往返，缓存下来供后续逐个查询复用；写操作后失效。 */
    private Map<String, Levels> cache;

    /**
     * @throws IllegalStateException 目标 JVM 上没有 actuator 的 loggers 端点
     * @throws java.io.IOException   连接本身不可用
     */
    public ActuatorJmxProvider(TargetConnector connector) throws java.io.IOException {
        this(connector, null);
    }

    /**
     * 直接点名端点 MBean（{@code --object-name}）：同一目标上有多个候选端点时用它挑一个。
     *
     * @param explicit 为 {@code null} 时按 {@link #ENDPOINT_PATTERN} 查询再按名字过滤
     * @throws IllegalStateException 点名的 MBean 没有注册
     */
    public ActuatorJmxProvider(TargetConnector connector, ObjectName explicit) throws java.io.IOException {
        this.connector = connector;
        this.mbsc = connector.getMBeanServerConnection();
        this.endpointName = explicit == null ? findEndpointObjectName() : verifyExplicit(explicit);
    }

    /** 点名的端点必须真实存在：与其后面每个操作都报错，不如在建 Provider 时就失败。 */
    private ObjectName verifyExplicit(ObjectName name) throws java.io.IOException {
        if (!mbsc.isRegistered(name)) {
            throw new IllegalStateException("目标 JVM 上没有注册 " + name + " 这个 MBean。\n"
                    + "用 doctor 可以列出目标上真实存在的候选 MBean。");
        }
        return name;
    }

    /** 目标侧实际解析到的 ObjectName，报错与诊断信息里会用到。 */
    public ObjectName getEndpointName() {
        return endpointName;
    }

    /**
     * Spring Boot 各版本的端点命名不同（2.x 是 {@code name=Loggers}），因此先按 domain+type
     * 全量查再按名字过滤，而不是写死某一种拼法；同名多个时取第一个（稳定排序）。
     */
    private ObjectName findEndpointObjectName() throws java.io.IOException {
        Set<ObjectName> names;
        try {
            names = mbsc.queryNames(new ObjectName(ENDPOINT_PATTERN), null);
        } catch (java.io.IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ObjectName 模式非法: " + ENDPOINT_PATTERN, e);
        }

        List<ObjectName> matched = new ArrayList<ObjectName>();
        for (ObjectName name : names) {
            String key = name.getKeyProperty("name");
            if (key != null && key.toLowerCase(Locale.ROOT).contains("logger")) {
                matched.add(name);
            }
        }
        Collections.sort(matched, new java.util.Comparator<ObjectName>() {
            @Override
            public int compare(ObjectName a, ObjectName b) {
                return a.getCanonicalName().compareTo(b.getCanonicalName());
            }
        });

        if (matched.isEmpty()) {
            throw new IllegalStateException(
                    "目标 JVM 中未找到 Spring Boot Actuator 的 loggers 端点（" + ENDPOINT_PATTERN + "）。\n"
                    + "目标侧需引入 spring-boot-starter-actuator：\n"
                    + "  Spring Boot 2.7 默认 JMX 全暴露，无需额外配置；\n"
                    + "  Spring Boot 3.x 需 management.endpoints.jmx.exposure.include=health,loggers。");
        }
        return matched.get(0);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.withoutReload();
    }

    @Override
    public List<String> listLoggerNames() throws Exception {
        return new ArrayList<String>(snapshot().keySet());
    }

    @Override
    public String getLoggerLevel(String loggerName) throws Exception {
        Levels levels = levelsOf(loggerName);
        // 与 logback 侧一致：查不到 / 未配置都返回空串，由命令层留空（含义见 README）
        return levels == null ? "" : levels.configured;
    }

    @Override
    public String getLoggerEffectiveLevel(String loggerName) throws Exception {
        Levels levels = levelsOf(loggerName);
        return levels == null ? "" : levels.effective;
    }

    @Override
    public void setLoggerLevel(String loggerName, String level) throws Exception {
        MBeanInfo info = mbsc.getMBeanInfo(endpointName);
        MBeanOperationInfo operation = null;
        for (String name : SET_OPERATIONS) {
            operation = JmxInvocation.findOperation(info, name, 2);
            if (operation != null) {
                break;
            }
        }
        if (operation == null) {
            throw new IllegalStateException("Actuator loggers 端点 " + endpointName + " 上没有 "
                    + "configureLogLevel / setLogLevel(name, level) 操作，无法设置级别。\n"
                    + "用 doctor 打印该端点的 MBeanInfo 看真实可用操作。");
        }
        // 空串 / null 表示「恢复继承」，actuator 侧的清除指令是 Java null
        // （JmxInvocation 对 null 原样穿透，级别参数是 LogLevel 枚举时同样成立），
        // 不能原样下发空串——那是无效级别，不是清除。
        String targetLevel = level == null || level.isEmpty() ? null : level;
        Object[] params = JmxInvocation.buildParams(operation, new String[]{loggerName, targetLevel});
        mbsc.invoke(endpointName, operation.getName(), params, JmxInvocation.signature(operation));
        cache = null;
    }

    @Override
    public void reloadDefaultConfiguration() {
        throw unsupportedReload();
    }

    @Override
    public void reloadByFileName(String filePath) {
        throw unsupportedReload();
    }

    private IllegalStateException unsupportedReload() {
        return new IllegalStateException("当前通道 " + ID + "（Actuator loggers 端点）不支持重载配置："
                + "该端点只能读写 logger 级别。\n替代方案：\n"
                + "  1) 目标 logback.xml 开 <configuration scan=\"true\" scanPeriod=\"30 seconds\">，"
                + "改文件后目标会自动重载；\n"
                + "  2) 目标 logback.xml 加 <jmxConfigurator/> 并改用 --target logback，即可用 reload 命令。");
    }

    @Override
    public void close() {
        connector.close();
    }

    /** 一次拿到全部 logger；失败时抛带类型信息的错，便于对照 doctor 的输出修正解析。 */
    private Map<String, Levels> snapshot() throws Exception {
        if (cache == null) {
            cache = fetchLevels();
        }
        return cache;
    }

    private Map<String, Levels> fetchLevels() throws Exception {
        Object raw = readLoggersValue();
        Map<String, Levels> parsed = parseLoggers(raw);
        if (parsed == null) {
            throw new IllegalStateException("无法从 Actuator loggers 端点 " + endpointName
                    + " 解析出 logger 列表（返回值类型 " + (raw == null ? "null" : raw.getClass().getName())
                    + "）。\n用 doctor 打印该端点的属性与操作签名，再把解析补上——"
                    + "端点返回的形态（CompositeData / TabularData / Map）随版本变化，不做猜测性兜底。");
        }
        return parsed;
    }

    /**
     * 全量列表：优先属性（Spring Boot 的 {@code @ReadOperation} 通常映射成属性），
     * 退回无参操作。两者都没有就明确报错。
     */
    private Object readLoggersValue() throws Exception {
        MBeanInfo info = mbsc.getMBeanInfo(endpointName);
        for (String attribute : LIST_ATTRIBUTES) {
            if (hasAttribute(info, attribute)) {
                try {
                    Object value = mbsc.getAttribute(endpointName, attribute);
                    if (value != null) {
                        return value;
                    }
                } catch (Exception ignored) {
                    // 属性存在但读失败（权限/异常）时继续试下一个入口
                }
            }
        }
        for (String name : LIST_OPERATIONS) {
            MBeanOperationInfo operation = JmxInvocation.findOperation(info, name, 0);
            if (operation == null) {
                continue;
            }
            Object value = mbsc.invoke(endpointName, name, new Object[0], new String[0]);
            if (value != null) {
                return value;
            }
        }
        throw new IllegalStateException("Actuator loggers 端点 " + endpointName + " 既没有 "
                + LIST_ATTRIBUTES[0] + " 属性，也没有 " + LIST_OPERATIONS[0] + "() / "
                + LIST_OPERATIONS[1] + "() 操作，无法获取 logger 列表。\n"
                + "用 doctor 打印该端点的 MBeanInfo 看真实形态。");
    }

    private static boolean hasAttribute(MBeanInfo info, String attributeName) {
        for (MBeanAttributeInfo attribute : info.getAttributes()) {
            if (attribute.getName().equals(attributeName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单个 logger 的级别：优先用已经拿到的全量列表（0 次额外往返），拿不到才单独调用。
     * 注意不能用单查判断"logger 是否存在"——实测查不存在的名字也会返回带 effectiveLevel 的 Map。
     */
    private Levels levelsOf(String loggerName) throws Exception {
        Map<String, Levels> all = cachedLevelsOrNull();
        if (all != null) {
            return all.get(loggerName);
        }
        return invokeSingle(loggerName);
    }

    private Map<String, Levels> cachedLevelsOrNull() throws Exception {
        try {
            return snapshot();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private Levels invokeSingle(String loggerName) throws Exception {
        MBeanInfo info = mbsc.getMBeanInfo(endpointName);
        for (String name : SINGLE_OPERATIONS) {
            MBeanOperationInfo operation = JmxInvocation.findOperation(info, name, 1);
            if (operation == null) {
                continue;
            }
            Object[] params = JmxInvocation.buildParams(operation, new String[]{loggerName});
            Object raw = mbsc.invoke(endpointName, name, params, JmxInvocation.signature(operation));
            return parseSingle(raw);
        }
        return null;
    }

    /**
     * 解析"全量列表"的返回值。实测/预期的形态：
     * <ul>
     *   <li>Spring Boot 1.5（已实测）：{@code LinkedHashMap{levels=[…],
     *       loggers=LinkedHashMap{名字 → {configuredLevel, effectiveLevel}}}}；</li>
     *   <li>Spring Boot 2.x：{@code CompositeData{levels=String[], loggers=TabularData{name, configuredLevel, effectiveLevel}}}；</li>
     *   <li>以及退化形态：直接的 {@code TabularData} / {@code Map<名字, 级别结构>}。</li>
     * </ul>
     *
     * @return 解析结果；形态认不出来返回 {@code null}，由调用方给出带类型信息的报错
     */
    static Map<String, Levels> parseLoggers(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof CompositeData) {
            CompositeData composite = (CompositeData) raw;
            // 全量结果的壳子里装着 loggers 这个子结构
            if (composite.containsKey("loggers")) {
                return parseLoggers(composite.get("loggers"));
            }
            return null;
        }
        if (raw instanceof TabularData) {
            return parseTable((TabularData) raw);
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            // Spring Boot 1.5 的外壳：{levels=…, loggers={…}}，真正的数据在里面这一层
            Object nested = map.get("loggers");
            if (isNestedLoggerMap(nested)) {
                return parseLoggers(nested);
            }
            return parseMap(map);
        }
        return null;
    }

    /**
     * {@code loggers} 这一层才是 logger 列表。加"不能带 configuredLevel"这个条件，
     * 是为了避免把一个恰好叫 {@code loggers} 的 logger 误当成外壳。
     */
    private static boolean isNestedLoggerMap(Object nested) {
        if (nested instanceof Map) {
            return !((Map<?, ?>) nested).containsKey("configuredLevel");
        }
        return nested instanceof TabularData || nested instanceof CompositeData;
    }

    private static Map<String, Levels> parseTable(TabularData table) {
        Collection<String> indexNames = table.getTabularType().getIndexNames();
        String nameKey = indexNames.contains("name") ? "name"
                : (indexNames.isEmpty() ? "name" : indexNames.iterator().next());

        Map<String, Levels> result = new LinkedHashMap<String, Levels>();
        for (Object row : table.values()) {
            if (!(row instanceof CompositeData)) {
                continue;
            }
            CompositeData composite = (CompositeData) row;
            String name = text(composite.get(nameKey));
            if (name == null) {
                name = text(composite.get("name"));
            }
            if (name == null) {
                continue;
            }
            result.put(name, new Levels(text(composite.get("configuredLevel")),
                    text(composite.get("effectiveLevel"))));
        }
        return result.isEmpty() ? null : result;
    }

    private static Map<String, Levels> parseMap(Map<?, ?> map) {
        Map<String, Levels> result = new LinkedHashMap<String, Levels>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (key == null) {
                continue;
            }
            Levels levels = parseSingle(entry.getValue());
            if (levels == null) {
                continue;
            }
            result.put(String.valueOf(key), levels);
        }
        return result.isEmpty() ? null : result;
    }

    /** 解析"单个 logger"的返回值：{@code CompositeData{configuredLevel, effectiveLevel}} 或 Map 或纯字符串。 */
    static Levels parseSingle(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof CompositeData) {
            CompositeData composite = (CompositeData) raw;
            return new Levels(text(composite.get("configuredLevel")), text(composite.get("effectiveLevel")));
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            return new Levels(text(map.get("configuredLevel")), text(map.get("effectiveLevel")));
        }
        // 只给了一个字符串时无从区分配置级别与生效级别，按生效级别处理
        if (raw instanceof String) {
            String level = (String) raw;
            return new Levels("", level);
        }
        return null;
    }

    /** 端点里"未配置级别"是 null（不是 logback 那种空串），统一收敛成空串。 */
    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "" : text;
    }

    /** 配置级别 + 生效级别；{@code configured} 为空串表示"继承父 logger"。 */
    static final class Levels {

        private final String configured;
        private final String effective;

        Levels(String configured, String effective) {
            this.configured = configured == null ? "" : configured;
            this.effective = effective == null ? "" : effective;
        }
    }
}
