package com.jmxlogger.command;

import com.jmxlogger.JmxLoggerCli;
import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.transport.LocalPidConnector;
import com.jmxlogger.transport.RemoteJmxConnector;
import com.jmxlogger.transport.TargetConnector;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断目标 JVM："连不连得上"、"连上之后有哪些可用通道"、"目标侧该加什么配置"。
 *
 * <pre>
 * jmx-logger doctor -s host:port
 * </pre>
 *
 * <p>只做只读探测（读属性、读 {@link MBeanInfo}），不改动目标 JVM 任何状态。
 * 打印出来的操作签名同时是 P3 的 actuator 兜底通道的实现依据——
 * 版本差异（{@code String} 还是 {@code LogLevel} 枚举）必须先看这里，不能猜。
 */
@Command(name = "doctor",
        description = "诊断目标 JVM 的 JMX 连接与可用通道（只读，不改目标状态）",
        mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    /** logback JMXConfigurator 的 ObjectName 模式（与 {@code LogbackJmxProvider} 一致）。 */
    private static final String LOGBACK_PATTERN =
            "ch.qos.logback.classic:Type=ch.qos.logback.classic.jmx.JMXConfigurator,*";

    /** Spring Boot actuator 端点统一挂在 {@code org.springframework.boot:type=Endpoint} 下。 */
    private static final String BOOT_ENDPOINT_PATTERN = "org.springframework.boot:type=Endpoint,*";

    private static final String RUNTIME_OBJECT_NAME = "java.lang:type=Runtime";

    private static final Pattern ARTIFACT_VERSION = Pattern.compile(
            "(logback-classic|spring-boot-actuator|spring-boot)-([0-9][^/\\\\]*)\\.jar$");

    @ParentCommand
    private JmxLoggerCli parent;

    /**
     * 同 {@code GetCommand}：异常交给顶层处理器，本方法不 {@code System.exit}。
     * 注意"连不上"与"连上了但报告无可用通道"是两回事：前者抛异常（退出码 1），
     * 后者诊断成功（退出码 0）——诊断的价值就在于把后者说清楚。
     */
    @Override
    public Integer call() throws Exception {
        try (TargetConnector connector = parent.openConnector()) {
            System.out.print(diagnose(connector));
        }
        return ExitCodes.OK;
    }

    /**
     * 生成诊断报告。与 {@link #call()} 分开，便于在测试里直接断言报告内容
     * （不必依赖顶层命令对象）。
     *
     * @throws Exception 读取 MBean 失败；连接本身的失败发生在建连阶段，不在这里
     */
    public String diagnose(TargetConnector connector) throws Exception {
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();
        StringBuilder out = new StringBuilder();

        section(out, "连接");
        out.append("  目标: ").append(connector.describe()).append('\n');
        if (connector instanceof RemoteJmxConnector) {
            out.append("  URL: ").append(((RemoteJmxConnector) connector).url()).append('\n');
        } else if (connector instanceof LocalPidConnector) {
            out.append("  通道: 本地 attach（目标侧无需预开 JMX 端口）\n");
            out.append("  本地连接器地址: ").append(((LocalPidConnector) connector).address()).append('\n');
        }
        appendJvmInfo(out, mbsc);
        appendClasspathHints(out, mbsc);

        Set<ObjectName> logbackConfigurators = queryNames(mbsc, LOGBACK_PATTERN);
        Set<ObjectName> loggerEndpoints = findLoggerEndpoints(mbsc);

        section(out, "候选 MBean");
        out.append("  logback JMXConfigurator    ").append(LOGBACK_PATTERN)
                .append("  ->  ").append(logbackConfigurators.size()).append(" 个\n");
        out.append("  Spring Boot loggers 端点          ").append(BOOT_ENDPOINT_PATTERN)
                .append("  ->  ").append(loggerEndpoints.size()).append(" 个\n");
        out.append('\n');
        for (ObjectName name : sorted(logbackConfigurators)) {
            appendMBeanInfo(out, mbsc, name);
        }
        for (ObjectName name : sorted(loggerEndpoints)) {
            appendMBeanInfo(out, mbsc, name);
        }

        section(out, "结论与建议");
        appendAdvice(out, logbackConfigurators, loggerEndpoints, connector instanceof LocalPidConnector);
        return out.toString();
    }

    private void appendJvmInfo(StringBuilder out, MBeanServerConnection mbsc) {
        try {
            ObjectName runtime = new ObjectName(RUNTIME_OBJECT_NAME);
            String name = readAttribute(mbsc, runtime, "Name");
            if (name != null) {
                out.append("  进程: ").append(name).append('\n');
            }
            String vm = readAttribute(mbsc, runtime, "VmName");
            String version = readAttribute(mbsc, runtime, "VmVersion");
            if (vm != null || version != null) {
                out.append("  JVM: ").append(vm).append(' ').append(version).append('\n');
            }
        } catch (Exception e) {
            out.append("  JVM 信息不可用: ").append(e).append('\n');
        }
    }

    /**
     * 从目标 JVM 的 classpath 里认 logback / boot 的版本：这决定了
     * 「有没有 JMXConfigurator」（logback ≥ 1.3 已移除）与「actuator 在不在」。
     * 只认 jar 名，不读内容，也不打印整条 classpath。
     */
    private void appendClasspathHints(StringBuilder out, MBeanServerConnection mbsc) {
        String classPath = null;
        try {
            classPath = readAttribute(mbsc, new ObjectName(RUNTIME_OBJECT_NAME), "ClassPath");
        } catch (Exception ignored) {
            // classpath 读不到（权限/精简 JVM）时不影响其余诊断
        }
        if (classPath == null || classPath.isEmpty()) {
            return;
        }

        Map<String, String> found = new LinkedHashMap<>();
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            Matcher m = ARTIFACT_VERSION.matcher(entry.trim());
            if (m.find() && !found.containsKey(m.group(1))) {
                found.put(m.group(1), m.group(2));
            }
        }
        if (found.isEmpty()) {
            return;
        }

        out.append("  classpath 识别: ");
        boolean first = true;
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            out.append(e.getKey()).append(' ').append(e.getValue());
            first = false;
        }
        out.append('\n');

        String logbackVersion = found.get("logback-classic");
        if (logbackVersion != null && isLogbackWithoutJmxConfigurator(logbackVersion)) {
            out.append("  注意: logback ").append(logbackVersion)
                    .append(" 已移除 JMXConfigurator（≥1.3），只能走 actuator / HTTP 通道\n");
        }
    }

    /** logback ≥ 1.3 起 {@code ch.qos.logback.classic.jmx} 包被删除。 */
    private static boolean isLogbackWithoutJmxConfigurator(String version) {
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            if (major > 1) {
                return true;
            }
            return major == 1 && parts.length > 1 && Integer.parseInt(parts[1]) >= 3;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    /**
     * Spring Boot 各版本的端点命名不同（1.5 是 {@code name=loggersEndpoint}，2.x 是
     * {@code name=Loggers}），因此先按 domain+type 全量查，再按名字过滤，
     * 而不是写死某一种拼法。
     */
    private Set<ObjectName> findLoggerEndpoints(MBeanServerConnection mbsc) {
        Set<ObjectName> all = queryNames(mbsc, BOOT_ENDPOINT_PATTERN);
        Set<ObjectName> matched = new java.util.LinkedHashSet<>();
        for (ObjectName name : all) {
            String key = name.getKeyProperty("name");
            if (key != null && key.toLowerCase(Locale.ROOT).contains("logger")) {
                matched.add(name);
            }
        }
        return matched;
    }

    /**
     * @param localAttach 本次是 {@code -p pid} 本地 attach 连上的：此时"开 JMX 端口"已经不是问题，
     *                    建议里不该再让人去配 {@code -Dcom.sun.management.jmxremote.port}。
     */
    private void appendAdvice(StringBuilder out, Set<ObjectName> logbackConfigurators,
                              Set<ObjectName> loggerEndpoints, boolean localAttach) {
        boolean hasLogback = !logbackConfigurators.isEmpty();
        boolean hasActuator = !loggerEndpoints.isEmpty();

        if (hasLogback) {
            out.append("  [可用] logback JMXConfigurator 存在：get / set / reload 全部可用。\n");
            if (logbackConfigurators.size() > 1) {
                out.append("  目标有多个 LoggerContext（").append(logbackConfigurators.size())
                        .append(" 个），当前固定取第一个；P4 的 --object-name 可显式指定。\n");
            }
        } else {
            out.append("  [缺失] 未找到 logback JMXConfigurator：目标 logback.xml 未配置 ")
                    .append("<jmxConfigurator/>，或 logback 版本 ≥ 1.3。\n");
        }

        if (hasActuator) {
            out.append("  [可用] Spring Boot Actuator 的 loggers 端点存在：")
                    .append(hasLogback
                            ? "logback 通道优先，需强制走它时用 -t actuator。\n"
                            : "--target auto 已自动兜底到它（get / set 可用，reload 不支持）。\n");
        } else {
            out.append("  [缺失] 未找到 Actuator loggers 端点。\n");
        }

        if (hasLogback) {
            return;
        }

        out.append('\n');
        if (localAttach) {
            out.append("  本地 attach 已直连目标进程，只需在目标的 logback.xml 中加 <jmxConfigurator/>，");
            out.append("重启后即可 get / set / reload。\n");
            return;
        }
        out.append("  目标侧二选一即可：\n");
        out.append("    1) logback 通道（推荐，支持 reload）：logback.xml 中加 <jmxConfigurator/>，\n");
        out.append("       并以 -Dcom.sun.management.jmxremote.port=<port> 启动（rmi.port 与 port 保持一致）；\n");
        out.append("    2) actuator 通道：引入 spring-boot-starter-actuator。\n");
        out.append("       Spring Boot 2.7 默认 JMX 全暴露无需额外配置；");
        out.append("Spring Boot 3.x 需 management.endpoints.jmx.exposure.include=health,loggers。\n");
    }

    private void appendMBeanInfo(StringBuilder out, MBeanServerConnection mbsc, ObjectName name) {
        out.append("  ").append(name).append('\n');
        try {
            MBeanInfo info = mbsc.getMBeanInfo(name);
            MBeanAttributeInfo[] attributes = info.getAttributes();
            out.append("    属性:\n");
            if (attributes.length == 0) {
                out.append("      （无）\n");
            }
            for (MBeanAttributeInfo attribute : attributes) {
                out.append("      ").append(attribute.getName())
                        .append(": ").append(attribute.getType())
                        .append(attribute.isWritable() ? "  [可写]" : "  [只读]")
                        .append('\n');
            }

            MBeanOperationInfo[] operations = info.getOperations();
            out.append("    操作:\n");
            if (operations.length == 0) {
                out.append("      （无）\n");
            }
            for (MBeanOperationInfo operation : operations) {
                out.append("      ").append(operation.getName()).append('(');
                MBeanParameterInfo[] params = operation.getSignature();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    // 打印完整类型名：P3 要靠它区分 java.lang.String 与 LogLevel 枚举
                    out.append(params[i].getType());
                }
                out.append(") -> ").append(operation.getReturnType()).append('\n');
            }
        } catch (Exception e) {
            out.append("    读取 MBeanInfo 失败: ").append(e).append('\n');
        }
        out.append('\n');
    }

    private static Set<ObjectName> queryNames(MBeanServerConnection mbsc, String pattern) {
        try {
            return mbsc.queryNames(new ObjectName(pattern), null);
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private static String readAttribute(MBeanServerConnection mbsc, ObjectName name, String attribute) {
        try {
            Object value = mbsc.getAttribute(name, attribute);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 稳定排序，避免 JMX 返回集合顺序不稳定导致输出每次都不一样。 */
    private static List<ObjectName> sorted(Set<ObjectName> names) {
        List<ObjectName> list = new ArrayList<>(names);
        Collections.sort(list, new java.util.Comparator<ObjectName>() {
            @Override
            public int compare(ObjectName a, ObjectName b) {
                return a.getCanonicalName().compareTo(b.getCanonicalName());
            }
        });
        return list;
    }

    private static void section(StringBuilder out, String title) {
        out.append('[').append(title).append("]\n");
    }
}
