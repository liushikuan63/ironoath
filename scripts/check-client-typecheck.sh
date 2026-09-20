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
npm run typecheck
echo "[check-client-typecheck] 客户端两个工程都过 tsc --noEmit。"
