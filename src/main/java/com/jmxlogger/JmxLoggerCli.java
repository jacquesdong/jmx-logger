package com.jmxlogger;

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
                ReloadCommand.class
        }
)
public class JmxLoggerCli implements Runnable {

    @Option(names = {"-s", "--server"},
            description = "目标 JVM 的 JMX 地址，格式 host:port")
    private String server;

    @Option(names = {"-u", "--username"}, description = "JMX 用户名（可选）")
    private String username;

    @Option(names = {"-p", "--password"}, description = "JMX 密码（可选）")
    private String password;

    public String getServer() {
        return server;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /** 建立到目标 JVM 的 JmxClient 连接。 */
    public JmxClient connect() throws Exception {
        if (server == null || server.isEmpty()) {
            throw new IllegalArgumentException("必须通过 -s/--server 指定目标 JVM 的 JMX 地址 (host:port)");
        }
        return new JmxClient(server, username, password);
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
