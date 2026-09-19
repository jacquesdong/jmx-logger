package com.jmxlogger;

import com.jmxlogger.command.DoctorCommand;
import com.jmxlogger.command.GetCommand;
import com.jmxlogger.command.ReloadCommand;
import com.jmxlogger.command.SetCommand;
import com.jmxlogger.transport.RemoteJmxConnector;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * jmx-logger 顶层命令，持有全局连接参数并注册子命令。
 *
 * <pre>
 * 用法: jmx-logger -s host:port [-u user] [-p pass] <get|set|reload> ...
 * </pre>
 */
@Command(
        name = "jmx-logger",
        mixinStandardHelpOptions = true,
        version = "jmx-logger 1.0.0",
        description = "通过 JMX 远程管理 Logback 的 Logger 级别与配置重载。",
        subcommands = {
                GetCommand.class,
                SetCommand.class,
                ReloadCommand.class,
                DoctorCommand.class
        }
)
public class JmxLoggerCli implements Runnable {

    @Option(names = {"-s", "--server"},
            description = "目标 JVM 的 JMX 地址，默认值为 ${DEFAULT-VALUE}")
    private String server = "127.0.0.1:19000";

    @Option(names = {"-u", "--username"}, description = "JMX 用户名（可选）")
    private String username;

    @Option(names = {"-p", "--password"}, description = "JMX 密码（可选）")
    private String password;

    @Option(names = {"--timeout"}, paramLabel = "秒",
            description = "连接超时（秒），0 表示不限制，默认值为 ${DEFAULT-VALUE}")
    private long timeoutSeconds = JmxClient.DEFAULT_CONNECT_TIMEOUT_MILLIS / 1000L;

    public String getServer() {
        return server;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * 建立到目标 JVM 的<b>传输层</b>连接，不解析任何 MBean。
     * 供 {@code doctor} 这类"先看看目标上有什么"的命令使用——直接建 Provider
     * 会因为 MBean 不存在而直接报错，就诊断不出原因了。
     */
    public RemoteJmxConnector openConnector() throws Exception {
        return new RemoteJmxConnector(requireServer(), username, password, toMillis(timeoutSeconds));
    }

    /** 建立到目标 JVM 的 JmxClient 连接。 */
    public JmxClient connect() throws Exception {
        return new JmxClient(requireServer(), username, password, toMillis(timeoutSeconds));
    }

    private String requireServer() {
        if (server == null || server.isEmpty()) {
            throw new IllegalArgumentException("必须通过 -s/--server 指定目标 JVM 的 JMX 地址 (host:port)");
        }
        return server;
    }

    /** 秒 → 毫秒，{@code <= 0} 保持为"不限制"，并防止极端取值溢出。 */
    private static long toMillis(long seconds) {
        if (seconds <= 0) {
            return 0L;
        }
        return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
    }

    @Override
    public void run() {
        // 无子命令时打印使用说明
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JmxLoggerCli()).execute(args);
        System.exit(exitCode);
    }
}
