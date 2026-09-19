package com.jmxlogger.support;

import picocli.CommandLine;

/**
 * picocli 的版本提供者，支撑 {@code -V/--version}。
 *
 * <p>版本不再是写死在注解里的字符串，而是构建时落在 {@code git.properties} 里的
 * 版本号 + commit + 构建时间（见 {@link BuildInfo}）。
 */
public class VersionProvider implements CommandLine.IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[]{BuildInfo.load().versionLine()};
    }
}
