package com.jmxlogger;

import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 针对 {@link JmxClient} 的刻画测试（characterization tests）。
 *
 * <p>这些用例锁定的是<b>当前对外的行为契约</b>：ObjectName 查询方式、属性读取、
 * 各操作的 invoke 签名、以及未找到 MBean / 连不上时的报错文案。
 * 后续把 JmxClient 拆分为 transport/ + provider/ 时，这些用例必须原样通过，
 * 以此证明重构没有改变既有行为（对应计划 P1 的验收项）。
 */
public class JmxClientTest {

    private TestJmxServer server;
    private StubLogbackConfigurator stub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        stub.put("com.example.Foo", null, "INFO");
        stub.put("com.example.Foo.bar", "DEBUG", "DEBUG");
        server.registerLogbackConfigurator(stub, "default");
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void getLoggerListReturnsEveryLoggerName() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            assertEquals(Arrays.asList("ROOT", "com.example.Foo", "com.example.Foo.bar"),
                    Arrays.asList(client.getLoggerList()));
        } finally {
            client.close();
        }
    }

    @Test
    public void getLoggerLevelReturnsConfiguredLevelOrNullWhenInherited() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            assertEquals("DEBUG", client.getLoggerLevel("com.example.Foo.bar"));
            // 未单独配置时 logback 返回 null，GetCommand 据此显示 (inherited)
            assertNull(client.getLoggerLevel("com.example.Foo"));
        } finally {
            client.close();
        }
    }

    @Test
    public void getLoggerEffectiveLevelReturnsEffectiveLevel() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            assertEquals("INFO", client.getLoggerEffectiveLevel("com.example.Foo"));
            assertEquals("DEBUG", client.getLoggerEffectiveLevel("com.example.Foo.bar"));
        } finally {
            client.close();
        }
    }

    @Test
    public void setLoggerLevelInvokesTwoStringArgumentOperation() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.setLoggerLevel("com.example.Foo", "WARN");
        } finally {
            client.close();
        }
        assertTrue(stub.getInvocations().contains("setLoggerLevel(com.example.Foo,WARN)"));
        assertEquals("WARN", stub.getLoggerLevel("com.example.Foo"));
    }

    @Test
    public void setLoggerLevelWithNullResetsToInherited() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.setLoggerLevel("com.example.Foo.bar", null);
        } finally {
            client.close();
        }
        assertNull(stub.getLoggerLevel("com.example.Foo.bar"));
    }

    @Test
    public void reloadDefaultConfigurationIsInvokedOnTarget() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.reloadDefaultConfiguration();
        } finally {
            client.close();
        }
        assertTrue(stub.getInvocations().contains("reloadDefaultConfiguration()"));
    }

    @Test
    public void reloadByFileNamePassesRawPathToTarget() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.reloadByFileName("/opt/app/conf/logback.xml");
        } finally {
            client.close();
        }
        assertTrue(stub.getInvocations().contains("reloadByFileName(/opt/app/conf/logback.xml)"));
    }

    @Test
    public void failsWithActionableMessageWhenConfiguratorMissing() throws Exception {
        server.unregisterAll();
        try {
            new JmxClient(server.server(), null, null);
            fail("目标 JVM 没有 JMXConfigurator 时应当报错");
        } catch (IllegalStateException e) {
            assertTrue("错误信息应指引目标侧开启 <jmxConfigurator/>，实际为: " + e.getMessage(),
                    e.getMessage().contains("<jmxConfigurator/>"));
        }
    }

    @Test
    public void connectFailureIsWrappedAsIOExceptionWithUrl() throws Exception {
        int closedPort = TestJmxServer.findFreePort();
        try {
            new JmxClient("127.0.0.1:" + closedPort, null, null);
            fail("端口不可达时应当抛出 IOException");
        } catch (IOException e) {
            assertTrue("错误信息应包含目标地址与 URL，实际为: " + e.getMessage(),
                    e.getMessage().contains("无法连接到 JMX 服务器")
                            && e.getMessage().contains("127.0.0.1:" + closedPort));
        }
    }
}
