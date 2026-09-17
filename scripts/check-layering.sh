#!/usr/bin/env bash
# 职责：CI 静态检查 —— 强制分层纯净性（B01 验收 8、9）。
# 规则来源：B00「分层依赖规则」与铁律 2「逻辑与容器分离」。
# 检查项：
#   1) game-common / game-core / game-battle 源码中不得出现框架 import（Spring / jakarta / cc / 数据库驱动）
#   2) 上述三个模块 pom.xml 中不得声明框架依赖（spring-*、mongo、redis、redisson）
#   3) game-config 只能依赖 game-common
#   4) 禁止 Math.random / java.util.Random / System.currentTimeMillis 出现在 core 层
set -euo pipefail
cd "$(dirname "$0")/.."

PURE_MODULES=(game-common game-core game-battle)
FORBIDDEN_IMPORT_REGEX='^[[:space:]]*import[[:space:]]+(org\.springframework|jakarta\.|com\.mongodb|org\.mongodb|org\.redisson|org\.springframework\.data)'
FORBIDDEN_POM_REGEX='<artifactId>(spring-[a-z-]*|spring-boot[a-z-]*|mongodb-driver[a-z-]*|redisson[a-z-]*|jakarta\.[a-z.-]*)</artifactId>'
FORBIDDEN_CALL_REGEX='\b(Math\.random\(|new[[:space:]]+(java\.util\.)?Random\b|java\.util\.Random\(|ThreadLocalRandom\.|new[[:space:]]+SecureRandom\b|System\.currentTimeMillis\()'

fail=0
report() { echo "[check-layering] $*"; }
err() { echo "[check-layering][FAIL] $*" >&2; fail=1; }

# 提取 pom 中 <dependencies> 段内的 game-* 依赖（排除 <dependencyManagement> 与模块自身的 artifactId）
project_module_deps() {
  awk '
    /<dependencyManagement>/ { inmgt = 1 }
    /<\/dependencyManagement>/ { inmgt = 0 }
    /<dependencies>/ { if (!inmgt) indeps = 1; next }
    /<\/dependencies>/ { indeps = 0 }
    indeps && match($0, /<artifactId>game-[a-z]+<\/artifactId>/) {
      s = substr($0, RSTART, RLENGTH)
      gsub(/<\/?artifactId>/, "", s)
      print s
    }
  ' "$1"
}

# 过滤掉注释行：grep -rn 输出为 文件:行号:内容，只锚定「:行号:」之后的内容起始，
# 避免 Windows 绝对路径里的盘符冒号干扰匹配
COMMENT_LINE_FILTER=':[0-9]+:[[:space:]]*(\*|//|/\*)'

for m in "${PURE_MODULES[@]}"; do
  dir="server/$m/src"
  [ -d "$dir" ] || { report "跳过 $m（无 src）"; continue; }

  # 1) 框架 import
  hits=$(grep -rnE "$FORBIDDEN_IMPORT_REGEX" "$dir" --include='*.java' || true)
  if [ -n "$hits" ]; then
    err "$m 存在框架依赖 import（纯 Java 层禁止）:"
    echo "$hits" >&2
  fi

  # 2) pom 框架依赖
  pom="server/$m/pom.xml"
  if [ -f "$pom" ]; then
    phits=$(grep -nE "$FORBIDDEN_POM_REGEX" "$pom" || true)
    if [ -n "$phits" ]; then
      err "$m/pom.xml 声明了框架依赖（纯 Java 层禁止）:"
      echo "$phits" >&2
    fi
  fi

  # 4) 禁用随机与系统时钟（必须走 Rng / TimeService，时间由参数传入）
  #    类注释里提到「禁止 Math.random()」属于文档说明，不能当成真实调用
  chits=$(grep -rnE "$FORBIDDEN_CALL_REGEX" "$dir" --include='*.java' \
    | grep -v '/test/' \
    | grep -vE "$COMMENT_LINE_FILTER" || true)
  if [ -n "$chits" ]; then
    err "$m 使用了被禁止的随机/时钟 API（应使用 Rng 与注入的 TimeService）:"
    echo "$chits" >&2
  fi
done

# 7) B05 验收 12：结算层禁止浮点类型。
#    game-common 不在扫描范围内 —— Rng.next() 按 B01 契约返回 double（仅供表现层，不参与结算），
#    FixedPoint 内部也用 BigDecimal 而非 double。真正必须零浮点的是战斗与玩法结算层。
FLOAT_MODULES=(game-battle game-core)
FLOAT_REGEX='\b(double|float|Double|Float)\b'
for m in "${FLOAT_MODULES[@]}"; do
  dir="server/$m/src/main"
  [ -d "$dir" ] || continue
  fhits=$(grep -rnE "$FLOAT_REGEX" "$dir" --include='*.java' \
    | grep -vE "$COMMENT_LINE_FILTER" || true)
  if [ -n "$fhits" ]; then
    err "$m 的结算代码出现了浮点类型（铁律 5：战斗与资源结算全程 long 定点数）:"
    echo "$fhits" >&2
  fi
done

# 8) B03 验收 9：服务端不得有任何常驻定时器用于业务结算（B00 陷阱 1、2；B03 禁止项）。
#    产出必须惰性结算，行军必须走延迟队列 —— 两者都不需要 @Scheduled / setInterval / setTimeout。
#    只扫 server 下的运行期模块（tools/ 是构建期 CLI，不受此约束）。
SCHED_REGEX='(@Scheduled|@EnableScheduling|setInterval[[:space:]]*\(|new[[:space:]]+Timer[[:space:]]*\(|newScheduledThreadPool|scheduleAtFixedRate|scheduleWithFixedDelay)'
# 命中点在同一行的 `//` 之后 —— 那是散文或被注释掉的代码，不是活的注解。
# 不排掉它的话，「解释为什么这里没有定时器」的注释会让检查自己变红（生成物的 def 注释就是这么写的），
# 而一条会假红的规则的下场通常是被人删掉，那才是真的把这一族放出去。
# 锚法与 COMMENT_LINE_FILTER 同一条：先认「:行号:」，避开 Windows 盘符里的冒号。
SCHED_AFTER_COMMENT_FILTER=':[0-9]+:.*//.*(Scheduled|setInterval|new[[:space:]]+Timer|scheduleAtFixedRate|scheduleWithFixedDelay)'
for m in game-common game-config game-core game-battle game-world game-social game-bot game-web; do
  dir="server/$m/src/main"
  [ -d "$dir" ] || continue
  shits=$(grep -rnE "$SCHED_REGEX" "$dir" --include='*.java' \
    | grep -vE "$COMMENT_LINE_FILTER" | grep -vE "$SCHED_AFTER_COMMENT_FILTER" || true)
  if [ -n "$shits" ]; then
    err "$m 存在常驻定时器/定时调度（产出必须惰性结算，行军必须走延迟队列）:"
    echo "$shits" >&2
  fi
done

# 3) game-config 只能依赖 game-common
cfg_pom="server/game-config/pom.xml"
if [ -f "$cfg_pom" ]; then
  bad=$(project_module_deps "$cfg_pom" | grep -v '^game-common$' || true)
  if [ -n "$bad" ]; then
    err "game-config 依赖了 game-common 之外的本项目模块: $bad"
  fi
fi

# 5) game-core 只能依赖 game-common
core_pom="server/game-core/pom.xml"
if [ -f "$core_pom" ]; then
  bad=$(project_module_deps "$core_pom" | grep -v '^game-common$' || true)
  if [ -n "$bad" ]; then
    err "game-core 依赖了 game-common 之外的本项目模块: $bad"
  fi
fi

# 6) game-battle 只能依赖 game-common
battle_pom="server/game-battle/pom.xml"
if [ -f "$battle_pom" ]; then
  bad=$(project_module_deps "$battle_pom" | grep -v '^game-common$' || true)
  if [ -n "$bad" ]; then
    err "game-battle 依赖了 game-common 之外的本项目模块: $bad"
  fi
fi

if [ "$fail" -ne 0 ]; then
  report "分层检查未通过。"
  exit 1
fi
report "分层纯净性检查通过：${PURE_MODULES[*]} 无任何框架依赖。"
