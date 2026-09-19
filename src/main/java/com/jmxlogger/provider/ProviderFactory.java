package com.jmxlogger.provider;

import com.jmxlogger.transport.TargetConnector;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 按 {@code --target} 选择日志通道。
 *
 * <ul>
 *   <li>{@code auto}（默认）：<b>先 logback 后 actuator</b>——logback 的
 *       {@code JMXConfigurator} 能力最全（含配置重载），actuator 只作为兜底；
 *       两条通道都没有时，报错里同时给出两边的缺失原因与目标侧该加的配置。</li>
 *   <li>{@code logback} / {@code actuator}：强制指定，不再自动切换，
 *       便于"目标两条都有但我要走某一条"以及把失败原因收敛到一条通道上。</li>
 * </ul>
 *
 * <p>只有"MBean 不存在"（{@code IllegalStateException}）才触发兜底；
 * 连接本身不可用（{@code IOException}）直接抛出，不在这里被吞掉。
 */
public final class ProviderFactory {

    /** 自动选择：logback 优先，缺失时兜底到 actuator。 */
    public static final String AUTO = "auto";

    /** 全部可选取值，用于 {@code --target} 的校验与报错。 */
    public static final List<String> TARGETS = Collections.unmodifiableList(Arrays.asList(
            AUTO, LogbackJmxProvider.ID, ActuatorJmxProvider.ID));

    private ProviderFactory() {
    }

    /**
     * @param target {@code --target} 的取值；{@code null} 或空按 {@link #AUTO} 处理
     * @throws IllegalArgumentException 取值不合法（用法错误）
     * @throws IllegalStateException    两条通道都不可用
     * @throws IOException              连接不可用
     */
    public static LoggerProvider open(TargetConnector connector, String target) throws IOException {
        String normalized = normalize(target);
        if (LogbackJmxProvider.ID.equals(normalized)) {
            return new LogbackJmxProvider(connector);
        }
        if (ActuatorJmxProvider.ID.equals(normalized)) {
            return new ActuatorJmxProvider(connector);
        }
        return auto(connector);
    }

    private static LoggerProvider auto(TargetConnector connector) throws IOException {
        String logbackFailure;
        try {
            return new LogbackJmxProvider(connector);
        } catch (IllegalStateException e) {
            // 只有"目标没有这个 MBean"才算可兜底；连不上（IOException）继续往外抛
            logbackFailure = firstLine(e.getMessage());
        }

        try {
            return new ActuatorJmxProvider(connector);
        } catch (IllegalStateException e) {
            connector.close();
            throw new IllegalStateException(noChannelMessage(logbackFailure, firstLine(e.getMessage())));
        }
    }

    private static String noChannelMessage(String logbackFailure, String actuatorFailure) {
        return "目标 JVM 上没有可用的日志通道：\n"
                + "  1) logback JMXConfigurator（--target " + LogbackJmxProvider.ID + "）: " + logbackFailure + "\n"
                + "  2) Actuator loggers 端点（--target " + ActuatorJmxProvider.ID + "）: " + actuatorFailure + "\n"
                + "目标侧二选一即可：\n"
                + "  a) logback.xml 中加 <jmxConfigurator/>（推荐，支持 reload）；\n"
                + "  b) 引入 spring-boot-starter-actuator（Spring Boot 2.7 默认 JMX 全暴露，无需额外配置）。\n"
                + "doctor 可以列出目标上真实存在的 MBean；也可用 --target 强制指定通道。";
    }

    private static String normalize(String target) {
        if (target == null || target.trim().isEmpty()) {
            return AUTO;
        }
        String value = target.trim();
        if (!TARGETS.contains(value)) {
            throw new IllegalArgumentException("未知的 -t/--target 取值 \"" + target + "\"，可选: "
                    + String.join(", ", TARGETS));
        }
        return value;
    }

    /** 兜底失败文案只取首行，避免两条通道的报错拼成一大坨。 */
    private static String firstLine(String message) {
        if (message == null) {
            return "不可用";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
