package com.jmxlogger.support;

/**
 * 进程退出码。一旦发布即为对外契约：脚本会据此分支，改动要同步 README。
 *
 * <p>取值沿用 picocli 的惯例（成功 0、用法错误 2），运行时错误统一为 1。
 */
public final class ExitCodes {

    /** 成功。 */
    public static final int OK = 0;

    /** 运行时错误：连不上、目标无可用通道、JMX 调用失败等。 */
    public static final int ERROR = 1;

    /** 用法错误：参数缺失、级别非法、未知选项等。 */
    public static final int USAGE = 2;

    private ExitCodes() {
    }
}
