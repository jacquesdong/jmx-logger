# JMX Logger CLI 实施计划

## 目标
编写一个命令行工具 `jmx-logger`，通过 JMX 远程连接到运行中的 Java 应用（使用 Logback），实现：
1. **get** — 查看 Logger 的配置级别与生效级别（支持列出全部、按名称过滤、`-r/--recursive` 递归列出子 logger）
2. **set** — 修改指定 Logger 的级别
3. **reload** — 重新加载 Logback 配置（默认配置或指定文件路径）

## 仓库调研
- 当前工作目录 `/Users/Jacques/Work/trae/work/jmx-logger` 为空（全新项目）。
- 用户已确认：日志框架为 **Logback**，构建工具 **Maven**，程序形式为**独立进程（CLI）远程连接目标 JVM**。

## 核心技术方案

### 远程 JMX 连接
- 使用 JDK 自带 `javax.management.remote.*`，无需额外依赖。
- 由 `-s host:port` 构造 `JMXServiceURL`：`service:jmx:rmi:///jndi/rmi://<host>:<port>/jmxrmi`。
- 若提供 `-u/-p`，则在 environment map 中放入 `JMXConnector.CREDENTIALS` 进行鉴权。

### Logback MBean 调用
- 目标应用的 logback.xml 必须启用 `<jmxConfigurator/>`，才会注册 `JMXConfigurator` MBean。
- MBean ObjectName 形如 `ch.qos.logback.classic:Name=<contextName>,Type=ch.qos.logback.classic.jmx.JMXConfigurator`。
- CLI 启动时通过 `queryNames("ch.qos.logback.classic:Type=ch.qos.logback.classic.jmx.JMXConfigurator,*", null)` 自动发现 MBean（取第一个匹配），若未找到则报错并提示目标需启用 `<jmxConfigurator/>`。
- 通过 `MBeanServerConnection.invoke(objectName, operation, params, signature)` 调用以下操作（**客户端无需引入 Logback 依赖**）：
  - **Attribute** `LoggerList`（`java.util.List`）→ 全部已存在的 logger 名称（用 `getAttribute` 获取，而非 invoke）
  - `getLoggerLevel(String)` → `String`（配置级别，可能为 null）
  - `getLoggerEffectiveLevel(String)` → `String`（生效级别）
  - `setLoggerLevel(String, String)` → void
  - `reloadDefaultConfiguration()` → void
  - `reloadByFileName(String fileUrl)` → void（参数须为 URL 字符串）

### CLI 框架
- 使用 **picocli**（`info.picocli:picocli`）实现子命令与全局参数。
- 顶层命令持有全局参数 `-s/--server`、`-u/--username`、`-p/--password`，子命令 `get` / `set` / `reload`。

### 命令语义
1. **get**
   - `jmx-logger get`（无 name）：列出全部 logger 及其 effective level。
   - `jmx-logger get <name>`：打印该 logger 的 configured level 与 effective level。
   - `jmx-logger get <name> -r`（`--recursive`）：递归打印 `<name>` 及其所有子 logger（名称以 `<name>` 开头，子 logger 用 `.` 分隔），类似文件系统递归。
   - 输出对齐表格，含 `Logger` / `Level` / `Effective` 三列。
2. **set**
   - `jmx-logger set <name> <level>`：调用 `setLoggerLevel(name, level)`。level 合法值：`TRACE/DEBUG/INFO/WARN/ERROR/ALL/OFF`（大小写不敏感）。
3. **reload**
   - `jmx-logger reload`（无路径参数）：调用 `reloadDefaultConfiguration()`，恢复默认配置。
   - `jmx-logger reload <file-path>`：直接将文件路径传给 `reloadByFileName(filePath)`。Logback 内部会 `new File(filePath)` 校验存在性并自行转为 URL 加载。注意：路径是**目标 JVM** 上的路径。

## 文件与模块
```
jmx-logger/
├── pom.xml                          # Maven 构建，依赖 picocli，打包可执行 fat-jar（maven-shade-plugin）
└── src/main/java/com/jmxlogger/
    ├── JmxLoggerCli.java            # 顶层命令：全局参数 + 子命令注册 + main 入口
    ├── JmxClient.java               # 封装 JMX 连接、MBean 发现、操作调用
    ├── GetCommand.java              # get 子命令
    ├── SetCommand.java              # set 子命令
    └── ReloadCommand.java           # reload 子命令
```

## 实现步骤（依赖顺序）
1. 创建 `pom.xml`：groupId/artifactId、Java 版本、picocli 依赖、`maven-shade-plugin` 生成带 `Main-Class` 的可执行 jar。
2. 实现 `JmxClient.java`：
   - `connect(server, user, pass)` 建立 `MBeanServerConnection`。
   - `findConfiguratorObjectName()` 自动发现 JMXConfigurator MBean。
   - 封装 `getLoggerList()`、`getLoggerLevel(name)`、`getLoggerEffectiveLevel(name)`、`setLoggerLevel(name, level)`、`reloadDefaultConfiguration()`、`reloadByFile(path)`。
   - 统一异常处理与错误提示。
3. 实现 `JmxLoggerCli.java`：picocli `@Command` 顶层类，全局字段，`main` 调用 `CommandLine`。
4. 实现三个子命令：`GetCommand`、`SetCommand`、`ReloadCommand`，各自注入全局参数并调用 `JmxClient`。
5. 输出格式化：对齐表格（`get`）、成功/失败提示（`set`/`reload`）。

## 依赖与注意事项
- **picocli**：`info.picocli:picocli:4.7.6`（稳定版）。
- **目标端前置条件**：目标应用 logback.xml 必须包含 `<jmxConfigurator/>`，否则 CLI 无法发现 MBean。计划在错误提示中明确告知。
- **reloadByFileName 的路径语义**：传入的 file-path 直接作为 `reloadByFileName` 的参数（文件路径，非 URL），由**目标 JVM** 通过 `new File(path)` 读取，因此路径必须在目标机器上有效。需在帮助信息中说明。
- **JMX URL 形式**：采用 RMI connector 标准 URL `service:jmx:rmi:///jndi/rmi://host:port/jmxrmi`，适用于通过 `-Dcom.sun.management.jmxremote.port` 暴露的 JMX。
- **安全性**：密码以命令行明文传递，仅建议在可信环境使用。

## 验证
- `mvn package` 构建成功，生成可执行 jar。
- 用一个内嵌 Logback + `<jmxConfigurator/>` 的简单测试应用启动（带 JMX 远程端口），验证：
  - `jmx-logger get` 列出 logger 与级别。
  - `jmx-logger get <name>` 正确显示 configured/effective level。
  - `jmx-logger get <name> -r` 递归列出子 logger。
  - `jmx-logger set <name> DEBUG` 后再次 get 级别已变更。
  - `jmx-logger reload` 恢复默认配置。
  - `jmx-logger reload /path/to/logback.xml` 按文件重新加载。
- 对未启用 `<jmxConfigurator/>` 的目标，验证给出清晰错误。

## 风险与处理
- **MBean 未注册**：目标应用未加 `<jmxConfigurator/>` → 捕获 `InstanceNotFoundException`/查询为空，提示用户在 logback.xml 中添加 `<jmxConfigurator/>`。
- **网络/鉴权失败**：捕获 `IOException`、`SecurityException`，输出友好错误并以非零退出码退出。
- **reload 文件路径不存在（目标端）**：`reloadByFileName` 会抛出 `JoranException`，透传错误信息。
- **contextName 非 default**：通过 `queryNames` 模糊匹配 Type，不硬编码 Name，支持任意 contextName。
