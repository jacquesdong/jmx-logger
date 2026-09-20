package com.jmxlogger.command;

import com.jmxlogger.JmxClient;
import com.jmxlogger.JmxLoggerCli;
import com.jmxlogger.support.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

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
public class GetCommand implements Callable<Integer> {

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", arity = "0..1", description = "Logger 名称（留空则列出全部）")
    private String name;

    @Option(names = {"-r", "--recursive"}, description = "递归列出该 logger 及其所有子 logger")
    private boolean recursive;

    /**
     * 异常直接抛给顶层 {@code JmxLoggerCli} 的执行异常处理器，由它统一决定退出码与输出格式；
     * 本方法不 {@code System.exit}，因此测试可以直接调用。
     */
    @Override
    public Integer call() throws Exception {
        try (JmxClient client = parent.connect()) {
            if (name == null || name.isEmpty()) {
                listAll(client);
            } else if (recursive) {
                listRecursive(client, name);
            } else {
                showOne(client, name);
            }
        }
        return ExitCodes.OK;
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
                // 目标侧的真值就是「空」：logback 对「未配置级别」与「logger 不存在」都返回空串
                // （源码常量 JMXConfigurator.EMPTY），actuator 的 configuredLevel 同样为空。
                // 空就留空，不渲染成 "(inherited)" 这类目标侧没有的取值；
                // 空值的含义（未单独配置级别、继承父 logger）写在 README，命令行输出不再带脚注
                level == null ? "" : level,
                effective == null ? "" : effective);
    }
}
