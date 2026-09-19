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
用法: jmx-logger [-hvV] [-p=pid] [-s=<server>] [--timeout=秒] [--username=<username>] [COMMAND]
```

| 全局选项 | 说明 | 默认 |
| --- | --- | --- |
| `-s, --server` | 目标 JVM 的 JMX 地址 `host:port`（远程 RMI 通道） | `127.0.0.1:19000` |
| `-p, --pid` | 目标 JVM 的进程号（本地 attach 通道）；指定后**优先于** `-s` | 空 |
| `--username` | JMX 用户名（开启认证时） | 空 |
| `--password` | JMX 密码（开启认证时） | 空 |
| `--timeout` | 连接超时（秒），`0` 表示不限制 | `10` |
| `-v, --verbose` | 出错时打印完整堆栈（默认只打一行原因） | 关 |
| `-h, --help` / `-V, --version` | 帮助 / 版本 | — |

短选项只给"指向哪个 JVM"用（`-s` / `-p`）；认证参数只有长选项。

连接串形如 `service:jmx:rmi:///jndi/rmi://<server>/jmxrmi`。

RMI 握手本身没有超时参数，网络不通时会一直挂到 TCP 默认超时（经常是几分钟），
所以工具默认给连接加了 10 秒上限，超时即报错退出（退出码 `1`）。对面确实很慢时用 `--timeout 60` 放宽。

### 连接目标的两条通道

| 通道 | 用法 | 目标侧需要 |
| --- | --- | --- |
| 远程 RMI | `-s host:port` | `<jmxConfigurator/>` **+ 暴露 JMX 端口** |
| 本地 attach | `-p <pid>` | 只需 `<jmxConfigurator/>`（**不用开端口**） |

目标进程没开 JMX 端口时，用 `jps -l` 找到 PID 直接连：

```bash
jmx-logger -p 2235675 get                       # 目标零端口配置也能查
jmx-logger -p 2235675 set com.example DEBUG
jmx-logger -p 2235675 doctor
```

原理：attach 到目标进程后调用 `VirtualMachine#startLocalManagementAgent()`，
让目标 JVM 现场启动一个**仅本机可连**的 JMX 代理，再连它的本地连接器地址。
attach API 全程反射调用（JDK 8 位于 `tools.jar`，JDK 9+ 归入 `jdk.attach` 模块），
所以同一个 fat jar 在 JDK 8 / 11 / 17 上都能用，编译期也不依赖 `tools.jar`。

前提条件（不满足时报错会逐条列出，不会甩堆栈）：

1. jmx-logger 与目标进程**同一 OS 用户**（非 root 不能 attach 别人的进程）；
2. 用**完整 JDK** 运行（JRE 没有 attach 能力）；
3. `/tmp` 可写（attach 依赖 `/tmp` 下的 UNIX socket）；
4. 容器场景需与目标是同一 PID namespace（`--pid=host` 或同一 Pod）；
5. 目标进程未被 ptrace 限制（docker 默认 seccomp、K8s 安全策略可能拦）。

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

### doctor — 诊断（只读）

```bash
jmx-logger -s 10.0.0.5:19000 doctor
```

只读探测，**不改动目标 JVM 任何状态**。依次输出：

1. **连接**：目标地址、JMX Service URL、进程（`pid@host`）、JVM 版本，以及从 classpath 认出的
   `logback-classic` / `spring-boot-actuator` 版本（logback ≥ 1.3 会提示 `JMXConfigurator` 已被移除）；
2. **候选 MBean**：logback `JMXConfigurator` 与 Spring Boot 的 loggers 端点各命中几个，
   并打印命中 MBean 的**完整 MBeanInfo**（属性及其可写性、操作名 + 完整参数类型 + 返回类型）；
3. **结论与建议**：哪条通道可用、缺什么，以及目标侧该加的最小配置。

真实目标（Boot 1.5.6 + logback 1.1.11）上的一段输出：

```
[连接]
  目标: 127.0.0.1:19000
  URL: service:jmx:rmi:///jndi/rmi://127.0.0.1:19000/jmxrmi
  进程: 2235675@ubuntu
  JVM: OpenJDK 64-Bit Server VM 25.432-b06
  classpath 识别: logback-classic 1.1.11, spring-boot-actuator 1.5.6.RELEASE
[候选 MBean]
  logback JMXConfigurator    ch.qos.logback.classic:Type=...JMXConfigurator,*  ->  1 个
  Boot loggers 端点          org.springframework.boot:type=Endpoint,*  ->  1 个

  ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator
    属性:
      LoggerList: java.util.List  [只读]
      Statuses: java.util.List  [只读]
    操作:
      getLoggerLevel(java.lang.String) -> java.lang.String
      setLoggerLevel(java.lang.String, java.lang.String) -> void
      ...
```

Boot 各版本的端点命名不同（1.5 是 `name=loggersEndpoint`，2.7 是 `name=Loggers`），
因此 `doctor` 按 `org.springframework.boot:type=Endpoint,*` 全量查再按名字过滤，不写死某一种拼法。

> 连不上或找不到 MBean 时，先跑 `doctor`。

## 目标应用侧配置

> 用 `-p/--pid` 本地 attach 时，只需做第 1 步（`<jmxConfigurator/>`），第 2 步"暴露 JMX 端口"可以整段跳过。

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

> 未开启认证时不要传 `--username`/`--password`，反之亦然。

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
| `2` | 用法错误（级别非法、参数缺失、未知选项等） |

错误只打到 stderr，且默认只有一行原因 + 一行提示；加 `-v/--verbose` 才打印完整堆栈，
栈里的密码会被替换成 `******`。脚本按上表分支即可：

```bash
java -jar target/jmx-logger.jar -s 10.0.0.5:19000 get || echo "失败，退出码 $?"
```

> `doctor` 是例外：连不上才返回 `1`；连上了但报告"无可用通道"仍返回 `0`——
> 诊断的目的就是把这种情形说清楚，它不算失败。

## 排查

| 现象 | 原因与处理 |
| --- | --- |
| 不知道该从哪查起 | 先跑 `doctor`：它会列出候选 MBean、打印操作签名，并给出目标侧该加的配置 |
| `无法连接到 JMX 服务器` | 端口不通 / 目标未加 `com.sun.management.jmxremote` / 防火墙未放通 RMI 端口；用 `nc -vz host port` 先确认连通性 |
| `连接 JMX 服务器超时（超过 N ms）` | TCP 能建连但对面不回应，典型是防火墙丢包或 `jmxremote.rmi.port` 未放通；按报错里的提示逐项核对，或先用 `--timeout 30` 排除"只是慢" |
| `未找到 Logback JMXConfigurator MBean` | 目标 `logback.xml` 缺 `<jmxConfigurator/>`，或该 JVM 用的不是 Logback |
| 连上后很快断开 / 卡住 | 未设 `java.rmi.server.hostname`，或 `rmi.port` 与 `port` 不一致 |
| `set` 后级别没变 | 确认改的是正确的 logger 名；子 logger 会覆盖父 logger；`reload` 会重置为配置文件中的值 |
| 认证失败 | 检查 `jmxremote.password` 文件权限必须为 `600`，且 `--username`/`--password` 与目标配置一致 |
| `非法的 PID "..."` | `-p` 只接受正整数进程号，用 `jps -l` 确认 PID |
| `无法 attach 到本地进程 <pid>` | 按报错里的 5 条排查清单逐项核对：PID 是否存在、是否同用户、是否用 JDK 运行、`/tmp` 是否可写、容器是否同一 PID namespace |
| `attach ... 需要 com.sun.tools.attach.VirtualMachine` | 用 **JRE** 跑了 jmx-logger（缺 `tools.jar`）：换成完整 JDK，或改用 `-s host:port` |
| `已 attach 到进程 N，但目标未提供本地 JMX 连接器地址` | 目标 JVM 禁用了管理代理（`-XX:+DisableAttachMechanism`、`-Dcom.sun.management.jmxremote=false`），或 JDK 过旧 |

## 安全建议

`--password` 明文密码会出现在 `ps` 输出里。当前版本只支持命令行传密码，建议用交互式读取绕开：

```bash
read -s JMX_PASS && java -jar target/jmx-logger.jar -s 10.0.0.5:19000 --username admin --password "$JMX_PASS" get
```

支持环境变量/凭证文件读取已列入路线图。

## 路线图

按阶段推进，每阶段可独立验收与回滚（详见 `doc/plan_v1.0.1.md`）。**P1、P2 已完成**：
transport/provider 抽象、`doctor` 诊断子命令、统一退出码与 `--verbose`、`-p/--pid` 本地 attach。

| 阶段 | 内容 |
| --- | --- |
| P1 | ~~抽出 transport/provider 抽象~~ ✅；~~新增 `doctor` 诊断子命令~~ ✅；~~统一退出码与 `--verbose`~~ ✅ |
| P2 | ~~`-p/--pid` 本地 attach（目标未开 JMX 端口时，通过 attach API 动态拉起管理代理，目标侧零配置）~~ ✅ |
| P3 | Spring Boot Actuator 兜底通道（Boot 1.5 `name=loggersEndpoint` / Boot 2.7 `name=Loggers`，签名由 `doctor` 实测驱动），目标无 `<jmxConfigurator/>` 时自动切换 |
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
- 退出码与报错形态由 `CommandSupportTest` 与 `JmxLoggerCliTest` 里的 `execute(...)` 用例锁定
  （用法错误 2 / 运行时错误 1 / 成功 0）。改退出码要同步 `ExitCodes`、本文档与 `doc/plan_v1.0.1.md`。
- `LocalPidConnectorTest` 会真的 attach 一次测试进程自身：环境不支持（JRE / 容器 / seccomp）时
  用 JUnit `Assume` 跳过，不会让构建失败。
