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
 * {@code clear} 命令测试：把「恢复继承」从 {@code set} 的边界取值里独立出来后的对外契约。
 *
 * <p>CLI 侧只有 {@code clear <name>} 这一个入口；下发到 logback 目标侧的是<b>字符串
 * {@code "null"}</b>（直接下发 Java {@code null} 或空串都会被目标侧静默忽略），
 * 这条翻译必须被测试锁住，否则会退化成"命令成功但级别没变"。
 */
public class ClearCommandTest {

    private TestJmxServer server;
    private StubLogbackConfigurator stub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        stub.put("com.example.Foo", "DEBUG", "DEBUG");
        server.registerLogbackConfigurator(stub, "default");
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void clearsConfiguredLevelAndRestoresInheritance() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "clear", "com.example.Foo");

        assertEquals("清除成功应返回 0，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertTrue("应回显恢复继承，实际:\n" + result,
                result.out.contains("已清除 logger [com.example.Foo] 的级别配置，恢复继承父 logger"));
        assertTrue("logback 侧必须把空串翻译成字符串 \"null\"，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().contains("setLoggerLevel(com.example.Foo,null)"));
        // 目标侧的真值就是空串：get 里 Level 列留空
        assertEquals("", stub.getLoggerLevel("com.example.Foo"));
    }

    /** 本来就是继承状态的 logger 再 clear 一次是幂等的，不该报错。 */
    @Test
    public void clearingAnAlreadyInheritedLoggerSucceeds() throws Exception {
        stub.put("com.example.Bar", null, "INFO");

        CliRunner.Result result = CliRunner.run("-s", server.server(), "clear", "com.example.Bar");

        assertEquals("本来就继承时清除应同样成功，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertEquals("", stub.getLoggerLevel("com.example.Bar"));
    }

    @Test
    public void requiresTheLoggerName() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "clear");

        assertEquals("缺 logger 名应返回用法错误，实际:\n" + result, ExitCodes.USAGE, result.exitCode);
        assertTrue("不该为此连一次目标 JVM，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().isEmpty());
    }

    @Test
    public void failsWhenTargetHasNoConfigurator() throws Exception {
        server.unregisterAll();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "clear", "com.example.Foo");

        assertEquals("目标无 JMXConfigurator 属于运行时错误，实际:\n" + result, ExitCodes.ERROR, result.exitCode);
        assertTrue("报错应指引目标侧开启 <jmxConfigurator/>，实际:\n" + result,
                result.err.contains("<jmxConfigurator/>"));
    }
}
