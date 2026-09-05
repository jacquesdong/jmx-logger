package com.jmxlogger;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 封装到目标 JVM 的 JMX 连接以及对 Logback JMXConfigurator MBean 的操作调用。
 * 客户端无需引入 Logback 依赖，全部通过 {@link MBeanServerConnection#invoke} 反射式调用。
 */
public class JmxClient implements AutoCloseable {

    private static final String CONFIGURATOR_TYPE = "ch.qos.logback.classic.jmx.JMXConfigurator";

    private final JMXConnector connector;
    private final MBeanServerConnection mbsc;
    private final ObjectName configuratorName;

    public JmxClient(String server, String username, String password) throws IOException {
        this.connector = connect(server, username, password);
        this.mbsc = connector.getMBeanServerConnection();
        this.configuratorName = findConfiguratorObjectName();
    }

    private JMXConnector connect(String server, String username, String password) throws IOException {
        String url = "service:jmx:rmi:///jndi/rmi://" + server + "/jmxrmi";
        JMXServiceURL serviceURL = new JMXServiceURL(url);

        Map<String, Object> env = new HashMap<>();
        if (username != null) {
            String[] credentials = new String[]{username, password == null ? "" : password};
            env.put(JMXConnector.CREDENTIALS, credentials);
        }

        try {
            return JMXConnectorFactory.connect(serviceURL, env);
        } catch (IOException e) {
            throw new IOException("无法连接到 JMX 服务器: " + server + " (" + url + "): " + e.getMessage(), e);
        }
    }

    private ObjectName findConfiguratorObjectName() throws IOException {
        ObjectName pattern;
        try {
            pattern = new ObjectName("ch.qos.logback.classic:Type=" + CONFIGURATOR_TYPE + ",*");
        } catch (Exception e) {
            throw new IllegalStateException("ObjectName 模式非法", e);
        }

        Set<ObjectName> names = mbsc.queryNames(pattern, null);
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "目标 JVM 中未找到 Logback JMXConfigurator MBean。\n" +
                    "请确认目标应用的 logback.xml 中已启用 <jmxConfigurator/>，并已通过 " +
                    "-Dcom.sun.management.jmxremote.port 暴露 JMX。");
        }
        return names.iterator().next();
    }

    private Object invoke(String operation, Object[] params, String[] signature) throws Exception {
        try {
            return mbsc.invoke(configuratorName, operation, params, signature);
        } catch (InstanceNotFoundException e) {
            throw new IllegalStateException(
                    "Logback JMXConfigurator MBean 不存在，请确认目标应用已启用 <jmxConfigurator/>。", e);
        }
    }

    public String[] getLoggerList() throws Exception {
        // Logback 将 logger 列表以 Attribute "LoggerList" (java.util.List) 形式暴露，
        // 而非 Operation。
        Object result = mbsc.getAttribute(configuratorName, "LoggerList");
        if (result instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) result;
            String[] arr = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                arr[i] = item == null ? null : item.toString();
            }
            return arr;
        }
        return new String[0];
    }

    /** 返回配置的级别（可能为 null，表示继承父 logger）。 */
    public String getLoggerLevel(String loggerName) throws Exception {
        Object result = invoke("getLoggerLevel", new Object[]{loggerName},
                new String[]{String.class.getName()});
        return result == null ? null : result.toString();
    }

    /** 返回实际生效的级别。 */
    public String getLoggerEffectiveLevel(String loggerName) throws Exception {
        Object result = invoke("getLoggerEffectiveLevel", new Object[]{loggerName},
                new String[]{String.class.getName()});
        return result == null ? null : result.toString();
    }

    public void setLoggerLevel(String loggerName, String level) throws Exception {
        invoke("setLoggerLevel", new Object[]{loggerName, level},
                new String[]{String.class.getName(), String.class.getName()});
    }

    public void reloadDefault() throws Exception {
        invoke("reloadDefaultConfiguration", new Object[]{}, new String[]{});
    }

    /**
     * 按文件路径重新加载配置。
     * 注意：Logback 的 reloadByFileName 接受的是文件路径（内部会 new File(path) 校验存在性，
     * 再自行转 URL），因此这里直接传原始路径，由目标 JVM 解析读取。
     */
    public void reloadByFile(String filePath) throws Exception {
        invoke("reloadByFileName", new Object[]{filePath},
                new String[]{String.class.getName()});
    }

    @Override
    public void close() {
        if (connector != null) {
            try {
                connector.close();
            } catch (IOException ignored) {
            }
        }
    }
}
