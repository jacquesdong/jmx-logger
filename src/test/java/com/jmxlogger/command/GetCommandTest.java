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
    public void listsAllLoggersSortedWithBlankLevelForInherited() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get");

        assertEquals("列出全部应当成功，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertTrue("应列出全部 logger 并给出总数，实际:\n" + result, result.out.contains("共 3 个 logger"));
        // 排序是输出契约的一部分：ROOT(大写)排在 com.* 之前
        assertTrue("输出应按 logger 名排序，实际:\n" + result,
                result.out.indexOf("ROOT") < result.out.indexOf("com.example.Foo"));
        // 目标侧的真值是空串（logback 的 EMPTY 常量），输出就该是空：
        // 不渲染 "(inherited)" 这类目标侧并不存在的取值
        assertTrue("不该再渲染 (inherited)，实际:\n" + result, !result.out.contains("(inherited)"));
        // 脚注已按需求去掉：空 Level 的含义记在 README，命令行输出保持紧凑
        assertTrue("不该再打印空 Level 的脚注，实际:\n" + result,
                !result.out.contains("Level 为空"));

        String row = rowOf(result.out, "com.example.Foo");
        assertTrue("应列出 com.example.Foo，实际:\n" + result, row.length() > 61);
        assertEquals("未配置级别时 Level 列应留空，实际:\n" + result, "", row.substring(51, 61).trim());
        assertTrue("应显示生效级别，实际:\n" + result, result.out.contains("DEBUG"));
    }

    /** 取表格中该 logger 所在的行：列宽固定，logger 50 + 空格 + level 10 + 空格 + effective 10。 */
    private static String rowOf(String out, String loggerName) {
        for (String line : out.split("\r?\n")) {
            if (line.startsWith(loggerName)) {
                return line;
            }
        }
        return "";
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
