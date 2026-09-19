package com.jmxlogger.transport;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * 通过<b>本地 attach</b> 连到目标 JVM（{@code -p pid}）：目标不必预先开 JMX 端口。
 *
 * <p>原理：attach 到目标进程后调用 {@code VirtualMachine#startLocalManagementAgent()}，
 * 让目标 JVM 现场启动一个<b>仅本机可连</b>的 JMX 代理，再把返回的连接器地址
 * （{@code service:jmx:rmi://...}）当普通 JMX 地址连上去。目标侧因此零配置。
 *
 * <p>attach API 在 JDK 8 位于 {@code tools.jar}（不在默认 classpath 上），JDK 9+ 归入
 * {@code jdk.attach} 模块，所以全程<b>反射调用</b>：编译期不依赖 tools.jar，
 * 同一份代码在 JDK 8 / 11 / 17 上都能跑（Java 8 语法红线不变）。
 *
 * <p>失败分支是本类的重点：attach 受 OS 权限、JRE、容器 PID namespace 影响，
 * 每种失败都要给出可操作提示，而不是甩一堆栈。
 */
public class LocalPidConnector implements TargetConnector {

    private static final String VM_CLASS_NAME = "com.sun.tools.attach.VirtualMachine";

    /** attach 启动的管理代理会把连接器地址写进 agent 属性（{@code startLocalManagementAgent} 的兜底来源）。 */
    private static final String LOCAL_CONNECTOR_ADDRESS_PROPERTY =
            "com.sun.management.jmxremote.localConnectorAddress";

    /** attach 类失败的统一排查清单——这些是 attach 特有的、用户能自己改的前提条件。 */
    private static final String ATTACH_HINTS =
            "排查清单：\n"
            + "  1) PID 是否存在且仍在运行（jps -l 或 ps -p <pid>）；\n"
            + "  2) jmx-logger 必须与目标进程同一 OS 用户（非 root 不能 attach 别人的进程）；\n"
            + "  3) 必须用完整 JDK 运行（JRE 没有 attach 能力），且 /tmp 可写（attach 依赖 /tmp 下的 UNIX socket）；\n"
            + "  4) 容器场景需与目标是同一 PID namespace（--pid=host 或共享 Pod），跨容器/跨 namespace 不可见；\n"
            + "  5) 目标进程被 ptrace 限制（docker 默认 seccomp、K8s 安全策略）时会被拒绝。";

    private final String pid;
    private final String address;
    private final JMXConnector connector;
    private final MBeanServerConnection mbsc;

    /** attach 到的 {@code VirtualMachine}（反射持有，{@code close} 时要 detach）。 */
    private volatile Object virtualMachine;

    public LocalPidConnector(String pid, String username, String password) throws IOException {
        this(pid, username, password, RemoteJmxConnector.DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * @param pid 目标进程号（正整数）
     * @param timeoutMillis 每一步（attach / 启动代理 / 连 JMX）的超时（毫秒），{@code <= 0} 表示不限制
     */
    public LocalPidConnector(String pid, String username, String password, long timeoutMillis) throws IOException {
        this.pid = requirePid(pid);
        final Class<?> vmClass = loadVirtualMachineClass();
        try {
            this.virtualMachine = attach(vmClass, this.pid, timeoutMillis);
            this.address = startLocalManagementAgent(vmClass, this.virtualMachine, this.pid, timeoutMillis);
            this.connector = connect(this.pid, this.address, username, password, timeoutMillis);
            this.mbsc = this.connector.getMBeanServerConnection();
        } catch (IOException | RuntimeException e) {
            // 构造失败时没人会调 close()，必须自己收尾，否则目标进程上会留下一个 attach 句柄
            detachQuietly();
            throw e;
        }
    }

    @Override
    public MBeanServerConnection getMBeanServerConnection() throws IOException {
        return mbsc;
    }

    @Override
    public String describe() {
        return "pid:" + pid;
    }

    /** attach 得到的本地连接器地址（仅本机可达），诊断时打印出来便于核对。 */
    public String address() {
        return address;
    }

    @Override
    public void close() {
        if (connector != null) {
            try {
                connector.close();
            } catch (IOException ignored) {
                // 关闭失败不影响命令结果
            }
        }
        detachQuietly();
    }

    /** PID 是用法层面的输入，非法值在建连之前就拒绝（退出码 2，不会去 attach）。 */
    private static String requirePid(String pid) {
        if (pid == null || pid.trim().isEmpty()) {
            throw new IllegalArgumentException("必须通过 -p/--pid 指定目标 JVM 的 PID");
        }
        String value = pid.trim();
        try {
            if (Long.parseLong(value) <= 0) {
                throw new NumberFormatException(value);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法的 PID \"" + pid + "\"：PID 必须是正整数", e);
        }
        return value;
    }

    /**
     * 找 attach API 的 {@code VirtualMachine} 类。
     * JDK 9+ 直接能加载；JDK 8 需要先把 {@code tools.jar} 挂到系统 classpath 上。
     */
    private static Class<?> loadVirtualMachineClass() throws IOException {
        try {
            return Class.forName(VM_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            File toolsJar = toolsJar();
            if (toolsJar != null && addToSystemClassPath(toolsJar)) {
                try {
                    return Class.forName(VM_CLASS_NAME);
                } catch (ClassNotFoundException ignored) {
                    // 挂进去了还是找不到，落到下面统一报错
                }
            }
            throw new IOException("本地 attach 需要 JDK 自带的 " + VM_CLASS_NAME + "，当前找不到它。\n"
                    + (toolsJar == null
                        ? "未在任何位置找到 tools.jar（${java.home}/lib/tools.jar 及其上一级）。\n"
                        : "已尝试加载 " + toolsJar.getAbsolutePath() + " 仍未找到。\n")
                    + "多半是用 JRE 而非 JDK 运行 jmx-logger：请用完整 JDK 运行，或改用 -s host:port。", e);
        }
    }

    /** JDK 8 的 {@code tools.jar} 位置：优先 {@code java.home/lib}，其次 {@code java.home/../lib}（java.home 指向 jre 时）。 */
    private static File toolsJar() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return null;
        }
        File direct = new File(javaHome, "lib" + File.separator + "tools.jar");
        if (direct.isFile()) {
            return direct;
        }
        File parent = new File(javaHome).getParentFile();
        if (parent != null) {
            File sibling = new File(parent, "lib" + File.separator + "tools.jar");
            if (sibling.isFile()) {
                return sibling;
            }
        }
        return null;
    }

    /** 反射调用 {@code URLClassLoader#addURL}：JDK 9+ 系统类加载器不再是 URLClassLoader，返回 false 即可（那里本就能直接加载）。 */
    private static boolean addToSystemClassPath(File jar) {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (!(loader instanceof URLClassLoader)) {
            return false;
        }
        try {
            Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", java.net.URL.class);
            addURL.setAccessible(true);
            addURL.invoke(loader, jar.toURI().toURL());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object attach(final Class<?> vmClass, final String pid, long timeoutMillis) throws IOException {
        Callable<Object> task = new Callable<Object>() {
            @Override
            public Object call() throws IOException {
                try {
                    Method attach = vmClass.getMethod("attach", String.class);
                    return attach.invoke(null, pid);
                } catch (InvocationTargetException e) {
                    throw attachFailed(pid, e.getCause());
                } catch (Exception e) {
                    // 缺方法、不可访问等都与"环境不支持 attach"同因，统一给排查清单
                    throw attachFailed(pid, e);
                }
            }
        };
        return ConnectWithTimeout.call(task, timeoutMillis,
                "attach 到本地进程 " + pid + " 超时（超过 " + timeoutMillis + " ms）。\n" + ATTACH_HINTS);
    }

    /** attach 失败一律翻译成"原因 + 排查清单 + 替代方案"，用户据此能自己动手。 */
    private static IOException attachFailed(String pid, Throwable cause) {
        String reason = cause == null ? "未知原因" : String.valueOf(cause.getMessage());
        return new IOException("无法 attach 到本地进程 " + pid + ": " + reason + "\n"
                + ATTACH_HINTS + "\n"
                + "替代方案：给目标加 -Dcom.sun.management.jmxremote.port=<port> 后用 -s host:port。", cause);
    }

    /**
     * 启动目标进程上的本地管理代理，并返回它的连接器地址。
     * JDK 8+ 的 {@code startLocalManagementAgent()} 直接返回地址；读 agent 属性是兜底。
     */
    private static String startLocalManagementAgent(final Class<?> vmClass, final Object vm, final String pid,
                                                    long timeoutMillis) throws IOException {
        Callable<String> task = new Callable<String>() {
            @Override
            public String call() throws IOException {
                String address = invokeStartLocalManagementAgent(vmClass, vm, pid);
                if (address == null || address.isEmpty()) {
                    address = agentProperty(vmClass, vm, LOCAL_CONNECTOR_ADDRESS_PROPERTY);
                }
                if (address == null || address.isEmpty()) {
                    throw new IOException("已 attach 到进程 " + pid + "，但目标未提供本地 JMX 连接器地址（"
                            + LOCAL_CONNECTOR_ADDRESS_PROPERTY + "）。\n"
                            + "常见原因：目标 JVM 禁用了管理代理（-XX:+DisableAttachMechanism、"
                            + "-Dcom.sun.management.jmxremote=false），或 JDK 过旧。\n"
                            + "替代方案：给目标加 -Dcom.sun.management.jmxremote.port=<port> 后用 -s host:port。");
                }
                return address;
            }
        };
        return ConnectWithTimeout.call(task, timeoutMillis,
                "启动进程 " + pid + " 的本地管理代理超时（超过 " + timeoutMillis + " ms）。\n" + ATTACH_HINTS);
    }

    private static String invokeStartLocalManagementAgent(Class<?> vmClass, Object vm, String pid)
            throws IOException {
        try {
            Method start = vmClass.getMethod("startLocalManagementAgent");
            Object result = start.invoke(vm);
            return result instanceof String ? (String) result : null;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IOException("无法在进程 " + pid + " 上启动本地 JMX 管理代理: "
                    + (cause == null ? "未知原因" : cause.getMessage()) + "\n"
                    + "常见原因：目标 JVM 禁用了 attach 或管理代理、磁盘/内存不足、JDK 过旧。", cause);
        } catch (NoSuchMethodException e) {
            // 极旧 JDK 没有这个方法，退回读 agent 属性
            return null;
        } catch (Exception e) {
            throw new IOException("无法在进程 " + pid + " 上启动本地 JMX 管理代理: " + e, e);
        }
    }

    /** 读目标的 agent 属性：{@code startLocalManagementAgent} 返回空时的兜底取值来源。 */
    private static String agentProperty(Class<?> vmClass, Object vm, String key) {
        try {
            Method getAgentProperties = vmClass.getMethod("getAgentProperties");
            Object props = getAgentProperties.invoke(vm);
            if (props instanceof Properties) {
                return ((Properties) props).getProperty(key);
            }
        } catch (Exception ignored) {
            // 读不到就走"未提供地址"的统一报错
        }
        return null;
    }

    private static JMXConnector connect(final String pid, final String address,
                                        String username, String password, long timeoutMillis) throws IOException {
        final JMXServiceURL serviceURL = new JMXServiceURL(address);
        final Map<String, Object> env = RemoteJmxConnector.credentialsEnv(username, password);

        Callable<JMXConnector> task = new Callable<JMXConnector>() {
            @Override
            public JMXConnector call() throws IOException {
                try {
                    return JMXConnectorFactory.connect(serviceURL, env);
                } catch (IOException e) {
                    throw new IOException("无法连接进程 " + pid + " 的本地 JMX 代理 (" + address + "): "
                            + e.getMessage(), e);
                }
            }
        };
        return ConnectWithTimeout.call(task, timeoutMillis,
                "连接进程 " + pid + " 的本地 JMX 代理超时（超过 " + timeoutMillis + " ms）: " + address);
    }

    /** detach 失败不该掩盖真正的业务错误，也不该把正常命令变成非零退出。 */
    private void detachQuietly() {
        Object vm = virtualMachine;
        virtualMachine = null;
        if (vm == null) {
            return;
        }
        try {
            Method detach = vm.getClass().getMethod("detach");
            detach.invoke(vm);
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
