package com.jmxlogger.testing;

/** {@link StubBootEndpointMBean} 的实现，仅供注册用。 */
public class StubBootEndpoint implements StubBootEndpointMBean {

    @Override
    public String ping() {
        return "pong";
    }
}
