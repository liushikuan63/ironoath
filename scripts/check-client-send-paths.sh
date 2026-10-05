#!/usr/bin/env bash
# 职责：客户端 `GameApi` 的**每一个发送口都必须有生产调用点** —— 「生产零调用点」读数必须为 0。
#
# 为什么这一族要进门（2026-10-06 升，第 43 道）：它已经咬过三次 ——
#   `connectSocket` 推送整条是死的 / 出征与集结四条发送口零调用点 / 商店货架玩家看不见。
#   而「445 条用例 + `check.sh` 全绿」期间**一条都不报**：单测只测"方法本身能不能跑通"，
#   不测"有没有人点它"（AGENTS.md §四 第 2 条「判定写了没接上」的客户端那一半）。
#   报告器当年不做门是因为缺口有几十个，做成门会把真缺陷埋进噪声里；
#   现跑「生产零调用点 = 0」⇒ 见底，`客户端发送口缺口清单.md:126` 自述的进门条件已达成。
#
# ⚠️ 两个读数都是判据，缺一个就成假绿：
#   ① 「GameApi 方法」必须 > 0 —— 解析失败、客户端源码被移走、报告器改名，
#      都会让「零调用点」读成 0，那不是"没有缺口"而是"什么都没量到"；
#   ② 「生产零调用点」必须 = 0，且违规时要把方法名与 `GameApi.ts:行号` 一起打出来（否则没法定位）。
#
# 数据来自 `node tools/report-client-send-paths.mjs`（它自己永远退 0，是给人读的报告器；判红由本门做）。
# 可选输入口（**透传给报告器，不设时逐字节等同改动前**，harness 靠它们做零污染三读数）：
#   SENDPATHS_CLIENT / SENDPATHS_TESTS / SENDPATHS_TOOLS / SENDPATHS_API
#   SENDPATHS_REPORT  指到一个"现成报告文本"文件时**不再跑 node**（只给自检与离线复算用）
set -uo pipefail
cd "$(dirname "$0")/.."

REPORT=""
if [ -n "${SENDPATHS_REPORT:-}" ]; then
  if [ ! -f "$SENDPATHS_REPORT" ]; then
    echo "[send-paths][FAIL] 报告文件不存在：$SENDPATHS_REPORT（fail-closed，不静默跳过）" >&2
    exit 1
  fi
  REPORT="$(cat "$SENDPATHS_REPORT")"
else
  if ! REPORT="$(node tools/report-client-send-paths.mjs 2>&1)"; then
    echo "[send-paths][FAIL] 报告器跑失败了（不是缺口，是量具没架起来）：" >&2
    printf '%s\n' "$REPORT" | head -5 >&2
    exit 1
  fi
fi

FIRST_LINE="$(printf '%s\n' "$REPORT" | head -1)"
METHODS="$(printf '%s' "$FIRST_LINE" | grep -oE 'GameApi 方法：[0-9]+' | grep -oE '[0-9]+' || true)"
UNWIRED="$(printf '%s' "$FIRST_LINE" | grep -oE '生产零调用点：[0-9]+' | grep -oE '[0-9]+' || true)"

if [ -z "${METHODS:-}" ] || [ -z "${UNWIRED:-}" ]; then
  echo "[send-paths][FAIL] 读不到报告器的两个数（方法数 / 生产零调用点）—— 报告格式变了就是判据失效：" >&2
  echo "  首行：$FIRST_LINE" >&2
  exit 1
fi
if [ "$METHODS" -eq 0 ]; then
  echo "[send-paths][FAIL] GameApi 方法数 = 0 ⇒ 一个发送口都没扫到，'零调用点 0' 是假的" >&2
  exit 1
fi

if [ "$UNWIRED" -gt 0 ]; then
  echo "[send-paths][FAIL] GameApi 共 $METHODS 个发送口，其中 $UNWIRED 个**生产代码里没人调**：" >&2
  printf '%s\n' "$REPORT" | grep -E '^  (完全没人用|只有测试/探针在用)' | head -40 >&2
  echo "  ⇒ 要么接上入口（面板 / 按钮 / 行池），要么把这条发送口从 GameApi 删掉；" >&2
  echo "    留着就是「判定写了没接上」，单测与构建都不会报，只有玩家点不到。" >&2
  exit 1
fi

echo "[send-paths] GameApi $METHODS 个发送口全部有生产调用点（零调用点 0）"
