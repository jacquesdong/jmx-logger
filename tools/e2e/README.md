# 端到端验收夹具（tools/e2e）

这里放**真实的目标应用**，用来验收 jmx-logger 对 Spring Boot 1.5.6 / 2.7.18 的行为。

它不属于 jmx-logger 的构建（根 pom 的 `<modules>` 里没有它），也不进 `./mvnw test`：

- `src/test` 的单测跑在测试 JVM 内：秒级、离线、每次提交都跑，锁的是**契约**；
- 这里跑的是**真机**：真 Spring Boot 进程、真 JMX 端口、真 logback / actuator 端点。
  起进程、占端口、几十秒，手工或 CI 单独触发。

## 为什么要真实例

目标侧的东西只有真跑才知道。这个夹具已经抓到两件事：

1. **Spring Boot 2.2 起 `spring.jmx.enabled` 默认为 `false`**：只引 `spring-boot-starter-actuator`
   还不够，不打开它，端点**根本不会注册到 JMX**（`management.endpoints.jmx.exposure.include`
   的默认值虽然是 `*`，但没有 MBeanServer 可以暴露）。客户端原先"2.7 默认全暴露、无需配置"的文案是错的。
2. **Boot 2.7.18 的端点真实签名**：`configureLogLevel(java.lang.String, java.lang.String)`、
   `loggers()` / `loggerLevels(String)` 均返回 `java.util.Map`——第二参不是 `LogLevel` 枚举，
   返回值也不是 `CompositeData` / `TabularData`，所以客户端不需要任何类型转换兜底。

## 用法

```bash
tools/e2e/verify.sh 2.7.18                  # 自动起停 + 断言（推荐）
tools/e2e/verify.sh 1.5.6                   # 1.5.6 只跑 jmx / attach 两段（回归对照）

tools/e2e/run-target.sh 2.7.18 actuator     # 前台起一个目标，人工边看边试（Ctrl-C 结束）
```

`run-target.sh` 的模式：

| 模式 | 日志配置 | JMX 端口 | 用途 |
| --- | --- | --- | --- |
| `jmx`（默认） | 有 `<jmxConfigurator/>` | 开 | logback 通道：get / set / clear / reload |
| `attach` | 有 `<jmxConfigurator/>` | **不开** | 验证 `-p <pid>` 本地 attach |
| `actuator` | 无 `<jmxConfigurator/>` | 开 | `auto` 必须兜底到 actuator（仅 2.7.18） |
| `none` | 无 | 开 | 两条通道都没有时的报错路径（仅 2.7.18） |

端口按版本错开，可以两个版本同时开着：**1.5.6 → 19100，2.7.18 → 19200**（RMI 端口取 +1）。

## 前置条件

- **JDK 8**：1.5.6 只能跑在 JDK 8 上；2.7.18 也支持 JDK 8（本仓库开发环境就是 Temurin 8u504）。
- **本地 Maven 仓库**：1.5.6 与 2.7.18 的依赖已缓存时可离线构建；否则首次构建需要能访问 Maven 镜像。
- 目标以 **exploded classpath** 启动（不是 fat jar）：这样 `java.class.path` 里能看到
  `logback-classic` / `spring-boot-actuator` 的真实 jar，`doctor` 的 classpath 识别才有效。
  真实生产常用 fat jar，那时 `doctor` 不会打印 classpath 识别行——属已知限制。

## 覆盖的场景

`verify.sh` 依次断言：

1. **jmx 模式**：`doctor`（候选 MBean + MBeanInfo）、`get`、`get --json`、`set DEBUG`、`clear`；
2. **actuator 模式**（仅 2.7.18）：`doctor` 命中 `name=Loggers`；`get` / `set` / `clear` 走通；
   `reload` 退出码 1 并给出替代方案；同时把**目标侧真实签名**打印出来（每次都要看一眼）；
3. **none 模式**（仅 2.7.18）：退出码 1、报"没有可用的日志通道"、`doctor` 指出多半是没开
   `spring.jmx.enabled`；
4. **attach 模式**：`-p <pid>` 能直连一个**没开 JMX 端口**的目标。

失败时脚本以非 0 退出，并把实测输出打出来；目标日志留在 `tools/e2e/target-<版本>-<模式>.log`。
