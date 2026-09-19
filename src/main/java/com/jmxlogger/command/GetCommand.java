package com.jmxlogger.command;

import com.jmxlogger.JmxClient;
import com.jmxlogger.JmxLoggerCli;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 查看 Logger 级别。
 *
 * <pre>
 * jmx-logger get                       列出全部 logger 及其生效级别
 * jmx-logger get <name>                查看指定 logger 的配置级别与生效级别
 * jmx-logger get <name> -r             递归查看指定 logger 及其所有子 logger
 * </pre>
 */
@Command(name = "get", description = "查看 Logger 的级别", mixinStandardHelpOptions = true)
public class GetCommand implements Runnable {

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", arity = "0..1", description = "Logger 名称（留空则列出全部）")
    private String name;

    @Option(names = {"-r", "--recursive"}, description = "递归列出该 logger 及其所有子 logger")
    private boolean recursive;

    @Override
    public void run() {
        try (JmxClient client = parent.connect()) {
            if (name == null || name.isEmpty()) {
                listAll(client);
            } else if (recursive) {
                listRecursive(client, name);
            } else {
                showOne(client, name);
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private void listAll(JmxClient client) throws Exception {
        String[] loggers = client.getLoggerList();
        Arrays.sort(loggers);
        printHeader();
        for (String logger : loggers) {
            String level = client.getLoggerLevel(logger);
            String effective = client.getLoggerEffectiveLevel(logger);
            printRow(logger, level, effective);
        }
        System.out.println("\n共 " + loggers.length + " 个 logger");
    }

    private void listRecursive(JmxClient client, String base) throws Exception {
        String[] loggers = client.getLoggerList();
        List<String> matched = new ArrayList<>();
        for (String logger : loggers) {
            if (logger.equals(base) || logger.startsWith(base + ".")) {
                matched.add(logger);
            }
        }
        matched.sort(String::compareTo);

        if (matched.isEmpty()) {
            System.out.println("未找到以 \"" + base + "\" 开头的 logger");
            return;
        }

        printHeader();
        for (String logger : matched) {
            String level = client.getLoggerLevel(logger);
            String effective = client.getLoggerEffectiveLevel(logger);
            printRow(logger, level, effective);
        }
        System.out.println("\n共 " + matched.size() + " 个 logger");
    }

    private void showOne(JmxClient client, String loggerName) throws Exception {
        String level = client.getLoggerLevel(loggerName);
        String effective = client.getLoggerEffectiveLevel(loggerName);
        printHeader();
        printRow(loggerName, level, effective);
    }

    private void printHeader() {
        System.out.printf("%-50s %-10s %-10s%n", "Logger", "Level", "Effective");
        System.out.println("-------------------------------------------------- ---------- ----------");
    }

    private void printRow(String logger, String level, String effective) {
        System.out.printf("%-50s %-10s %-10s%n",
                logger,
                // 真实 logback 对「未配置级别 / logger 不存在」返回空串而非 null，空串同样按继承显示
                level == null || level.isEmpty() ? "(inherited)" : level,
                effective == null ? "" : effective);
    }
}
