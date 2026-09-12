#!/usr/bin/env bash
# 职责：CI 静态检查 —— 双端契约一致性（B00 跨语言一致性策略第 3 条）。
# 做法：用 tools/config-gen 依据 contract/proto/*.schema.json 重新生成 Java DTO 与 TS interface
#       到临时目录，与仓库中已提交的生成物逐字节 diff。不一致即失败。
# 依赖：JDK 17、Maven。
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

# 临时目录必须每次独有（并发跑会互删，症状是看不懂的一句 "No such file or directory"），
# 但也**不能用 mktemp 的绝对路径**：Git Bash 的 /tmp 交给 Windows 侧的 JVM 会被解析到别处，
# TS 生成结果就落错目录，表现为"所有 TS 文件都 Only in committed" —— 又一次假红。
# 所以：仓库内相对路径 + 进程号唯一。
TMP=".tmp-contract-check.$$"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/java" "$TMP/ts"

echo "[check-contract-sync] 依据 contract/proto 重新生成契约代码 ..."
mvn -q -f server/pom.xml -pl tools/config-gen -am -Dmaven.test.skip=true install
mvn -q -f server/pom.xml -pl tools/config-gen exec:java \
  -Dexec.mainClass=com.ironoath.codegen.ContractGenMain \
  -Dexec.classpathScope=compile \
  -Dexec.args="--schema-dir=contract/proto --config-dir=contract/config --java-out=$TMP/java --ts-out=$TMP/ts --java-cfg-out=$TMP/java-cfg --ts-cfg-out=$TMP/ts-cfg"

fail=0
diff_dir() {
  local label="$1" gen="$2" committed="$3"
  if [ ! -d "$gen" ]; then
    # 与"内容不一致"分开报：前者是生成没跑出来（或临时目录被人删了），
    # 让人去 npm run gen 只会浪费时间
    echo "[check-contract-sync][FAIL] $label 的生成结果不在 $gen —— 生成步骤没跑出来，不是不同步" >&2
    fail=1
    return
  fi
  if [ ! -d "$committed" ]; then
    echo "[check-contract-sync][FAIL] $label 生成物目录不存在: $committed" >&2
    fail=1
    return
  fi
  # 忽略 *.meta：那是 Cocos 编辑器的资产数据库产物（uuid/导入设置），不是本生成器的输出。
  # 不排除的话，编辑器一打开工程就会给生成物补 .meta，此后 CI 永远报"不同步"，
  # 而真正该被发现的"改了 schema 忘跑 gen"反而会被这堆噪音淹没。
  if ! diff -r -q -x '*.meta' "$gen" "$committed" >/dev/null 2>&1; then
    echo "[check-contract-sync][FAIL] $label 与契约不同步，请重新运行 'npm run gen' 并提交:" >&2
    diff -r -x '*.meta' "$gen" "$committed" 2>&1 | head -60 >&2
    fail=1
  else
    echo "[check-contract-sync] $label 同步 OK"
  fi
}

diff_dir "Java DTO" \
  "$TMP/java" \
  "server/game-web/src/main/java/com/ironoath/web/dto/generated"

diff_dir "TS Protocol" \
  "$TMP/ts" \
  "client/assets/scripts/net/generated"

diff_dir "Java 配置表类型" \
  "$TMP/java-cfg" \
  "server/game-config/src/main/java/com/ironoath/config/cfg"

diff_dir "TS 配置表类型" \
  "$TMP/ts-cfg" \
  "client/assets/scripts/config/generated"

if [ "$fail" -ne 0 ]; then exit 1; fi
echo "[check-contract-sync] 双端契约一致性检查通过。"
