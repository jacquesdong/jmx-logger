# 更新日志

jmx-logger 的对外可见变化记录在此。日期用 `YYYY-MM-DD`，版本遵循语义化版本；
条目只写使用者能感知的部分，内部重构与测试细节见 `git log`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [1.0.1] - 2026-09-20

### 新增

- **`doctor` 子命令**：诊断目标 JVM 的 JMX 连接与可用通道（只读）——列出候选 MBean、
  实际端点签名、当前走的是远程 `-s` 还是本地 attach，并给出目标侧该补的配置。
- **`clear` 命令**：清除 logger 自身配置的级别、恢复继承父 logger，不再需要用 `set` 传空值。
- **`-p/--pid` 本地 attach 通道**：目标没开 JMX 端口时，直接连本机进程号并现场启动管理代理；
  指定后优先于 `-s`。
- **`-t/--target` 通道选择**：`auto`（默认，先 logback、缺失时兜底 actuator）/ `logback` / `actuator`。
- **`--object-name`**：直接指定目标 MBean 的 ObjectName，多 LoggerContext（多个 `Name=...`）时点名。
- **`--json`**：`get` 的机器可读输出，字段与表格列一一对应（手写序列化，不引第三方依赖）。
- **`--all`**：`get` 列出全部 logger（默认只列单独配了级别的）。
- **`--effective`**：`get` 附带"实际生效级别"列，取值由目标侧计算。
- **`--timeout`**：连接超时（秒），避免 RMI 握手无限等待；默认 10，`0` 表示不限制。
- **`-v/--verbose`**：出错时打印完整堆栈（默认只打印一行错误原因）。
- `--password` 不带取值时交互式读取（不回显），完全省略时回退到环境变量 `JMX_LOGGER_PASSWORD`。
- `-V` 输出构建信息：`git describe` + 提交时间（无 `.git` 的构建回退到 pom 版本号）。

### 变更（含破坏性变更）

- **`-p` 的含义变了**：1.0.0 的 `-p` 是 `--password`；现在 `-p/--pid` 表示本地进程号，
  认证参数改为长选项 `--username` / `--password`（`-u` 已移除）。这是升级时最容易踩的一处。
- **`get` 的默认输出收窄为两列**（Logger / Level）：logback 通道下每个 logger 的生效级别
  都要一次独立的远程调用，是 `get` 在大目标上的主要耗时，故默认不查；要看生效级别加 `--effective`。
  按列位置解析输出的脚本需要加 `--effective` 才能拿回原来的三列。
- **`get` 默认只列单独配了级别的 logger**：未配置级别的行不再出现（看全量加 `--all`）。
- `--help` 输出改为按语义分组的缩写概要，选项按声明顺序排布（不再字母序）。
- 退出码统一收口：成功 `0`、运行时错误 `1`、用法错误 `2`。
- fat jar 产物名固定为 `jmx-logger.jar`（不含版本号），脚本无需随版本改动。

### 修复

- logback 通道的级别语义按真实 JMX 行为对齐，正确区分"未配置级别"与显式设置的值。
- 修正文档与实现不一致处：MBean 名改用完整 ObjectName（原先写的是 `Type` 属性取值）、
  `-V` 示例与 pom 兜底版本、actuator 端点签名的实测结论。

### 移除

- `get` 输出末尾的"Level 为空表示该 logger 未单独配置级别，继承父 logger"脚注
  （语义改记在 README）。

### 已知限制

- Spring Boot 2.7 的 actuator 通道按官方文档实现、单测用桩 MBean 覆盖，**未在真实目标上实测**；
  2.7 目标建议先跑 `doctor` 看实际签名（`configureLogLevel` 的第二个参数可能是 `String`，
  也可能是本地 classpath 里没有的 `LogLevel` 枚举）。
- Boot 3.x / Logback 1.4+ 走 HTTP 通道的方案尚未支持：JMX 下 logback 已不再暴露
  `JMXConfigurator`，届时需要自定义 endpoint 或 HTTP 接口（1.0.1 只做预留说明，不含实现）。

## [1.0.0] - 2026-09-06

首个版本。

- `get` / `set` / `reload` 三个子命令，通过 `-s host:port` 连接 Logback 的
  `JMXConfigurator`（默认 `127.0.0.1:19000`）。
- `-u/--username`、`-p/--password` 认证参数。
- 可执行 fat jar：`jmx-logger.jar`。

[1.0.1]: https://github.com/jacquesdong/jmx-logger/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/jacquesdong/jmx-logger/releases/tag/v1.0.0
