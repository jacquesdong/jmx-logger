#!/usr/bin/env bash
#
# 起一个 e2e 目标应用（真实 Spring Boot + logback），供 jmx-logger 做端到端验证。
#
# 用法：tools/e2e/run-target.sh [版本] [模式]
#   版本  1.5.6 | 2.7.18（默认 2.7.18）
#   模式  jmx       配 <jmxConfigurator/> + 开 JMX 端口            （默认）
#         attach    配 <jmxConfigurator/>，但**不开 JMX 端口**     —— 验证 -p/--pid
#         actuator  不配 <jmxConfigurator/>，靠 actuator 端点       —— 仅 2.7.18
#         none      两条通道都不提供                                —— 仅 2.7.18
#
# 前台运行（Ctrl-C 结束）。自动起停 + 断言请用 tools/e2e/verify.sh。
# 端口按版本错开，两个版本可以同时开着：1.5.6 -> 19100，2.7.18 -> 19200。
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../.." && pwd)
version=${1:-2.7.18}
mode=${2:-jmx}

case "$version" in
  1.5.6)  port=19100 ;;
  2.7.18) port=19200 ;;
  *) echo "未知版本：$version（支持 1.5.6 / 2.7.18）" >&2; exit 2 ;;
esac

if [ "$version" = "1.5.6" ] && { [ "$mode" = "actuator" ] || [ "$mode" = "none" ]; }; then
  echo "1.5.6 夹具只提供 jmx / attach 模式（1.5 的 actuator 通道早已在真机实测过）" >&2
  exit 2
fi

case "$mode" in
  jmx|attach)    logging_config="classpath:logback-jmx.xml" ;;
  actuator|none) logging_config="classpath:logback-plain.xml" ;;
  *) echo "未知模式：$mode（jmx / attach / actuator / none）" >&2; exit 2 ;;
esac

project="$here/boot-$version"
classes="$project/target/classes"
cp_file="$project/target/cp.txt"

# 目标工程优先离线构建；本地仓库缺依赖时自动联网补（首次构建需要网络）
mvn_target() {
  if ! "$root/mvnw" -o -q -f "$project/pom.xml" "$@" 2>/dev/null; then
    echo "    本地仓库缺依赖，改为联网构建（首次需要网络）……"
    "$root/mvnw" -q -f "$project/pom.xml" "$@"
  fi
}

# 用 exploded classpath 启动（而不是 fat jar）：这样目标 JVM 的 java.class.path 里能看到
# logback-classic / spring-boot-actuator 的真实 jar —— doctor 正是据此识别版本、
# 以及"引了 actuator 却没开 spring.jmx.enabled"这类情况。
# 真实生产常用 fat jar，那时 java.class.path 只有那一个 jar，classpath 识别会失效（已知限制）。
if [ ! -d "$classes" ] || [ ! -f "$cp_file" ]; then
  echo "==> 首次构建目标工程（之后复用）：$project"
  mvn_target compile
  mvn_target dependency:build-classpath -Dmdep.outputFile="$cp_file"
fi

# HTTP 端口用 0（随机）：这个夹具不需要 HTTP，只是别和本机 8080 抢
runtime_opts=(-Dserver.port=0 -Dlogging.config="$logging_config")
if [ "$mode" != "attach" ]; then
  runtime_opts+=(
    -Dcom.sun.management.jmxremote
    -Dcom.sun.management.jmxremote.port="$port"
    -Dcom.sun.management.jmxremote.rmi.port="$((port + 1))"
    -Dcom.sun.management.jmxremote.authenticate=false
    -Dcom.sun.management.jmxremote.ssl=false
    -Djava.rmi.server.hostname=127.0.0.1
  )
fi
if [ "$mode" = "actuator" ]; then
  # 关键：Spring Boot 2.2 起 spring.jmx.enabled 默认为 false。
  # 不开它，actuator 端点**根本不会注册到 JMX**（management.endpoints.jmx.exposure.include
  # 的默认值确实是 *，但没有 MBeanServer 可以暴露）——这是实测踩出来的坑。
  runtime_opts+=(-Dspring.jmx.enabled=true)
fi
if [ "$mode" = "none" ]; then
  # 刻意"两条通道都不给"：不设 spring.jmx.enabled，也不配 <jmxConfigurator/>
  runtime_opts+=(-Dmanagement.endpoints.jmx.exposure.include=health)
fi

echo "==> boot-$version（mode=$mode）"
if [ "$mode" = "attach" ]; then
  echo "    JMX 端口：未开启 —— 用 jmx-logger -p <pid> 连"
else
  echo "    JMX 端口：$port —— jmx-logger -s 127.0.0.1:$port <命令>"
fi
echo "    日志配置：$logging_config"
echo

exec java "${runtime_opts[@]}" -cp "$classes:$(cat "$cp_file")" com.jmxlogger.e2e.TargetApp
