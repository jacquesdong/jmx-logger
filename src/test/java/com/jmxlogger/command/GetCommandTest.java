package com.jmxlogger.command;

import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.testing.CliRunner;
import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code get} 命令的输出契约测试：走真实 RMI + logback 语义桩，
 * 覆盖「列全部」「查单个」「-r 递归」「未命中」以及目标没配
 * {@code <jmxConfigurator/>} 时的失败路径。
 */
public class GetCommandTest {

    private TestJmxServer server;
    private StubLogbackConfigurator stub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        // 未单独配置级别：真实 logback 返回空串，命令行要按「继承」显示
        stub.put("com.example.Foo", null, "INFO");
        stub.put("com.example.Foo.bar", "DEBUG", "DEBUG");
        server.registerLogbackConfigurator(stub, "default");
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void listsAllLoggersSortedWithInheritedMarker() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get");

        assertEquals("列出全部应当成功，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertTrue("应列出全部 logger 并给出总数，实际:\n" + result, result.out.contains("共 3 个 logger"));
        // 排序是输出契约的一部分：ROOT(大写)排在 com.* 之前
        assertTrue("输出应按 logger 名排序，实际:\n" + result,
                result.out.indexOf("ROOT") < result.out.indexOf("com.example.Foo"));
        assertTrue("未配置级别的 logger 应显示为继承，实际:\n" + result, result.out.contains("(inherited)"));
        assertTrue("应显示生效级别，实际:\n" + result, result.out.contains("DEBUG"));
    }

    @Test
    public void showsConfiguredAndEffectiveLevelForSingleLogger() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo.bar");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("应只显示指定 logger，实际:\n" + result, result.out.contains("com.example.Foo.bar"));
        assertTrue("应显示 DEBUG 级别，实际:\n" + result, result.out.contains("DEBUG"));
        assertFalse("查单个 logger 时不该把 ROOT 也列出来，实际:\n" + result, result.out.contains("ROOT"));
    }

    @Test
    public void recursiveListsTheLoggerAndItsChildren() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo", "-r");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("递归结果应包含自身，实际:\n" + result, result.out.contains("com.example.Foo.bar"));
        assertTrue("递归计数应为自身+子 logger，实际:\n" + result, result.out.contains("共 2 个 logger"));
        assertFalse("递归不该把无关的 ROOT 带出来，实际:\n" + result, result.out.contains("ROOT"));
    }

    @Test
    public void recursiveReportsNoMatchInsteadOfEmptyTable() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "no.such", "-r");

        // 没命中只是「查无此 logger」，不是错误：退出码仍是 0，但要明确说出来
        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("应提示未找到匹配的 logger，实际:\n" + result,
                result.out.contains("未找到以 \"no.such\" 开头的 logger"));
    }

    @Test
    public void failsWhenTargetHasNoConfigurator() throws Exception {
        server.unregisterAll();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "get");

        assertEquals("目标无 JMXConfigurator 属于运行时错误，实际:\n" + result, ExitCodes.ERROR, result.exitCode);
        assertTrue("报错应指引目标侧开启 <jmxConfigurator/>，实际:\n" + result,
                result.err.contains("<jmxConfigurator/>"));
        assertFalse("失败时不应往 stdout 打表格，实际:\n" + result, result.out.contains("共"));
    }
}
