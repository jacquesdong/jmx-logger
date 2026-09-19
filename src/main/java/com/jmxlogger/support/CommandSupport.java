package com.jmxlogger.support;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 命令层错误处理的统一收口：异常 → 退出码，异常 → 报错文本。
 *
 * <p>子命令不再各自 {@code System.exit}，而是把异常抛给 picocli 的
 * {@link CommandLine.IExecutionExceptionHandler}；由它决定退出码并打印错误。
 * 好处是子命令可以被测试直接调用（不会动辄退出 JVM），退出码也只有一个出处。
 *
 * <p>默认只打印一行原因，避免把几十行堆栈糊到终端上；
 * 加 {@code --verbose} 才打完整堆栈，且会把密码替换成 {@code ******}。
 */
public final class CommandSupport {

    /** 密码等敏感值在报错里的替换符。 */
    private static final String MASK = "******";

    private CommandSupport() {
    }

    /**
     * 异常 → 退出码。
     *
     * <p>参数/取值类错误归为用法错误（{@link ExitCodes#USAGE}），
     * 其余（连不上、MBean 操作失败等）归为运行时错误（{@link ExitCodes#ERROR}）。
     */
    public static int exitCodeOf(Throwable t) {
        if (t instanceof CommandLine.ParameterException || t instanceof IllegalArgumentException) {
            return ExitCodes.USAGE;
        }
        return ExitCodes.ERROR;
    }

    /**
     * 打印错误。非 verbose 模式只打一行原因 + 一行提示；verbose 模式追加完整堆栈。
     *
     * @param secret 需要脱敏的字符串（JMX 密码）；为 null 或空时不脱敏
     */
    public static void printError(Throwable t, boolean verbose, String secret) {
        System.err.println("错误: " + redact(message(t), secret));
        if (verbose) {
            System.err.print(redact(stackTrace(t), secret));
        } else {
            System.err.println("提示: 加 -v/--verbose 查看完整堆栈。");
        }
    }

    /** 取最有信息量的一条消息：异常自身的消息，没有则退回 cause，最后退回类名。 */
    public static String message(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            Throwable cause = t.getCause();
            message = cause == null ? null : cause.getMessage();
        }
        if (message == null || message.isEmpty()) {
            message = t.getClass().getName();
        }
        return message;
    }

    public static String stackTrace(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** 报错里不能带出明文密码（verbose 堆栈里可能含有凭证数组）。 */
    public static String redact(String text, String secret) {
        if (text == null || secret == null || secret.isEmpty()) {
            return text;
        }
        return text.replace(secret, MASK);
    }
}
