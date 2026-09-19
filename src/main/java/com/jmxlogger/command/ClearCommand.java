package com.jmxlogger.command;

import com.jmxlogger.JmxClient;
import com.jmxlogger.JmxLoggerCli;
import com.jmxlogger.support.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * 清除 Logger 自身配置的级别，恢复继承父 logger。
 *
 * <pre>
 * jmx-logger clear &lt;name&gt;
 * </pre>
 *
 * <p>底层就是"把级别设为空"：API 层（{@code LoggerProvider#setLoggerLevel}）用<b>空串</b>
 * 表示"恢复继承"，两侧真正的清除指令由 Provider 按通道翻译——
 * logback 下发<b>字符串 {@code "null"}</b>（直接下发 Java {@code null} 会被目标侧静默忽略），
 * actuator 下发 <b>Java {@code null}</b>。
 *
 * <p>与 {@code reload} 的区别：本命令只动这一个 logger；{@code reload} 会把<b>所有</b> logger
 * 拉回配置文件状态，且 actuator 通道不支持。临时调完级别要复原时用本命令，不要用 reload。
 *
 * <p>{@code <name>} 必填：不带名字的"全部清除"风险太高，不做；批量需求走 {@code reload}。
 */
@Command(name = "clear", description = "清除 Logger 自身配置的级别，恢复继承父 logger",
        mixinStandardHelpOptions = true)
public class ClearCommand implements Callable<Integer> {

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", description = "Logger 名称")
    private String name;

    /** 同 {@code GetCommand}：异常交给顶层处理器，本方法不 {@code System.exit}。 */
    @Override
    public Integer call() throws Exception {
        try (JmxClient client = parent.connect()) {
            client.setLoggerLevel(name, "");
            System.out.println("已清除 logger [" + name + "] 的级别配置，恢复继承父 logger");
        }
        return ExitCodes.OK;
    }
}
