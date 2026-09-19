package com.jmxlogger.support;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 按目标 MBean 自报的 {@link MBeanInfo} 调用操作：<b>签名不写死</b>。
 *
 * <p>为什么需要它：Spring Boot actuator 的端点 MBean 是 {@code DynamicMBean}，
 * 操作签名随 Spring Boot 版本变化——{@code configureLogLevel} 的级别参数可能是
 * {@code java.lang.String}，也可能是 {@code org.springframework.boot.logging.LogLevel}
 * 这类本地没有的类。写死任何一种都会在另一个版本上失败，所以必须在运行时读签名、
 * 按签名构造参数：
 * <ul>
 *   <li>{@code java.lang.String}：直接传；</li>
 *   <li>其它类型：本地能构造就构造（枚举走 {@code Enum.valueOf}，其余优先 {@code (String)} 构造器、
 *       其次静态 {@code valueOf(String)}）；</li>
 *   <li>构造不出来：<b>明确报错</b>并给出替代方案，不猜、不静默降级。</li>
 * </ul>
 *
 * <p>{@code doctor} 打印的"操作签名"就是这套逻辑的输入，二者必须保持一致。
 */
public final class JmxInvocation {

    private JmxInvocation() {
    }

    /**
     * 按操作名 + 参数个数查找操作。用参数个数而不是只按名字，避免同名重载选错。
     *
     * @return 找到的操作；找不到返回 {@code null}（由调用方决定降级还是报错）
     */
    public static MBeanOperationInfo findOperation(MBeanInfo info, String operationName, int paramCount) {
        for (MBeanOperationInfo operation : info.getOperations()) {
            if (operation.getName().equals(operationName) && operation.getSignature().length == paramCount) {
                return operation;
            }
        }
        return null;
    }

    /** 目标 MBeanInfo 里声明的参数类型数组，直接用于 {@code invoke} 的 signature。 */
    public static String[] signature(MBeanOperationInfo operation) {
        MBeanParameterInfo[] params = operation.getSignature();
        String[] types = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            types[i] = params[i].getType();
        }
        return types;
    }

    /** 按签名把字符串取值逐个转成目标类型。 */
    public static Object[] buildParams(MBeanOperationInfo operation, String[] values) {
        MBeanParameterInfo[] params = operation.getSignature();
        if (values == null || values.length != params.length) {
            throw new IllegalArgumentException("操作 " + operation.getName() + " 需要 " + params.length
                    + " 个参数，实际给了 " + (values == null ? 0 : values.length) + " 个");
        }
        Object[] converted = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            converted[i] = coerce(values[i], params[i].getType(), operation.getName(), i + 1);
        }
        return converted;
    }

    /**
     * 单个取值的类型转换。{@code null} 原样穿透（"重置为继承"要靠它，
     * 与 logback 侧必须传字符串 {@code "null"} 的约定不同）。
     */
    public static Object coerce(String value, String type, String operation, int position) {
        if (value == null) {
            return null;
        }
        if (type == null || String.class.getName().equals(type)) {
            return value;
        }

        Class<?> target;
        try {
            target = Class.forName(type);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("无法把取值 \"" + value + "\" 转成目标 MBean 声明的参数类型 " + type
                    + "（操作 " + operation + " 的第 " + position + " 个参数）：本地没有这个类。\n"
                    + "这类签名（典型如 Spring Boot 的 LogLevel 枚举）无法从远程构造，请：\n"
                    + "  1) 改用 logback 通道：--target logback（目标 logback.xml 需 <jmxConfigurator/>）；\n"
                    + "  2) 或用 doctor 打印该端点的真实签名后再决定。");
        }

        Object converted = enumValue(target, value, operation, position);
        if (converted == null) {
            converted = stringConstructor(target, value);
        }
        if (converted == null) {
            converted = valueOf(target, value);
        }
        if (converted == null) {
            throw new IllegalStateException("无法把取值 \"" + value + "\" 转成目标 MBean 声明的参数类型 " + type
                    + "（操作 " + operation + " 的第 " + position + " 个参数）："
                    + "该类既不是枚举，也没有 (String) 构造器或 valueOf(String) 方法。");
        }
        return converted;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String value, String operation, int position) {
        if (!type.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class<Enum>) type, value);
        } catch (IllegalArgumentException e) {
            Object[] constants = type.getEnumConstants();
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) {
                    names.append(", ");
                }
                names.append(constants[i]);
            }
            throw new IllegalStateException("目标 MBean 的级别类型 " + type.getName() + " 不接受取值 \"" + value
                    + "\"（操作 " + operation + " 的第 " + position + " 个参数），可选: " + names);
        }
    }

    private static Object stringConstructor(Class<?> type, String value) {
        try {
            Constructor<?> constructor = type.getConstructor(String.class);
            return constructor.newInstance(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 包装类型（Integer / Boolean / …）走静态 {@code valueOf(String)}。 */
    private static Object valueOf(Class<?> type, String value) {
        try {
            Method method = type.getMethod("valueOf", String.class);
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            return method.invoke(null, value);
        } catch (Exception e) {
            return null;
        }
    }
}
