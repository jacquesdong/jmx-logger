package com.jmxlogger.command;

import com.jmxlogger.testing.StubBootEndpoint;
import com.jmxlogger.testing.StubLogbackConfigurator;
import com.jmxlogger.testing.TestJmxServer;
import com.jmxlogger.transport.RemoteJmxConnector;
import org.junit.After;
import org.junit.Test;

import javax.management.ObjectName;

import static org.junit.Assert.assertTrue;

/**
 * {@link DoctorCommand} 的诊断报告测试：走真实 RMI + 平台 MBeanServer，
 * 分别覆盖「logback 通道可用」「目标没配 {@code <jmxConfigurator/>}」
 * 「只有 actuator 端点」三种情形。
 */
public class DoctorCommandTest {

    private TestJmxServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private String diagnose() throws Exception {
        RemoteJmxConnector connector = new RemoteJmxConnector(server.server(), null, null);
        try {
            return new DoctorCommand().diagnose(connector);
        } finally {
            connector.close();
        }
    }

    @Test
    public void reportsLogbackChannelAndItsOperationSignatures() throws Exception {
        server = TestJmxServer.start();
        StubLogbackConfigurator stub = new StubLogbackConfigurator();
        stub.put("ROOT", "INFO", "INFO");
        server.registerLogbackConfigurator(stub, "default");

        String report = diagnose();
        assertTrue("应打印解析到的 ObjectName，实际:\n" + report,
                report.contains("ch.qos.logback.classic:Name=default,Type="));
        // 操作签名必须带完整类型名——P3 要靠它区分 String 与 LogLevel 枚举
        assertTrue("应打印 getLoggerLevel 的签名，实际:\n" + report,
                report.contains("getLoggerLevel(java.lang.String) -> java.lang.String"));
        assertTrue("应打印 setLoggerLevel 的双 String 签名，实际:\n" + report,
                report.contains("setLoggerLevel(java.lang.String, java.lang.String) -> void"));
        assertTrue("应打印 LoggerList 属性，实际:\n" + report, report.contains("LoggerList"));
        assertTrue("应判定 logback 通道可用，实际:\n" + report,
                report.contains("[可用] logback JMXConfigurator 存在"));
    }

    @Test
    public void explainsHowToFixWhenConfiguratorMissing() throws Exception {
        server = TestJmxServer.start();

        String report = diagnose();
        assertTrue("应说明缺失原因，实际:\n" + report,
                report.contains("[缺失] 未找到 logback JMXConfigurator"));
        assertTrue("应指引目标侧开启 <jmxConfigurator/>，实际:\n" + report,
                report.contains("<jmxConfigurator/>"));
        assertTrue("应给出 JMX 端口启动参数，实际:\n" + report,
                report.contains("-Dcom.sun.management.jmxremote.port"));
    }

    @Test
    public void detectsBootActuatorLoggerEndpointAsFallback() throws Exception {
        server = TestJmxServer.start();
        server.register(new StubBootEndpoint(),
                new ObjectName("org.springframework.boot:type=Endpoint,name=Loggers"));

        String report = diagnose();
        assertTrue("应列出 Boot loggers 端点，实际:\n" + report,
                report.contains("org.springframework.boot:type=Endpoint,name=Loggers"));
        assertTrue("应判定 actuator 可作为兜底通道，实际:\n" + report,
                report.contains("[可用] Spring Boot Actuator 的 loggers 端点存在"));
        assertTrue("logback 通道缺失时仍应给出配置指引，实际:\n" + report,
                report.contains("目标侧二选一即可"));
    }

    @Test
    public void reportsConnectionTarget() throws Exception {
        server = TestJmxServer.start();
        String report = diagnose();
        assertTrue("报告应带上目标地址与 URL，实际:\n" + report,
                report.contains(server.server())
                        && report.contains("service:jmx:rmi:///jndi/rmi://" + server.server() + "/jmxrmi"));
    }
}
