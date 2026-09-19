package com.jmxlogger.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link StubLogbackConfiguratorMBean} 的实现，并额外记录所有被调用的操作，
 * 便于断言客户端确实通过 JMX 触发了对应的动作。
 *
 * <p>行为严格对齐 logback 1.1.x / 1.2.x 的 {@code JMXConfigurator} 源码语义，
 * 尤其这两条最容易踩空的规则：
 * <ul>
 *   <li>未配置级别 / logger 不存在时返回<b>空串</b>而非 {@code null}（源码里的 {@code EMPTY}）；</li>
 *   <li>{@code setLoggerLevel} 收到 <b>Java null</b> 时直接 return（静默忽略）；
 *       要重置为继承级别必须传<b>字符串 "null"</b>（{@code "null".equalsIgnoreCase(levelStr)}），
 *       且级别无法识别时同样静默忽略（{@code Level.toLevel(levelStr, null) == null}）。</li>
 * </ul>
 */
public class StubLogbackConfigurator implements StubLogbackConfiguratorMBean {

    /** 与 logback {@code JMXConfigurator.EMPTY} 对齐：空串表示"无级别"。 */
    public static final String EMPTY = "";

    private static final Set<String> KNOWN_LEVELS = new HashSet<String>(Arrays.asList(
            "OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"));

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
    public List<String> getStatuses() {
        return Collections.emptyList();
    }

    @Override
    public String getLoggerLevel(String loggerName) {
        String level = configuredLevels.get(loggerName);
        return level == null ? EMPTY : level;
    }

    @Override
    public String getLoggerEffectiveLevel(String loggerName) {
        String level = effectiveLevels.get(loggerName);
        return level == null ? EMPTY : level;
    }

    @Override
    public void setLoggerLevel(String loggerName, String level) {
        invocations.add("setLoggerLevel(" + loggerName + "," + level + ")");
        if (loggerName == null || level == null) {
            // 目标侧 logback 直接 return，既不报错也不改动级别
            return;
        }
        String trimmed = level.trim();
        if ("null".equalsIgnoreCase(trimmed)) {
            // 唯一的"重置为继承"入口
            configuredLevels.put(loggerName, null);
            return;
        }
        if (!KNOWN_LEVELS.contains(trimmed.toUpperCase())) {
            // Level.toLevel(x, null) == null，logback 静默忽略
            return;
        }
        configuredLevels.put(loggerName, trimmed.toUpperCase());
        effectiveLevels.put(loggerName, trimmed.toUpperCase());
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
