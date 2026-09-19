package com.jmxlogger.testing;

import com.jmxlogger.JmxLoggerCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * 在测试里执行完整 CLI（含 {@code JmxLoggerCli} 装好的统一错误处理），
 * 并分别捕获 stdout 与 stderr。
 *
 * <p>直接跑 CLI（而不是 new 一个子命令后手动塞字段）才能覆盖"全局参数 → {@code parent.connect()}
 * → Provider → 输出"这条完整链路，这正是 {@code get/set/reload} 三命令真正需要守住的行为。
 */
public final class CliRunner {

    private CliRunner() {
    }

    /** 执行 CLI，返回退出码与两侧输出；stdout 不再混进测试自身的输出里。 */
    public static Result run(String... args) throws UnsupportedEncodingException {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));
            System.setErr(new PrintStream(err, true, "UTF-8"));
            exitCode = JmxLoggerCli.commandLine().execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exitCode, text(out), text(err));
    }

    private static String text(ByteArrayOutputStream buffer) throws UnsupportedEncodingException {
        return new String(buffer.toByteArray(), "UTF-8");
    }

    public static final class Result {

        public final int exitCode;
        public final String out;
        public final String err;

        Result(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }

        /** 断言失败时把两侧输出都打出来，省得再跑一遍去看实际输出。 */
        @Override
        public String toString() {
            return "exitCode=" + exitCode + "\n--- stdout ---\n" + out + "--- stderr ---\n" + err;
        }
    }
}
