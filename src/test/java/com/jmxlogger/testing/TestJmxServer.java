package com.jmxlogger.testing;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 JMX 服务端：在测试 JVM 内启动一个真实的 RMI JMX 连接器（随机端口），
 * 复用平台 MBeanServer，可注册任意测试桩 MBean。
 *
 * <p>这样 {@code JmxClient} 走的是与生产完全相同的
 * {@code service:jmx:rmi:///jndi/rmi://host:port/jmxrmi} 路径，
 * 能覆盖序列化、ObjectName 查询、invoke 签名等真实链路，而不是 mock 出来的假象。
 */
public final class TestJmxServer implements AutoCloseable {

    /** 与 logback 注册 JMXConfigurator 时使用的 Type 一致。 */
    public static final String LOGBACK_CONFIGURATOR_TYPE = "ch.qos.logback.classic.jmx.JMXConfigurator";

    private final int port;
    private final Registry registry;
    private final JMXConnectorServer connectorServer;
    private final List<ObjectName> registered = new ArrayList<ObjectName>();

    private TestJmxServer() throws IOException {
        this.port = findFreePort();
        this.registry = LocateRegistry.createRegistry(port);
        JMXServiceURL url = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
        Map<String, Object> env = new HashMap<String, Object>();
        this.connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(
                url, env, ManagementFactory.getPlatformMBeanServer());
        this.connectorServer.start();
    }

    public static TestJmxServer start() throws IOException {
        return new TestJmxServer();
    }

    /** 启动一个"黑洞"TCP 服务：接受连接后既不回应也不关闭，用于验证连接超时分支。 */
    public static BlackholeServer startBlackhole() throws IOException {
        return new BlackholeServer();
    }

    /** 返回可直接传给 {@code -s/--server} 的 {@code host:port}。 */
    public String server() {
        return "127.0.0.1:" + port;
    }

    public int port() {
        return port;
    }

    /** 按 logback 的真实 ObjectName 约定注册配置器桩：{@code ch.qos.logback.classic:Name=<ctx>,Type=...}。 */
    public ObjectName registerLogbackConfigurator(Object stub, String contextName) throws Exception {
        ObjectName name = new ObjectName(
                "ch.qos.logback.classic:Name=" + contextName + ",Type=" + LOGBACK_CONFIGURATOR_TYPE);
        return register(stub, name);
    }

    public ObjectName register(Object mbean, ObjectName name) throws Exception {
        ManagementFactory.getPlatformMBeanServer().registerMBean(mbean, name);
        registered.add(name);
        return name;
    }

    /** 注销本实例注册过的全部 MBean，用于模拟"目标 JVM 未启用 &lt;jmxConfigurator/&gt;"。 */
    public void unregisterAll() throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        for (ObjectName name : registered) {
            if (mbs.isRegistered(name)) {
                mbs.unregisterMBean(name);
            }
        }
        registered.clear();
    }

    public static int findFreePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    /**
     * 只 accept、不回应的 TCP 服务，模拟"端口能连上但对面不说话"——正是防火墙丢包、
     * 或 RMI 数据端口（jmxremote.rmi.port）未放通时的真实表现。
     */
    public static final class BlackholeServer implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread acceptor;

        private BlackholeServer() throws IOException {
            this.socket = new ServerSocket(0);
            this.acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!socket.isClosed()) {
                        try {
                            // 刻意不关闭 accept 到的 socket：关了客户端会立刻 EOF，就挂不住了
                            socket.accept();
                        } catch (IOException e) {
                            return;
                        }
                    }
                }
            }, "jmx-logger-blackhole");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        public String server() {
            return "127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Override
    public void close() {
        try {
            unregisterAll();
        } catch (Exception ignored) {
            // 测试清理阶段忽略
        }
        try {
            connectorServer.stop();
        } catch (IOException ignored) {
            // 测试清理阶段忽略
        }
        try {
            UnicastRemoteObject.unexportObject(registry, true);
        } catch (Exception ignored) {
            // 测试清理阶段忽略
        }
    }
}
