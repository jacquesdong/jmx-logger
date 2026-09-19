package com.jmxlogger.provider;

import java.util.List;

/**
 * <b>日志级别操作</b>抽象：只关心"连上之后操作哪个 MBean、怎么读写级别"，
 * 连接本身由 {@link com.jmxlogger.transport.TargetConnector} 负责。
 *
 * <p>当前实现只有 {@link LogbackJmxProvider}；计划 P3 会加
 * {@code BootActuatorJmxProvider} 作为兜底通道，届时由 ProviderFactory 按
 * {@code --target} 选择。
 *
 * <p>方法命名沿用 logback 侧的概念（configured / effective），
 * 与 actuator 的 {@code configuredLevel / effectiveLevel} 也是同一套语义。
 */
public interface LoggerProvider extends AutoCloseable {

    /** Provider 标识，如 {@code logback-jmx}、{@code boot-actuator-jmx}。 */
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
     * <p>{@code level} 原样下发给目标侧，不做本地改写：目标侧 logback 的
     * {@code setLoggerLevel} 有特殊约定——传 <b>Java null 会被静默忽略</b>
     * （源码首行 {@code if (levelStr == null) return;}），要恢复"继承父 logger"
     * 必须传<b>字符串 {@code "null"}</b>；传了无法识别的级别同样静默忽略。
     * 由调用方（命令层）负责把用户输入翻译成目标侧认可的取值。
     */
    void setLoggerLevel(String loggerName, String level) throws Exception;

    /** 重新加载目标 JVM 的默认配置。 */
    void reloadDefaultConfiguration() throws Exception;

    /** 按目标 JVM 上的文件路径重新加载配置。 */
    void reloadByFileName(String filePath) throws Exception;

    @Override
    void close();
}
