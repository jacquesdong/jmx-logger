package com.jmxlogger.e2e;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * e2e 夹具的目标应用：本身不做任何业务，存在的意义是提供一个<b>真实的</b>
 * Spring Boot + logback（外加可选的 actuator 端点）进程，让 jmx-logger 对着它跑
 * doctor / get / set / clear / reload。
 *
 * <p>默认日志级别是 INFO，脚本会把 {@code com.jmxlogger.e2e.TargetApp} 调成 DEBUG
 * 再调回来，用于验证 set / clear 真的作用到了目标 JVM 上。
 */
@SpringBootApplication
public class TargetApp implements CommandLineRunner {

    /** 脚本按类名操作这个 logger。 */
    public static final String LOGGER_NAME = "com.jmxlogger.e2e.TargetApp";

    private static final Logger log = LoggerFactory.getLogger(TargetApp.class);

    public static void main(String[] args) {
        SpringApplication.run(TargetApp.class, args);
    }

    @Override
    public void run(String... args) {
        log.info("e2e target 已就绪（logger 名 {}），可以用 jmx-logger 连上来了", LOGGER_NAME);
        log.debug("这条 DEBUG 只有在把级别改成 DEBUG 之后才会出现");
    }
}
