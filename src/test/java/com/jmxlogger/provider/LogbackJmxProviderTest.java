package com.jmxlogger.provider;

import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import com.jmxlogger.transport.RemoteJmxConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link LogbackJmxProvider} 的行为测试：走真实 RMI + 平台 MBeanServer + logback 语义桩，
 * 覆盖 ObjectName 解析、属性读取、各操作的 invoke 签名，以及「目标没配
 * {@code <jmxConfigurator/>}」时的报错文案。
 */
public class LogbackJmxProviderTest {

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

    private LogbackJmxProvider connect() throws Exception {
        return new LogbackJmxProvider(new RemoteJmxConnector(server.server(), null, null));
    }

    @Test
    public void identityAndCapabilities() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            assertEquals("logback-jmx", provider.id());
            assertTrue("logback 通道支持配置重载", provider.capabilities().isReloadSupported());
            assertEquals("default", provider.getConfiguratorName().getKeyProperty("Name"));
        } finally {
            provider.close();
        }
    }

    @Test
    public void listLoggerNamesReturnsEveryLoggerName() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            assertEquals(Arrays.asList("ROOT", "com.example.Foo", "com.example.Foo.bar"),
                    provider.listLoggerNames());
        } finally {
            provider.close();
        }
    }

    @Test
    public void getLoggerLevelReturnsConfiguredLevelOrEmptyWhenInherited() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            assertEquals("DEBUG", provider.getLoggerLevel("com.example.Foo.bar"));
            // 真实 logback 对「未配置级别」与「logger 不存在」都返回空串（JMXConfigurator.EMPTY）
            assertEquals("", provider.getLoggerLevel("com.example.Foo"));
            assertEquals("", provider.getLoggerLevel("no.such.logger"));
        } finally {
            provider.close();
        }
    }

    @Test
    public void getLoggerEffectiveLevelReturnsEffectiveLevel() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            assertEquals("INFO", provider.getLoggerEffectiveLevel("com.example.Foo"));
            assertEquals("DEBUG", provider.getLoggerEffectiveLevel("com.example.Foo.bar"));
        } finally {
            provider.close();
        }
    }

    @Test
    public void setLoggerLevelInvokesTwoStringArgumentOperation() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            provider.setLoggerLevel("com.example.Foo", "WARN");
        } finally {
            provider.close();
        }
        assertTrue(stub.getInvocations().contains("setLoggerLevel(com.example.Foo,WARN)"));
        assertEquals("WARN", stub.getLoggerLevel("com.example.Foo"));
    }

    @Test
    public void setLoggerLevelPassesJavaNullThroughAndTargetIgnoresIt() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            provider.setLoggerLevel("com.example.Foo.bar", null);
        } finally {
            provider.close();
        }
        // 原样下发、不在本地改写：目标侧 logback 首行就 return，级别不变
        assertTrue(stub.getInvocations().contains("setLoggerLevel(com.example.Foo.bar,null)"));
        assertEquals("DEBUG", stub.getLoggerLevel("com.example.Foo.bar"));
    }

    @Test
    public void setLoggerLevelWithNullStringResetsToInherited() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            provider.setLoggerLevel("com.example.Foo.bar", "null");
        } finally {
            provider.close();
        }
        assertEquals("", stub.getLoggerLevel("com.example.Foo.bar"));
    }

    @Test
    public void reloadOperationsAreInvokedOnTarget() throws Exception {
        LogbackJmxProvider provider = connect();
        try {
            provider.reloadDefaultConfiguration();
            provider.reloadByFileName("/opt/app/conf/logback.xml");
        } finally {
            provider.close();
        }
        assertTrue(stub.getInvocations().contains("reloadDefaultConfiguration()"));
        assertTrue(stub.getInvocations().contains("reloadByFileName(/opt/app/conf/logback.xml)"));
    }

    @Test
    public void failsWithActionableMessageWhenConfiguratorMissing() throws Exception {
        server.unregisterAll();
        RemoteJmxConnector connector = new RemoteJmxConnector(server.server(), null, null);
        try {
            new LogbackJmxProvider(connector);
            fail("目标 JVM 没有 JMXConfigurator 时应当报错");
        } catch (IllegalStateException e) {
            assertTrue("错误信息应指引目标侧开启 <jmxConfigurator/>，实际为: " + e.getMessage(),
                    e.getMessage().contains("<jmxConfigurator/>"));
        } finally {
            connector.close();
        }
    }
}
