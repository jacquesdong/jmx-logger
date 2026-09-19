package com.jmxlogger.support;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * JMX 密码的取值来源，按"越显式越优先"解析：
 * <ol>
 *   <li>{@code --password <明文>}：命令行显式给出（兼容旧用法，但会出现在 ps 输出里）；</li>
 *   <li>{@code --password} 不带取值：交互式读取（有控制台时不回显）；</li>
 *   <li>环境变量 {@value #ENV_PASSWORD}：适合脚本/CI，不进命令行也不进 ps；</li>
 *   <li>都没有：{@code null}，即不带凭证连接。</li>
 * </ol>
 *
 * <p>读取方式被抽象成 {@link Reader}，是为了让测试不必真的去读终端：
 * 生产用 {@link #system()}（{@link System#getenv()} + {@link Console#readPassword}），
 * 测试注入假的 env 与 Reader。
 *
 * <p>红线：密码只在本类与连接器之间传递，<b>不写进任何日志或报错</b>；
 * 报错脱敏见 {@link CommandSupport#redact(String, String)}。
 */
public final class PasswordResolver {

    /** 密码的默认环境变量名。 */
    public static final String ENV_PASSWORD = "JMX_LOGGER_PASSWORD";

    /**
     * picocli 的 {@code fallbackValue}：{@code --password} 不带取值时的取值。
     * 本类把"空串"解释为"交互式读取"——命令行上无法给出比空串更弱的密码信号。
     */
    public static final String INTERACTIVE = "";

    private static final String PROMPT = "JMX 密码: ";

    /** 一行密码的读取方式，抽出来是为了测试可替换。 */
    public interface Reader {
        /** @return 读到的密码；EOF/无输入返回 null 或空串 */
        String read(String prompt) throws IOException;
    }

    private final Map<String, String> env;
    private final Reader reader;

    public PasswordResolver(Map<String, String> env, Reader reader) {
        this.env = env == null ? java.util.Collections.<String, String>emptyMap() : env;
        this.reader = reader == null ? consoleReader() : reader;
    }

    /** 生产用实例：环境变量取进程环境，读取走控制台（无控制台时退回 stdin）。 */
    public static PasswordResolver system() {
        return new PasswordResolver(System.getenv(), consoleReader());
    }

    /**
     * @param optionValue {@code --password} 的原始取值：null=未给该选项，
     *                    {@link #INTERACTIVE}=给了但不带值，其余=明文
     * @return 解析出的密码；无密码时返回 null
     */
    public String resolve(String optionValue) {
        if (optionValue != null) {
            return INTERACTIVE.equals(optionValue) ? prompt() : optionValue;
        }
        String fromEnv = env.get(ENV_PASSWORD);
        return fromEnv == null || fromEnv.isEmpty() ? null : fromEnv;
    }

    /** 命令行上直接写明文（会出现在 ps 输出里）时为 true，用于给出提示。 */
    public static boolean isLiteral(String optionValue) {
        return optionValue != null && !INTERACTIVE.equals(optionValue);
    }

    private String prompt() {
        String value;
        try {
            value = reader.read(PROMPT);
        } catch (IOException e) {
            throw new IllegalArgumentException("交互式读取密码失败: " + e.getMessage());
        }
        if (value == null || value.isEmpty()) {
            // 非交互环境（CI、stdin 被重定向且为空）最容易撞上：给出可执行的替代方案
            throw new IllegalArgumentException("交互式读取密码未获得输入；"
                    + "非交互环境请改用环境变量 " + ENV_PASSWORD + " 传密码");
        }
        return value;
    }

    /**
     * 控制台读取（不回显）；没有控制台时（stdin 被重定向、CI）退回 stdin 一行，
     * 并明确告知"输入会回显"——脚本里更该用环境变量。
     */
    static Reader consoleReader() {
        return new Reader() {
            @Override
            public String read(String prompt) throws IOException {
                Console console = System.console();
                if (console != null) {
                    char[] chars = console.readPassword("%s", prompt);
                    return chars == null ? null : new String(chars);
                }
                System.err.println("提示: 无可用控制台，密码输入会回显；脚本里建议改用环境变量 " + ENV_PASSWORD + "。");
                System.err.print(prompt);
                System.err.flush();
                return new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
            }
        };
    }
}
