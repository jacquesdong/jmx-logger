package com.jmxlogger.support;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 密码来源的解析顺序：显式明文 → 交互式 → 环境变量 → 无密码。
 * 终端读取被抽象成 {@link PasswordResolver.Reader}，这里用假的 Reader，
 * 因此测试不碰真实控制台。
 */
public class PasswordResolverTest {

    private static final Map<String, String> NO_ENV = new HashMap<String, String>();

    private static Map<String, String> env(String password) {
        Map<String, String> env = new HashMap<String, String>();
        env.put(PasswordResolver.ENV_PASSWORD, password);
        return env;
    }

    @Test
    public void usesLiteralPasswordFromCommandLine() {
        PasswordResolver resolver = new PasswordResolver(env("fromEnv"), reader("typed"));

        assertEquals("plain", resolver.resolve("plain"));
    }

    /** 脚本/CI 的主力路径：不把密码放到命令行上，也就不会出现在 ps 里。 */
    @Test
    public void fallsBackToEnvironmentVariable() {
        PasswordResolver resolver = new PasswordResolver(env("fromEnv"), reader("typed"));

        assertEquals("fromEnv", resolver.resolve(null));
    }

    @Test
    public void promptsWhenOptionHasNoValue() {
        PasswordResolver resolver = new PasswordResolver(env("fromEnv"), reader("typed"));

        assertEquals("typed", resolver.resolve(PasswordResolver.INTERACTIVE));
    }

    @Test
    public void returnsNullWhenNothingIsProvided() {
        PasswordResolver resolver = new PasswordResolver(NO_ENV, reader("typed"));

        assertNull(resolver.resolve(null));
    }

    /** 环境变量设了但为空串：等同于没设，不能拿空串去当密码。 */
    @Test
    public void treatsEmptyEnvironmentVariableAsAbsent() {
        PasswordResolver resolver = new PasswordResolver(env(""), reader("typed"));

        assertNull(resolver.resolve(null));
    }

    /** 非交互环境（stdin 为空/EOS）要给出可执行的替代方案，而不是拿空密码去连。 */
    @Test
    public void failsLoudlyWhenPromptGetsNoInput() {
        PasswordResolver resolver = new PasswordResolver(NO_ENV, reader(""));
        try {
            resolver.resolve(PasswordResolver.INTERACTIVE);
            fail("读到空输入应当报错，而不是用空密码连接");
        } catch (IllegalArgumentException e) {
            assertTrue("报错要指向环境变量方案，实际: " + e.getMessage(),
                    e.getMessage().contains(PasswordResolver.ENV_PASSWORD));
        }
    }

    @Test
    public void distinguishesLiteralFromInteractive() {
        assertTrue(PasswordResolver.isLiteral("plain"));
        assertFalse(PasswordResolver.isLiteral(PasswordResolver.INTERACTIVE));
        assertFalse(PasswordResolver.isLiteral(null));
    }

    private static PasswordResolver.Reader reader(final String value) {
        return new PasswordResolver.Reader() {
            @Override
            public String read(String prompt) {
                return value;
            }
        };
    }
}
