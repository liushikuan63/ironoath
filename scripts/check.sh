#!/usr/bin/env bash
# 职责：CI 静态检查 = 分层纯净性 + 双端契约一致性 + 禁止施舍机制（B08 验收 10）
#       + Bot 无特权捷径（B11 验收 8）+ 红点无散落（B12 验收 2）
#       + 无绕支付（B15 验收 10）+ 首包体积（B16 验收 4）
#       + 埋点覆盖率（B16 验收 3：UI 动作清单比对，漏埋点=看板那一环永远为 0）
#       + 端点路径一致性（客户端绑的路径服务端必须存在，否则点一下就是 404）
#       + 收口清单表格形状（清单是唯一活得过会话的待修载体，而多出来的单元格会被渲染整格丢掉）
#       + 错误码唯一性与段位归属（码值重了客户端分不开，跑出错段等于把两个系统的号段混一起）
#       + 配置表装配（每张表都要有生产代码读它 —— 没人读的表就是"改了不生效、也不报错"的装饰品）。
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/check-layering.sh
bash scripts/check-contract-sync.sh
bash scripts/check-no-handout.sh
bash scripts/check-no-bot-privilege.sh
bash scripts/check-no-scattered-reddot.sh
bash scripts/check-no-payment-bypass.sh
bash scripts/check-package-size.sh
bash scripts/check-track-coverage.sh
bash scripts/check-endpoint-paths.sh
bash scripts/check-permission-bits.sh
bash scripts/check-config-refs.sh
bash scripts/check-checklist-table.sh
bash scripts/check-error-codes.sh
bash scripts/check-config-consumers.sh
echo "[check] 全部静态检查通过。"
