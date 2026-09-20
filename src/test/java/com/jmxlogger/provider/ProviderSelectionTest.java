package com.jmxlogger.provider;

import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.testing.CliRunner;
import com.jmxlogger.testing.StubSpringBootLoggersEndpoint;
import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import com.jmxlogger.transport.RemoteJmxConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.management.ObjectName;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 通道选择（{@code --target}）的契约测试：谁优先、什么时候兜底、两条都没有时报什么。
 *
 * <p>这是 P3 的验收点——目标没有 {@code <jmxConfigurator/>} 但带 actuator 时，
 * 默认路径必须自动走 actuator，而不是直接失败。
 */
public class ProviderSelectionTest {

    private TestJmxServer server;
    private StubLogbackConfigurator logbackStub;
    private StubSpringBootLoggersEndpoint actuatorStub;

    @Before
    public void setUp() throws Exception {
        server = TestJmxServer.start();
        registerBoth();
    }

    @After
    public void tearDown() {
        server.close();
    }

    private void registerBoth() throws Exception {
        server.unregisterAll();
        logbackStub = new StubLogbackConfigurator();
        logbackStub.put("ROOT", "INFO", "INFO");
        logbackStub.put("com.example.Foo", null, "INFO");
        server.registerLogbackConfigurator(logbackStub, "default");

        actuatorStub = new StubSpringBootLoggersEndpoint();
        actuatorStub.put("ROOT", "INFO", "INFO");
        actuatorStub.put("com.example.Foo", null, "INFO");
        server.register(actuatorStub, new ObjectName("org.springframework.boot:type=Endpoint,name=Loggers"));
    }

    private void onlyActuator() throws Exception {
        server.unregisterAll();
        server.register(actuatorStub, new ObjectName("org.springframework.boot:type=Endpoint,name=Loggers"));
    }

    private void onlyLogback() throws Exception {
        server.unregisterAll();
        server.registerLogbackConfigurator(logbackStub, "default");
    }

    private LoggerProvider open(String target) throws Exception {
        return ProviderFactory.open(new RemoteJmxConnector(server.server(), null, null), target);
    }

    /** logback 能力最全（含 reload），两条通道都在时 auto 必须选它。 */
    @Test
    public void autoPrefersLogbackWhenBothChannelsExist() throws Exception {
        LoggerProvider provider = open(ProviderFactory.AUTO);
        try {
            assertEquals("logback", provider.id());
            assertTrue(provider.capabilities().isReloadSupported());
        } finally {
            provider.close();
        }
    }

    /** P3 的核心收益：目标没配 <jmxConfigurator/> 但有 actuator 时自动兜底。 */
    @Test
    public void autoFallsBackToActuatorWhenLogbackIsMissing() throws Exception {
        onlyActuator();

        LoggerProvider provider = open(ProviderFactory.AUTO);
        try {
            assertEquals("actuator", provider.id());
            assertTrue("兜底通道不支持 reload，命令层要据此给替代方案",
                    !provider.capabilities().isReloadSupported());
        } finally {
            provider.close();
        }
    }

    /** 显式指定时不自动切换：便于"两条都有但我要走某一条"。 */
    @Test
    public void explicitTargetOverridesAuto() throws Exception {
        LoggerProvider provider = open(ActuatorJmxProvider.ID);
        try {
            assertEquals("actuator", provider.id());
        } finally {
            provider.close();
        }
    }

    /** 显式指定 logback 后即使 actuator 可用也不兜底，失败原因要收敛在一条通道上。 */
    @Test
    public void explicitLogbackDoesNotFallBackToActuator() throws Exception {
        onlyActuator();
        try {
            open(LogbackJmxProvider.ID).close();
            fail("显式指定 logback 时不该兜底到 actuator");
        } catch (IllegalStateException e) {
            assertTrue("错误应指向目标侧缺 <jmxConfigurator/>，实际: " + e.getMessage(),
                    e.getMessage().contains("<jmxConfigurator/>"));
        }
    }

    /** 两条都没有：报错里要同时给出两边的缺失原因与目标侧该加的配置。 */
    @Test
    public void reportsBothChannelsWhenNeitherIsAvailable() throws Exception {
        server.unregisterAll();
        try {
            open(ProviderFactory.AUTO).close();
            fail("两条通道都没有时应当报错");
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            assertTrue("应说明 logback 通道缺失，实际: " + message, message.contains("<jmxConfigurator/>"));
            assertTrue("应说明 actuator 通道缺失，实际: " + message,
                    message.contains("spring-boot-starter-actuator"));
        }
    }

    /** 未知取值属于用法错误（退出码 2），不该去连目标 JVM。 */
    @Test
    public void unknownTargetIsAUsageError() throws Exception {
        try {
            open("nope").close();
            fail("未知 --target 应当报错");
        } catch (IllegalArgumentException e) {
            assertTrue("报错要列出当前可选值，实际: " + e.getMessage(),
                    e.getMessage().contains("auto, logback, actuator"));
        }
        assertEquals(ExitCodes.USAGE,
                CliRunner.run("-s", server.server(), "--target", "nope", "get").exitCode);
    }

    /** {@code -t} 是 {@code --target} 的短选项：端到端确实选中了通道（reload 在两条通道上结果相反）。 */
    @Test
    public void shortTargetOptionSelectsChannel() throws Exception {
        CliRunner.Result logback = CliRunner.run("-s", server.server(), "-t", "logback", "reload");
        assertEquals(logback.toString(), ExitCodes.OK, logback.exitCode);

        CliRunner.Result actuator = CliRunner.run("-s", server.server(), "-t", "actuator", "reload");
        assertEquals(actuator.toString(), ExitCodes.ERROR, actuator.exitCode);
        assertTrue("应该是 actuator 通道在拒绝 reload，实际: " + actuator.err,
                actuator.err.contains("当前通道 actuator"));
    }

    /**
     * 目标有多个 LoggerContext（多个 JMXConfigurator）时，{@code --object-name} 要点到指定的那一个：
     * 不带它只能取查询结果里的第一个，"改错了 context"是这类工具最阴的坑。
     */
    @Test
    public void objectNamePicksTheNamedConfigurator() throws Exception {
        server.unregisterAll();
        StubLogbackConfigurator first = new StubLogbackConfigurator();
        first.put("ROOT", "INFO", "INFO");
        first.put("com.example.Foo", "INFO", "INFO");
        server.registerLogbackConfigurator(first, "default");

        StubLogbackConfigurator second = new StubLogbackConfigurator();
        second.put("ROOT", "INFO", "INFO");
        second.put("com.example.Foo", "DEBUG", "DEBUG");
        server.registerLogbackConfigurator(second, "other");

        CliRunner.Result other = CliRunner.run("-s", server.server(),
                "--object-name=" + configuratorName("other"), "get", "com.example.Foo");
        assertEquals(other.toString(), ExitCodes.OK, other.exitCode);
        assertTrue("应读到 other 这个 context（DEBUG），实际:\n" + other, other.out.contains("DEBUG"));
        assertTrue("不该读到 default 的值，实际:\n" + other, !other.out.contains("INFO"));

        CliRunner.Result defaultOne = CliRunner.run("-s", server.server(),
                "--object-name=" + configuratorName("default"), "get", "com.example.Foo");
        assertTrue("点名 default 应读到 INFO，实际:\n" + defaultOne, defaultOne.out.contains("INFO"));
    }

    /** logback 配置器的完整 ObjectName：{@code Name=} 就是 LoggerContext 名。 */
    private static String configuratorName(String contextName) {
        return LogbackJmxProvider.DOMAIN + ":Name=" + contextName
                + ",Type=" + LogbackJmxProvider.CONFIGURATOR_TYPE;
    }

    /** 点名的 MBean 属于 actuator 时，不写 {@code --target} 也能按 domain 认出通道。 */
    @Test
    public void objectNameInfersActuatorChannel() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(),
                "--object-name=org.springframework.boot:type=Endpoint,name=Loggers", "get", "com.example.Foo");

        assertEquals(result.toString(), ExitCodes.OK, result.exitCode);
        assertTrue(result.out.contains("com.example.Foo"));
    }

    /** ObjectName 语法错误属于用法错误（退出码 2），且不该去连目标。 */
    @Test
    public void malformedObjectNameIsAUsageError() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(),
                "--object-name=ch.qos.logback.classic:Name", "get");

        assertEquals(result.toString(), ExitCodes.USAGE, result.exitCode);
        assertTrue("应指出完整形态，实际:\n" + result, result.err.contains("非法的 --object-name"));
    }

    /** 既不是 logback 也不是 Spring Boot 的 MBean：本工具不操作它，按用法错误报。 */
    @Test
    public void foreignDomainObjectNameIsAUsageError() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(),
                "--object-name=java.lang:type=Runtime", "get");

        assertEquals(result.toString(), ExitCodes.USAGE, result.exitCode);
        assertTrue("应说明只认两条通道的 domain，实际:\n" + result,
                result.err.contains("无法从 --object-name 的 domain"));
    }

    /** {@code --object-name} 与 {@code --target} 指向不同通道时是用法错误，不能默默按其中一个来。 */
    @Test
    public void objectNameConflictingWithTargetIsAUsageError() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(),
                "--object-name=org.springframework.boot:type=Endpoint,name=Loggers",
                "--target=logback", "get");

        assertEquals(result.toString(), ExitCodes.USAGE, result.exitCode);
        assertTrue("应指出两者不一致，实际:\n" + result, result.err.contains("不一致"));
    }

    /** 点名的 MBean 没注册：运行时错误（退出码 1），并指向 doctor。 */
    @Test
    public void missingObjectNameIsARuntimeError() throws Exception {
        CliRunner.Result result = CliRunner.run("-s", server.server(),
                "--object-name=" + configuratorName("nope"), "get");

        assertEquals(result.toString(), ExitCodes.ERROR, result.exitCode);
        assertTrue("应提示该 MBean 没有注册，实际:\n" + result, result.err.contains("没有注册"));
        assertTrue("应指向 doctor，实际:\n" + result, result.err.contains("doctor"));
    }

    /** 点名了同名但 Type 不对的 MBean：明确说 Type 要求，而不是等 invoke 报看不懂的错。 */
    @Test
    public void objectNameWithWrongTypeIsARuntimeError() throws Exception {
        String name = LogbackJmxProvider.DOMAIN + ":Name=weird,Type=java.lang.String";
        server.register(new StubLogbackConfigurator(), new ObjectName(name));

        CliRunner.Result result = CliRunner.run("-s", server.server(), "--object-name=" + name, "get");

        assertEquals(result.toString(), ExitCodes.ERROR, result.exitCode);
        assertTrue("应指出 Type 不对，实际:\n" + result,
                result.err.contains("不是 logback 的 JMXConfigurator"));
    }

    /** 端到端：没有 logback 通道时，默认路径的 get 依然能列出 logger。 */
    @Test
    public void cliGetWorksViaActuatorFallback() throws Exception {
        onlyActuator();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo");
        assertEquals(result.toString(), ExitCodes.OK, result.exitCode);
        assertTrue(result.out.contains("com.example.Foo"));
        // 目标侧的真值是空串：Level 列留空，不渲染 (inherited)；含义见 README
        assertTrue("不该再渲染 (inherited)，实际输出:\n" + result.out, !result.out.contains("(inherited)"));
        assertTrue("不该再打印空 Level 的脚注，实际输出:\n" + result.out,
                !result.out.contains("Level 为空"));
    }

    /** 端到端：actuator 通道下 set 要真正改到目标。 */
    @Test
    public void cliSetWorksViaActuatorFallback() throws Exception {
        onlyActuator();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "set", "com.example.Foo", "WARN");
        assertEquals(result.toString(), ExitCodes.OK, result.exitCode);
        assertEquals("WARN", actuatorStub.configuredLevel("com.example.Foo"));
    }

    /** 端到端：actuator 通道下 reload 要给出替代方案（退出码 1，不是崩堆栈）。 */
    @Test
    public void cliReloadOnActuatorSuggestsAlternatives() throws Exception {
        onlyActuator();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "reload");
        assertEquals(result.toString(), ExitCodes.ERROR, result.exitCode);
        assertTrue("应给出 scan=\"true\" 等替代方案，实际: " + result.err, result.err.contains("scan=\"true\""));
    }

    /** 回归：目标有 logback 时行为不变（logback 通道优先）。 */
    @Test
    public void cliGetStillUsesLogbackWhenAvailable() throws Exception {
        onlyLogback();

        CliRunner.Result result = CliRunner.run("-s", server.server(), "get", "com.example.Foo");
        assertEquals(result.toString(), ExitCodes.OK, result.exitCode);
        assertTrue("有 logback 通道时不该去碰 actuator 端点，实际调用: " + actuatorStub.getInvocations(),
                actuatorStub.getInvocations().isEmpty());
    }
}
