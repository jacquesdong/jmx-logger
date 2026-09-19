package com.jmxlogger.provider;

import java.util.List;

/**
 * <b>日志级别操作</b>抽象：只关心"连上之后操作哪个 MBean、怎么读写级别"，
 * 连接本身由 {@link com.jmxlogger.transport.TargetConnector} 负责。
 *
 * <p>当前实现只有 {@link LogbackJmxProvider}；计划 P3 会加
 * {@code ActuatorJmxProvider} 作为兜底通道，届时由 ProviderFactory 按
 * {@code --target} 选择。
 *
 * <p>方法命名沿用 logback 侧的概念（configured / effective），
 * 与 actuator 的 {@code configuredLevel / effectiveLevel} 也是同一套语义。
 */
public interface LoggerProvider extends AutoCloseable {

    /** Provider 标识，即 {@code --target} 的取值：{@code logback} / {@code actuator}。 */
    String id();

    /** 能力声明；{@code reload} 类命令要先看这里再决定能不能做。 */
    Capabilities capabilities();

    /** 列出目标 JVM 中所有 logger 的名字。 */
    List<String> listLoggerNames() throws Exception;

    /**
     * 读取「配置级别」。
     *
     * <p>真实 logback（1.1.x / 1.2.x）的 {@code JMXConfigurator#getLoggerLevel} 在
     * 「该 logger 未单独配置级别」和「logger 不存在」两种情况下都返回<b>空串</b>
     * （源码常量 {@code JMXConfigurator.EMPTY}），而不是 {@code null}；
     * 调用方要按空串判断"继承"，不能判 null。
     */
    String getLoggerLevel(String loggerName) throws Exception;

    /** 读取「实际生效级别」（会向上追溯父 logger）。 */
    String getLoggerEffectiveLevel(String loggerName) throws Exception;

    /**
     * 设置 logger 级别。
     *
     * <p>{@code level} 为<b>空串或 {@code null}</b> 时表示「清除该 logger 自身的级别配置，
     * 恢复继承父 logger」，由本实现按通道翻译成目标侧认可的取值：
     * <ul>
     *   <li>logback：下发<b>字符串 {@code "null"}</b>——Java {@code null} 与空串都会被目标侧
     *       静默忽略（源码首行 {@code if (levelStr == null) return;}，空串则过不了
     *       {@code Level.toLevel}），绝不能原样下发；</li>
     *   <li>actuator：下发 <b>Java {@code null}</b>——{@code JmxInvocation} 对 null 原样穿透，
     *       而空串是无效级别，同样不能原样下发。</li>
     * </ul>
     * 命令行侧只有 {@code clear} 子命令产生这个取值；{@code set} 不接受空串，也不接受
     * {@code "null"}（那是 logback 目标侧的指令，不是用户输入）。
     *
     * <p>其余取值原样下发；目标侧对无法识别的级别同样静默忽略，所以调用方仍要在本地先校验。
     */
    void setLoggerLevel(String loggerName, String level) throws Exception;

    /** 重新加载目标 JVM 的默认配置。 */
    void reloadDefaultConfiguration() throws Exception;

    /** 按目标 JVM 上的文件路径重新加载配置。 */
    void reloadByFileName(String filePath) throws Exception;

    @Override
    void close();
}
