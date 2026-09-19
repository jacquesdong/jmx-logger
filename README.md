# jmx-logger

通过 JMX 远程查看和修改 Logback 的 Logger 级别、并重载日志配置的命令行工具。

工具独立于目标应用：客户端**不需要**引入 Logback 或 Spring Boot 依赖，全部调用通过
`MBeanServerConnection#invoke` 反射完成，只要目标 JVM 暴露了 JMX 即可使用。

- 运行时依赖：仅 [picocli](https://picocli.info/)（打包进 fat jar）
- 构建要求：JDK 8+（编译目标固定为 Java 8）
- 目标应用：任何带 Logback（1.1.x / 1.2.x）的 JVM，面向 Spring Boot 1.5.6 与 2.7.18

---

## 快速开始

```bash
# 打成 fat jar：target/jmx-logger.jar（产物名不含版本号）
./mvnw clean package          # Windows 用 mvnw.cmd

# 运行
java -jar target/jmx-logger.jar -s 10.0.0.5:19000 get

# 改代码时免打包直接跑（走 exec-maven-plugin，首次使用需联网拉插件）
./mvnw compile exec:java -Dexec.mainClass=com.jmxlogger.JmxLoggerCli \
    -Dexec.args="-s 10.0.0.5:19000 get"
```

fat jar 内已包含 picocli，拷到任意有 JRE/JDK 的机器上 `java -jar` 即可，不需要 Maven。

## 用法

```
用法: jmx-logger [-hV] [-p=<password>] [-s=<server>] [--timeout=秒] [-u=<username>] [COMMAND]
```

| 全局选项 | 说明 | 默认 |
| --- | --- | --- |
| `-s, --server` | 目标 JVM 的 JMX 地址 `host:port` | `127.0.0.1:19000` |
| `-u, --username` | JMX 用户名（开启认证时） | 空 |
| `-p, --password` | JMX 密码（开启认证时） | 空 |
| `--timeout` | 连接超时（秒），`0` 表示不限制 | `10` |
| `-h, --help` / `-V, --version` | 帮助 / 版本 | — |

连接串形如 `service:jmx:rmi:///jndi/rmi://<server>/jmxrmi`。

RMI 握手本身没有超时参数，网络不通时会一直挂到 TCP 默认超时（经常是几分钟），
所以工具默认给连接加了 10 秒上限，超时即报错退出（退出码 `1`）。对面确实很慢时用 `--timeout 60` 放宽。

### get — 查看级别

```bash
jmx-logger -s 10.0.0.5:19000 get                 # 列出全部 logger
jmx-logger -s 10.0.0.5:19000 get com.example     # 查看单个 logger
jmx-logger -s 10.0.0.5:19000 get -r com.example  # 递归：com.example 及其全部子 logger
```

输出：

```
Logger                                             Level      Effective
-------------------------------------------------- ---------- ----------
ROOT                                               INFO       INFO
com.example                                        (inherited) INFO
com.example.service.OrderService                   DEBUG      DEBUG
```

- `Level` 为该 logger 自身配置的级别，`(inherited)` 表示未单独配置、继承父 logger；
- `Effective` 为实际生效级别（由 Logback 侧计算）。

### set — 修改级别

```bash
jmx-logger -s 10.0.0.5:19000 set com.example.service.OrderService DEBUG
```

合法级别（大小写不敏感）：`TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`ALL`、`OFF`。
级别非法时不会连接目标，直接以退出码 `2` 结束。

### reload — 重载配置

```bash
jmx-logger -s 10.0.0.5:19000 reload                       # reloadDefaultConfiguration()
jmx-logger -s 10.0.0.5:19000 reload /opt/app/logback.xml  # reloadByFileName(路径)
```

> 文件路径是**目标 JVM 文件系统**上的路径，由目标进程自行读取；不要填本机路径。

## 目标应用侧配置

### 1. 启用 Logback 的 JMX 配置器

在 `logback.xml` 的 `<configuration>` 根节点下加一行：

```xml
<configuration>
    <jmxConfigurator/>
    ...
</configuration>
```

启用后 Logback 会注册 MBean：

```
ch.qos.logback.classic:Name=<contextName>,Type=ch.qos.logback.classic.jmx.JMXConfigurator
```

工具正是按 `ch.qos.logback.classic:Type=...JMXConfigurator,*` 去查询它的。**没配这一行，一切命令都不可用。**

### 2. 暴露 JMX 端口

目标 JVM 启动参数：

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=19000
-Dcom.sun.management.jmxremote.rmi.port=19000
-Dcom.sun.management.jmxremote.authenticate=true
-Dcom.sun.management.jmxremote.ssl=false
-Djava.rmi.server.hostname=10.0.0.5
```

| 参数 | 要点 |
| --- | --- |
| `jmxremote.port` | 注册表端口，必须与 `-s` 里填的一致 |
| `jmxremote.rmi.port` | **必须与 `port` 相同**（或至少固定且放通），否则 RMI 会随机开端口，防火墙后握手必然超时 |
| `java.rmi.server.hostname` | 目标机器的对外 IP/主机名；多网卡或容器里不设会拿到 `127.0.0.1`，表现为"能连上但立刻断开" |
| `authenticate` | 生产环境建议 `true`，并配合 `jmxremote.access` / `jmxremote.password` |

> 未开启认证时不要同时给 `-u/-p`，反之亦然。

## 兼容性

| 目标应用 | 内置 Logback | `JMXConfigurator` | 本工具 |
| --- | --- | --- | --- |
| Spring Boot 1.5.6（JDK 8） | 1.1.x | 有 | 支持 |
| Spring Boot 2.7.18（JDK 8+） | 1.2.12 | 有 | 支持，无需改动 |
| Spring Boot 3.x（JDK 17+） | 1.4.x+ | **已移除** | 暂不支持 |

关键点：

- **升级到 Spring Boot 2.7.18 不会破坏本工具**。Logback 1.1.x 与 1.2.x 的
  `ch.qos.logback.classic.jmx.JMXConfigurator` 方法签名完全一致
  （`LoggerList` 属性、`getLoggerLevel`、`getLoggerEffectiveLevel`、`setLoggerLevel`、
  `reloadDefaultConfiguration`、`reloadByFileName`），因此同一份客户端在两个版本上通用。
- **Logback ≥ 1.3 起彻底移除了 JMXConfigurator**（Boot 3.x 自带 1.4.x），届时本工具当前的实现路径失效。
  应对方案见下方"路线图"（Actuator / HTTP 通道）。
- 工具自身编译目标保持 Java 8，Boot 2.7.18 同样要求 Java 8，无需调整。

## 退出码

| 码 | 含义 |
| --- | --- |
| `0` | 成功 |
| `1` | 运行时错误（连不上、目标无 JMXConfigurator、JMX 调用失败等） |
| `2` | 用法错误（非法级别、参数缺失等） |

## 排查

| 现象 | 原因与处理 |
| --- | --- |
| `无法连接到 JMX 服务器` | 端口不通 / 目标未加 `com.sun.management.jmxremote` / 防火墙未放通 RMI 端口；用 `nc -vz host port` 先确认连通性 |
| `连接 JMX 服务器超时（超过 N ms）` | TCP 能建连但对面不回应，典型是防火墙丢包或 `jmxremote.rmi.port` 未放通；按报错里的提示逐项核对，或先用 `--timeout 30` 排除"只是慢" |
| `未找到 Logback JMXConfigurator MBean` | 目标 `logback.xml` 缺 `<jmxConfigurator/>`，或该 JVM 用的不是 Logback |
| 连上后很快断开 / 卡住 | 未设 `java.rmi.server.hostname`，或 `rmi.port` 与 `port` 不一致 |
| `set` 后级别没变 | 确认改的是正确的 logger 名；子 logger 会覆盖父 logger；`reload` 会重置为配置文件中的值 |
| 认证失败 | 检查 `jmxremote.password` 文件权限必须为 `600`，且 `-u/-p` 与目标配置一致 |

## 安全建议

`-p` 明文密码会出现在 `ps` 输出里。当前版本只支持 `-p`，建议用交互式读取绕开：

```bash
read -s JMX_PASS && java -jar target/jmx-logger.jar -s 10.0.0.5:19000 -u admin -p "$JMX_PASS" get
```

支持环境变量/凭证文件读取已列入路线图。

## 路线图

以下能力**尚未实现**，按阶段推进，每阶段可独立验收与回滚（详见 `doc/plan_v1.0.1.md`）：

| 阶段 | 内容 |
| --- | --- |
| P1 | 抽出 transport/provider 抽象；新增 `doctor` 诊断子命令；统一退出码与 `--verbose` |
| P2 | `-P/--pid` 本地 attach（目标未开 JMX 端口时，通过 attach API 动态拉起管理代理，目标侧零配置） |
| P3 | Spring Boot Actuator 兜底通道（`type=Endpoint,name=Loggers`），目标无 `<jmxConfigurator/>` 时自动切换 |
| P4 | `--json` 输出、`set inherit`（重置为继承级别）、`--object-name`（多 LoggerContext） |
| P5 | Boot 3.x / Logback 1.4+ 的 HTTP 通道预留 |

## 开发

```bash
./mvnw test            # 运行单元测试
./mvnw clean package   # 打成 fat jar
```

测试说明：

- `JmxClientTest` 是**刻画测试**，锁定当前对外行为契约（ObjectName 查询方式、各操作的 invoke 签名、错误文案）。
  后续重构（P1 拆分 transport/provider）必须让这些用例原样通过。
- 测试不 mock JMX，而是在测试 JVM 内起一个**真实的 RMI JMX 连接器**（随机端口），
  注册 `StubLogbackConfigurator` 桩 MBean，覆盖序列化与真实调用链路。
