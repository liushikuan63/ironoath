#!/usr/bin/env bash
# 职责：客户端类型检查进门禁。
#
# 为什么单独立一道门（而不是"记得手工跑"）：`client/tests/*.test.ts` 跑的是 **tsc 编译后的产物**，
# 而 `tsconfig.test.json` 只覆盖测试源那一片 —— 场景层（`assets/scripts/scene/**`）里一处类型错
# 既不会让单测变红，也不会让构建变红，只有 `npm run typecheck`（headless + testcheck 两份工程）抓得到。
# 现场证据：B26 S6 那一轮 `RowAction` 少加一个成员，`check.sh` 一路退 0，
# 是同轮手工跑的 `npm run typecheck` 报出 `SocialPanelView.ts` 两处类型错。
#
# 反空转：两份 tsconfig 少任何一份都按失败处理 —— 门"跑了但只查半个工程"比不跑更危险，
# 因为它会给出一个绿色的假凭证。
set -euo pipefail
cd "$(dirname "$0")/.."

for cfg in client/tsconfig.headless.json client/tsconfig.testcheck.json; do
  if [ ! -f "$cfg" ]; then
    echo "[check-client-typecheck] 缺少 $cfg —— typecheck 会只查半个工程，这道门就是假绿"
    exit 1
  fi
done

cd client

# 为什么直接调 tsc 而不是 `npm run typecheck`：
#   本机 npm 10.5.2 在 **Git Bash / PowerShell** 下跑任何 script 都会炸
#   （`npm ERR! Cannot read properties of undefined (reading 'stdin')`，
#    实测：主仓 client/、一个只有 package.json 的空目录里都复现 ⇒ 与本仓无关，
#    是这台机器的 npm 安装问题；同一个 script 在 `cmd //c` 下正常）。
#   `npm run` 只是把 `typecheck` 这行脚本跑起来，而它跑的就是两条 tsc ——
#   直接调 tsc 跑的是**同一条命令**，少一层坏掉的壳。
# ⚠️ 判据不变：两份 tsconfig 都要过（headless --noEmit 与 testcheck），
#    少一份就是「门跑了但只查半个工程」，那比不跑更危险。
TSC="./node_modules/.bin/tsc"
if [ ! -x "$TSC" ] && [ ! -f "$TSC" ]; then
  echo "[check-client-typecheck][FAIL] 找不到 $TSC —— 先装 client 依赖（client/node_modules）" >&2
  exit 1
fi
"$TSC" -p tsconfig.headless.json --noEmit
"$TSC" -p tsconfig.testcheck.json
echo "[check-client-typecheck] 客户端两个工程都过 tsc --noEmit。"
