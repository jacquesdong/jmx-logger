package com.jmxlogger;

import org.junit.Test;
import picocli.CommandLine;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * CLI 表层契约测试：子命令注册、默认连接参数、以及全局参数能被子命令解析到。
 * 只做解析，不执行 {@code run()}，避免触发子命令里的 {@code System.exit}。
 */
public class JmxLoggerCliTest {

    @Test
    public void registersGetSetReloadSubcommands() {
        Set<String> names = new CommandLine(new JmxLoggerCli()).getSubcommands().keySet();
        assertTrue(names.contains("get"));
        assertTrue(names.contains("set"));
        assertTrue(names.contains("reload"));
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
        cmd.parseArgs("-s", "10.0.0.5:19000", "-u", "admin", "-p", "secret", "get", "com.example.Foo");

        JmxLoggerCli cli = (JmxLoggerCli) cmd.getCommand();
        assertEquals("10.0.0.5:19000", cli.getServer());
        assertEquals("admin", cli.getUsername());
        assertEquals("secret", cli.getPassword());

        CommandLine parsedGet = cmd.getSubcommands().get("get");
        assertNotNull(parsedGet);
        // 子命令字段是私有的，用反射读取，避免为了测试在生产代码里开后门
        assertEquals("com.example.Foo", readField(parsedGet.getCommand(), "name"));
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
