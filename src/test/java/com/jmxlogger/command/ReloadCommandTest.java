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
 * {@code reload} 命令测试：留空走 {@code reloadDefaultConfiguration}，
 * 带路径走 {@code reloadByFileName}，两类失败路径的退出码与文案。
 */
public class ReloadCommandTest {

    private TestJmxServer server;
    private StubLogbackConfigurator stub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        server.registerLogbackConfigurator(stub, "default");
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void reloadsDefaultConfigurationWhenNoPathGiven() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "reload");

        assertEquals("重载成功应返回 0，实际:\n" + result, ExitCodes.OK, result.exitCode);
        assertTrue("应说明走的是默认配置重载，实际:\n" + result,
                result.out.contains("已重新加载默认配置 (reloadDefaultConfiguration)"));
        assertTrue("必须真的调用 reloadDefaultConfiguration，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().contains("reloadDefaultConfiguration()"));
    }

    @Test
    public void reloadsByFileNameWhenPathGiven() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(), "reload", "/opt/app/conf/logback.xml");

        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue("应回显按文件重载，实际:\n" + result,
                result.out.contains("已按文件重新加载配置: /opt/app/conf/logback.xml"));
        assertTrue("路径应按原样下发，由目标 JVM 解析，实际下发:\n" + stub.getInvocations(),
                stub.getInvocations().contains("reloadByFileName(/opt/app/conf/logback.xml)"));
    }

    @Test
    public void failsWhenTargetHasNoConfigurator() throws Exception {
        server.unregisterAll();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "reload");

        assertEquals("目标无 JMXConfigurator 属于运行时错误，实际:\n" + result, ExitCodes.ERROR, result.exitCode);
        assertTrue("报错应指引目标侧开启 <jmxConfigurator/>，实际:\n" + result,
                result.err.contains("<jmxConfigurator/>"));
        assertTrue("失败时不该回显成功文案，实际:\n" + result,
                !result.out.contains("已重新加载默认配置"));
    }
}
