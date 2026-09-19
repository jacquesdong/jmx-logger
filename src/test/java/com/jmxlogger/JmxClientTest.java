package com.jmxlogger;

import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
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
    public void getLoggerLevelReturnsConfiguredLevelOrEmptyWhenInherited() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            assertEquals("DEBUG", client.getLoggerLevel("com.example.Foo.bar"));
            // 真实 logback 对「未配置级别」与「logger 不存在」都返回空串（JMXConfigurator.EMPTY），
            // 而不是 null —— 已在真实目标进程（logback 1.1.x/1.2.x）上核对过。
            assertEquals("", client.getLoggerLevel("com.example.Foo"));
            assertEquals("", client.getLoggerLevel("no.such.logger"));
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

    /**
     * API 层把 {@code null} 与空串都翻译成目标侧字符串 {@code "null"}：
     * 直接下发 Java null 会被 logback 静默忽略（源码首行 return），翻译后才真的重置。
     */
    @Test
    public void setLoggerLevelWithJavaNullResetsToInherited() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.setLoggerLevel("com.example.Foo.bar", null);
        } finally {
            client.close();
        }
        assertTrue(stub.getInvocations().contains("setLoggerLevel(com.example.Foo.bar,null)"));
        assertEquals("", stub.getLoggerLevel("com.example.Foo.bar"));
    }

    /** {@code clear} 命令下发的就是空串，必须与 Java null 等价。 */
    @Test
    public void setLoggerLevelWithEmptyStringResetsToInherited() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.setLoggerLevel("com.example.Foo.bar", "");
        } finally {
            client.close();
        }
        assertTrue(stub.getInvocations().contains("setLoggerLevel(com.example.Foo.bar,null)"));
        assertEquals("", stub.getLoggerLevel("com.example.Foo.bar"));
    }

    @Test
    public void setLoggerLevelWithNullStringResetsToInherited() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            // 字符串 "null" 才是 logback 认可的「恢复继承」指令
            client.setLoggerLevel("com.example.Foo.bar", "null");
        } finally {
            client.close();
        }
        assertEquals("", stub.getLoggerLevel("com.example.Foo.bar"));
    }

    @Test
    public void setLoggerLevelWithUnknownLevelIsIgnored() throws Exception {
        JmxClient client = new JmxClient(server.server(), null, null);
        try {
            client.setLoggerLevel("com.example.Foo.bar", "BOGUS");
        } finally {
            client.close();
        }
        assertEquals("DEBUG", stub.getLoggerLevel("com.example.Foo.bar"));
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
    public void connectTimesOutWhenTargetAcceptsButNeverAnswers() throws Exception {
        TestJmxServer.BlackholeServer blackhole = TestJmxServer.startBlackhole();
        try {
            long start = System.currentTimeMillis();
            try {
                new JmxClient(blackhole.server(), null, null, 500L);
                fail("对面不应答时应当在超时后放弃，而不是一直挂着");
            } catch (IOException e) {
                assertTrue("错误信息应说明超时并带上目标地址，实际为: " + e.getMessage(),
                        e.getMessage().contains("超时") && e.getMessage().contains(blackhole.server()));
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("应当在超时上限附近返回，实际耗时 " + elapsed + " ms", elapsed < 5000L);
        } finally {
            blackhole.close();
        }
    }

    @Test
    public void connectTimeoutIsDisabledWhenNotPositive() throws Exception {
        int closedPort = TestJmxServer.findFreePort();
        try {
            new JmxClient("127.0.0.1:" + closedPort, null, null, 0L);
            fail("端口不可达时应当抛出 IOException");
        } catch (IOException e) {
            assertTrue("不限制超时应沿用原有报错文案，实际为: " + e.getMessage(),
                    e.getMessage().contains("无法连接到 JMX 服务器")
                            && e.getMessage().contains("127.0.0.1:" + closedPort));
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
