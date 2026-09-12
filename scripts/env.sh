# 职责：统一构建环境探测（JDK 17 定位）。被其它 scripts/*.sh 与根 package.json 引用。
# 依赖：无。本机默认 JAVA_HOME 可能是 JDK 8，本项目强制 Java 17。

set -euo pipefail

# 候选 JDK 17 路径，按优先级排列
_JDK17_CANDIDATES=(
  "/d/Java/jdk/microsoft-jdk-17"
  "/c/Program Files/Microsoft/jdk-17"
  "/c/Program Files/Eclipse Adoptium/jdk-17"
  "/c/Program Files/Java/jdk-17"
)

_java_major() {
  "$1" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

resolve_jdk17() {
  # 已指向 17 则直接用
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    if [ "$(_java_major "$JAVA_HOME/bin/java")" = "17" ]; then
      echo "$JAVA_HOME"
      return 0
    fi
  fi
  for c in "${_JDK17_CANDIDATES[@]}"; do
    if [ -x "$c/bin/java" ] && [ "$(_java_major "$c/bin/java")" = "17" ]; then
      echo "$c"
      return 0
    fi
  done
  echo "ERROR: 未找到 JDK 17。请安装后设置 JAVA_HOME。" >&2
  return 1
}

export IRONOATH_JDK17="$(resolve_jdk17)"
export JAVA_HOME="$IRONOATH_JDK17"
export PATH="$JAVA_HOME/bin:$PATH"

# 铁律 10 要求中文日志。Windows 控制台默认 GBK，不强制 UTF-8 会输出乱码。
# exec:java 默认与 Maven 同 JVM，所以这里设置一次即可覆盖代码生成器的输出。
export MAVEN_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 ${MAVEN_OPTS:-}"

# 项目根目录（scripts/ 的上一级）
export IRONOATH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
