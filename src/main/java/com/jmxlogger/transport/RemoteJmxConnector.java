package com.jmxlogger.transport;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 通过 RMI 连到目标 JVM 已开启的 JMX 代理（{@code -s host:port}）。
 *
 * <p>对应 URL 形态 {@code service:jmx:rmi:///jndi/rmi://host:port/jmxrmi}，
 * 即目标进程以 {@code -Dcom.sun.management.jmxremote.port} 启动的方式。
 */
public class RemoteJmxConnector implements TargetConnector {

    /** 默认连接超时：10 秒。{@code <= 0} 表示不限制（等同于改造前的行为）。 */
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L;

    private final String server;
    private final String url;
    private final JMXConnector connector;
    private final MBeanServerConnection mbsc;

    public RemoteJmxConnector(String server, String username, String password) throws IOException {
        this(server, username, password, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * @param connectTimeoutMillis 连接超时（毫秒），{@code <= 0} 表示不限制。
     */
    public RemoteJmxConnector(String server, String username, String password, long connectTimeoutMillis)
            throws IOException {
        this.server = server;
        this.url = "service:jmx:rmi:///jndi/rmi://" + server + "/jmxrmi";
        this.connector = connect(server, url, username, password, connectTimeoutMillis);
        this.mbsc = connector.getMBeanServerConnection();
    }

    @Override
    public MBeanServerConnection getMBeanServerConnection() throws IOException {
        return mbsc;
    }

    @Override
    public String describe() {
        return server;
    }

    /** 完整的 JMX Service URL，报错时一并打印，便于对照目标侧的 {@code java.rmi.server.hostname}。 */
    public String url() {
        return url;
    }

    /**
     * 凭证环境。包内可见：{@link LocalPidConnector} 连本地代理时用的是同一套约定
     * （不传 {@code -u} 就不带 CREDENTIALS，本地代理通常免认证）。
     */
    static Map<String, Object> credentialsEnv(String username, String password) {
        Map<String, Object> env = new HashMap<>();
        if (username != null) {
            String[] credentials = new String[]{username, password == null ? "" : password};
            env.put(JMXConnector.CREDENTIALS, credentials);
        }
        return env;
    }

    private static JMXConnector connect(final String server, final String url,
                                        String username, String password,
                                        long timeoutMillis) throws IOException {
        // 与改造前一致：URL 非法时直接抛出 MalformedURLException，不做包装
        final JMXServiceURL serviceURL = new JMXServiceURL(url);
        final Map<String, Object> env = credentialsEnv(username, password);

        Callable<JMXConnector> task = new Callable<JMXConnector>() {
            @Override
            public JMXConnector call() throws IOException {
                try {
                    return JMXConnectorFactory.connect(serviceURL, env);
                } catch (IOException e) {
                    throw connectFailed(server, url, e.getMessage(), e);
                }
            }
        };

        if (timeoutMillis <= 0) {
            return ConnectWithTimeout.call(task, timeoutMillis, null);
        }

        return ConnectWithTimeout.call(task, timeoutMillis,
                "连接 JMX 服务器超时（超过 " + timeoutMillis + " ms）: " + server + " (" + url + ")\n"
                        + "常见原因：目标未开 JMX 端口、防火墙拦了 RMI 数据端口、"
                        + "-Djava.rmi.server.hostname 指向不可达地址、"
                        + "com.sun.management.jmxremote.rmi.port 与 port 不一致且未放通。\n"
                        + "可用 --timeout 放宽（0 表示不限）。");
    }

    private static IOException connectFailed(String server, String url, String reason, Throwable cause) {
        return new IOException("无法连接到 JMX 服务器: " + server + " (" + url + "): " + reason, cause);
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
    }
}
