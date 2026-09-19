package com.jmxlogger;

import com.jmxlogger.provider.LoggerProvider;
import com.jmxlogger.provider.LogbackJmxProvider;
import com.jmxlogger.transport.RemoteJmxConnector;
import com.jmxlogger.transport.TargetConnector;

import java.io.IOException;
import java.util.List;

/**
 * 到目标 JVM 的 JMX 连接 + Logback 级别操作的一次性门面。
 *
 * <p>内部已拆成两层，本类只保留原有方法名与行为，便于上层命令逐步迁移：
 * <ul>
 *   <li>传输层：{@link RemoteJmxConnector}（{@code -s host:port} 的 RMI 连接）与
 *       {@code LocalPidConnector}（{@code -P pid} 的本地 attach），统一为 {@link TargetConnector}；</li>
 *   <li>{@link LogbackJmxProvider}：Provider 层，负责"连上之后操作哪个 MBean"。 </li>
 * </ul>
 * 新代码请直接用这两个类（或 {@link LoggerProvider} 接口），本类在命令层全部迁移完成后会移除。
 */
public class JmxClient implements AutoCloseable {

    /** 默认连接超时：10 秒。{@code <= 0} 表示不限制（等同于改造前的行为）。 */
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = RemoteJmxConnector.DEFAULT_CONNECT_TIMEOUT_MILLIS;

    private final TargetConnector connector;
    private final LoggerProvider provider;

    public JmxClient(String server, String username, String password) throws IOException {
        this(server, username, password, DEFAULT_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * @param connectTimeoutMillis 连接超时（毫秒），{@code <= 0} 表示不限制。
     */
    public JmxClient(String server, String username, String password, long connectTimeoutMillis)
            throws IOException {
        this(new RemoteJmxConnector(server, username, password, connectTimeoutMillis));
    }

    /**
     * 在已建立的传输层连接上工作：{@code -P/--pid} 的本地 attach 与 {@code -s} 的 RMI
     * 走的是同一个 {@link TargetConnector} 接口，本类不需要区分。
     */
    public JmxClient(TargetConnector connector) throws IOException {
        this.connector = connector;
        try {
            this.provider = new LogbackJmxProvider(connector);
        } catch (IOException | RuntimeException e) {
            // 构造失败时不会有人调用 close()，这里必须自己收尾，否则 RMI 连接泄漏
            connector.close();
            throw e;
        }
    }

    /** 底层 Provider，供逐步迁移期需要能力协商（如 reload）的调用方使用。 */
    public LoggerProvider provider() {
        return provider;
    }

    public String[] getLoggerList() throws Exception {
        List<String> names = provider.listLoggerNames();
        return names.toArray(new String[names.size()]);
    }

    /**
     * 返回配置的级别。
     *
     * <p>真实 logback（1.1.x / 1.2.x）的 {@code JMXConfigurator#getLoggerLevel} 在
     * 「该 logger 未单独配置级别」和「logger 不存在」两种情况下都返回<b>空串</b>，
     * 而不是 {@code null}；调用方要按空串判断"继承"，不能判 null。
     */
    public String getLoggerLevel(String loggerName) throws Exception {
        return provider.getLoggerLevel(loggerName);
    }

    /** 返回实际生效的级别。 */
    public String getLoggerEffectiveLevel(String loggerName) throws Exception {
        return provider.getLoggerEffectiveLevel(loggerName);
    }

    /**
     * 设置 logger 级别。
     *
     * <p>目标侧 logback 的 {@code setLoggerLevel} 有特殊约定：{@code level} 传
     * <b>Java null 会被静默忽略</b>（源码首行 {@code if (levelStr == null) return;}），
     * 想恢复"继承父 logger"必须传<b>字符串 {@code "null"}</b>；传了无法识别的级别同样静默忽略。
     * 两种情形都不报错，所以调用方要在本地就把级别校验干净。
     */
    public void setLoggerLevel(String loggerName, String level) throws Exception {
        provider.setLoggerLevel(loggerName, level);
    }

    public void reloadDefaultConfiguration() throws Exception {
        provider.reloadDefaultConfiguration();
    }

    /**
     * 按文件路径重新加载配置。
     * 注意：Logback 的 reloadByFileName 接受的是文件路径（内部会 new File(path) 校验存在性，
     * 再自行转 URL），因此这里直接传原始路径，由目标 JVM 解析读取。
     */
    public void reloadByFileName(String filePath) throws Exception {
        provider.reloadByFileName(filePath);
    }

    @Override
    public void close() {
        connector.close();
    }
}
