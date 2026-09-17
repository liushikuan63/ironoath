#!/usr/bin/env bash
# 职责：B20 个人科技的 HTTP 层验收 —— 对**已启动的那个进程**走一遍科技端点。
# 前置：服务端已启动（npm run dev 或 bash scripts/dev.sh）
# 用法：bash scripts/verify-b20.sh [baseUrl]
#
# 与 JUnit 的分工：TechEndpointTest 能把学院摆到 8 级、能把完成时刻写成过去再读一次结算，
# 但那是测试上下文。本脚本证的是真进程的三件事：端点存在且路由对、整棵树是从表里来的、
# 每一条拒绝都按**错误码名字**回来（而不是 500 也不是 200 空数据）。
#
# **本脚本不覆盖"研究成功"那一条**，这是刻意的而不是遗漏：新号要研究科技得先有学院，
# 而学院要主城 6 级 + 资源 + 真实等待时间。仓库里没有（也不许有）替玩家跳过这些的后门，
# 所以正向链路只在 JUnit 里验（那里可以直接摆建筑）。这里能验的是：所有该拒的都拒得清楚。
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

echo "=== B20 个人科技验收：$BASE_URL ==="

if ! curl -s --max-time 5 -o /dev/null "$BASE_URL/time/sync" -X POST \
     -H 'Content-Type: application/json' -d '{"clientTime":1}'; then
  echo "服务端未启动或不可达，请先运行：npm run dev" >&2
  exit 1
fi

# 错误码按名字取，不写数字：数字改了脚本不会跟着改，那是最容易烂掉的一种断言
node -e '
const fs = require("fs");
const src = fs.readFileSync(process.argv[1] + "/server/game-common/src/main/java/com/ironoath/common/ErrorCode.java", "utf8");
const out = {};
for (const m of src.matchAll(/([A-Z][A-Z][A-Z0-9_]+)\s*\(\s*(\d+)\s*,\s*"/g)) out[m[1]] = m[2];
fs.writeFileSync(process.argv[2], JSON.stringify(out));
' "$ROOT_DIR" "$TMP_DIR/codes.json"
code_of() { node -e "console.log(require(process.argv[1])[process.argv[2]] || 'MISSING')" "$TMP_DIR/codes.json" "$1"; }
# 表的行数从 contract/config 现读：写死 11 的话，加一行科技就要来改脚本
TECH_ROWS=$(node -e "console.log(JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')).rows.length)" "$ROOT_DIR/contract/config/tech.json")
ACADEMY_NEED=$(node -e "
const rows=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')).rows;
console.log(rows.find(r=>r.id==='tech_agri_wood').requireAcademyLevel);
" "$ROOT_DIR/contract/config/tech.json")

post() { # $1=path $2=playerId(可空) $3=bodyFile(可空)
  local headers=(-H 'Content-Type: application/json')
  [ -n "${2:-}" ] && headers+=(-H "X-Player-Id: $2")
  if [ -n "${3:-}" ]; then
    curl -s -X POST "$BASE_URL$1" "${headers[@]}" --data-binary "@$3"
  else
    curl -s -X POST "$BASE_URL$1" "${headers[@]}"
  fi
}
get() { # $1=path $2=playerId
  curl -s -X GET "$BASE_URL$1" -H "X-Player-Id: $2"
}
field() { node -e "
const fs=require('fs');const body=fs.readFileSync(process.argv[1],'utf8');
let d; try { d=JSON.parse(body); } catch (e) { console.log('NOT_JSON:'+body.slice(0,120)); process.exit(0); }
const path=process.argv[2].split('.'); let v=d;
for (const k of path) { v = v == null ? undefined : v[k]; }
console.log(v === undefined ? '' : (typeof v === 'object' ? JSON.stringify(v) : v));
" "$1" "$2"; }

echo "-- 1. 对照组：不存在的路径必须是 404（否则「端点通」这句话是空的）--"
CTRL_HTTP=$(curl -s -o "$TMP_DIR/ctrl.json" -w '%{http_code}' "$BASE_URL/tech/nope-not-an-endpoint" -H 'X-Player-Id: P-control')
check "/tech/nope-not-an-endpoint 的 HTTP 状态" "$CTRL_HTTP" "404"
check "/tech/list 存在（不是 404）" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/tech/list" -H 'X-Player-Id: P-control')" "200"

echo "-- 2. 新号建档 --"
cat > "$TMP_DIR/init.json" <<EOF
{"requestId":"init-$RUN_ID","deviceId":"dev-$RUN_ID","nickName":"b20-check","clientTime":1,"wxCode":""}
EOF
INIT=$(post /player/init "" "$TMP_DIR/init.json"); echo "$INIT" > "$TMP_DIR/init.resp"
PID_VAL=$(field "$TMP_DIR/init.resp" "data.playerId")
if [ -n "$PID_VAL" ] && [ "$PID_VAL" != "NOT_JSON:$PID_VAL" ]; then ok "建档拿到 playerId"; else bad "建档没拿到 playerId：$INIT"; fi

echo "-- 3. 整棵树来自表：$TECH_ROWS 行，且新号一行都研究不了 --"
LIST=$(get /tech/list "$PID_VAL"); echo "$LIST" > "$TMP_DIR/list.resp"
check "/tech/list 的 code" "$(field "$TMP_DIR/list.resp" code)" "0"
check "下发的行数 = tech.json 的行数" "$(field "$TMP_DIR/list.resp" data.techs.length)" "$TECH_ROWS"
check "新号 academyLevel（没建学院就是 0，不是 null 也不是缺字段）" "$(field "$TMP_DIR/list.resp" data.academyLevel)" "0"
check "队列里确实没有东西（JSON 里是 null 而不是缺字段）" "$(field "$TMP_DIR/list.resp" data.queue.techId)" "null"
REASONS=$(node -e "
const d=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));
const set=new Set(d.data.techs.map(t=>t.blockedReason));
const canAny=d.data.techs.some(t=>t.canResearch);
console.log([...set].join('+')+'|canAny='+canAny);
" "$TMP_DIR/list.resp")
NEED_REASON="ACADEMY_LOW|canAny=false"
if [ "$REASONS" = "$NEED_REASON" ]; then ok "全部 $TECH_ROWS 行都标 ACADEMY_LOW 且没有一行 canResearch（学院要 $ACADEMY_NEED 级）"; else bad "拦因分布不对：期望 $NEED_REASON，实际 $REASONS"; fi
FIRST_NAME=$(node -e "
const fs=require('fs');
const rows=JSON.parse(fs.readFileSync(process.argv[2],'utf8')).rows;
const d=JSON.parse(fs.readFileSync(process.argv[1],'utf8'));
const hit=d.data.techs.find(t=>t.techId===rows[0].id);
console.log(hit && hit.name===rows[0].name ? 'ok' : 'mismatch');
" "$TMP_DIR/list.resp" "$ROOT_DIR/contract/config/tech.json")
check "科技名从表里来（不是客户端硬编码的那一份）" "$FIRST_NAME" "ok"

echo "-- 4. 每一条拒绝都按名字回错误码 --"
cat > "$TMP_DIR/research.json" <<EOF
{"requestId":"r-$RUN_ID","techId":"tech_agri_wood"}
EOF
REJ=$(post /tech/research "$PID_VAL" "$TMP_DIR/research.json"); echo "$REJ" > "$TMP_DIR/rej.resp"
check "没学院就研究 → TECH_ACADEMY_REQUIRED" "$(field "$TMP_DIR/rej.resp" code)" "$(code_of TECH_ACADEMY_REQUIRED)"
check "错误提示带「需要几级 / 当前几级」" "$(node -e "
const d=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));
console.log((d.detail||d.msg||'').includes('需要学院 '+process.argv[2]+' 级')?'ok':'no');" "$TMP_DIR/rej.resp" "$ACADEMY_NEED")" "ok"

cat > "$TMP_DIR/badid.json" <<EOF
{"requestId":"b-$RUN_ID","techId":"tech_not_in_table"}
EOF
post /tech/research "$PID_VAL" "$TMP_DIR/badid.json" > "$TMP_DIR/badid.resp"
check "表里没有的 id → PARAM_INVALID（不是 500，也不是「隐藏科技」）" \
  "$(field "$TMP_DIR/badid.resp" code)" "$(code_of PARAM_INVALID)"

cat > "$TMP_DIR/noreq.json" <<EOF
{"requestId":"","techId":"tech_agri_wood"}
EOF
post /tech/research "$PID_VAL" "$TMP_DIR/noreq.json" > "$TMP_DIR/noreq.resp"
check "缺 requestId → REQUEST_ID_MISSING" \
  "$(field "$TMP_DIR/noreq.resp" code)" "$(code_of REQUEST_ID_MISSING)"

cat > "$TMP_DIR/cancel.json" <<EOF
{"requestId":"c-$RUN_ID"}
EOF
post /tech/cancel "$PID_VAL" "$TMP_DIR/cancel.json" > "$TMP_DIR/cancel.resp"
check "队列空着时取消 → TECH_NOT_RESEARCHING" \
  "$(field "$TMP_DIR/cancel.resp" code)" "$(code_of TECH_NOT_RESEARCHING)"

cat > "$TMP_DIR/speedup_noreq.json" <<EOF
{"requestId":"","itemId":"item_speedup_research_1h","count":1}
EOF
post /tech/speedUp "$PID_VAL" "$TMP_DIR/speedup_noreq.json" > "$TMP_DIR/speedup_noreq.resp"
check "加速缺 requestId → REQUEST_ID_MISSING（付费道具的写路径也要有幂等键）" \
  "$(field "$TMP_DIR/speedup_noreq.resp" code)" "$(code_of REQUEST_ID_MISSING)"

cat > "$TMP_DIR/speedup.json" <<EOF
{"requestId":"s-$RUN_ID","itemId":"item_not_in_table","count":1}
EOF
post /tech/speedUp "$PID_VAL" "$TMP_DIR/speedup.json" > "$TMP_DIR/speedup.resp"
check "队列空着时加速 → TECH_NOT_RESEARCHING 而不是 ITEM_NOT_FOUND：" \
"$(field "$TMP_DIR/speedup.resp" code)" "$(code_of TECH_NOT_RESEARCHING)"
echo "     （这一条判的是校验顺序：队列先判、道具后判，顺序倒了就会先扣一张真道具再报配置不存在）"

echo "-- 5. 任务侧的接线在这个进程里也成立 --"
QUESTS=$(get /quest/list "$PID_VAL"); echo "$QUESTS" > "$TMP_DIR/quest.resp"
check "quest_main_08 的 goalType 仍是 RESEARCH_TECH、目标指 tech_agri_wood" "$(node -e "
const d=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));
const q=(d.data.quests||[]).find(x=>x.questId==='quest_main_08');
console.log(q && q.goalType==='RESEARCH_TECH' && q.goalTarget==='tech_agri_wood' ? 'ok' : 'missing');
" "$TMP_DIR/quest.resp")" "ok"

echo "-- 6. 国家科技（块③）：没有国家的人读到的是 NATION_NOT_FOUND，而不是 500 或空数据 --"
# 正向链路同样只在 JUnit 里验：建国要主城 16 级 + 一个联盟，真进程的新号两样都没有，
# 而仓库不许有跳门槛的后门（NationTechEndpointTest 用夹具把这两样造出来，全程真跑）
get /nation/tech "$PID_VAL" > "$TMP_DIR/ntech.resp"
check "GET /nation/tech 无国家 → NATION_NOT_FOUND" \
  "$(field "$TMP_DIR/ntech.resp" code)" "$(code_of NATION_NOT_FOUND)"
cat > "$TMP_DIR/ntech.json" <<EOF
{"requestId":"nt-$RUN_ID","techId":"nt_agri_grain"}
EOF
post /nation/tech/research "$PID_VAL" "$TMP_DIR/ntech.json" > "$TMP_DIR/ntech2.resp"
check "POST /nation/tech/research 无国家 → 同一枚码（先定位国家，再谈能不能研究）" \
  "$(field "$TMP_DIR/ntech2.resp" code)" "$(code_of NATION_NOT_FOUND)"
check "对照组：/nation/tech/nope-not-an-endpoint 必须 404" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/nation/tech/nope-not-an-endpoint" -H "X-Player-Id: $PID_VAL")" "404"

echo
echo "=== 结果：PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] || exit 1
echo "[verify-b20] 全部判据通过（正向链路见文件头那条说明：它只在 JUnit 里验）"
