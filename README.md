# jmx-logger

通过 JMX 远程查看和修改 Logback 的 Logger 级别、并重载日志配置的命令行工具。

工具独立于目标应用：客户端**不需要**引入 Logback 或 Spring Boot 依赖，全部调用通过
`MBeanServerConnection#invoke` 反射完成，只要目标 JVM 暴露了 JMX 即可使用。

- 运行时依赖：仅 [picocli](https://picocli.info/)（打包进 fat jar）
- 构建要求：JDK 8+（编译目标固定为 Java 8）
- 目标应用：任何带 Logback（1.1.x / 1.2.x）的 JVM，实测覆盖 Spring Boot 1.5.6 与 1.5.20，并兼容 2.7.18
- 变更历史：[CHANGELOG.md](CHANGELOG.md)

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

下面是 `jmx-logger --help` 在 80 列终端下的实际输出（选项按"连接 → 认证 → 通道 → 输出"排列，
不是字母序）。`--password` 一行的折行位置随终端宽度变化，属正常现象；
改了 `--help` 的形态记得同步这里：

```text
Usage: jmx-logger [OPTIONS] [COMMAND]
通过 JMX 远程管理 Logback 的 Logger 级别与配置重载。
  -s, --server=<server>     目标 JVM 的 JMX 地址，默认值为 127.0.0.1:19000
      --username=<username> JMX 用户名（可选）
      --password[=<password>]
                            JMX 密码（可选）。不带取值时交互式读取（不回显）；完
                              全省略时回退到环境变量 JMX_LOGGER_PASSWORD
  -p, --pid=<pid>           目标 JVM 的进程号：本地 attach 并现场启动管理代理，
                              目标侧无需预先开 JMX 端口；指定后优先于 -s
  -t, --target=<target>     日志通道: auto（先 logback，缺失时兜底 actuator）/
                              logback / actuator，默认值为 auto
      --object-name=<objectName>
                            直接指定目标 MBean 的 ObjectName（多 LoggerContext
                              时用，取值见 doctor 的候选 MBean）
      --timeout=<seconds>   连接超时（秒），0 表示不限制，默认值为 10
  -v, --verbose             出错时打印完整堆栈（默认只打印一行错误原因）
  -h, --help                Show this help message and exit.
  -V, --version             Print version information and exit.
Commands:
  get     查看 Logger 的级别（默认只列单独配置了级别的）
  set     设置 Logger 的级别
  clear   清除 Logger 自身配置的级别，恢复继承父 logger
  reload  重新加载 Logback 配置
  doctor  诊断目标 JVM 的 JMX 连接与可用通道（只读，不改目标状态）
```

| 全局选项 | 说明 | 默认 |
| --- | --- | --- |
| `-s, --server` | 目标 JVM 的 JMX 地址 `host:port`（远程 RMI 通道） | `127.0.0.1:19000` |
| `-p, --pid` | 目标 JVM 的进程号（本地 attach 通道）；指定后**优先于** `-s` | 空 |
| `-t, --target` | 日志通道：`auto` / `logback` / `actuator`，见"两条日志通道" | `auto` |
| `--object-name` | 直接指定目标 MBean 的 ObjectName（多 LoggerContext 时用；取值抄 `doctor` 的候选 MBean，通道按 domain 判断） | 按模式自动探测 |
| `--username` | JMX 用户名（开启认证时） | 空 |
| `--password` | JMX 密码（开启认证时）。不带取值时交互式读取；省略时读环境变量 `JMX_LOGGER_PASSWORD` | 空 |
| `--timeout` | 连接超时（秒），`0` 表示不限制 | `10` |
| `-v, --verbose` | 出错时打印完整堆栈（默认只打一行原因） | 关 |
| `-h, --help` / `-V, --version` | 帮助 / 版本与构建信息（`git describe` + 提交时间，取不到时为版本号） | — |

短选项只给高频参数："指向哪个 JVM"（`-s` / `-p`）与"走哪条通道"（`-t`）；
认证参数只有长选项。

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

### 两条日志通道（`-t/--target`）

解决的是"连得上 JMX、但目标没配 `<jmxConfigurator/>`"（或 Logback ≥ 1.3 已移除该 MBean）：

| 通道 | 依据的 MBean | `get` / `set` | `reload` | 目标侧需要 |
| --- | --- | --- | --- | --- |
| `logback` | `ch.qos.logback.classic:`<br>`Name=<contextName>,Type=ch.qos.logback.classic.jmx.JMXConfigurator` | 支持 | **支持** | `logback.xml` 加 `<jmxConfigurator/>` |
| `actuator` | `org.springframework.boot:type=Endpoint,name=Loggers`（Spring Boot 2.7）<br>`name=loggersEndpoint`（Spring Boot 1.5） | 支持 | 不支持 | `spring-boot-starter-actuator` + **`spring.jmx.enabled=true`** |

端点命名与操作名两套都认（Spring Boot 1.5 的 `getLoggers()`/`getLogger`/`setLogLevel`
与 Spring Boot 2.7 的 `loggers()`/`loggerLevels`/`configureLogLevel`），
已在真实 Spring Boot 1.5.6、1.5.20 与 2.7.18 目标上实测：一次调用即可拿到全部 logger，
条数与 `logback` 通道一致（数量取决于目标应用自身的类加载情况，不固定）。
2.7.18 上实测到的签名是 `configureLogLevel(java.lang.String, java.lang.String)`，
`loggers()` / `loggerLevels(String)` 均返回 `java.util.Map`——第二参不是 `LogLevel` 枚举、
返回值也不是 `CompositeData`/`TabularData`，所以这条通道不需要任何类型转换兜底。
（这些用例固化在 `tools/e2e/`，见"开发"一节。）

> ⚠ 走 actuator 通道**必须**给目标加 `-Dspring.jmx.enabled=true`：Spring Boot 2.2 起 JMX
> 默认为关闭，只引 `spring-boot-starter-actuator` 端点不会注册到 JMX
> （`management.endpoints.jmx.exposure.include` 的默认值确实是 `*`，但没有 MBeanServer 可暴露）。
> 目标"引了 actuator 却看不到端点"时，`doctor` 会把这条原因直接指出来。

`-t auto`（默认）**先 logback 后 actuator**：logback 能力最全（含配置重载），
actuator 只作为兜底；两条都没有时报错里同时给出两边的缺失原因与目标侧该加的配置。
显式指定某一条则不再自动切换——用于"两条都有但我要走某一条"。

```bash
jmx-logger -s 10.0.0.5:19000 get                # auto（默认）
jmx-logger -s 10.0.0.5:19000 -t actuator get    # 强制走 actuator
```

actuator 通道的两点限制：

- **不支持 `reload`**（端点只能读写级别）。此时 `reload` 会直接给出替代方案
  （目标 `logback.xml` 开 `scan="true"`；或加 `<jmxConfigurator/>` 后走 `-t logback`），
  退出码 `1`，不会崩堆栈。
- **操作签名不写死**：端点 MBean 是动态 MBean，`configureLogLevel` 的级别参数在
  Spring Boot 各版本可能是 `String`，也可能是本地 classpath 没有的 `LogLevel` 枚举。
  工具按目标自报的 `MBeanInfo` 现场构造参数（`doctor` 打印的就是这份签名）；
  构造不出来就明确报错并建议换通道，不会猜。

### get — 查看级别

```bash
jmx-logger -s 10.0.0.5:19000 get                      # 列出单独配置了级别的 logger
jmx-logger -s 10.0.0.5:19000 get --all                # 连同未单独配置级别的一起列出
jmx-logger -s 10.0.0.5:19000 get --effective          # 额外显示实际生效级别
jmx-logger -s 10.0.0.5:19000 get com.example          # 查看单个 logger（未配置也给出这一行）
jmx-logger -s 10.0.0.5:19000 get -r com.example       # 递归：该 logger 及子 logger 中配置过级别的
```

输出（默认只查配置级别，两列；列表只列单独配置了级别的）：

```
Logger                                             Level
-------------------------------------------------- ----------
ROOT                                               INFO
com.example.service.OrderService                   DEBUG

共 2 个 logger（仅列单独配置了级别的；另有 1 个未配置，已省略）
```

加 `--effective` 后多一列——每个 logger 多一次远程调用，这是 `get` 在大目标上的主要耗时：

```
Logger                                             Level      Effective
-------------------------------------------------- ---------- ----------
ROOT                                               INFO       INFO
com.example.service.OrderService                   DEBUG      DEBUG

共 2 个 logger（仅列单独配置了级别的；另有 1 个未配置，已省略）
```

- 列表默认**只列单独配置了级别的 logger**：未配置的行是噪音（目标应用里通常是绝大多数，
  全打出来会把"谁被改过级别"淹掉），省略掉的数量在统计行里交代；要看全量用 `--all`；
- 显式指定名字时不过滤，因为"没配"本身就是答案——此时 `Level` 为空表示该 logger 未单独配置、继承父 logger，
  目标侧返回的就是空串（logback 的 `EMPTY`，actuator 的空 `configuredLevel`），输出如实留空，
  不另造 `(inherited)` 这类目标侧并不存在的取值；
- `Effective`（加 `--effective` 才有）为实际生效级别，由目标侧计算：logback 通道下每个
  logger 要多一次 `getLoggerEffectiveLevel` 远程调用，因此默认不查，输出只有两列。

`--all` 与 `--effective` 是两个**正交**的开关：前者决定**列哪些行**（是否省略未单独配置
级别的 logger），后者决定**每行多列什么**（是否附带生效级别），所以四种组合都成立：

| 命令 | 输出 |
| --- | --- |
| `get` | 只列配了级别的 logger，两列 |
| `get --all` | 全部 logger，两列（未配置的那些 Level 留空） |
| `get --effective` | 只列配了级别的 logger，三列 |
| `get --all --effective` | 全部 logger，三列 |

`--json` 把同一份数据输出成机器可读的形态，字段名与表格列一一对应（`logger` / `level` / `effective`）；
未单独配置级别是 `null`（表格里那一列留空），没加 `--effective` 时 `effective` 字段不出现，
有省略时才有 `omitted`：

```bash
jmx-logger -s 10.0.0.5:19000 get --json                            # 单行紧凑 JSON，管道友好
jmx-logger -s 10.0.0.5:19000 get --json | jq '.loggers[] | select(.level != null)'
```

```json
{"loggers":[{"logger":"ROOT","level":"INFO"},{"logger":"com.example.service.OrderService","level":"DEBUG"}],"listed":2,"omitted":1}
```

### set — 修改级别

```bash
jmx-logger -s 10.0.0.5:19000 set com.example.service.OrderService DEBUG
```

合法级别（大小写不敏感）：`TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`ALL`、`OFF`。
级别非法时不会连接目标，直接以退出码 `2` 结束。

要把 logger 恢复成"继承父 logger"，**不是**给 `set` 传特殊值——空串与 `null` 都会被拒绝
并提示改用 `clear` 命令：

```bash
jmx-logger -s 10.0.0.5:19000 set com.example.Foo ""      # 退出码 2：非法级别
jmx-logger -s 10.0.0.5:19000 clear com.example.Foo       # 正确写法
```

### clear — 恢复继承父 logger

```bash
jmx-logger -s 10.0.0.5:19000 clear com.example.service.OrderService
```

清除该 logger **自身**配置的级别，使其回到继承父 logger 的状态（`get` 里该行的 `Level` 变成空）。

- 只影响这一个 logger：临时调完级别要复原就用它，不要用 `reload`——`reload` 会把**所有**
  logger 拉回配置文件状态，且 actuator 通道不支持
- 两条通道都支持。目标侧"清除"的指令并不相同（logback 要字符串 `"null"`，actuator 要
  Java `null`），由工具按通道翻译，命令行不用关心
- `<name>` 必填：不带名字的"全部清除"不做，批量复原请走 `reload`
- 对本来就是继承状态的 logger 再执行一次是幂等的，不报错

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

真实目标（Spring Boot 1.5.20 + logback 1.1.11）上的一段输出（1.5.6 那台的端点命名与操作签名逐字一致）：

```
[连接]
  目标: 127.0.0.1:19000
  URL: service:jmx:rmi:///jndi/rmi://127.0.0.1:19000/jmxrmi
  进程: 2389786@ubuntu
  JVM: OpenJDK 64-Bit Server VM 25.432-b06
  classpath 识别: spring-boot 1.5.20.RELEASE, spring-boot-actuator 1.5.20.RELEASE, logback-classic 1.1.11
[候选 MBean]
  logback JMXConfigurator    ch.qos.logback.classic:Type=ch.qos.logback.classic.jmx.JMXConfigurator,*  ->  1 个
  Spring Boot loggers 端点          org.springframework.boot:type=Endpoint,*  ->  1 个

  ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator
    属性:
      LoggerList: java.util.List  [只读]
      Statuses: java.util.List  [只读]
    操作:
      getLoggerLevel(java.lang.String) -> java.lang.String
      setLoggerLevel(java.lang.String, java.lang.String) -> void
      ...
```

Spring Boot 各版本的端点命名不同（1.5 是 `name=loggersEndpoint`，2.7 是 `name=Loggers`），
因此 `doctor` 按 `org.springframework.boot:type=Endpoint,*` 全量查再按名字过滤，不写死某一种拼法。

> 连不上或找不到 MBean 时，先跑 `doctor`。

> **不指定 `--object-name` 时的默认行为**：logback 侧按模式
> `ch.qos.logback.classic:Type=ch.qos.logback.classic.jmx.JMXConfigurator,*` 查询，命中多个就取
> **第一个**——该顺序由目标 JVM 的返回决定、不做排序，所以多 LoggerContext 时"这次连上的是哪个
> context"并不可预测；actuator 侧按 `org.springframework.boot:type=Endpoint,*` 全量查、再按名字里含
> `logger` 过滤，命中多个时按 ObjectName 排序取第一个（这一条是稳定的）。
> 想确认当前实际用的是哪条，看 `doctor` 的"候选 MBean"段：它会打印解析到的完整 ObjectName。

> 目标有多个 LoggerContext（多个 `JMXConfigurator`）时，`doctor` 会提示用 `--object-name`
> 指定要操作的那一个——取值直接抄它打印的候选 MBean：
>
> ```bash
> jmx-logger -s 10.0.0.5:19000 \
>     --object-name ch.qos.logback.classic:Name=custom,Type=ch.qos.logback.classic.jmx.JMXConfigurator \
>     get com.example
> ```
>
> 通道按 ObjectName 的 domain 判断（`ch.qos.logback.classic` → logback，
> `org.springframework.boot` → actuator）；与 `--target` 指的通道不一致时按用法错误退出（码 `2`），
> 不会默默挑一个。

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

工具正是按 `ch.qos.logback.classic:Type=ch.qos.logback.classic.jmx.JMXConfigurator,*` 去查询它的。**没配这一行，一切命令都不可用。**
应用里注册了多个 LoggerContext 时会命中多个，默认取查询结果的第一个（不排序）；
要操作其中特定一个，用 `--object-name` 点名（见"doctor"一节）。

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
> 密码建议走环境变量或交互式读取，`--password <明文>` 会出现在 `ps` 里（详见"安全建议"）。

### 3. 走 actuator 兜底（可选，目标侧没有 `<jmxConfigurator/>` 时）

两件事缺一不可：

1. classpath 里有 `spring-boot-starter-actuator`；
2. 启动参数加 `-Dspring.jmx.enabled=true` —— **Spring Boot 2.2 起 JMX 默认为关闭**，
   不打开它，端点根本不会注册到 JMX。

`management.endpoints.jmx.exposure.include` 的默认值本来就是 `*`（`loggers` 在其中），
所以**不需要**为它额外配置；真正容易漏的是 `spring.jmx.enabled`。目标"引了 actuator
却看不到端点"时，`doctor` 会据此直接点出原因：

```text
[缺失] 未找到 Actuator loggers 端点。
       classpath 里有 spring-boot-actuator，但端点没注册到 JMX：
       多半是没设 spring.jmx.enabled=true（Spring Boot 2.2 起 JMX 默认关闭）。
```

> Spring Boot 3.x 还需显式 `management.endpoints.jmx.exposure.include=health,loggers`（3.x 的 JMX 默认只暴露 `health`）；
> 而 logback ≥ 1.3 已移除 `JMXConfigurator`，那时 actuator 就是唯一的通道。

## 兼容性

| 目标应用 | 内置 Logback | `JMXConfigurator` | 本工具 |
| --- | --- | --- | --- |
| Spring Boot 1.5.6 / 1.5.20（JDK 8） | 1.1.x | 有 | 支持（实测） |
| Spring Boot 2.7.18（JDK 8+） | 1.2.12 | 有 | logback 通道无需改动；actuator 通道需 `spring.jmx.enabled=true`（已实测） |
| Spring Boot 3.x（JDK 17+） | 1.4.x+ | **已移除** | 不支持：本工具只用 JMX，不做 HTTP 通道（见"路线图"） |

关键点：

- **升级到 Spring Boot 2.7.18 不会破坏本工具**。Logback 1.1.x 与 1.2.x 的
  `ch.qos.logback.classic.jmx.JMXConfigurator` 方法签名完全一致
  （`LoggerList` 属性、`getLoggerLevel`、`getLoggerEffectiveLevel`、`setLoggerLevel`、
  `reloadDefaultConfiguration`、`reloadByFileName`），因此同一份客户端在两个版本上通用。
- **Logback ≥ 1.3 起彻底移除了 JMXConfigurator**（Spring Boot 3.x 自带 1.4.x），届时本工具当前的实现路径失效。
  本工具**不做 HTTP 通道**（见"路线图"的 P5）：真要在 3.x 上改级别，用 actuator 的 HTTP 端点
  （`GET/POST /actuator/loggers`）配合 curl 即可。
- 工具自身编译目标保持 Java 8，Spring Boot 2.7.18 同样要求 Java 8，无需调整。

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
| `未找到 Logback JMXConfigurator MBean` | 目标 `logback.xml` 缺 `<jmxConfigurator/>`，或该 JVM 用的不是 Logback；带 actuator 时会自动兜底（见"两条日志通道"），不想兜底就 `-t logback` 看原始报错 |
| `目标 JVM 上没有可用的日志通道` | logback 与 actuator 两条都没找到：按报错里的 a/b 二选一加配置，或 `-t` 强制指定；用 `doctor` 看目标上真实有哪些 MBean |
| `未找到 Spring Boot Actuator 的 loggers 端点` | 目标侧两步缺一不可：引 `spring-boot-starter-actuator` **且** 设 `spring.jmx.enabled=true`——Spring Boot 2.2 起 JMX 默认关闭，不设它端点不会注册到 JMX；`doctor` 若在 classpath 里认到 actuator，会直接把这条点出来 |
| `无法把取值 "DEBUG" 转成目标 MBean 声明的参数类型 org.springframework.boot.logging.LogLevel` | actuator 端点把级别暴露成本地没有的 `LogLevel` 枚举，远程无法构造：改用 `-t logback`，或用 `doctor` 看真实签名 |
| `当前通道 actuator 不支持重载配置` | actuator 端点不支持重载：目标 `logback.xml` 开 `scan="true"`，或加 `<jmxConfigurator/>` 后走 `-t logback` |
| `未知的 -t/--target 取值 "..."` | 取值只有 `auto` / `logback` / `actuator` 三个 |
| 连上后很快断开 / 卡住 | 未设 `java.rmi.server.hostname`，或 `rmi.port` 与 `port` 不一致 |
| `set` 后级别没变 | 确认改的是正确的 logger 名；子 logger 会覆盖父 logger；`reload` 会重置为配置文件中的值 |
| 认证失败 | 检查 `jmxremote.password` 文件权限必须为 `600`，且 `--username`/密码与目标配置一致；密码来源优先级见"安全建议"（环境变量没生效时通常是漏了 `--username`） |
| `非法的 PID "..."` | `-p` 只接受正整数进程号，用 `jps -l` 确认 PID |
| `无法 attach 到本地进程 <pid>` | 按报错里的 5 条排查清单逐项核对：PID 是否存在、是否同用户、是否用 JDK 运行、`/tmp` 是否可写、容器是否同一 PID namespace |
| `attach ... 需要 com.sun.tools.attach.VirtualMachine` | 用 **JRE** 跑了 jmx-logger（缺 `tools.jar`）：换成完整 JDK，或改用 `-s host:port` |
| `已 attach 到进程 N，但目标未提供本地 JMX 连接器地址` | 目标 JVM 禁用了管理代理（`-XX:+DisableAttachMechanism`、`-Dcom.sun.management.jmxremote=false`），或 JDK 过旧 |

## 安全建议

`--password <明文>` 会出现在 `ps` 输出里（同机任何用户都能看到）。优先用下面两种方式，按"越靠前越推荐"：

```bash
# 1) 环境变量（脚本/CI 首选）：不进命令行，也不进 ps
export JMX_LOGGER_PASSWORD='...'
java -jar target/jmx-logger.jar -s 10.0.0.5:19000 --username admin get

# 2) 交互式：--password 不带取值，从终端读取且终端下不回显
java -jar target/jmx-logger.jar -s 10.0.0.5:19000 --username admin --password get

# 3) 兼容但会泄漏：命令行明文（此时工具会打印一行提示到 stderr）
java -jar target/jmx-logger.jar -s 10.0.0.5:19000 --username admin --password '...' get
```

取值优先级：`--password <明文>` → `--password`（不带取值，交互式）→ 环境变量 `JMX_LOGGER_PASSWORD` →
无密码（不带凭证连接）。要点：

- `--username` 为空时密码不会生效（JMX 只在给了用户名的情况下带凭证）。
- 交互式在**无控制台**（stdin 被重定向、CI）时退回读 stdin 一行并提示"输入会回显"；
  读到空输入直接报错退出（退出码 `2`），不会拿空密码去连。
- 密码不进日志：`-v/--verbose` 的堆栈与报错里出现的密码会被替换成 `******`。
- 环境变量设为空串等同于没设。

## 路线图

按阶段推进，每阶段可独立验收与回滚（详见 `doc/plan_v1.0.1.md`）。**P1–P4 已完成，P5 已放弃**：
transport/provider 抽象、`doctor` 诊断子命令、统一退出码与 `--verbose`、`-p/--pid` 本地 attach、
`-t/--target` 通道选择与 Actuator 兜底。

| 阶段 | 内容 |
| --- | --- |
| P1 | ~~抽出 transport/provider 抽象~~ ✅；~~新增 `doctor` 诊断子命令~~ ✅；~~统一退出码与 `--verbose`~~ ✅ |
| P2 | ~~`-p/--pid` 本地 attach（目标未开 JMX 端口时，通过 attach API 动态拉起管理代理，目标侧零配置）~~ ✅ |
| P3 | ~~Spring Boot Actuator 兜底通道（`-t auto` 在目标无 `<jmxConfigurator/>` 时自动切换到 `actuator`：按 `MBeanInfo` 现场构造参数、reload 不支持时给出替代方案）~~ ✅ |
| P4 | ~~`clear` 命令（恢复继承级别）~~ ✅；~~`--object-name`（多 LoggerContext）~~ ✅；~~`--json` 输出~~ ✅ |
| P5 | ~~Spring Boot 3.x / Logback 1.4+ 的 HTTP 通道预留~~ ❌ **不做**：现有目标栈是 JDK 8 + Spring Boot 1.5.6 → 2.7.18，不涉及 3.x；真要支持时，`LoggerProvider` 抽象已经够容纳一个 HTTP 实现，届时按真实签名实现即可，不做提前预留 |

## 开发

```bash
./mvnw test            # 运行单元测试
./mvnw clean package   # 打成 fat jar
```

`jmx-logger -V` 能回答"这个 jar 是哪次提交打的"，读两个构建期生成的属性文件：

- `git.properties`（`git-commit-id-maven-plugin` 生成）：`git describe`、commit、提交时间；
- `app.properties`（maven 资源过滤写入 `app.version`）：**只依赖 pom，一定有**，
  没有 `.git` 的构建（源码包、CI 归档）也随 fat jar 发布。

```console
$ java -jar target/jmx-logger.jar -V
jmx-logger v1.0.1 (20260920)                 # describe + 提交时间；结尾的 + 表示工作区有未提交改动
```

输出优先级：`git.commit.id.describe` → pom 版本号（`jmx-logger 1.0.1`，无 `.git` / 浅克隆 / 无 tag）→
`jmx-logger (构建信息不可用：未找到版本属性文件)`（IDE 直接跑未过滤的 resources 才会出现）。
没有 `.git` 时插件跳过而非构建失败，其他命令不受影响。

测试说明：

- `JmxClientTest` 是**刻画测试**，锁定当前对外行为契约（ObjectName 查询方式、各操作的 invoke 签名、错误文案）。
  后续重构（P1 拆分 transport/provider）必须让这些用例原样通过。
- 测试不 mock JMX，而是在测试 JVM 内起一个**真实的 RMI JMX 连接器**（随机端口），
  注册 `StubLogbackConfigurator` 桩 MBean，覆盖序列化与真实调用链路。
- 退出码与报错形态由 `CommandSupportTest` 与 `JmxLoggerCliTest` 里的 `execute(...)` 用例锁定
  （用法错误 2 / 运行时错误 1 / 成功 0）。改退出码要同步 `ExitCodes`、本文档与 `doc/plan_v1.0.1.md`。
- `GetCommandTest` / `SetCommandTest` / `ReloadCommandTest` 直接跑完整 CLI（经 `CliRunner` 捕获 stdout/stderr），
  覆盖输出表格与 `--json`、默认只列配了级别的（`--all` 全列）、`--effective` 三列、递归 `-r`、
  级别大小写归一化、"非法级别不连目标就失败"，以及目标缺失 MBean 时的退出码与文案。
- `JsonTest` 锁住 `support/Json` 的转义与拼装契约（引号、反斜杠、控制字符、成对与孤立的代理项、
  值片段为 null 的字段不出现）；将来把这个手写实现换成 Gson / Jackson 时，这些用例必须原样通过。
- `LocalPidConnectorTest` 会真的 attach 一次测试进程自身：环境不支持（JRE / 容器 / seccomp）时
  用 JUnit `Assume` 跳过，不会让构建失败。
- `tools/e2e/` 是**端到端夹具**（真实的 Spring Boot 1.5.6 / 2.7.18 目标进程 + 四种目标侧配置），
  单独跑：`just verify`（默认 1.5.6）/ `just verify 2.7.18` / `just target 2.7.18 actuator`（前台起目标手工试）。
  它不在根 pom 的 `<modules>` 里、也不进 `./mvnw test`——
  单测锁契约（秒级、离线），夹具做真机验收（起进程、占端口），理由与用法见该目录的 README。
