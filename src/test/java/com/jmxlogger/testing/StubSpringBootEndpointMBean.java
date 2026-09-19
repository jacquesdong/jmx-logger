package com.jmxlogger.testing;

/**
 * 极简的 Spring Boot actuator 端点桩：{@code doctor} 只关心 ObjectName 与 MBeanInfo，
 * 不调用端点方法，因此这里只需保证能按标准 MBean 约定注册上去。
 *
 * <p>真实端点的操作签名留待 P3 用 {@code doctor} 实测后再落地，此处不臆造。
 */
public interface StubSpringBootEndpointMBean {

    String ping();
}
