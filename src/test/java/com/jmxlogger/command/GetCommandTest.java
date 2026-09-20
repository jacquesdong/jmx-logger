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
 * 覆盖「列表只列配了级别的」「查单个（未配置也给出一行）」「-r 递归」「未命中」、
 * 默认两列与 {@code --effective} 三列的差异，以及目标没配
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

    /** 列表默认只列单独配置了级别的 logger，未配置的省略并在统计行里交代数量。 */
    @Test
    public void listsOnlyLoggersWithConfiguredLevel() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get");

        assertEquals("列出全部应当成功，实际:\n" + result, ExitCodes.OK, result.exitCode);
        // 目标上 3 个 logger，只有 ROOT 与 com.example.Foo.bar 单独配了级别
        assertTrue("统计行应只算配置了级别的，并交代省略数量，实际:\n" + result,
                result.out.contains("共 2 个 logger（仅列单独配置了级别的；另有 1 个未配置，已省略）"));
        // 排序是输出契约的一部分：ROOT(大写)排在 com.* 之前
        assertTrue("输出应按 logger 名排序，实际:\n" + result,
                result.out.indexOf("ROOT") < result.out.indexOf("com.example.Foo.bar"));
        // 未配置的 com.example.Foo 不该占一行（注意 com.example.Foo.bar 是它的前缀，要按整行判断）
        assertFalse("未配置级别的 logger 不该出现，实际:\n" + result, hasRow(result.out, "com.example.Foo"));
        // 目标侧的真值是空串（logback 的 EMPTY 常量），也不渲染 "(inherited)"
        assertFalse("不该渲染 (inherited)，实际:\n" + result, result.out.contains("(inherited)"));
        // 默认不查生效级别：省掉每个 logger 一次远程调用，表格也就只有两列
        assertFalse("默认不该有 Effective 列，实际:\n" + result, result.out.contains("Effective"));

        assertEquals("已配置的级别应显示在 Level 列，实际:\n" + result,
                "DEBUG", rowOf(result.out, "com.example.Foo.bar").substring(51, 61).trim());
    }

    /** 取表格中该 logger 所在的行：列宽固定，logger 50 + 空格 + level 10（+ 空格 + effective 10）。 */
    private static String rowOf(String out, String loggerName) {
        for (String line : out.split("\r?\n")) {
            if (line.startsWith(loggerName)) {
                return line;
            }
        }
        return "";
    }

    /**
     * 是否存在"整行就是该 logger"的行。不能用 {@code contains}：{@code com.example.Foo}
     * 是 {@code com.example.Foo.bar} 的前缀，断言会永远为真。
     */
    private static boolean hasRow(String out, String loggerName) {
        for (String line : out.split("\r?\n")) {
            int end = Math.min(50, line.length());
            if (line.substring(0, end).trim().equals(loggerName)) {
                return true;
            }
        }
        return false;
    }

    /** {@code --all} 不再省略未配置的 logger，统计行也就没有"已省略"的交代。 */
    @Test
    public void allFlagListsUnconfiguredLoggersToo() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "--all");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("应列出未配置级别的 logger，实际:\n" + result, hasRow(result.out, "com.example.Foo"));
        assertTrue("应列出全部 3 个 logger，实际:\n" + result, result.out.contains("共 3 个 logger"));
        assertFalse("没有省略就不该出现省略说明，实际:\n" + result, result.out.contains("已省略"));
    }

    @Test
    public void showsConfiguredLevelForSingleLogger() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo.bar");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("应只显示指定 logger，实际:\n" + result, result.out.contains("com.example.Foo.bar"));
        assertTrue("应显示 DEBUG 级别，实际:\n" + result, result.out.contains("DEBUG"));
        assertFalse("默认不查生效级别，不该出现 Effective 列，实际:\n" + result,
                result.out.contains("Effective"));
        assertFalse("查单个 logger 时不该把 ROOT 也列出来，实际:\n" + result, result.out.contains("ROOT"));
    }

    /** 显式点名的 logger 即使没配级别也要给出一行——空 Level 本身就是「继承」的答案。 */
    @Test
    public void singleLookupStillShowsUnconfiguredLogger() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("显式查询应给出该 logger 的行，实际:\n" + result, hasRow(result.out, "com.example.Foo"));
        assertEquals("未配置时 Level 列留空，实际:\n" + result,
                "", rowOf(result.out, "com.example.Foo").substring(51, 61).trim());
    }

    /** {@code --effective} 多出来的那一列，取值由目标侧计算（可能继承自父 logger）。 */
    @Test
    public void effectiveFlagAddsEffectiveColumn() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo", "--effective");

        assertEquals("加 --effective 后应当依然成功，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertTrue("应显示 Effective 列名，实际:\n" + result, result.out.contains("Effective"));

        String row = rowOf(result.out, "com.example.Foo");
        assertEquals("自身未配级别，Level 列应留空，实际:\n" + result, "", row.substring(51, 61).trim());
        // 生效级别来自父 logger ROOT=INFO：这一列正是要目标侧多算一次才有
        assertEquals("生效级别列应显示继承来的 INFO，实际:\n" + result, "INFO", row.substring(62, 72).trim());
    }

    @Test
    public void recursiveListsTheLoggerAndItsChildren() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo", "-r");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("递归结果应包含配了级别的子 logger，实际:\n" + result,
                result.out.contains("com.example.Foo.bar"));
        // 自身 com.example.Foo 没配级别：不占一行，但匹配到的总数仍要交代
        assertTrue("应统计并交代省略的未配置 logger，实际:\n" + result,
                result.out.contains("共 1 个 logger（仅列单独配置了级别的；另有 1 个未配置，已省略）"));
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
