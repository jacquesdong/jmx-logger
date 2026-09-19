package com.jmxlogger.testing;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot actuator {@code loggers} 端点的测试桩，形态对齐 Spring Boot 2.x 的真实暴露方式：
 * <ul>
 *   <li>是 {@code DynamicMBean}（Spring Boot 的端点 MBean 就是动态 MBean），
 *       MBeanInfo 由本桩自己声明，因此能精确复现"签名随版本变化"这件事；</li>
 *   <li>全量列表默认走 {@code Loggers} 属性，返回
 *       {@code CompositeData{levels=String[], loggers=TabularData{name, configuredLevel, effectiveLevel}}}；
 *       也可切换成无参 {@code loggers()} 操作（{@code loggersAsAttribute=false}）；</li>
 *   <li>{@code configureLogLevel} 的级别参数类型可配置：{@code String.class} 或
 *       {@link StubLogLevel}（枚举），用来验证客户端按签名构造参数的两条路径。</li>
 * </ul>
 */
public class StubSpringBootLoggersEndpoint implements DynamicMBean {

    private static final String ATTRIBUTE_LOGGERS = "Loggers";
    private static final String OP_LOGGERS = "loggers";
    private static final String OP_LOGGER_LEVELS = "loggerLevels";
    private static final String OP_CONFIGURE = "configureLogLevel";

    private static final Set<String> KNOWN_LEVELS = new HashSet<String>(
            Arrays.asList("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"));

    private final Class<?> levelType;
    private final Map<String, String> configuredLevels = new LinkedHashMap<String, String>();
    private final Map<String, String> effectiveLevels = new LinkedHashMap<String, String>();
    private final List<String> invocations = new ArrayList<String>();

    private final CompositeType rowType;
    private final TabularType tableType;
    private final CompositeType loggersType;
    private final MBeanInfo mbeanInfo;

    /** 默认形态：级别参数是 {@code String}，全量列表走 {@code Loggers} 属性。 */
    public StubSpringBootLoggersEndpoint() throws Exception {
        this(String.class, true);
    }

    /**
     * @param levelType            {@code configureLogLevel} 第二个参数的声明类型
     * @param loggersAsAttribute   true=暴露成 {@code Loggers} 属性；false=暴露成 {@code loggers()} 操作
     */
    public StubSpringBootLoggersEndpoint(Class<?> levelType, boolean loggersAsAttribute) throws Exception {
        this.levelType = levelType;

        this.rowType = new CompositeType("LoggerLevels", "LoggerLevels",
                new String[]{"name", "configuredLevel", "effectiveLevel"},
                new String[]{"name", "configuredLevel", "effectiveLevel"},
                new OpenType<?>[]{SimpleType.STRING, SimpleType.STRING, SimpleType.STRING});
        this.tableType = new TabularType("Loggers", "Loggers", rowType, new String[]{"name"});
        this.loggersType = new CompositeType("Loggers", "Loggers",
                new String[]{"levels", "loggers"},
                new String[]{"levels", "loggers"},
                new OpenType<?>[]{new ArrayType<String>(1, SimpleType.STRING), tableType});

        MBeanAttributeInfo[] attributes;
        if (loggersAsAttribute) {
            attributes = new MBeanAttributeInfo[]{
                    new MBeanAttributeInfo(ATTRIBUTE_LOGGERS, CompositeData.class.getName(),
                            "全部 logger 及其级别", true, false, false)};
        } else {
            attributes = new MBeanAttributeInfo[0];
        }

        List<MBeanOperationInfo> operations = new ArrayList<MBeanOperationInfo>();
        operations.add(new MBeanOperationInfo(OP_CONFIGURE, "设置 logger 级别",
                new MBeanParameterInfo[]{
                        new MBeanParameterInfo("name", String.class.getName(), "logger 名"),
                        new MBeanParameterInfo("configuredLevel", levelType.getName(), "级别")},
                "void", MBeanOperationInfo.ACTION));
        operations.add(new MBeanOperationInfo(OP_LOGGER_LEVELS, "查询单个 logger 的级别",
                new MBeanParameterInfo[]{
                        new MBeanParameterInfo("name", String.class.getName(), "logger 名")},
                CompositeData.class.getName(), MBeanOperationInfo.INFO));
        if (!loggersAsAttribute) {
            operations.add(new MBeanOperationInfo(OP_LOGGERS, "全部 logger 及其级别",
                    new MBeanParameterInfo[0],
                    CompositeData.class.getName(), MBeanOperationInfo.INFO));
        }

        this.mbeanInfo = new MBeanInfo(StubSpringBootLoggersEndpoint.class.getName(), "loggers endpoint",
                attributes, null, operations.toArray(new MBeanOperationInfo[operations.size()]), null);
    }

    public void put(String loggerName, String configuredLevel, String effectiveLevel) {
        configuredLevels.put(loggerName, configuredLevel);
        effectiveLevels.put(loggerName, effectiveLevel);
    }

    public String configuredLevel(String loggerName) {
        return configuredLevels.get(loggerName);
    }

    public String effectiveLevel(String loggerName) {
        return effectiveLevels.get(loggerName);
    }

    public List<String> getInvocations() {
        return new ArrayList<String>(invocations);
    }

    @Override
    public Object getAttribute(String attribute)
            throws AttributeNotFoundException, MBeanException, ReflectionException {
        if (ATTRIBUTE_LOGGERS.equals(attribute)) {
            return loggersComposite();
        }
        throw new AttributeNotFoundException(attribute);
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
        throw new AttributeNotFoundException(attribute.getName());
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList list = new AttributeList();
        for (String attribute : attributes) {
            try {
                list.add(new Attribute(attribute, getAttribute(attribute)));
            } catch (Exception ignored) {
                // 逐个取，取不到的跳过
            }
        }
        return list;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature)
            throws MBeanException, ReflectionException {
        if (OP_CONFIGURE.equals(actionName)) {
            String name = String.valueOf(params[0]);
            // 枚举参数在客户端已按签名构造成本地枚举实例，这里统一按字符串处理
            String level = params[1] == null ? null : String.valueOf(params[1]);
            invocations.add(OP_CONFIGURE + "(" + name + "," + level + ")");
            if (level == null) {
                // Spring Boot 的语义：传 null 表示恢复继承
                configuredLevels.put(name, null);
                return null;
            }
            if (!KNOWN_LEVELS.contains(level.toUpperCase())) {
                return null;
            }
            configuredLevels.put(name, level.toUpperCase());
            effectiveLevels.put(name, level.toUpperCase());
            return null;
        }
        if (OP_LOGGER_LEVELS.equals(actionName)) {
            String name = String.valueOf(params[0]);
            invocations.add(OP_LOGGER_LEVELS + "(" + name + ")");
            if (!configuredLevels.containsKey(name)) {
                return null;
            }
            return loggerComposite(name);
        }
        if (OP_LOGGERS.equals(actionName)) {
            invocations.add(OP_LOGGERS + "()");
            return loggersComposite();
        }
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return mbeanInfo;
    }

    private CompositeData loggersComposite() {
        try {
            TabularDataSupport table = new TabularDataSupport(tableType);
            for (String name : configuredLevels.keySet()) {
                table.put(loggerComposite(name));
            }
            Map<String, Object> items = new HashMap<String, Object>();
            items.put("levels", new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"});
            items.put("loggers", table);
            return new CompositeDataSupport(loggersType, items);
        } catch (Exception e) {
            throw new IllegalStateException("构造 CompositeData 失败", e);
        }
    }

    private CompositeData loggerComposite(String name) {
        try {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("name", name);
            row.put("configuredLevel", nullToEmpty(configuredLevels.get(name)));
            row.put("effectiveLevel", nullToEmpty(effectiveLevels.get(name)));
            return new CompositeDataSupport(rowType, row);
        } catch (Exception e) {
            throw new IllegalStateException("构造 CompositeData 失败", e);
        }
    }

    /**
     * JMX 的开放类型不一定允许 null 取值（JDK 版本相关），桩里统一把"未配置"落成空串；
     * 客户端侧仍需同时兼容 null 与空串（见 {@code ActuatorJmxProvider}）。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
