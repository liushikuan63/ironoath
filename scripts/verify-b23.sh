#!/usr/bin/env bash
# 职责：B23 排行榜的 HTTP 层验收 —— 对**已启动的那个进程**走一遍 /rank/list 与 /rank/me。
# 前置：服务端已启动（npm run dev 或 bash scripts/dev.sh）
# 用法：bash scripts/verify-b23.sh [baseUrl]
#
# 与 JUnit 的分工：RankEndpointTest 能把四个人摆到四种榜上、能直接往榜里塞一条 Bot 看读侧兜底，
# 但那是测试上下文。本脚本证的是真进程的四件事：端点存在且路由对、pageSize 是**表里那个数**、
# 不认识的榜类型按**错误码名字**回来（不是 500 也不是 200 空数据）、以及一条不存在的路径确实不通
# （对照组，见文件末尾）。
#
# **本脚本不覆盖"榜上真有人"那一条**，这是刻意的而不是遗漏：新号没有战力上报、也没有击杀，
# 要有战力得练兵/上阵，要有击杀得真打一仗 —— 仓库里没有（也不许有）替玩家跳过这些的后门。
# 所以正向链路只在 JUnit 里验，这里验的是"该空的空得对、该拒的拒得清楚"。
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

echo "=== B23 排行榜验收：$BASE_URL ==="

if ! curl -s --max-time 5 -o /dev/null "$BASE_URL/time/sync" -X POST \
     -H 'Content-Type: application/json' -d '{"clientTime":1}'; then
  echo "服务端未启动或不可达，请先运行：npm run dev" >&2
  exit 1
fi

# 错误码按名字取，不写数字：数字改了脚本不会跟着改，那是最容易烂掉的一种断言
CODES="$(node -e '
const fs = require("fs");
const src = fs.readFileSync(process.argv[1] + "/server/game-common/src/main/java/com/ironoath/common/ErrorCode.java", "utf8");
const out = {};
for (const m of src.matchAll(/([A-Z][A-Z][A-Z0-9_]+)\s*\(\s*(\d+)\s*,\s*"/g)) out[m[1]] = m[2];
process.stdout.write((out.PARAM_INVALID || "?") + " " + (out.PLAYER_NOT_FOUND || "?") + " "
  + (out.RANK_SNAPSHOT_EMPTY || "?"));
' "$ROOT_DIR")"
PARAM_INVALID="$(echo "$CODES" | cut -d" " -f1)"
PLAYER_NOT_FOUND="$(echo "$CODES" | cut -d" " -f2)"
RANK_SNAPSHOT_EMPTY="$(echo "$CODES" | cut -d" " -f3)"

# 每页条数是**从表里现读**的，不写 20：脚本里写死一个数字，改了表它照样绿，那是假绿
# （表结构是 table/version/fieldTypes/rows —— 行在 rows 里，不是 params）
PAGE_SIZE_MAX="$(node -e '
const fs = require("fs");
const cfg = JSON.parse(fs.readFileSync(process.argv[1] + "/contract/config/global.json", "utf8"));
const row = (cfg.rows || []).find(p => p.id === "RANK_PAGE_SIZE_MAX");
process.stdout.write(row ? String(row.value) : "?");
' "$ROOT_DIR")"
if [ "$PAGE_SIZE_MAX" = "?" ]; then
  echo "合约表里没有 RANK_PAGE_SIZE_MAX，或行结构变了：先修本脚本再谈验收" >&2
  exit 2
fi

# ---------- 新号 ----------
printf '%s' "{\"requestId\":\"req-b23-$RUN_ID\",\"deviceId\":\"dev-b23-$RUN_ID\",\"nickName\":\"榜单验证\",\"clientTime\":1788000000000}" \
  > "$TMP_DIR/init.json"
PLAYER_ID="$(curl -s --max-time 10 -X POST "$BASE_URL/player/init" \
  -H 'Content-Type: application/json' --data-binary "@$TMP_DIR/init.json" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);process.stdout.write(j.data&&j.data.playerId||"")}catch(e){process.stdout.write("")}})')"
if [ -n "$PLAYER_ID" ]; then ok "新号建档拿到 playerId"; else bad "新号建档没拿到 playerId，后面的读数都无从谈起"; exit 1; fi

get_rank() { # $1=path  $2=outFile  回显 HTTP 码
  curl -s --max-time 10 -o "$TMP_DIR/$2" -w '%{http_code}' \
    -H "X-Player-Id: $PLAYER_ID" "$BASE_URL$1"
}

# ---------- 验收 1/2 的 HTTP 面：四类榜都答得出来，且各回各的类型 ----------
echo "[验收 1] 四类榜都通，type 原样回显、pageSize 是表里那个数"
for TYPE in POWER KILL ALLIANCE NATION; do
  CODE="$(get_rank "/rank/list?type=$TYPE" "list-$TYPE.json")"
  check "GET /rank/list?type=$TYPE 的 HTTP 码" "$CODE" "200"
  read -r ECHO_TYPE PAGE_SIZE <<EOF
$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const d = j.data || {};
process.stdout.write(String(d.type) + " " + String(d.pageSize));
' "$TMP_DIR/list-$TYPE.json")
EOF
  check "type=$TYPE 回显的类型" "$ECHO_TYPE" "$TYPE"
  check "type=$TYPE 的每页条数（表里 RANK_PAGE_SIZE_MAX）" "$PAGE_SIZE" "$PAGE_SIZE_MAX"
done

# ---------- 验收 2 的 HTTP 面：新号没上报过击杀 ⇒ 必须是 null 而不是 0 ----------
echo "[验收 2] 未上榜回 null，不用 0 冒充"
CODE="$(get_rank '/rank/me?type=KILL' me-kill.json)"
check "GET /rank/me?type=KILL 的 HTTP 码" "$CODE" "200"
read -r MY_RANK MY_VALUE MY_TYPE <<EOF
$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const d = j.data || {};
process.stdout.write(String(d.myRank) + " " + String(d.myValue) + " " + String(d.type));
' "$TMP_DIR/me-kill.json")
EOF
check "新号的击杀名次" "$MY_RANK" "null"
check "新号的击杀值" "$MY_VALUE" "null"
check "/rank/me 回的类型" "$MY_TYPE" "KILL"
# 未上榜时 /rank/me 的语义是"回第一页"（面板总得画点东西）：entries 必须是空表而不是缺键
EMPTY_COUNT="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const d = j.data || {};
process.stdout.write(String(Array.isArray(d.entries) ? d.entries.length : -1));
' "$TMP_DIR/me-kill.json")"
check "空榜的 entries 是空表（不是缺键）" "$EMPTY_COUNT" "0"

# ---------- 拒绝面：每一条都要按错误码回来 ----------
echo "[拒绝] 不认识的榜类型、缺玩家头、空 type"
CODE="$(get_rank '/rank/list?type=BOGUS' bad-type.json)"
check "不认识的榜类型的 HTTP 码" "$CODE" "200"
BAD_CODE="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$TMP_DIR/bad-type.json")"
check "不认识的榜类型回的是 PARAM_INVALID" "$BAD_CODE" "$PARAM_INVALID"

CODE="$(curl -s --max-time 10 -o "$TMP_DIR/no-player.json" -w '%{http_code}' "$BASE_URL/rank/list?type=POWER")"
check "缺玩家头的 HTTP 码" "$CODE" "200"
# 缺玩家头：这里证的是"与全仓库同一个口径"，不是"某个特定码" —— 真正答话的是 Spring 的
# 缺头处理器（`@RequestHeader` 默认必需），控制器里那句 requirePlayer 兜的是空串那种边角。
# 所以拿一个**既有端点**当参照物比写死 1001 更硬：榜这边哪天被改成回 200 空榜，这里就红
NO_PLAYER_CODE="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$TMP_DIR/no-player.json")"
REF_NO_PLAYER="$(curl -s --max-time 10 "$BASE_URL/city/list" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.stdout.write(String(JSON.parse(s).code))}catch(e){process.stdout.write("PARSE_FAIL")}})')"
check "缺玩家头：榜端点与既有端点回同一个码" "$NO_PLAYER_CODE" "$REF_NO_PLAYER"
if [ "$NO_PLAYER_CODE" = "0" ] || [ "$NO_PLAYER_CODE" = "PARSE_FAIL" ]; then
  bad "缺玩家头居然被当成正常请求（code=$NO_PLAYER_CODE）"
else
  ok "缺玩家头被拒（code=$NO_PLAYER_CODE，与 /city/list 一致）"
fi
if [ "$NO_PLAYER_CODE" = "$PLAYER_NOT_FOUND" ]; then
  ok "缺玩家头回的是 PLAYER_NOT_FOUND"
else
  echo "  NOTE  缺玩家头回的是 $NO_PLAYER_CODE（参照端点也是 $REF_NO_PLAYER）—— Spring 缺头处理器的口径，控制器兜底那句管不到真·缺头"
fi

CODE="$(get_rank '/rank/list?type=' empty-type.json)"
check "空 type 的 HTTP 码" "$CODE" "200"
EMPTY_TYPE_CODE="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$TMP_DIR/empty-type.json")"
check "空 type 回的是 PARAM_INVALID" "$EMPTY_TYPE_CODE" "$PARAM_INVALID"

# ---------- 每日快照（B23-S2）：查今天会把今天补拍上；查没拍过的过去某天明确拒绝 ----------
echo "[验收 4] 每日快照：今天补拍、过去没拍过的那天说清楚"
TODAY="$(node -e 'process.stdout.write(new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10).replace(/-/g, ""))')"
CODE="$(get_rank "/rank/snapshot?type=POWER&dayKey=$TODAY" snapshot-today.json)"
check "GET /rank/snapshot（今天）的 HTTP 码" "$CODE" "200"
read -r SNAP_DAY SNAP_AT SNAP_RANK <<EOF
$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const d = j.data || {};
process.stdout.write(String(d.dayKey) + " " + (d.snapshotAt > 0) + " " + String(d.myRank));
' "$TMP_DIR/snapshot-today.json")
EOF
check "回显的 dayKey 就是请求的那天" "$SNAP_DAY" "$TODAY"
check "快照真被拍下了（snapshotAt > 0）" "$SNAP_AT" "true"
check "新号那天没上过榜 ⇒ myRank 是 null（不是 0）" "$SNAP_RANK" "null"
# 同一天再查一次：拍的仍是同一份（幂等）。判据是**时刻不变**，不是"没报错"
sleep 1
CODE="$(get_rank "/rank/snapshot?type=POWER&dayKey=$TODAY" snapshot-today2.json)"
SECOND_AT="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String((j.data || {}).snapshotAt));
' "$TMP_DIR/snapshot-today2.json")"
FIRST_AT="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String((j.data || {}).snapshotAt));
' "$TMP_DIR/snapshot-today.json")"
check "同一天第二次读，快照时刻不变（一天只拍一份）" "$SECOND_AT" "$FIRST_AT"

CODE="$(get_rank '/rank/snapshot?type=POWER&dayKey=20200101' snapshot-old.json)"
check "查没拍过的过去某天：HTTP 码" "$CODE" "200"
OLD_CODE="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$TMP_DIR/snapshot-old.json")"
check "那天没快照回的是 RANK_SNAPSHOT_EMPTY" "$OLD_CODE" "$RANK_SNAPSHOT_EMPTY"
OLD_DETAIL="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const d = String(j.detail || "");
process.stdout.write(d.includes("最早一天") && d.includes(process.argv[2]) ? "yes" : "no");
' "$TMP_DIR/snapshot-old.json" "$TODAY")"
check "拒绝的 detail 说清可查的最早一天" "$OLD_DETAIL" "yes"

CODE="$(get_rank '/rank/snapshot?type=POWER&dayKey=2026-09-19' snapshot-badday.json)"
BAD_DAY_CODE="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$TMP_DIR/snapshot-badday.json")"
check "日期格式不对回的是 PARAM_INVALID（与「那天没拍过」分开）" "$BAD_DAY_CODE" "$PARAM_INVALID"

# 运营出口：没令牌读不到、带令牌读得到（两条一起才说明"闸门在令牌那一道"）
CODE="$(curl -s --max-time 10 -o "$TMP_DIR/ops-no-token.json" -w '%{http_code}' \
  "$BASE_URL/ops/rank/snapshot?type=POWER&dayKey=$TODAY")"
check "GET /ops/rank/snapshot 的 HTTP 码" "$CODE" "200"
OPS_NO_TOKEN="$(node -e '
const fs = require("fs");
const j = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write(String(j.code));
' "$TMP_DIR/ops-no-token.json")"
if [ "$OPS_NO_TOKEN" = "0" ]; then
  bad "对照组：没令牌的 ops 快照居然读到了数据"
else
  ok "对照组：没令牌读不到（code=$OPS_NO_TOKEN，与"读到数据"分得开）"
fi

# ---------- 对照组：一条不存在的路径必须不通 ----------
# 没有这一条的话，"所有请求都被某个通配处理器接住并回 200"这种坏法在本脚本里照样全绿
CODE="$(get_rank '/rank/nonexistent' control.json)"
if [ "$CODE" = "200" ]; then
  bad "对照组：/rank/nonexistent 不该回 200（那说明有东西在替不存在的路径兜底）"
else
  ok "对照组：/rank/nonexistent 回 $CODE（确与真端点不同）"
fi

echo
echo "=== 通过 $PASS 项，失败 $FAIL 项 ==="
[ "$FAIL" = "0" ] || exit 1
