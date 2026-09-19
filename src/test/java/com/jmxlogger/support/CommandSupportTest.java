package com.jmxlogger.support;

import org.junit.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link CommandSupport} 的契约测试：异常 → 退出码的映射，以及报错输出形态
 * （默认一行、{@code --verbose} 才打堆栈、密码被脱敏）。
 */
public class CommandSupportTest {

    @Test
    public void mapsUsageErrorsToExitCode2() {
        assertEquals(ExitCodes.USAGE, CommandSupport.exitCodeOf(new IllegalArgumentException("非法的日志级别")));
        assertEquals(ExitCodes.USAGE, CommandSupport.exitCodeOf(
                new CommandLine.ParameterException(new CommandLine(new Dummy()), "未知选项 --nope")));
    }

    @Test
    public void mapsRuntimeFailuresToExitCode1() {
        assertEquals(ExitCodes.ERROR, CommandSupport.exitCodeOf(new java.io.IOException("无法连接到 JMX 服务器")));
        assertEquals(ExitCodes.ERROR, CommandSupport.exitCodeOf(new javax.management.MBeanException(null)));
    }

    @Test
    public void fallsBackToCauseMessageWhenOwnMessageIsNull() {
        Throwable root = new java.io.IOException("Connection refused");
        // 自身无消息时退到 cause：JMX 的常见形态就是外层包装异常不带消息
        assertEquals("Connection refused", CommandSupport.message(new RuntimeException(null, root)));
        // 连 cause 都没有时至少给出异常类名，不能输出空行
        assertNotNull(CommandSupport.message(new IllegalStateException()));
    }

    /** picocli 要求被包装的对象带注解，这里仅用于构造 {@code ParameterException}。 */
    @picocli.CommandLine.Command(name = "dummy")
    private static class Dummy {
    }

    @Test
    public void printsSingleLineUnlessVerbose() throws Exception {
        String normal = captureError(false, null);
        assertTrue(normal.startsWith("错误: 连接失败"));
        assertTrue("非 verbose 应给出查看堆栈的提示，实际:\n" + normal,
                normal.contains("--verbose"));
        assertFalse("非 verbose 不应打印堆栈，实际:\n" + normal,
                normal.contains("at com.jmxlogger"));

        String verbose = captureError(true, null);
        assertTrue("verbose 应打印堆栈，实际:\n" + verbose,
                verbose.contains("com.jmxlogger.support.CommandSupportTest"));
    }

    @Test
    public void masksPasswordInErrorOutput() throws Exception {
        String verbose = captureError(true, "s3cret");
        assertFalse("报错里不能出现明文密码，实际:\n" + verbose, verbose.contains("s3cret"));
        assertTrue(verbose.contains("******"));
    }

    private static String captureError(boolean verbose, String password) throws UnsupportedEncodingException {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, "UTF-8"));
            // 让堆栈里带上密码，模拟 JMX 认证失败时凭证出现在异常里的情形
            RuntimeException failure = new RuntimeException("连接失败: 凭证 s3cret 被拒绝");
            failure.fillInStackTrace();
            CommandSupport.printError(failure, verbose, password);
        } finally {
            System.setErr(original);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }
}
