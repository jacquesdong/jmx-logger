#!/usr/bin/env bash
#
# 端到端验收：起目标应用 → 跑一串 jmx-logger 命令 → 断言关键输出 → 停掉进程。
#
# 用法：tools/e2e/verify.sh [版本]     版本默认 2.7.18；1.5.6 只跑 jmx / attach 两段
#
# 先打主项目的 fat jar，再拿它去连夹具 —— 验的是"用户实际拿到的那个 jar"，
# 而不是 IDE 里跑的 class。
#
# 与 ./mvnw test 的分工：单测锁契约（秒级、离线、每次提交都跑）；
# 这个脚本做真机验收（起进程、占端口、几十秒，手工或 CI 单独触发）。
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/../.." && pwd)
version=${1:-2.7.18}
logger="com.jmxlogger.e2e.TargetApp"

case "$version" in
  1.5.6)  port=19100 ;;
  2.7.18) port=19200 ;;
  *) echo "未知版本：$version（支持 1.5.6 / 2.7.18）" >&2; exit 2 ;;
esac

jar="$root/target/jmx-logger.jar"

# 目标工程优先离线构建；本地仓库缺依赖时自动联网补（首次构建需要网络）
mvn_target() {
  if ! "$root/mvnw" -o -q -f "$here/boot-$version/pom.xml" "$@" 2>/dev/null; then
    echo "    本地仓库缺依赖，改为联网构建（首次需要网络）……"
    "$root/mvnw" -q -f "$here/boot-$version/pom.xml" "$@"
  fi
}
pass=0
fail=0
rc=0
out=""
target_pid=""
target_log=""

ok()  { echo "  ✓ $1"; pass=$((pass + 1)); }
bad() { echo "  ✗ $1"; printf '      %s\n' "$2"; fail=$((fail + 1)); }

check_exit() { # 描述 期望 实际
  if [ "$2" = "$3" ]; then ok "$1（退出码 $3）"; else bad "$1" "期望退出码 $2，实际 $3"; fi
}

check_contains() { # 描述 输出 子串
  case "$2" in
    *"$3"*) ok "$1" ;;
    *) bad "$1" "输出里找不到「$3」"
       printf '%s\n' "$2" | sed 's/^/      /' ;;
  esac
}

check_not_contains() { # 描述 输出 子串
  case "$2" in
    *"$3"*) bad "$1" "输出里不该出现「$3」"
            printf '%s\n' "$2" | sed 's/^/      /' ;;
    *) ok "$1" ;;
  esac
}

# 跑一条 jmx-logger 命令：输出进 $out，退出码进 $rc
capture() {
  rc=0
  out=$(java -jar "$jar" "$@" 2>&1) || rc=$?
}

start_target() { # 模式：jmx / actuator / none / attach
  local mode="$1"
  target_log="$here/target-$version-$mode.log"
  : > "$target_log"
  nohup "$here/run-target.sh" "$version" "$mode" >"$target_log" 2>&1 &
  target_pid=$!

  local i
  for i in $(seq 1 120); do
    if grep -q "Started TargetApp" "$target_log" 2>/dev/null; then
      sleep 1   # 启动日志出现后 JMX 连接器通常已就绪，留一点余量再连
      return 0
    fi
    if ! kill -0 "$target_pid" 2>/dev/null; then
      echo "目标进程意外退出，日志尾部（$target_log）：" >&2
      tail -n 30 "$target_log" >&2
      exit 1
    fi
    sleep 0.5
  done
  echo "等目标启动超时（$target_log）" >&2
  exit 1
}

stop_target() {
  if [ -n "$target_pid" ] && kill -0 "$target_pid" 2>/dev/null; then
    kill "$target_pid" 2>/dev/null || true
    wait "$target_pid" 2>/dev/null || true
  fi
  target_pid=""
}

trap stop_target EXIT

echo "==> 构建 jmx-logger fat jar"
"$root/mvnw" -o -q package -DskipTests
echo "==> 构建 e2e 目标（boot-$version，exploded classpath）"
mvn_target compile
mvn_target dependency:build-classpath -Dmdep.outputFile="$here/boot-$version/target/cp.txt"

base=(-s "127.0.0.1:$port")

echo
echo "== [1] jmx 模式：logback 通道（doctor / get / set / --json / clear）"
start_target jmx

capture "${base[@]}" doctor
check_exit "doctor" 0 "$rc"
check_contains "doctor 列出候选 MBean" "$out" "候选 MBean"
check_contains "doctor 打印 MBeanInfo（属性与操作签名）" "$out" "LoggerList"

capture "${base[@]}" get --json
check_exit "get --json（列出全部）" 0 "$rc"
check_contains "--json 输出 loggers 数组" "$out" '"loggers":['
check_contains "--json 带统计字段 listed" "$out" '"listed":'

capture "${base[@]}" get "$logger"
check_exit "get <logger>" 0 "$rc"
check_contains "get 读到目标 logger" "$out" "TargetApp"

capture "${base[@]}" set "$logger" DEBUG
check_exit "set DEBUG" 0 "$rc"
capture "${base[@]}" get "$logger"
check_contains "set 之后读到 DEBUG" "$out" "DEBUG"

capture "${base[@]}" clear "$logger"
check_exit "clear" 0 "$rc"
capture "${base[@]}" get "$logger"
check_not_contains "clear 之后不再是 DEBUG" "$out" "DEBUG"

if [ "$version" = "2.7.18" ]; then
  stop_target
  echo
  echo "== [2] actuator 模式：目标没有 <jmxConfigurator/>，auto 必须兜底到 actuator"
  start_target actuator

  capture "${base[@]}" doctor
  check_exit "doctor" 0 "$rc"
  check_contains "doctor 命中 actuator 的 loggers 端点" "$out" "Loggers"
  echo "  · 目标侧真实签名（关键验证点：configureLogLevel 第二参到底是什么类型）"
  printf '%s\n' "$out" \
    | grep -E "configureLogLevel|setLogLevel|loggers\(\)|getLoggers\(\)|loggerLevels|getLogger\(" \
    | sed 's/^/      /' || true

  capture "${base[@]}" get "$logger"
  check_exit "get（兜底 actuator）" 0 "$rc"
  check_contains "get 拿到目标 logger" "$out" "TargetApp"

  capture "${base[@]}" set "$logger" DEBUG
  if [ "$rc" = "0" ]; then
    ok "set DEBUG 经 actuator 成功（级别参数能在本地构造出来）"
    capture "${base[@]}" clear "$logger"
    check_exit "clear 经 actuator" 0 "$rc"
  else
    # 这正是要人工确认的分支：2.7 的 configureLogLevel 第二参可能是本地没有的
    # LogLevel 枚举。实现按 MBeanInfo 现场构造，构造不出来会明确报错并建议换通道。
    echo "  ! set 未成功（退出码 $rc）—— 若报错说「签名需要本地没有的类型」，属已知分支："
    printf '%s\n' "$out" | sed 's/^/      /'
  fi

  capture "${base[@]}" reload
  check_exit "actuator 下 reload 应失败" 1 "$rc"
  check_contains "reload 给出 scan=true 的替代方案" "$out" 'scan="true"'

  stop_target
  echo
  echo "== [3] none 模式：两条通道都不提供"
  start_target none
  capture "${base[@]}" get
  check_exit "get 应失败" 1 "$rc"
  check_contains "报错说明没有可用通道" "$out" "没有可用的日志通道"
  check_contains "并给出目标侧该加的配置" "$out" "jmxConfigurator"

  # classpath 里有 actuator 却没注册端点 = 多半没开 spring.jmx.enabled（Boot 2.2 起默认 false）
  capture "${base[@]}" doctor
  check_exit "doctor" 0 "$rc"
  check_contains "doctor 指出端点没注册与 spring.jmx.enabled 有关" "$out" "spring.jmx.enabled"
fi

stop_target
echo
echo "== [4] attach 模式：目标未开 JMX 端口，用 -p <pid> 直连"
start_target attach
capture -p "$target_pid" get "$logger"
check_exit "get -p <pid>" 0 "$rc"
check_contains "attach 也能读到目标 logger" "$out" "TargetApp"
capture -p "$target_pid" doctor
check_contains "doctor 说明走的是本地 attach" "$out" "本地 attach"
stop_target

echo
echo "== 结果：$pass 通过 / $fail 失败"
echo "   目标日志留在 $here/target-$version-*.log"
[ "$fail" -eq 0 ]
