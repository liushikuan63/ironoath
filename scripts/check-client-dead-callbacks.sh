#!/usr/bin/env bash
# 职责：客户端视图里声明的 on* 回调必须真的有人调用它（只赋值不调用＝画了按钮但点不动）。
#
# 为什么开这道门（2026-10-07，台账 #770）：`GiftPopupView.onBuy` 声明着、GameBootstrap 赋了值，
#   但全仓没有任何调用点 ⇒ 玩家点「立即购买」什么都不会发生，整条 B19 支付链在玩家侧不可达；
#   而当时 45 道静态门 + 1031 项客户端单测全绿 —— 发送口门数的是 GameApi 那一层，孤儿门数的是文件 import，
#   这一族（契约有列 → 流程带出 → **视图不画/不接**）此前没人守。
#   落地本门时它又抓到一条同族的：`QuestPanelView.onRefreshRequested` 只有声明与一次置 null，
#   从未被赋值也从未被调用 ⇒ 按本门给的处置删掉，不留装饰。
#
# ⚠️ 谓词的教训写在扫描器头注释里（第一版把跨行**方法参数名** `onClick` 当字段声明，一次误报 10 处）：
#   计数型谓词先做误报自查，别把门判红当成"抓到十个缺陷"。
#
# 可选输入口（不设时与默认扫描完全一致，check-gates-can-fail.sh 靠它们做零污染三读数）：
#   DEADCALL_SCAN_DIRS / DEADCALL_ALLOW_EXTRA   —— 见扫描器头注释
set -uo pipefail
cd "$(dirname "$0")/.."

node scripts/check-client-dead-callbacks.js
