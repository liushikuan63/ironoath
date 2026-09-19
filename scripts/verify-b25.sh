#!/usr/bin/env bash
# 职责：B25-S2（自动续训 / 自动补兵）的 HTTP 层验收 —— 对**已启动的那个进程**走一遍
#       /army/list 的 autoTrain 视图与 /army/autoTrain 开关。
# 前置：服务端已启动（npm run dev 或 bash scripts/dev.sh）
# 用法：bash scripts/verify-b25.sh [baseUrl]
#
# 与 JUnit 的分工：AutoTrainEndpointTest 能把兵营与武将摆好、把一批训练"rewind"到已到点，
# 于是"开→排一批→训完→再排一批→资源对账"那条正向链路在那里验（验收 3/4/5）。
# 那是测试上下文。本脚本证的是**真进程**上的四件事：
#   ① /army/list 真的带上了 autoTrain 这一位（协议生成物、序列化、契约三者都对上了）；
#   ② 拒绝面按**错误码名字**回来：未解锁的兵种不许开、预算超上限当场拒、缺 requestId 说清楚；
#   ③ 幂等：被拒的那次不烧键、成功过的键重放回 REQUEST_DUPLICATED；
#   ④ 对照组：一条不存在的路径确实不通。
#
# **本脚本不覆盖"开起来真的排了一批"**，这是刻意的而不是遗漏：新号没有兵营（建营要主城 3 级 + 真实时长），
# 也没有兵，而"给探针发兵 / 跳过建造"要么开作弊端点、要么改服务端配置，两条都是明文禁止的。
# 所以正向链路只在 JUnit 里验（那里有 7 条断言 + 资源对账），这里验"该拒的拒得清楚、该空的空得对"。
#
# 注意：请求体一律走 UTF-8 临时文件而不是 -d '...' 内联字符串
#       （Windows 的 Git Bash 会把中文按 GBK 发出，服务端会报 Invalid UTF-8 middle byte）。
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_ID="$(date +%s)-$$"

PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); echo "  PASS  $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL  $*"; }
check(){ # $1=条件说明 $2=真值 $3=期望真值
  if [ "$2" = "$3" ]; then ok "$1（$2）"; else bad "$1：期望 $3，实际 $2"; fi
}

echo "=== B25-S2 自动续训/补兵验收：$BASE_URL ==="

if ! curl -s --max-time 5 -o /dev/null "$BASE_URL/time/sync" -X POST \
     -H 'Content-Type: application/json' -d '{"clientTime":1}'; then
  echo "服务端未启动或不可达，请先运行：npm run dev" >&2
  exit 1
fi

# 错误码按名字取，不写数字：数字改了脚本不会跟着变，那是最容易烂掉的一种断言
CODES="$(node -e '
const fs = require("fs");
const src = fs.readFileSync(process.argv[1] + "/server/game-common/src/main/java/com/ironoath/common/ErrorCode.java", "utf8");
const out = {};
for (const m of src.matchAll(/([A-Z][A-Z0-9_]+)\s*\(\s*(\d+)\s*,\s*"/g)) out[m[1]] = m[2];
process.stdout.write([out.UNIT_NOT_UNLOCKED, out.PARAM_INVALID, out.REQUEST_ID_MISSING,
  out.REQUEST_DUPLICATED].map(v => v || "?").join(" "));
' "$ROOT_DIR")"
UNIT_NOT_UNLOCKED="$(echo "$CODES" | cut -d" " -f1)"
PARAM_INVALID="$(echo "$CODES" | cut -d" " -f2)"
REQUEST_ID_MISSING="$(echo "$CODES" | cut -d" " -f3)"
REQUEST_DUPLICATED="$(echo "$CODES" | cut -d" " -f4)"
for C in "$UNIT_NOT_UNLOCKED" "$PARAM_INVALID" "$REQUEST_ID_MISSING" "$REQUEST_DUPLICATED"; do
  if [ "$C" = "?" ]; then echo "ErrorCode 里缺了要用的码，先修本脚本再谈验收" >&2; exit 2; fi
done

# 预算上限也是**从表里现读**的，不写死：写死一个数字，改了表它照样绿
MAX_BATCHES="$(node -e '
const fs = require("fs");
const cfg = JSON.parse(fs.readFileSync(process.argv[1] + "/contract/config/global.json", "utf8"));
const row = (cfg.rows || []).find(p => p.id === "AUTO_TRAIN_MAX_BATCHES");
process.stdout.write(row ? String(row.value) : "?");
' "$ROOT_DIR")"
if [ "$MAX_BATCHES" = "?" ]; then
  echo "合约表里没有 AUTO_TRAIN_MAX_BATCHES：先修本脚本再谈验收" >&2
  exit 2
fi
BUDGET_OVER=$((MAX_BATCHES + 1))

# ---------- 新号 ----------
printf '%s' "{\"requestId\":\"req-b25-$RUN_ID\",\"deviceId\":\"dev-b25-$RUN_ID\",\"nickName\":\"续训验证\",\"clientTime\":1788000000000}" \
  > "$TMP_DIR/init.json"
PLAYER_ID="$(curl -s --max-time 10 -X POST "$BASE_URL/player/init" \
  -H 'Content-Type: application/json' --data-binary "@$TMP_DIR/init.json" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);process.stdout.write(j.data&&j.data.playerId||"")}catch(e){process.stdout.write("")}})')"
if [ -n "$PLAYER_ID" ]; then ok "新号建档拿到 playerId"; else bad "新号建档没拿到 playerId，后面的读数都无从谈起"; exit 1; fi

# 读数一律走这条**没有 eval** 的路径：字段名由脚本写死，逐段取值，null 打成 "null"
read_field() { # $1=文件 $2=字段路径（相对 data，形如 autoTrain.enabled）
  node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
let v = j.data === undefined ? undefined : j.data;
for (const p of process.argv[2].split(".")) {
  v = (v === null || v === undefined) ? undefined : v[p];
}
process.stdout.write(v === undefined ? "undefined" : v === null ? "null" : String(v));
' "$1" "$2"
}
read_code() { # 响应信封里的 code（错误码断言用）
  node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$1"
}
post_auto() { # $1=outFile $2=requestId $3=enabled $4=unitId|"" $5=count $6=budget $7=target
  printf '%s' "{\"requestId\":\"$2\",\"enabled\":$3,\"unitId\":$4,\"count\":$5,\"batchBudget\":$6,\"targetCount\":$7}" \
    > "$TMP_DIR/body.json"
  curl -s --max-time 10 -o "$TMP_DIR/$1" -w '%{http_code}' \
    -H "X-Player-Id: $PLAYER_ID" -H 'Content-Type: application/json' \
    --data-binary "@$TMP_DIR/body.json" "$BASE_URL/army/autoTrain"
}
get_army() { curl -s --max-time 10 -o "$TMP_DIR/$1" -w '%{http_code}' -H "X-Player-Id: $PLAYER_ID" "$BASE_URL/army/list"; }

# ---------- ① 契约面：/army/list 必须带上 autoTrain 这一位 ----------
echo "[协议] /army/list 带 autoTrain，新号是干净的关"
CODE="$(get_army army-fresh.json)"
check "GET /army/list 的 HTTP 码" "$CODE" "200"
check "新号的 autoTrain.enabled" "$(read_field "$TMP_DIR/army-fresh.json" autoTrain.enabled)" "false"
check "新号的 autoTrain.batchBudget" "$(read_field "$TMP_DIR/army-fresh.json" autoTrain.batchBudget)" "0"
check "新号的 autoTrain.targetCount" "$(read_field "$TMP_DIR/army-fresh.json" autoTrain.targetCount)" "0"
check "新号没有停止原因（stopReason 是 null 而不是空串）" \
  "$(read_field "$TMP_DIR/army-fresh.json" autoTrain.stopReason)" "null"

# ---------- ② 拒绝面：每条都按错误码名字回来 ----------
echo "[拒绝] 未解锁的兵种、超上限的预算、缺 requestId"
RID_LOCKED="req-b25-$RUN_ID-locked"
CODE="$(post_auto locked.json "$RID_LOCKED" true '"unit_infantry_t3"' 10 1 null)"
check "开未解锁兵种的 HTTP 码" "$CODE" "200"
check "开未解锁兵种回的是 UNIT_NOT_UNLOCKED" "$(read_code "$TMP_DIR/locked.json")" "$UNIT_NOT_UNLOCKED"
get_army army-after-locked.json > /dev/null
check "被拒之后策略仍是关的（不留半个开关）" \
  "$(read_field "$TMP_DIR/army-after-locked.json" autoTrain.enabled)" "false"

RID_BUDGET="req-b25-$RUN_ID-budget"
CODE="$(post_auto budget.json "$RID_BUDGET" true '"unit_infantry_t3"' 10 "$BUDGET_OVER" null)"
check "预算超上限的 HTTP 码" "$CODE" "200"
check "预算超上限回的是 PARAM_INVALID（服务端不接受无限支出）" \
  "$(read_code "$TMP_DIR/budget.json")" "$PARAM_INVALID"
OVER_DETAIL="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const d = String(j.detail || "");
process.stdout.write(d.includes(process.argv[2]) ? "yes" : "no");
' "$TMP_DIR/budget.json" "$MAX_BATCHES")"
check "拒绝详情里写明上限 $MAX_BATCHES（上限是表里那个数）" "$OVER_DETAIL" "yes"

printf '%s' '{"enabled":true,"unitId":"unit_infantry_t1","count":10,"batchBudget":1}' > "$TMP_DIR/no-rid.json"
CODE="$(curl -s --max-time 10 -o "$TMP_DIR/no-rid-resp.json" -w '%{http_code}' \
  -H "X-Player-Id: $PLAYER_ID" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/no-rid.json" "$BASE_URL/army/autoTrain")"
check "缺 requestId 的 HTTP 码" "$CODE" "200"
check "缺 requestId 回的是 REQUEST_ID_MISSING" "$(read_code "$TMP_DIR/no-rid-resp.json")" "$REQUEST_ID_MISSING"

# ---------- ③ 幂等：被拒的那次不烧键，成功过的键重放要被拒 ----------
echo "[幂等] 被拒不烧键 / 成功过重放回 REQUEST_DUPLICATED"
CODE="$(post_auto replay.json "$RID_LOCKED" true '"unit_infantry_t3"' 10 1 null)"
check "同一个 requestId 在**被拒之后**再发：HTTP 码" "$CODE" "200"
check "被拒的那次没把键烧掉（否则玩家被拒一次就再也开不了）" \
  "$(read_code "$TMP_DIR/replay.json")" "$UNIT_NOT_UNLOCKED"

# 关闭是一条走得通的写：先关一次（成功），再用同一个键重放（必须被拒）
RID_OFF="req-b25-$RUN_ID-off"
CODE="$(post_auto off.json "$RID_OFF" false '""' null null null)"
check "关掉自动续训的 HTTP 码" "$CODE" "200"
check "只给 requestId 与 enabled=false 就能关（不必再报一遍目标）" \
  "$(read_code "$TMP_DIR/off.json")" "0"
check "关掉之后回的就是「关」这一态" "$(read_field "$TMP_DIR/off.json" autoTrain.enabled)" "false"
CODE="$(post_auto off-again.json "$RID_OFF" false '""' null null null)"
check "同一个键再关一次：HTTP 码" "$CODE" "200"
check "成功过的键重放回 REQUEST_DUPLICATED（不是静默成功）" \
  "$(read_code "$TMP_DIR/off-again.json")" "$REQUEST_DUPLICATED"

# ---------- ④ 对照组：一条不存在的路径必须不通 ----------
# 没有这一条的话，"所有请求都被某个通配处理器接住并回 200"这种坏法在本脚本里照样全绿
CODE="$(curl -s --max-time 10 -o "$TMP_DIR/control.json" -w '%{http_code}' \
  -H "X-Player-Id: $PLAYER_ID" "$BASE_URL/army/autoTrainX")"
if [ "$CODE" = "200" ]; then
  bad "对照组：/army/autoTrainX 不该回 200（那说明有东西在替不存在的路径兜底）"
else
  ok "对照组：/army/autoTrainX 回 $CODE（确与真端点不同）"
fi

echo
echo "=== 通过 $PASS 项，失败 $FAIL 项 ==="
[ "$FAIL" = "0" ] || exit 1
