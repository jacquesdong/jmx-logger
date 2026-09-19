package com.jmxlogger;

import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.support.PasswordResolver;
import com.jmxlogger.testing.CliRunner;
import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import org.junit.After;
import org.junit.Test;
import picocli.CommandLine;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * CLI 表层契约测试：子命令注册、默认连接参数、全局参数能被子命令解析到，
 * 以及各类失败的退出码（子命令已不再 {@code System.exit}，可以放心执行）。
 */
public class JmxLoggerCliTest {

    private TestJmxServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void registersGetSetReloadDoctorSubcommands() {
        Set<String> names = new CommandLine(new JmxLoggerCli()).getSubcommands().keySet();
        assertTrue(names.contains("get"));
        assertTrue(names.contains("set"));
        assertTrue(names.contains("reload"));
        assertTrue(names.contains("doctor"));
    }

    @Test
    public void defaultServerIsLocalhost19000() {
        CommandLine cmd = new CommandLine(new JmxLoggerCli());
        cmd.parseArgs("get");
        assertEquals("127.0.0.1:19000", ((JmxLoggerCli) cmd.getCommand()).getServer());
    }

    @Test
    public void globalOptionsAreVisibleToSubcommands() throws Exception {
        CommandLine cmd = new CommandLine(new JmxLoggerCli());
        // -p 是进程号，认证参数只有长选项
        cmd.parseArgs("-s", "10.0.0.5:19000", "--username", "admin", "--password", "secret",
                "get", "com.example.Foo");

        JmxLoggerCli cli = (JmxLoggerCli) cmd.getCommand();
        assertEquals("10.0.0.5:19000", cli.getServer());
        assertEquals("admin", cli.getUsername());
        assertEquals("secret", cli.getPassword());
        assertNull("未给 -p 时不应把进程号也解析出来", cli.getPid());

        CommandLine parsedGet = cmd.getSubcommands().get("get");
        assertNotNull(parsedGet);
        // 子命令字段是私有的，用反射读取，避免为了测试在生产代码里开后门
        assertEquals("com.example.Foo", readField(parsedGet.getCommand(), "name"));
    }

    /**
     * {@code --password} 不带取值表示"交互式读取"，用 arity=0..1 实现；
     * 必须确认它不会把后面的子命令名当成密码吞掉。
     */
    @Test
    public void passwordWithoutValueDoesNotSwallowSubcommand() {
        CommandLine cmd = new CommandLine(new JmxLoggerCli());
        cmd.parseArgs("-s", "10.0.0.5:19000", "--password", "get");

        assertEquals(PasswordResolver.INTERACTIVE, ((JmxLoggerCli) cmd.getCommand()).getPassword());
        assertNotNull(cmd.getSubcommands().get("get"));
    }

    /** 命令行不写明文时，密码从环境变量来（脚本/CI 不把密码暴露在 ps 里）。 */
    @Test
    public void resolvesPasswordFromEnvironmentWhenOptionAbsent() {
        Map<String, String> env = new HashMap<String, String>();
        env.put(PasswordResolver.ENV_PASSWORD, "fromEnv");
        JmxLoggerCli cli = new JmxLoggerCli();
        cli.setPasswordResolver(new PasswordResolver(env, new PasswordResolver.Reader() {
            @Override
            public String read(String prompt) {
                throw new AssertionError("没有 --password 时不应交互式读取");
            }
        }));

        assertEquals("fromEnv", cli.resolvePassword());
        // 脱敏用的是同一个已解析值
        assertEquals("fromEnv", cli.peekPassword());
    }

    /**
     * 退出码是脚本依赖的契约。set 的非法级别、空的 -s 都是"不该连目标就该失败"的用法错误，
     * 退出码必须是 2；这些用例不建连接，不依赖网络。
     */
    @Test
    public void usageErrorsExitWithCode2() throws Exception {
        assertEquals(ExitCodes.USAGE, run("set", "com.example", "NOPE"));
        assertEquals(ExitCodes.USAGE, run("-s", "", "get"));
        assertEquals(ExitCodes.USAGE, run("--nope", "get"));
    }

    /** 连不上目标属于运行时错误（1），而不是用法错误。用必然连不上的地址，快速失败。 */
    @Test
    public void connectionFailuresExitWithCode1() throws Exception {
        assertEquals(ExitCodes.ERROR, run("-s", "127.0.0.1:1", "--timeout", "1", "get"));
    }

    /** 成功路径必须是 0——改退出码体系最容易把成功也改成非零。 */
    @Test
    public void successfulCommandsExitWithCode0() throws Exception {
        server = TestJmxServer.start();
        StubLogbackConfigurator stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        server.registerLogbackConfigurator(stub, "default");

        assertEquals(ExitCodes.OK, run("-s", server.server(), "get"));
        assertEquals(ExitCodes.OK, run("-s", server.server(), "doctor"));
    }

    /** 执行命令时吞掉 stdout：成功路径会打印表格/报告，不该混进测试输出。 */
    private static int run(String... args) throws Exception {
        return CliRunner.run(args).exitCode;
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
