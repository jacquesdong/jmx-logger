package com.jmxlogger.transport;

import javax.management.MBeanServerConnection;
import java.io.IOException;

/**
 * 到目标 JVM 的<b>传输层</b>抽象：只负责"怎么连上"，不关心连上之后要操作哪个 MBean。
 *
 * <p>目前只有一个实现 {@link RemoteJmxConnector}（{@code -s host:port} 的 RMI 连接）；
 * 计划 P2 会加 {@code LocalPidConnector}（{@code -p pid} 的本地 attach）。
 * 把传输层独立出来后，上层 Provider 不需要知道连接是怎么建立的。
 */
public interface TargetConnector extends AutoCloseable {

    /** 返回可用于 {@code queryNames} / {@code getAttribute} / {@code invoke} 的连接。 */
    MBeanServerConnection getMBeanServerConnection() throws IOException;

    /** 目标的可读描述（如 {@code 127.0.0.1:19000}），用于错误信息。 */
    String describe();

    /**
     * 关闭底层连接。实现必须吞掉关闭时抛出的异常——关闭失败不该掩盖真正的业务错误，
     * 也不该把一个正常的命令变成非零退出。
     */
    @Override
    void close();
}
