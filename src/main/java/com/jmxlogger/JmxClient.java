package com.jmxlogger;

import com.jmxlogger.provider.LoggerProvider;
import com.jmxlogger.provider.LogbackJmxProvider;
import com.jmxlogger.provider.ProviderFactory;
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
 *       {@code LocalPidConnector}（{@code -p pid} 的本地 attach），统一为 {@link TargetConnector}；</li>
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
     * 在已建立的传输层连接上工作：{@code -p/--pid} 的本地 attach 与 {@code -s} 的 RMI
     * 走的是同一个 {@link TargetConnector} 接口，本类不需要区分。
     * 通道按 {@link ProviderFactory#AUTO} 选择。
     */
    public JmxClient(TargetConnector connector) throws IOException {
        this(connector, ProviderFactory.AUTO);
    }

    /**
     * @param target 通道选择，见 {@link ProviderFactory}（{@code auto} / {@code logback} /
     *               {@code actuator}）
     */
    public JmxClient(TargetConnector connector, String target) throws IOException {
        this(connector, target, null);
    }

    /**
     * @param objectName 直接点名的目标 MBean（{@code --object-name}）；{@code null} 时按
     *                   ObjectName 模式自动探测
     */
    public JmxClient(TargetConnector connector, String target, String objectName) throws IOException {
        this.connector = connector;
        try {
            this.provider = ProviderFactory.open(connector, target, objectName);
        } catch (IOException | RuntimeException e) {
            // 构造失败时不会有人调用 close()，这里必须自己收尾，否则 RMI 连接泄漏
            // （ProviderFactory 只在"两条通道都不可用"时才关，单条通道失败归这里收尾）
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
     * <p>{@code level} 为<b>空串或 {@code null}</b> 时表示「清除该 logger 自身的级别配置，
     * 恢复继承父 logger」，由 Provider 按通道翻译（logback 下发字符串 {@code "null"}，
     * actuator 下发 Java {@code null}）——命令行侧对应 {@code clear} 子命令。
     *
     * <p>其余取值原样下发；目标侧对无法识别的级别静默忽略（不报错），
     * 所以调用方要在本地就把级别校验干净。
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
