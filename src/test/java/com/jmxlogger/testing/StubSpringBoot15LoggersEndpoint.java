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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot <b>1.5</b> 的 {@code loggersEndpoint} 测试桩，形态完全照抄真实目标（1.5.6，本机 19000）的实测结果：
 *
 * <pre>
 * ATTR Loggers: java.lang.Object → LinkedHashMap{levels=[OFF, ERROR, …],
 *                                              loggers={名字 → {configuredLevel, effectiveLevel}}}
 * OP   getLoggers() → 同上（一次调用拿全量）
 * OP   getLogger(String) → LinkedHashMap{configuredLevel, effectiveLevel}
 * OP   setLogLevel(String, String) → void
 * </pre>
 *
 * <p>与 {@link StubSpringBootLoggersEndpoint}（Spring Boot 2.x 的 CompositeData 形态 + {@code configureLogLevel}）
 * 是两套不同的命名与数据结构，Provider 必须都能吃下。
 */
public class StubSpringBoot15LoggersEndpoint implements DynamicMBean {

    private static final Set<String> KNOWN_LEVELS = new HashSet<String>(
            Arrays.asList("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"));

    private final Map<String, String> configuredLevels = new LinkedHashMap<String, String>();
    private final Map<String, String> effectiveLevels = new LinkedHashMap<String, String>();
    private final List<String> invocations = new ArrayList<String>();
    private final MBeanInfo mbeanInfo;

    public StubSpringBoot15LoggersEndpoint() {
        MBeanAttributeInfo[] attributes = new MBeanAttributeInfo[]{
                new MBeanAttributeInfo("Loggers", Object.class.getName(), "全部 logger", true, false, false)};
        MBeanOperationInfo[] operations = new MBeanOperationInfo[]{
                new MBeanOperationInfo("getLoggers", "全部 logger 及级别", new MBeanParameterInfo[0],
                        Object.class.getName(), MBeanOperationInfo.INFO),
                new MBeanOperationInfo("getLogger", "单个 logger 的级别",
                        new MBeanParameterInfo[]{new MBeanParameterInfo("name", String.class.getName(), "logger 名")},
                        Object.class.getName(), MBeanOperationInfo.INFO),
                new MBeanOperationInfo("setLogLevel", "设置级别",
                        new MBeanParameterInfo[]{
                                new MBeanParameterInfo("name", String.class.getName(), "logger 名"),
                                new MBeanParameterInfo("level", String.class.getName(), "级别")},
                        "void", MBeanOperationInfo.ACTION)};
        this.mbeanInfo = new MBeanInfo(StubSpringBoot15LoggersEndpoint.class.getName(), "loggersEndpoint",
                attributes, null, operations, null);
    }

    public void put(String loggerName, String configuredLevel, String effectiveLevel) {
        configuredLevels.put(loggerName, configuredLevel);
        effectiveLevels.put(loggerName, effectiveLevel);
    }

    public String configuredLevel(String loggerName) {
        return configuredLevels.get(loggerName);
    }

    public List<String> getInvocations() {
        return new ArrayList<String>(invocations);
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException {
        if ("Loggers".equals(attribute)) {
            return loggersMap();
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
                // 逐项取，取不到就跳过
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
        if ("getLoggers".equals(actionName)) {
            invocations.add("getLoggers()");
            return loggersMap();
        }
        if ("getLogger".equals(actionName)) {
            String name = String.valueOf(params[0]);
            invocations.add("getLogger(" + name + ")");
            // 真实端点对不存在的 logger 也会返回一个带 effectiveLevel 的 Map（ROOT 继承下来的级别）
            return loggerMap(name, configuredLevels.get(name), effectiveLevels.get(name) == null
                    ? (effectiveLevels.containsKey("ROOT") ? effectiveLevels.get("ROOT") : "")
                    : effectiveLevels.get(name));
        }
        if ("setLogLevel".equals(actionName)) {
            String name = String.valueOf(params[0]);
            String level = params[1] == null ? null : String.valueOf(params[1]);
            invocations.add("setLogLevel(" + name + "," + level + ")");
            if (level == null) {
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
        throw new ReflectionException(new NoSuchMethodException(actionName));
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return mbeanInfo;
    }

    private Map<String, Object> loggersMap() {
        Map<String, Object> loggers = new LinkedHashMap<String, Object>();
        for (String name : configuredLevels.keySet()) {
            loggers.put(name, loggerMap(name, configuredLevels.get(name), effectiveLevels.get(name)));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("levels", new ArrayList<String>(
                Arrays.asList("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE")));
        result.put("loggers", loggers);
        return result;
    }

    /** 未配置的 logger 其 configuredLevel 是 null——与真实端点一致。 */
    private static Map<String, Object> loggerMap(String name, String configured, String effective) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("name", name);
        map.put("configuredLevel", configured);
        map.put("effectiveLevel", effective == null ? "INFO" : effective);
        return map;
    }
}
