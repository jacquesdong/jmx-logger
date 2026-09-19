package com.jmxlogger.support;

import com.jmxlogger.testing.StubLogLevel;
import org.junit.Test;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link JmxInvocation} 的契约测试：签名读取与参数构造。
 *
 * <p>这是 actuator 兜底通道"不写死签名"的保障——参数是 {@code String} 直通、
 * 是枚举按 {@code valueOf} 构造、本地没有该类时报错而不是猜。
 */
public class JmxInvocationTest {

    private static MBeanOperationInfo operation(String... paramTypes) {
        MBeanParameterInfo[] params = new MBeanParameterInfo[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            params[i] = new MBeanParameterInfo("arg" + i, paramTypes[i], "arg");
        }
        return new MBeanOperationInfo("configureLogLevel", "设置级别", params, "void",
                MBeanOperationInfo.ACTION);
    }

    @Test
    public void findsOperationByNameAndParameterCount() {
        MBeanInfo info = new MBeanInfo("java.lang.Object", "test", null, null,
                new MBeanOperationInfo[]{
                        operation(String.class.getName()),
                        operation(String.class.getName(), String.class.getName())}, null);

        assertEquals(2, JmxInvocation.findOperation(info, "configureLogLevel", 2).getSignature().length);
        assertEquals(1, JmxInvocation.findOperation(info, "configureLogLevel", 1).getSignature().length);
        assertNull("找不到时应返回 null 由调用方处理",
                JmxInvocation.findOperation(info, "configureLogLevel", 3));
    }

    @Test
    public void passesStringParametersThrough() {
        Object[] params = JmxInvocation.buildParams(
                operation(String.class.getName(), String.class.getName()),
                new String[]{"com.example.Foo", "WARN"});

        assertEquals(2, params.length);
        assertEquals("com.example.Foo", params[0]);
        assertEquals("WARN", params[1]);
        assertEquals(String.class.getName(),
                JmxInvocation.signature(operation(String.class.getName()))[0]);
    }

    /** Spring Boot 若把级别暴露成 LogLevel 枚举，客户端本地有该类时按 valueOf 构造。 */
    @Test
    public void buildsEnumParametersByName() {
        Object[] params = JmxInvocation.buildParams(
                operation(String.class.getName(), StubLogLevel.class.getName()),
                new String[]{"com.example.Foo", "WARN"});

        assertSame(StubLogLevel.WARN, params[1]);
    }

    /** null 要原样穿透：actuator 侧传 Java null 就是"恢复继承"。 */
    @Test
    public void passesNullThrough() {
        Object[] params = JmxInvocation.buildParams(
                operation(String.class.getName(), String.class.getName()),
                new String[]{"com.example.Foo", null});

        assertNull(params[1]);
    }

    /** 本地没有目标类型时给出可操作的报错，而不是猜一个类型去调。 */
    @Test
    public void failsWithActionableMessageWhenParameterTypeIsMissing() {
        try {
            JmxInvocation.buildParams(
                    operation(String.class.getName(), "org.springframework.boot.logging.LogLevel"),
                    new String[]{"com.example.Foo", "WARN"});
            fail("本地没有该类时应当报错");
        } catch (IllegalStateException e) {
            assertTrue("应提示改用 logback 通道，实际: " + e.getMessage(),
                    e.getMessage().contains("--target logback"));
        }
    }

    /** 枚举里没有这个取值时要说清楚，避免变成一个看不懂的反射异常。 */
    @Test
    public void failsWhenValueIsNotAValidEnumConstant() {
        try {
            JmxInvocation.buildParams(
                    operation(String.class.getName(), StubLogLevel.class.getName()),
                    new String[]{"com.example.Foo", "NOPE"});
            fail("枚举不接受该取值时应当报错");
        } catch (IllegalStateException e) {
            assertTrue("应列出可选取值，实际: " + e.getMessage(), e.getMessage().contains("WARN"));
        }
    }
}
