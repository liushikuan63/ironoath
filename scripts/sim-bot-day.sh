#!/usr/bin/env bash
# 职责：把 Bot 的 tick 驱动成一个可手跑的观测脚本（B11 验收 1/6 的最小量具，收口清单 §五 C1）。
#
# 用法：
#   IRONOATH_OPS_TOKEN=<REDACTED-secret> bash scripts/dev.sh &          # 服务端（同一个 token 才打得进 /bot/tick）
#   bash scripts/sim-bot-day.sh [轮数] [每轮间隔秒]                      # 默认 60 轮 × 0.2 秒
#
# 为什么需要脚本而不是只用单测：C1 的仿真用例证明的是"接上之后世界会动、预算与幂等成立"，
# 而"5000 Bot 全量一轮多久"（B11 验收 6）与"每任务真实耗时到底是多少"必须在**跑着的进程**上量一次
# —— 那两条数字决定 C0 留下的两条 todo 要不要重定（BOT_ROUND_BUDGET_MS / BOT_DUE_BATCH_LIMIT）。
# 本脚本就是那个量具：它只打端点、只读计数，不改任何状态。
#
# 依赖：curl、python3（解析 JSON）。不起服务端 —— 那是 dev.sh 的事。
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROUNDS="${1:-60}"
GAP_SECONDS="${2:-0.2}"
: "${IRONOATH_OPS_TOKEN:?需要先 export IRONOATH_OPS_TOKEN（与 dev.sh 用的同一个值），否则 /bot/tick 一律 1009}"

# 先建一个真人号 + 读一次图，让孵化按密度把 Bot 补出来（tick 只能推已有的 Bot）
#
# body 写进临时文件再用 --data-binary 发，不写成 curl -d '...'：本机（Windows Git Bash）的
# 命令行会把中文昵称的字节转坏，服务端直接回「请求体格式错误」——verify-b01.sh 早就踩过同一个坑
TMP_BODY="$(mktemp)"
trap 'rm -f "$TMP_BODY"' EXIT
printf '{"requestId":"sim-init-%s","deviceId":"sim-dev-%s","nickName":"仿真观察者","clientTime":1700000000000}' \
  "$(date +%s%N)" "$(date +%s%N)" > "$TMP_BODY"
PLAYER_JSON=$(curl -s -X POST "$BASE_URL/player/init" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_BODY")
PLAYER_ID=$(printf '%s' "$PLAYER_JSON" | python3 -c '
import json, sys
resp = json.load(sys.stdin)
if resp.get("data") is None:
    raise SystemExit("[sim] player/init 失败：code=%s msg=%s detail=%s" % (resp.get("code"), resp.get("msg"), resp.get("detail")))
print(resp["data"]["playerId"])
')
echo "[sim] 观察者=$PLAYER_ID"
curl -s -X POST "$BASE_URL/world/viewport" -H 'Content-Type: application/json' -H "X-Player-Id: $PLAYER_ID" \
  -d "{\"requestId\":\"sim-view-$(date +%s%N)\",\"centerX\":256,\"centerY\":256,\"zoom\":0,\"chunkVersions\":[]}" \
  | python3 -c '
import json, sys
d = (json.load(sys.stdin).get("data") or {})
chunks = d.get("chunks") or []
print("[sim] 读图后下发块数=%s 实体数=%s（chunks 分块下发，实体在各块的 entities 里）"
      % (len(chunks), sum(len(c.get("entities") or []) for c in chunks)))
'

echo "[sim] 开始打 $ROUNDS 轮 /bot/tick（间隔 ${GAP_SECONDS}s）"
for ((i = 1; i <= ROUNDS; i++)); do
  RESP=$(curl -s -X POST "$BASE_URL/bot/tick" -H 'Content-Type: application/json' \
    -H "X-Ops-Token: $IRONOATH_OPS_TOKEN" \
    -d "{\"requestId\":\"sim-tick-$i-$(date +%s%N)\"}")
  if [[ $((i % 10)) -eq 0 || $i -eq $ROUNDS ]]; then
    printf '%s' "$RESP" | python3 -c '
import json, sys
d = json.load(sys.stdin)["data"]
print("[sim] 第 %s 轮：processed=%s executed=%s failed=%s unhandled=%s pending=%s agents=%s"
      % (d["rounds"], d["processed"], d["executed"], d["failed"], d["unhandled"], d["pending"], d["agents"]))
print("       upgraded=%s trained=%s hunted=%s gathered=%s budgetHit=%s"
      % (d["upgraded"], d["trained"], d["hunted"], d["gathered"], d["budgetHit"]))
'
  fi
  sleep "$GAP_SECONDS"
done
echo "[sim] 完成。要看每任务真实耗时就对着 dev 日志里的「Bot tick 第 N 轮」那一行（本轮耗时=ms）。"
