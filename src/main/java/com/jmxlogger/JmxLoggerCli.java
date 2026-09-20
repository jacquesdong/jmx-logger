package com.jmxlogger;

import com.jmxlogger.command.ClearCommand;
import com.jmxlogger.command.DoctorCommand;
import com.jmxlogger.command.GetCommand;
import com.jmxlogger.command.ReloadCommand;
import com.jmxlogger.command.SetCommand;
import com.jmxlogger.provider.ProviderFactory;
import com.jmxlogger.support.CommandSupport;
import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.support.PasswordResolver;
import com.jmxlogger.support.VersionProvider;
import com.jmxlogger.transport.LocalPidConnector;
import com.jmxlogger.transport.RemoteJmxConnector;
import com.jmxlogger.transport.TargetConnector;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * jmx-logger 顶层命令，持有全局连接参数并注册子命令。
 *
 * <pre>
 * <pre>
 * 用法: jmx-logger -s host:port [--username user] [--password ...] <get|set|clear|reload|doctor> ...
 * 用法: jmx-logger -p pid <get|set|clear|reload|doctor> ...    （本地 attach，目标侧无需开端口）
 * </pre>
 *
 * <p>错误处理统一走 {@link CommandSupport}：子命令直接抛异常，
 * 由 {@code main} 装的执行异常处理器映射成 {@link ExitCodes} 里的退出码，
 * 子命令自身不再 {@code System.exit}。
 */
@Command(
        name = "jmx-logger",
        abbreviateSynopsis = true,
        sortOptions = false,
        mixinStandardHelpOptions = true,
        // 版本不再写死：由构建期生成的 git.properties 提供（版本号 + commit + 构建时间）
        versionProvider = VersionProvider.class,
        description = "通过 JMX 远程管理 Logback 的 Logger 级别与配置重载。",
        subcommands = {
                GetCommand.class,
                SetCommand.class,
                ClearCommand.class,
                ReloadCommand.class,
                DoctorCommand.class
        }
)
public class JmxLoggerCli implements Runnable {

    @Option(names = {"-s", "--server"},
            description = "目标 JVM 的 JMX 地址，默认值为 ${DEFAULT-VALUE}")
    private String server = "127.0.0.1:19000";

    @Option(names = {"--username"}, description = "JMX 用户名（可选）")
    private String username;

    /**
     * JMX 密码。三种给法（详见 {@link PasswordResolver}）：
     * {@code --password <明文>}（会出现在 ps 输出里）、{@code --password} 不带取值（交互式读取）、
     * 完全省略时回退到环境变量 {@value PasswordResolver#ENV_PASSWORD}。
     */
    @Option(names = {"--password"}, arity = "0..1",
            fallbackValue = PasswordResolver.INTERACTIVE,
            description = "JMX 密码（可选）。不带取值时交互式读取（不回显）；"
                    + "完全省略时回退到环境变量 " + PasswordResolver.ENV_PASSWORD)
    private String password;

    @Option(names = {"-p", "--pid"},
            description = "目标 JVM 的进程号：本地 attach 并现场启动管理代理，"
                    + "目标侧无需预先开 JMX 端口；指定后优先于 -s")
    private Long pid;

    private PasswordResolver passwordResolver = PasswordResolver.system();
    private String resolvedPassword;
    private boolean passwordResolved;

    @Option(names = {"-t", "--target"},
            description = "日志通道: auto（先 logback，缺失时兜底 actuator）/ logback / actuator，"
                    + "默认值为 ${DEFAULT-VALUE}")
    private String target = ProviderFactory.AUTO;

    /**
     * 直接点名的目标 MBean。目标上有多个 LoggerContext（多个 JMXConfigurator）时，
     * 默认"取第一个"未必是要操作的那个，用它显式指定；取值直接抄 doctor 打印的候选 MBean。
     */
    @Option(names = {"--object-name"},
            description = "直接指定目标 MBean 的 ObjectName（多 LoggerContext 时用，取值见 doctor 的候选 MBean）")
    private String objectName;

    // paramLabel 自带尖括号：字段叫 timeoutSeconds，不覆盖会渲染成 <timeoutSeconds>；
    // 显式覆盖时 picocli 原样输出，尖括号得自己带上，否则只有它跟别的选项长得不一样
    @Option(names = {"--timeout"}, paramLabel = "<seconds>",
            description = "连接超时（秒），0 表示不限制，默认值为 ${DEFAULT-VALUE}")
    private long timeoutSeconds = JmxClient.DEFAULT_CONNECT_TIMEOUT_MILLIS / 1000L;

    @Option(names = {"-v", "--verbose"},
            description = "出错时打印完整堆栈（默认只打印一行错误原因）")
    private boolean verbose;

    public String getServer() {
        return server;
    }

    public String getUsername() {
        return username;
    }

    /** {@code --password} 的原始取值（可能是"交互式读取"标记），解析请用 {@link #resolvePassword()}。 */
    public String getPassword() {
        return password;
    }

    /**
     * 解析出真正用于连接的密码：显式明文 → 交互式读取 → 环境变量 {@value PasswordResolver#ENV_PASSWORD}；
     * 都没有时返回 null（不带凭证连接）。结果只解析一次，连接与报错脱敏共用。
     */
    public String resolvePassword() {
        if (!passwordResolved) {
            if (PasswordResolver.isLiteral(password)) {
                System.err.println("提示: 密码来自命令行参数，会出现在 ps 输出里；"
                        + "建议改用环境变量 " + PasswordResolver.ENV_PASSWORD
                        + "，或 --password 不带取值交互式读取。");
            }
            resolvedPassword = passwordResolver.resolve(password);
            passwordResolved = true;
        }
        return resolvedPassword;
    }

    /** 已解析的密码，不触发交互式读取；仅供报错脱敏使用。 */
    public String peekPassword() {
        return resolvedPassword;
    }

    /** 测试注入用：替换密码来源（环境变量与读取方式）。 */
    void setPasswordResolver(PasswordResolver passwordResolver) {
        this.passwordResolver = passwordResolver;
    }

    /** 本地 attach 的目标进程号；未指定时为 null（此时按 -s 走 RMI）。 */
    public Long getPid() {
        return pid;
    }

    /** 是否打印完整堆栈。报错脱敏与 verbose 输出都从顶层命令取，子命令不各存一份。 */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * 建立到目标 JVM 的<b>传输层</b>连接，不解析任何 MBean。
     * 供 {@code doctor} 这类"先看看目标上有什么"的命令使用——直接建 Provider
     * 会因为 MBean 不存在而直接报错，就诊断不出原因了。
     *
     * <p>给了 {@code -p/--pid} 就走本地 attach（目标侧零配置），否则按 {@code -s/--server} 走 RMI。
     * {@code -p} 优先：用户既然显式指定了进程，就不该再要求目标开端口。
     */
    public TargetConnector openConnector() throws Exception {
        String secret = resolvePassword();
        if (pid != null) {
            return new LocalPidConnector(String.valueOf(pid), username, secret, toMillis(timeoutSeconds));
        }
        return new RemoteJmxConnector(requireServer(), username, secret, toMillis(timeoutSeconds));
    }

    /**
     * 建立到目标 JVM 的 JmxClient 连接（传输层由 {@link #openConnector()} 决定，
     * 日志通道由 {@code --target} 决定，见 {@link ProviderFactory}）。
     */
    public JmxClient connect() throws Exception {
        return new JmxClient(openConnector(), target, objectName);
    }

    /** {@code -t/--target} 的原始取值：{@code auto} / {@code logback} / {@code actuator}。 */
    public String getTarget() {
        return target;
    }

    /** {@code --object-name} 的原始取值；未指定时为 {@code null}（按 ObjectName 模式自动探测）。 */
    public String getObjectName() {
        return objectName;
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
        System.exit(commandLine().execute(args));
    }

    /**
     * 装上统一错误处理（{@link CommandSupport}）的 {@link CommandLine}。
     * {@code main} 与测试共用同一份装配，否则 picocli 的默认处理器会把异常直接抛出来，
     * 测试就断言不到退出码。
     */
    public static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new JmxLoggerCli());
        commandLine.setExecutionExceptionHandler(new CommandLine.IExecutionExceptionHandler() {
            @Override
            public int handleExecutionException(Exception ex, CommandLine parsed,
                                                CommandLine.ParseResult parseResult) {
                JmxLoggerCli cli = findTopCommand(parsed);
                CommandSupport.printError(ex,
                        cli != null && cli.isVerbose(),
                        cli == null ? null : cli.peekPassword());
                return CommandSupport.exitCodeOf(ex);
            }
        });
        return commandLine;
    }

    /**
     * 子命令抛异常时，从被执行的命令沿父命令往上找顶层 {@link JmxLoggerCli}，
     * 以便取到 {@code --verbose} 与（用于脱敏的）密码。
     */
    private static JmxLoggerCli findTopCommand(CommandLine parsed) {
        for (CommandLine current = parsed; current != null; current = current.getParent()) {
            if (current.getCommand() instanceof JmxLoggerCli) {
                return (JmxLoggerCli) current.getCommand();
            }
        }
        return null;
    }
}
