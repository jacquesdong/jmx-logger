package com.jmxlogger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * 重新加载 Logback 配置。
 *
 * <pre>
 * jmx-logger reload                 调用 reloadDefaultConfiguration()，恢复默认配置
 * jmx-logger reload <file-path>     调用 reloadByFileName，按目标 JVM 上的文件重新加载
 * </pre>
 */
@Command(name = "reload", description = "重新加载 Logback 配置", mixinStandardHelpOptions = true)
public class ReloadCommand implements Runnable {

    @ParentCommand
    private JmxLoggerCli parent;

    @Parameters(index = "0", arity = "0..1",
            description = "目标 JVM 上的 logback 配置文件路径（留空则恢复默认配置）")
    private String filePath;

    @Override
    public void run() {
        try (JmxClient client = parent.connect()) {
            if (filePath == null || filePath.isEmpty()) {
                client.reloadDefaultConfiguration();
                System.out.println("已重新加载默认配置 (reloadDefaultConfiguration)");
            } else {
                client.reloadByFile(filePath);
                System.out.println("已按文件重新加载配置: " + filePath);
            }
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }
}
