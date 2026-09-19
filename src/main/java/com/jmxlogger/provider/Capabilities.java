package com.jmxlogger.provider;

/**
 * Provider 的能力声明，用于能力协商：上层命令据此决定"做不到时该给什么建议"，
 * 而不是含糊地报一个错。
 *
 * <p>目前只有 {@code reload} 一项存在差异——{@code logback} 通道支持，
 * {@code actuator} 兜底通道不支持。
 */
public final class Capabilities {

    private static final Capabilities FULL = new Capabilities(true);
    private static final Capabilities WITHOUT_RELOAD = new Capabilities(false);

    private final boolean reloadSupported;

    private Capabilities(boolean reloadSupported) {
        this.reloadSupported = reloadSupported;
    }

    /** 完整能力（含配置重载），即 logback JMXConfigurator 通道。 */
    public static Capabilities full() {
        return FULL;
    }

    /** 只能读写级别、不能重载配置。 */
    public static Capabilities withoutReload() {
        return WITHOUT_RELOAD;
    }

    public boolean isReloadSupported() {
        return reloadSupported;
    }

    @Override
    public String toString() {
        return "Capabilities{reload=" + reloadSupported + "}";
    }
}
