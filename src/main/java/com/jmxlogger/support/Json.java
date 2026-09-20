package com.jmxlogger.support;

import java.util.List;

/**
 * 极简 JSON 拼装：只做本工具需要的那点事（转义、对象、数组），不引第三方依赖
 * ——pom 里唯一运行时依赖是 picocli，这条底线不为一个输出格式破例。
 *
 * <p>输出是<b>紧凑单行</b>：脚本管道友好，人要看展开自己接 {@code | jq .}。
 *
 * <p>约定：{@link #object(String...)} 里值片段为 {@code null} 的字段<b>整个不出现</b>
 * （不是输出 {@code null}）——用来表达"这一项本次没有查询/没有意义"；
 * 而"值就是 null"请传 {@link #string(String)} 的结果（它返回四个字母的 {@code null}）。
 *
 * <p><b>换库指引</b>（这个类是防腐层，第三方库只准出现在实现内部）：
 * <ol>
 *   <li>公开方法只用 JDK 类型，禁止出现 {@code JsonElement} / {@code JsonNode} 之类的库类型；</li>
 *   <li>换实现时选"值树"API（Gson 的 {@code JsonObject}、Jackson 的 {@code ObjectNode}），
 *       不要选流式 writer——后者会改变方法形态，迫使调用方改写；</li>
 *   <li>"值片段为 null 的字段不出现"这条约定必须在实现里复刻：Gson 的 {@code JsonNull}
 *       与 Jackson 的 {@code ObjectNode.put(k, null)} 行为都与之不同；</li>
 *   <li>改动后先跑 {@code JsonTest}：它锁的是转义与输出形态，全绿即行为等价。</li>
 * </ol>
 */
public final class Json {

    private Json() {
    }

    /** JSON 字符串字面量；{@code null} 返回裸 {@code null}（不带引号）。 */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                // 成对的代理项 = 一个完整的非 BMP 字符（如 emoji）：原样输出，不拆成两个转义
                out.append(c).append(value.charAt(i + 1));
                i++;
                continue;
            }
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    // 控制字符是 JSON 规范要求；孤立代理项不转义会写出非法 UTF-8
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        // 其余非 ASCII 原样输出：输出流本身是 UTF-8，转义只会降低可读性
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    /**
     * 组装对象，参数按"字段名, 值片段, 字段名, 值片段…"成对给出；
     * 值片段为 {@code null} 的字段会被跳过（见类注释）。
     */
    public static String object(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("字段名与值必须成对出现");
        }
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            String value = namesAndValues[i + 1];
            if (value == null) {
                continue;
            }
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(string(namesAndValues[i])).append(':').append(value);
        }
        return out.append('}').toString();
    }

    /** 数组；元素必须是已拼好的 JSON 片段。 */
    public static String arrayOf(List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    /** 数字：省得调用方用 {@code String.valueOf} 手工保证"不加引号"。 */
    public static String number(long value) {
        return String.valueOf(value);
    }
}
