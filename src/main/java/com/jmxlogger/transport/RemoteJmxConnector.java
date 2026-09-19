package com.jmxlogger.transport;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static JMXConnector connect(String server, String url, String username, String password,
                                        long timeoutMillis) throws IOException {
        // 与改造前一致：URL 非法时直接抛出 MalformedURLException，不做包装
        final JMXServiceURL serviceURL = new JMXServiceURL(url);

        final Map<String, Object> env = new HashMap<>();
        if (username != null) {
            String[] credentials = new String[]{username, password == null ? "" : password};
            env.put(JMXConnector.CREDENTIALS, credentials);
        }

        if (timeoutMillis <= 0) {
            try {
                return JMXConnectorFactory.connect(serviceURL, env);
            } catch (IOException e) {
                throw connectFailed(server, url, e.getMessage(), e);
            }
        }

        // RMI 的连接握手（JNDI 查注册表 + 连 RMI 数据端口）本身不可中断、也没有超时参数，
        // 因此放到守护线程里跑，由 Future 限时；超时后放弃这次连接（线程是 daemon，不阻碍 JVM 退出）。
        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jmx-logger-connect");
                t.setDaemon(true);
                return t;
            }
        });
        try {
            Future<JMXConnector> future = executor.submit(new Callable<JMXConnector>() {
                @Override
                public JMXConnector call() throws IOException {
                    return JMXConnectorFactory.connect(serviceURL, env);
                }
            });
            try {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("连接 JMX 服务器超时（超过 " + timeoutMillis + " ms）: "
                        + server + " (" + url + ")\n"
                        + "常见原因：目标未开 JMX 端口、防火墙拦了 RMI 数据端口、"
                        + "-Djava.rmi.server.hostname 指向不可达地址、"
                        + "com.sun.management.jmxremote.rmi.port 与 port 不一致且未放通。\n"
                        + "可用 --timeout 放宽（0 表示不限）。", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw connectFailed(server, url, cause.getMessage(), cause);
                }
                throw connectFailed(server, url, String.valueOf(cause), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw connectFailed(server, url, "连接被中断", e);
            }
        } finally {
            executor.shutdownNow();
        }
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
