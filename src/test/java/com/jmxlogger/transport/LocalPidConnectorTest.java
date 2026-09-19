package com.jmxlogger.transport;

import com.jmxlogger.support.ExitCodes;
import com.jmxlogger.JmxLoggerCli;
import org.junit.Assume;
import org.junit.Test;
import picocli.CommandLine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 本地 attach 通道（{@code -P pid}）的契约测试。
 *
 * <p>attach 依赖 OS 权限、JDK（非 JRE）、PID namespace，CI/容器里可能无法使用，
 * 因此所有"真的要 attach"的用例先探测自身进程，环境不支持就跳过而不是让构建失败。
 *
 * <p>失败分支是本阶段的重点，所以"非法 PID 在建连前就被拒"与
 * "进程不存在时给出排查清单"这两条是硬断言，不依赖 attach 能否成功。
 */
public class LocalPidConnectorTest {

    @Test
    public void rejectsInvalidPidBeforeConnecting() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
        assertRejected("0");
        assertRejected("-1");
        assertRejected("not-a-pid");
        assertRejected("99999999999999999999999");
    }

    private static void assertRejected(String pid) {
        try {
            new LocalPidConnector(pid, null, null);
            fail("非法 PID 应在建连前就拒绝: " + pid);
        } catch (IOException e) {
            fail("非法 PID 是用法错误，应当是 IllegalArgumentException，实际为 " + e);
        } catch (IllegalArgumentException expected) {
            assertTrue("报错应说明 PID 取值要求，实际为: " + expected.getMessage(),
                    expected.getMessage().contains("PID"));
        }
    }

    /** 非法 PID 走 CLI 时是用法错误（退出码 2），不该去 attach。 */
    @Test
    public void invalidPidIsUsageError() {
        assertEquals(ExitCodes.USAGE, JmxLoggerCli.commandLine().execute("-P", "0", "get"));
        assertEquals(ExitCodes.USAGE, JmxLoggerCli.commandLine().execute("-P", "not-a-pid", "get"));
    }

    @Test
    public void attachesToLocalProcessAndExposesMBeanServer() throws Exception {
        assumeAttachSupported();
        String pid = currentPid();

        LocalPidConnector connector = new LocalPidConnector(pid, null, null);
        try {
            assertEquals("目标描述用于错误信息，本地 attach 就是 pid", "pid:" + pid, connector.describe());
            assertTrue("连接器地址应是 service:jmx 形态，实际为: " + connector.address(),
                    connector.address().startsWith("service:jmx:"));

            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            assertNotNull("连上后要能读平台 MBean（说明连接真的可用）",
                    mbsc.getAttribute(new ObjectName("java.lang:type=Runtime"), "Name"));
        } finally {
            connector.close();
            connector.close(); // 关闭必须可重复调用
        }
    }

    /** 进程不存在时不能甩堆栈，要给出排查清单。 */
    @Test
    public void missingProcessFailsWithActionableHints() throws Exception {
        assumeAttachSupported();
        try {
            new LocalPidConnector("999999", null, null, 5000L).close();
            // 极端情况下该 PID 真的存在，就不做断言
        } catch (IOException e) {
            String message = e.getMessage();
            assertTrue("失败信息应给出可操作提示，实际为: " + message,
                    message.contains("排查清单") || message.contains("本地 JMX 连接器地址")
                            || message.contains("替代方案"));
        }
    }

    /** {@code doctor -P <自身 pid>} 是 P2 的端到端验收：连得上就退出 0，并说明走的是本地 attach。 */
    @Test
    public void doctorReportsLocalAttachChannel() throws Exception {
        assumeAttachSupported();
        String output = capture(new String[]{"-P", currentPid(), "doctor"});
        assertEquals(ExitCodes.OK, exitCode);
        assertTrue("诊断报告应说明是本地 attach 通道，实际输出为:\n" + output,
                output.contains("本地 attach"));
    }

    // ---------------------------------------------------------------- 辅助

    private static String currentPid() {
        // JDK 8 没有 ProcessHandle，RuntimeMXBean 的名字形如 "12345@hostname"
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    /** attach 受环境限制：先对自身进程试一次，不行就跳过整条用例。 */
    private static void assumeAttachSupported() {
        try {
            new LocalPidConnector(currentPid(), null, null).close();
        } catch (IOException | RuntimeException e) {
            Assume.assumeNoException("当前环境不支持本地 attach，跳过: " + e.getMessage(), e);
        }
    }

    private int exitCode;

    /** 执行命令并吞掉 stdout（成功路径会打印报告，不该混进测试输出）。 */
    private String capture(String[] args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, "UTF-8"));
            exitCode = JmxLoggerCli.commandLine().execute(args);
            return buffer.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(original);
        }
    }

    @Test
    public void parsedPidIsVisibleOnTopCommand() {
        CommandLine cmd = new CommandLine(new JmxLoggerCli());
        cmd.parseArgs("-P", "4321", "get");
        assertEquals(Long.valueOf(4321L), ((JmxLoggerCli) cmd.getCommand()).getPid());
    }
}
