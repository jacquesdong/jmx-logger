package com.jmxlogger.command;

import com.jmxlogger.JmxClient;
import com.jmxlogger.JmxLoggerCli;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.Arrays;
import java.util.List;

/**
 * 设置 Logger 级别。
 *
 * <pre>
 * jmx-logger set <name> <level>
 * </pre>
 */
@Command(name = "set", description = "设置 Logger 的级别", mixinStandardHelpOptions = true)
public class SetCommand implements Runnable {

    private static final List<String> VALID_LEVELS = Arrays.asList(
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "ALL", "OFF");

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", description = "Logger 名称")
    private String name;

    @Parameters(index = "1", description = "日志级别: TRACE, DEBUG, INFO, WARN, ERROR, ALL, OFF")
    private String level;

    @Override
    public void run() {
        String upper = level.toUpperCase();
        if (!VALID_LEVELS.contains(upper)) {
            System.err.println("错误: 非法的日志级别 \"" + level
                    + "\"，合法值: " + String.join(", ", VALID_LEVELS));
            System.exit(2);
            return;
        }

        try (JmxClient client = parent.connect()) {
            client.setLoggerLevel(name, upper);
            System.out.println("已将 logger [" + name + "] 的级别设置为 " + upper);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }
}
