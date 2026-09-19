package com.jmxlogger.testing;

/** {@link StubSpringBootEndpointMBean} 的实现，仅供注册用。 */
public class StubSpringBootEndpoint implements StubSpringBootEndpointMBean {

    @Override
    public String ping() {
        return "pong";
    }
}
