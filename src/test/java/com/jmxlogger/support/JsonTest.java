package com.jmxlogger.support;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link Json} 的转义与拼装契约：输出是给脚本消费的，转义错一个字符就是坏数据；
 * 换实现（Gson / Jackson）时这些用例必须原样通过。
 */
public class JsonTest {

    @Test
    public void escapesQuotesBackslashesAndControlChars() {
        assertEquals("\"a\\\"b\"", Json.string("a\"b"));
        assertEquals("\"a\\\\b\"", Json.string("a\\b"));
        assertEquals("\"a\\nb\"", Json.string("a\nb"));
        assertEquals("\"a\\tb\"", Json.string("a\tb"));
        assertEquals("\"\\u0001\"", Json.string("\u0001"));
        assertEquals("null", Json.string(null));
    }

    /** 中文与成对的代理项（emoji）原样输出：转义它们只会让输出难以阅读。 */
    @Test
    public void keepsReadableTextAndPairedSurrogatesAsIs() {
        assertEquals("\"中文\"", Json.string("中文"));
        assertEquals("\"\uD83D\uDE00\"", Json.string("\uD83D\uDE00"));
    }

    /** 孤立代理项表示不成合法 UTF-8，必须转义。 */
    @Test
    public void escapesLoneSurrogate() {
        assertEquals("\"\\ud800\"", Json.string("\uD800"));
        assertEquals("\"\\udc00\"", Json.string("\uDC00"));
    }

    @Test
    public void objectSkipsFieldsWhoseValueIsNull() {
        assertEquals("{\"a\":\"1\"}", Json.object("a", Json.string("1"), "b", null));
        assertEquals("{}", Json.object());
    }

    @Test
    public void objectRejectsUnpairedArguments() {
        try {
            Json.object("a");
            fail("字段名与值必须成对");
        } catch (IllegalArgumentException expected) {
            // 期望：拼错了要当场报，而不是输出坏 JSON
        }
    }

    @Test
    public void arrayOfJoinsItems() {
        assertEquals("[]", Json.arrayOf(Collections.<String>emptyList()));
        assertEquals("[\"1\",\"2\"]",
                Json.arrayOf(Arrays.asList(Json.string("1"), Json.string("2"))));
    }

    @Test
    public void numberIsNotQuoted() {
        assertEquals("15", Json.number(15));
    }
}
