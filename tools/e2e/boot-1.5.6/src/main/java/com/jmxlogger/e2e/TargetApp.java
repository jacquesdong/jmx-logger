package com.jmxlogger.e2e;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * e2e 夹具的目标应用（Spring Boot 1.5.6 + logback 1.1.11）。
 *
 * <p>与 2.7.18 的夹具刻意保持一致：同一个类名、同一个 logger 名，
 * 这样 tools/e2e/verify.sh 可以用同一套断言跑两个版本。
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
