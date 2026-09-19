package com.jmxlogger.command;

import com.jmxlogger.JmxClient;
import com.jmxlogger.JmxLoggerCli;
import com.jmxlogger.support.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * 设置 Logger 级别。
 *
 * <pre>
 * jmx-logger set <name> <level>
 * </pre>
 */
@Command(name = "set", description = "设置 Logger 的级别", mixinStandardHelpOptions = true)
public class SetCommand implements Callable<Integer> {

    private static final List<String> VALID_LEVELS = Arrays.asList(
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "ALL", "OFF");

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", description = "Logger 名称")
    private String name;

    @Parameters(index = "1", description = "日志级别: TRACE, DEBUG, INFO, WARN, ERROR, ALL, OFF")
    private String level;

    /** 同 {@code GetCommand}：异常交给顶层处理器，本方法不 {@code System.exit}。 */
    @Override
    public Integer call() throws Exception {
        String upper = level.toUpperCase(Locale.ROOT);
        if (!VALID_LEVELS.contains(upper)) {
            // 非法级别是用法错误（退出码 2），且不该为此连一次目标 JVM
            throw new IllegalArgumentException("非法的日志级别 \"" + level
                    + "\"，合法值: " + String.join(", ", VALID_LEVELS));
        }

        try (JmxClient client = parent.connect()) {
            client.setLoggerLevel(name, upper);
            System.out.println("已将 logger [" + name + "] 的级别设置为 " + upper);
        }
        return ExitCodes.OK;
    }
}
