package com.jmxlogger.testing;

/**
 * 占位用的级别枚举：模拟 Spring Boot 把 {@code configureLogLevel} 的级别参数暴露成
 * {@code org.springframework.boot.logging.LogLevel} 枚举的情形（客户端本地没有该类）。
 */
public enum StubLogLevel {
    OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE
}
