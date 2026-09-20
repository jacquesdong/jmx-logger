package com.jmxlogger.provider;

import com.jmxlogger.transport.TargetConnector;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
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
        return open(connector, target, null);
    }

    /**
     * @param objectName 直接点名的目标 MBean（{@code --object-name}）；非空时不再探测，
     *                   按 ObjectName 的 domain 判断这是哪条通道
     */
    public static LoggerProvider open(TargetConnector connector, String target, String objectName)
            throws IOException {
        String normalized = normalize(target);
        String explicit = objectName == null ? null : objectName.trim();
        if (explicit != null && !explicit.isEmpty()) {
            return byObjectName(connector, normalized, explicit);
        }
        if (LogbackJmxProvider.ID.equals(normalized)) {
            return new LogbackJmxProvider(connector);
        }
        if (ActuatorJmxProvider.ID.equals(normalized)) {
            return new ActuatorJmxProvider(connector);
        }
        return auto(connector);
    }

    /**
     * 用户直接点了 MBean：不再探测，按 domain 判断通道。domain 只认 logback 与 Spring Boot
     * 两个——别的 MBean 本工具也不会操作；与 {@code --target} 冲突时按用法错误报出来，
     * 而不是硬塞给某个 Provider、再抛一个看不懂的错。
     */
    private static LoggerProvider byObjectName(TargetConnector connector, String target, String value)
            throws IOException {
        ObjectName name = parseObjectName(value);
        String channel = channelOf(name.getDomain());
        if (!AUTO.equals(target) && !target.equals(channel)) {
            throw new IllegalArgumentException("--object-name 指向的是 " + channel + " 通道的 MBean，"
                    + "与 --target " + target + " 不一致；两者保持一致，或去掉 --target 让工具按 MBean 判断。");
        }
        if (LogbackJmxProvider.ID.equals(channel)) {
            return new LogbackJmxProvider(connector, name);
        }
        return new ActuatorJmxProvider(connector, name);
    }

    /** ObjectName 的 domain → 通道 id；认不出来直接按用法错误报，并指向 doctor。 */
    private static String channelOf(String domain) {
        if (LogbackJmxProvider.DOMAIN.equals(domain)) {
            return LogbackJmxProvider.ID;
        }
        if (ActuatorJmxProvider.DOMAIN.equals(domain)) {
            return ActuatorJmxProvider.ID;
        }
        throw new IllegalArgumentException("无法从 --object-name 的 domain \"" + domain
                + "\" 判断日志通道（只认 " + LogbackJmxProvider.DOMAIN + " 与 "
                + ActuatorJmxProvider.DOMAIN + "）。\n用 doctor 可以列出目标上真实存在的候选 MBean。");
    }

    private static ObjectName parseObjectName(String value) {
        try {
            return new ObjectName(value);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("非法的 --object-name \"" + value + "\"：" + e.getMessage()
                    + "\n完整形态形如 " + LogbackJmxProvider.DOMAIN + ":Name=default,Type="
                    + LogbackJmxProvider.CONFIGURATOR_TYPE + "（doctor 会打印候选 MBean）。", e);
        }
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
