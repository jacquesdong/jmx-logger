package com.jmxlogger.command;

import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.testing.CliRunner;
import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code set} 命令测试：级别下发到目标 JVM、大小写归一化，
 * 以及「非法级别不该连目标就该失败」这条最容易回归的约束。
 */
public class SetCommandTest {

    private TestJmxServer server;
    private StubLogbackConfigurator stub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        stub.put("com.example.Foo", null, "INFO");
        server.registerLogbackConfigurator(stub, "default");
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void setsLevelOnTargetAndReportsIt() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "WARN");

        assertEquals("设置成功应返回 0，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertTrue("应回显设置结果，实际:\n" + result,
                result.out.contains("已将 logger [com.example.Foo] 的级别设置为 WARN"));
        assertTrue("级别必须真的下发到目标 JVM，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().contains("setLoggerLevel(com.example.Foo,WARN)"));
        assertEquals("WARN", stub.getLoggerLevel("com.example.Foo"));
    }

    @Test
    public void normalizesLowerCaseLevel() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "debug");

        assertEquals(ExitCodes.OK, result.exitCode);
        // logback 侧只认大写级别，小写输入必须在本地归一化后再下发
        assertTrue("小写级别应归一化为大写再下发，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().contains("setLoggerLevel(com.example.Foo,DEBUG)"));
        assertEquals("DEBUG", stub.getLoggerLevel("com.example.Foo"));
    }

    @Test
    public void rejectsUnknownLevelWithoutTouchingTarget() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "NOPE");

        assertEquals("非法级别是用法错误（2），不是运行时错误，实际:\n" + result,
                ExitCodes.USAGE, result.exitCode);
        assertTrue("报错应列出合法级别，实际:\n" + result, result.err.contains("非法的日志级别"));
        assertTrue("非法级别不该为此连一次目标 JVM，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().isEmpty());
    }

    /**
     * 空串（以及 {@code "null"}）都不是合法级别：恢复继承是 {@code clear} 命令的事，
     * {@code set} 不接受这类边界取值——同一个操作有两个入口，迟早会让人分不清哪个才推荐。
     */
    @Test
    public void rejectsEmptyLevelAndPointsAtClearCommand() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "");

        assertEquals("空串是用法错误（2），不是运行时错误，实际:\n" + result, ExitCodes.USAGE, result.exitCode);
        assertTrue("报错应指引改用 clear 命令，实际:\n" + result, result.err.contains("clear"));
        assertTrue("非法级别不该为此连一次目标 JVM，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().isEmpty());
    }

    @Test
    public void rejectsNullStringLevel() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "null");

        assertEquals("\"null\" 是 logback 目标侧的指令，不是用户输入，实际:\n" + result,
                ExitCodes.USAGE, result.exitCode);
        assertTrue("报错应指引改用 clear 命令，实际:\n" + result, result.err.contains("clear"));
        assertTrue("不该为此连一次目标 JVM，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().isEmpty());
    }

    @Test
    public void requiresTheLevelParameter() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo");

        assertEquals("缺参数应返回用法错误，实际:\n" + result, ExitCodes.USAGE, result.exitCode);
        assertTrue("同样不该连目标 JVM，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().isEmpty());
    }

    @Test
    public void failsWhenTargetHasNoConfigurator() throws Exception {
        server.unregisterAll();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "DEBUG");

        assertEquals("目标无 JMXConfigurator 属于运行时错误，实际:\n" + result, ExitCodes.ERROR, result.exitCode);
        assertTrue("报错应指引目标侧开启 <jmxConfigurator/>，实际:\n" + result,
                result.err.contains("<jmxConfigurator/>"));
    }
}
