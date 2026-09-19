package com.jmxlogger.provider;

import com.jmxlogger.testing.StubSpringBoot15LoggersEndpoint;
import com.jmxlogger.testing.StubSpringBootLoggersEndpoint;
import com.jmxlogger.testing.StubLogLevel;
import com.jmxlogger.testing.TestJmxServer;
import com.jmxlogger.transport.RemoteJmxConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.management.ObjectName;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ActuatorJmxProvider} 的行为测试：走真实 RMI + 平台 MBeanServer +
 * 自建的 actuator loggers 端点桩（{@code DynamicMBean}，形态对齐 Spring Boot 2.x）。
 *
 * <p>重点覆盖"签名不写死"这条设计：同一个 Provider 既要能吃下级别参数是
 * {@code String} 的端点，也要能吃下参数是枚举的端点。
 */
public class ActuatorJmxProviderTest {

    private TestJmxServer server;
    private StubSpringBootLoggersEndpoint stub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        stub = defaultStub();
        server.register(stub, endpointName());
    }

    @After
    public void tearDown() {
        server.close();
    }

    private static ObjectName endpointName() throws Exception {
        return new ObjectName("org.springframework.boot:type=Endpoint,name=Loggers");
    }

    private static StubSpringBootLoggersEndpoint defaultStub() throws Exception {
        StubSpringBootLoggersEndpoint stub = new StubSpringBootLoggersEndpoint();
        stub.put("ROOT", "INFO", "INFO");
        stub.put("com.example.Foo", null, "INFO");
        stub.put("com.example.Foo.bar", "DEBUG", "DEBUG");
        return stub;
    }

    /** 换一个形态的桩（签名/暴露方式不同）重新注册，验证 Provider 不依赖写死的形态。 */
    private StubSpringBootLoggersEndpoint replaceWith(StubSpringBootLoggersEndpoint newStub) throws Exception {
        server.unregisterAll();
        server.register(newStub, endpointName());
        return newStub;
    }

    private ActuatorJmxProvider connect() throws Exception {
        return new ActuatorJmxProvider(new RemoteJmxConnector(server.server(), null, null));
    }

    @Test
    public void identityAndCapabilities() throws Exception {
        ActuatorJmxProvider provider = connect();
        try {
            assertEquals("actuator", provider.id());
            assertFalse("actuator 通道不支持配置重载", provider.capabilities().isReloadSupported());
            assertEquals("Loggers", provider.getEndpointName().getKeyProperty("name"));
        } finally {
            provider.close();
        }
    }

    @Test
    public void listLoggerNamesReturnsEveryLogger() throws Exception {
        ActuatorJmxProvider provider = connect();
        try {
            assertEquals(Arrays.asList("ROOT", "com.example.Foo", "com.example.Foo.bar"),
                    provider.listLoggerNames());
        } finally {
            provider.close();
        }
    }

    @Test
    public void getLoggerLevelReturnsEmptyForInheritedOrUnknownLogger() throws Exception {
        ActuatorJmxProvider provider = connect();
        try {
            assertEquals("DEBUG", provider.getLoggerLevel("com.example.Foo.bar"));
            // 与 logback 侧约定一致：未配置 / 不存在都返回空串
            assertEquals("", provider.getLoggerLevel("com.example.Foo"));
            assertEquals("", provider.getLoggerLevel("no.such.logger"));
        } finally {
            provider.close();
        }
    }

    @Test
    public void getLoggerEffectiveLevelResolvesFromSnapshot() throws Exception {
        ActuatorJmxProvider provider = connect();
        try {
            assertEquals("INFO", provider.getLoggerEffectiveLevel("com.example.Foo"));
            assertEquals("DEBUG", provider.getLoggerEffectiveLevel("com.example.Foo.bar"));
        } finally {
            provider.close();
        }
    }

    /** 级别参数是 {@code String} 的端点（最常见）。 */
    @Test
    public void setLoggerLevelInvokesConfigureLogLevelWithStringParameter() throws Exception {
        ActuatorJmxProvider provider = connect();
        try {
            provider.setLoggerLevel("com.example.Foo", "WARN");
        } finally {
            provider.close();
        }
        assertTrue(stub.getInvocations().contains("configureLogLevel(com.example.Foo,WARN)"));
        assertEquals("WARN", stub.configuredLevel("com.example.Foo"));
    }

    /**
     * 级别参数是枚举的端点：客户端本地没有 {@code LogLevel} 时必然失败，
     * 所以这里用同 classpath 的 {@link StubLogLevel} 证明"按签名构造枚举参数"这条路径是通的。
     */
    @Test
    public void setLoggerLevelWorksWhenParameterIsAnEnum() throws Exception {
        StubSpringBootLoggersEndpoint enumStub = replaceWith(new StubSpringBootLoggersEndpoint(StubLogLevel.class, true));
        enumStub.put("com.example.Foo", null, "INFO");

        ActuatorJmxProvider provider = connect();
        try {
            provider.setLoggerLevel("com.example.Foo", "WARN");
        } finally {
            provider.close();
        }
        assertEquals("WARN", enumStub.configuredLevel("com.example.Foo"));
    }

    /**
     * 与 logback 侧的"字符串 null 才表示重置"不同：actuator 的
     * {@code configureLogLevel(name, null)} 传 Java null 就是恢复继承。
     */
    @Test
    public void setLoggerLevelWithJavaNullResetsToInherited() throws Exception {
        StubSpringBootLoggersEndpoint mutable = defaultStub();
        replaceWith(mutable);

        ActuatorJmxProvider provider = connect();
        try {
            provider.setLoggerLevel("com.example.Foo.bar", null);
            assertEquals("", provider.getLoggerLevel("com.example.Foo.bar"));
        } finally {
            provider.close();
        }
        assertTrue(mutable.getInvocations().contains("configureLogLevel(com.example.Foo.bar,null)"));
    }

    /** 全量列表被暴露成 {@code loggers()} 操作而不是 {@code Loggers} 属性时也要能读。 */
    @Test
    public void readsLoggersFromOperationWhenAttributeIsAbsent() throws Exception {
        StubSpringBootLoggersEndpoint operationOnly = new StubSpringBootLoggersEndpoint(String.class, false);
        operationOnly.put("ROOT", "INFO", "INFO");
        operationOnly.put("com.example.Foo", "WARN", "WARN");
        replaceWith(operationOnly);

        ActuatorJmxProvider provider = connect();
        try {
            assertEquals(Arrays.asList("ROOT", "com.example.Foo"), provider.listLoggerNames());
            assertEquals("WARN", provider.getLoggerLevel("com.example.Foo"));
        } finally {
            provider.close();
        }
    }

    /** 不支持的操作要给可执行替代方案，而不是含糊失败。 */
    @Test
    public void reloadIsUnsupportedAndSuggestsAlternatives() throws Exception {
        ActuatorJmxProvider provider = connect();
        try {
            provider.reloadDefaultConfiguration();
            fail("actuator 通道不支持重载，应当报错");
        } catch (IllegalStateException e) {
            assertTrue("应给出 scan=\"true\" 等替代方案，实际: " + e.getMessage(),
                    e.getMessage().contains("scan=\"true\""));
        } finally {
            provider.close();
        }
    }

    /**
     * Spring Boot 1.5 的 {@code loggersEndpoint}（真实目标实测形态：{@code LinkedHashMap} 外壳 +
     * {@code getLoggers()}/{@code getLogger}/{@code setLogLevel}）——现网唯一可行的兜底通道，
     * 必须覆盖。
     */
    @Test
    public void readsSpringBoot15EndpointWithMapPayload() throws Exception {
        StubSpringBoot15LoggersEndpoint springBoot15 = new StubSpringBoot15LoggersEndpoint();
        springBoot15.put("ROOT", "WARN", "WARN");
        springBoot15.put("com.example.Foo", null, "WARN");
        springBoot15.put("com.example.Foo.bar", "DEBUG", "DEBUG");
        server.unregisterAll();
        server.register(springBoot15, new ObjectName("org.springframework.boot:type=Endpoint,name=loggersEndpoint"));

        ActuatorJmxProvider provider = connect();
        try {
            assertEquals(Arrays.asList("ROOT", "com.example.Foo", "com.example.Foo.bar"),
                    provider.listLoggerNames());
            assertEquals("WARN", provider.getLoggerLevel("ROOT"));
            assertEquals("", provider.getLoggerLevel("com.example.Foo"));
            assertEquals("WARN", provider.getLoggerEffectiveLevel("com.example.Foo"));
            // 不存在的 logger：全量列表里没有就是没有（单查在真实端点上会返回带 effectiveLevel 的 Map）
            assertEquals("", provider.getLoggerLevel("no.such.logger"));
            assertEquals("", provider.getLoggerEffectiveLevel("no.such.logger"));

            provider.setLoggerLevel("com.example.Foo", "WARN");
        } finally {
            provider.close();
        }
        assertTrue(springBoot15.getInvocations().contains("setLogLevel(com.example.Foo,WARN)"));
        assertEquals("WARN", springBoot15.configuredLevel("com.example.Foo"));
    }

    @Test
    public void failsWithActionableMessageWhenEndpointMissing() throws Exception {
        server.unregisterAll();
        RemoteJmxConnector connector = new RemoteJmxConnector(server.server(), null, null);
        try {
            new ActuatorJmxProvider(connector);
            fail("目标没有 actuator loggers 端点时应当报错");
        } catch (IllegalStateException e) {
            assertTrue("错误信息应指引引入 actuator，实际为: " + e.getMessage(),
                    e.getMessage().contains("spring-boot-starter-actuator"));
        } finally {
            connector.close();
        }
    }
}
