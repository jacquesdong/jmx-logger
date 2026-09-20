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
 * jmx-logger get                       列出单独配置了级别的 logger
 * jmx-logger get --all                 连同未单独配置级别的一起列出
 * jmx-logger get --effective           同时显示实际生效级别
 * jmx-logger get <name>                查看指定 logger（未配置也照样给出这一行）
 * jmx-logger get <name> -r             递归：该 logger 及其子 logger 中配置过级别的
 * </pre>
 *
 * <p><b>列表默认只列单独配置了级别的 logger</b>：目标上绝大多数 logger 都处于继承状态，
 * 全打出来会把"谁被改过级别"淹掉。省略掉的数量在统计行里交代，不至于让人以为目标上
 * 只有这几个 logger；要连未配置的一起看用 {@code --all}。显式指定名字时不过滤——
 * 空 Level 本身就是"继承父 logger"的答案。
 */
@Command(name = "get", description = "查看 Logger 的级别（默认只列单独配置了级别的）",
        mixinStandardHelpOptions = true)
public class GetCommand implements Callable<Integer> {

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", arity = "0..1", description = "Logger 名称（留空则列出全部已配置级别的）")
    private String name;

    @Option(names = {"-r", "--recursive"}, description = "递归列出该 logger 及其所有子 logger")
    private boolean recursive;

    /** 未单独配置级别的 logger 默认不列（它们是继承状态，占绝大多数）；{@code --all} 时全列出来。 */
    @Option(names = {"--all"},
            description = "连同未单独配置级别的 logger 一起列出（默认只列配了级别的）")
    private boolean all;

    /**
     * 是否额外显示「实际生效级别」。
     *
     * <p><b>默认不查</b>：logback 通道没有批量接口，每个 logger 的生效级别都要一次独立的
     * {@code getLoggerEffectiveLevel} 远程调用，这是 {@code get} 在大目标上的主要耗时；
     * 而多数时候扫一眼只想看「谁被单独配过级别」（Level 列）。actuator 通道一次调用就能拿到
     * 两者，但为了让两条通道的输出形态一致，同样默认不显示。
     */
    @Option(names = {"--effective"},
            description = "额外显示实际生效级别（每个 logger 多一次 JMX 调用，默认不查）")
    private boolean effective;

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

        List<String[]> rows = new ArrayList<String[]>();
        for (String logger : loggers) {
            String level = client.getLoggerLevel(logger);
            if (skipUnconfigured(level)) {
                continue;
            }
            rows.add(row(client, logger, level));
        }
        printTable(rows, loggers.length);
    }

    private void listRecursive(JmxClient client, String base) throws Exception {
        String[] loggers = client.getLoggerList();
        List<String> matched = new ArrayList<String>();
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

        List<String[]> rows = new ArrayList<String[]>();
        for (String logger : matched) {
            String level = client.getLoggerLevel(logger);
            if (skipUnconfigured(level)) {
                continue;
            }
            rows.add(row(client, logger, level));
        }
        printTable(rows, matched.size());
    }

    private void showOne(JmxClient client, String loggerName) throws Exception {
        // 显式点名的 logger 一定给出这一行：Level 留空本身就是「继承父 logger」的答案，
        // 什么都不打会让人以为命令没生效
        printHeader();
        printRow(loggerName, client.getLoggerLevel(loggerName), effectiveLevelOf(client, loggerName));
    }

    /** 组装一行待打印的数据；生效级别只在 {@code --effective} 时才查。 */
    private String[] row(JmxClient client, String logger, String level) throws Exception {
        return new String[]{logger, level, effective ? client.getLoggerEffectiveLevel(logger) : null};
    }

    /** 生效级别只在 {@code --effective} 时才查：每个 logger 都要一次独立的远程调用。 */
    private String effectiveLevelOf(JmxClient client, String loggerName) throws Exception {
        return effective ? client.getLoggerEffectiveLevel(loggerName) : null;
    }

    /**
     * 打印表格与统计行。{@code total} 是匹配到的 logger 总数，用来交代省略了多少个未配置级别的
     * ——只说"共 N 个"会让人以为目标上只有这么多 logger。
     */
    private void printTable(List<String[]> rows, int total) {
        int omitted = total - rows.size();
        if (rows.isEmpty()) {
            System.out.println(omitted == 0
                    ? "共 0 个 logger"
                    : "共 0 个 logger（" + omitted + " 个都没有单独配置级别）");
            return;
        }

        printHeader();
        for (String[] row : rows) {
            printRow(row[0], row[1], row[2]);
        }
        System.out.println("\n共 " + rows.size() + " 个 logger"
                + (omitted == 0 ? "" : "（仅列单独配置了级别的；另有 " + omitted + " 个未配置，已省略）"));
    }

    /** 未单独配置级别的行默认不列：它们在目标上占绝大多数，且正是"继承"的那一类。 */
    private boolean skipUnconfigured(String level) {
        return !all && isUnset(level);
    }

    /** 目标侧对「未单独配置级别」返回空串（logback 的 {@code EMPTY} / actuator 的空 configuredLevel）。 */
    private static boolean isUnset(String level) {
        return level == null || level.isEmpty();
    }

    private void printHeader() {
        if (effective) {
            System.out.printf("%-50s %-10s %-10s%n", "Logger", "Level", "Effective");
            System.out.println("-------------------------------------------------- ---------- ----------");
        } else {
            System.out.printf("%-50s %-10s%n", "Logger", "Level");
            System.out.println("-------------------------------------------------- ----------");
        }
    }

    private void printRow(String logger, String level, String effectiveLevel) {
        // 目标侧的真值就是「空」：logback 对「未配置级别」与「logger 不存在」都返回空串
        // （源码常量 JMXConfigurator.EMPTY），actuator 的 configuredLevel 同样为空。
        // 空就留空，不渲染成 "(inherited)" 这类目标侧没有的取值；含义见 README
        String configured = level == null ? "" : level;
        if (effective) {
            System.out.printf("%-50s %-10s %-10s%n",
                    logger, configured, effectiveLevel == null ? "" : effectiveLevel);
        } else {
            System.out.printf("%-50s %-10s%n", logger, configured);
        }
    }
}
