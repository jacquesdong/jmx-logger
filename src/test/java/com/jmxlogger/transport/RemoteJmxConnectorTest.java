package com.jmxlogger.transport;

import com.jmxlogger.testing.TestJmxServer;
import org.junit.After;
import org.junit.Test;

import javax.management.MBeanServerConnection;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 传输层契约测试：{@link RemoteJmxConnector} 的建连、超时与失败文案。
 *
 * <p>这里锁的是"怎么连上"这部分行为；连上之后操作哪个 MBean 由
 * {@code LogbackJmxProviderTest} 覆盖。
 */
public class RemoteJmxConnectorTest {

    private TestJmxServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void connectsToRmiJmxAgentAndExposesMBeanServer() throws Exception {
        server = TestJmxServer.start();
        RemoteJmxConnector connector = new RemoteJmxConnector(server.server(), null, null);
        try {
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            assertNotNull(mbsc);
            assertEquals("目标描述就是 -s 传入的 host:port，用于错误信息",
                    server.server(), connector.describe());
            // 平台 MBeanServer 上的 RuntimeMXBean 一定存在，说明连接是真的可用
            assertNotNull(mbsc.getAttribute(new javax.management.ObjectName("java.lang:type=Runtime"),
                    "Name"));
        } finally {
            connector.close();
        }
    }

    @Test
    public void urlFollowsJmxRmiConvention() throws Exception {
        server = TestJmxServer.start();
        RemoteJmxConnector connector = new RemoteJmxConnector(server.server(), null, null);
        try {
            assertEquals("service:jmx:rmi:///jndi/rmi://" + server.server() + "/jmxrmi", connector.url());
        } finally {
            connector.close();
        }
    }

    @Test
    public void closeIsSafeToCallTwice() throws Exception {
        server = TestJmxServer.start();
        RemoteJmxConnector connector = new RemoteJmxConnector(server.server(), null, null);
        connector.close();
        connector.close();
    }

    @Test
    public void connectTimesOutWhenTargetAcceptsButNeverAnswers() throws Exception {
        TestJmxServer.BlackholeServer blackhole = TestJmxServer.startBlackhole();
        try {
            long start = System.currentTimeMillis();
            try {
                new RemoteJmxConnector(blackhole.server(), null, null, 500L);
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
    public void connectFailureIsWrappedAsIOExceptionWithUrl() throws Exception {
        int closedPort = TestJmxServer.findFreePort();
        try {
            new RemoteJmxConnector("127.0.0.1:" + closedPort, null, null);
            fail("端口不可达时应当抛出 IOException");
        } catch (IOException e) {
            assertTrue("错误信息应包含目标地址与 URL，实际为: " + e.getMessage(),
                    e.getMessage().contains("无法连接到 JMX 服务器")
                            && e.getMessage().contains("127.0.0.1:" + closedPort));
        }
    }

    @Test
    public void timeoutIsDisabledWhenNotPositive() throws Exception {
        int closedPort = TestJmxServer.findFreePort();
        try {
            new RemoteJmxConnector("127.0.0.1:" + closedPort, null, null, 0L);
            fail("端口不可达时应当抛出 IOException");
        } catch (IOException e) {
            assertTrue("不限制超时应沿用原有报错文案，实际为: " + e.getMessage(),
                    e.getMessage().contains("无法连接到 JMX 服务器")
                            && e.getMessage().contains("127.0.0.1:" + closedPort));
        }
    }
}
