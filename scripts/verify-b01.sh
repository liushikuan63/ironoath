#!/usr/bin/env bash
# 职责：B01 端到端验收脚本 —— 对已启动的服务端跑 HTTP 层面的验收项（1、11）。
# 前置：服务端已启动（npm run dev 或 java -jar server/game-web/target/game-web.jar --spring.profiles.active=dev）
# 用法：bash scripts/verify-b01.sh [baseUrl]
#
# 注意：请求体一律走 UTF-8 临时文件而不是 -d '...' 内联字符串。
#       Windows 的 Git Bash 会把命令行里的中文按 GBK 编码发出，
#       服务端会正确地报「Invalid UTF-8 middle byte」—— 那是终端的编码问题，不是服务端 bug。
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
# 脚本可能被任意 cwd 调用，读配置表前先锚定仓库根
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# requestId / deviceId 每次运行都换新值：固定值会让第二次运行直接落到「登录」分支上，
# 而验收 1 要测的是「新号建档返回配置初始值」—— 两条路径返回的资源不是同一份口径
RUN_ID="$(date +%s)-$$"

pass=0
fail=0
ok() { echo "  [PASS] $*"; pass=$((pass + 1)); }
bad() { echo "  [FAIL] $*" >&2; fail=$((fail + 1)); }

# 用 printf 写出 UTF-8 请求体，避免终端编码干扰
write_json() {
  printf '%s' "$2" > "$TMP_DIR/$1"
}

echo "=== B01 端到端验收：$BASE_URL ==="

if ! curl -s --max-time 5 -o /dev/null "$BASE_URL/time/sync" -X POST \
     -H 'Content-Type: application/json' -d '{"clientTime":1}'; then
  echo "服务端未启动或不可达，请先运行：npm run dev" >&2
  exit 1
fi

# ---------- 验收 1：POST /player/init ----------
echo "[验收 1] POST /player/init 返回 resource 表里全部资源=配置初始值、cityLevel=1"
write_json init.json "{\"requestId\":\"req-verify-$RUN_ID\",\"deviceId\":\"dev-verify-$RUN_ID\",\"nickName\":\"流亡王裔\",\"clientTime\":1788000000000}"
resp_file="$TMP_DIR/init-resp.json"
curl -s -D "$TMP_DIR/init-headers.txt" -X POST "$BASE_URL/player/init" \
  -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/init.json" -o "$resp_file"

node -e '
  const fs = require("fs");
  const r = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  const assert = (cond, msg) => { if (!cond) { console.error("  [FAIL] " + msg); process.exitCode = 1; } else { console.log("  [PASS] " + msg); } };
  assert(r.code === 0, `code === 0（实际 ${r.code} ${r.msg ?? ""}）`);
  const d = r.data ?? {};
  assert(d.cityLevel === 1, `cityLevel === 1（实际 ${d.cityLevel}）`);
  const keys = Object.keys(d.resources ?? {});
  // 期望值取自配置表本身。这里曾经硬编码「5 种资源 + 各自初始量」，
  // 而 B09 加了体力、容量与产率也随建筑变化 —— 硬编码的验收脚本只会报假失败
  const table = JSON.parse(require("fs").readFileSync(process.argv[2], "utf8"));
  const expected = Object.fromEntries(table.rows.map(x => [x.id, [x.initAmount, x.initCap, x.basePerHour]]));
  assert(keys.length === Object.keys(expected).length,
    `资源种类与 resource 表一致（表 ${Object.keys(expected).length} 种 / 实际 ${keys.length}: ${keys.join(",")}）`);
  for (const [id, [cur, cap, perHour]] of Object.entries(expected)) {
    const s = (d.resources ?? {})[id];
    assert(s !== undefined, `资源 ${id} 存在`);
    if (s === undefined) continue;
    assert(s.current === cur, `${id}.current === ${cur}（实际 ${s.current}）`);
    assert(s.cap === cap, `${id}.cap === ${cap}（实际 ${s.cap}）`);
    assert(s.perHour === perHour, `${id}.perHour === ${perHour}（实际 ${s.perHour}）`);
    assert(s.lastSettle === d.serverNow, `${id}.lastSettle === serverNow（惰性结算起点）`);
  }
  assert(typeof d.playerId === "string" && d.playerId.length > 0, "playerId 非空");
  assert(typeof d.serverNow === "number" && d.serverNow > 0, "serverNow 为正数");
  assert(d.power && d.power.matchPower > 0, `matchPower > 0（避免 [0.5x,2.0x] 区间退化为空集，实际 ${d.power?.matchPower}）`);
  assert(typeof d.protectUntil === "number" && d.protectUntil > d.serverNow, "新手保护到期时间晚于当前");
  assert(d.profile && d.profile.nickName === "流亡王裔", `昵称中文往返无损（实际 ${d.profile?.nickName}）`);
  assert(typeof r.traceId === "string" && r.traceId.length > 0, "响应带 traceId（铁律 10）");
' "$resp_file" "$ROOT_DIR/contract/config/resource.json" || fail=$((fail + 1))

# traceId 响应头必须与 body 中的一致，玩家报障时凭它捞全链路
header_trace=$(grep -i '^X-Trace-Id:' "$TMP_DIR/init-headers.txt" | tr -d '\r' | awk '{print $2}')
body_trace=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).traceId)' "$resp_file")
if [ -n "$header_trace" ] && [ "$header_trace" = "$body_trace" ]; then
  ok "响应头 X-Trace-Id 与 body.traceId 一致（$header_trace）"
else
  bad "X-Trace-Id 不一致：header=$header_trace body=$body_trace"
fi

# ---------- 验收 11：同一 requestId 重复提交只创建一次玩家 ----------
echo "[验收 11] 同一 requestId 重复提交 /player/init，只创建一次玩家"
curl -s -X POST "$BASE_URL/player/init" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/init.json" -o "$TMP_DIR/init-resp2.json"
node -e '
  const fs = require("fs");
  const a = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).data;
  const b = JSON.parse(fs.readFileSync(process.argv[2], "utf8")).data;
  const same = a.playerId === b.playerId;
  console.log((same ? "  [PASS] " : "  [FAIL] ") + `重复 requestId 返回同一玩家 ${a.playerId}`);
  if (!same) process.exitCode = 1;
' "$resp_file" "$TMP_DIR/init-resp2.json" || fail=$((fail + 1))

# ---------- 同 deviceId 换 requestId：等同登录 ----------
echo "[附加] 同 deviceId 换 requestId 重复 init 应等同登录"
write_json init2.json "{\"requestId\":\"req-verify-$RUN_ID-login\",\"deviceId\":\"dev-verify-$RUN_ID\",\"nickName\":\"改名试试\",\"clientTime\":1788000000500}"
curl -s -X POST "$BASE_URL/player/init" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/init2.json" -o "$TMP_DIR/init-resp3.json"
node -e '
  const fs = require("fs");
  const a = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).data;
  const b = JSON.parse(fs.readFileSync(process.argv[2], "utf8")).data;
  const assert = (cond, msg) => { if (!cond) { console.error("  [FAIL] " + msg); process.exitCode = 1; } else { console.log("  [PASS] " + msg); } };
  assert(a.playerId === b.playerId, "同设备返回同一存档");
  assert(b.profile.nickName === "流亡王裔", `重复 init 不得覆盖已有昵称（实际 ${b.profile.nickName}）`);
  assert(b.profile.lastLoginAt >= a.profile.lastLoginAt, "lastLoginAt 已刷新");
  // 这里只能断言「存量不会比建档时更少」：零头结转的设计让几毫秒的间隔产不出 1 单位，
  // 所以登录响应的 lastSettle 本就不该等于 serverNow。强断言（登录必须带上挂机一小时的产出）
  // 在 PlayerInitTest.loginResponseSettlesResourcesWithoutPersisting，那边能把结算基准拨回一小时
  assert(b.resources.WOOD.current >= a.resources.WOOD.current,
    `登录返回的存量不少于建档值（建档 ${a.resources.WOOD.current} → 登录 ${b.resources.WOOD.current}）`);
' "$resp_file" "$TMP_DIR/init-resp3.json" || fail=$((fail + 1))

# ---------- 服务端独立校验 ----------
echo "[附加] 服务端独立校验非法入参"
write_json bad1.json '{"requestId":"short","deviceId":"dev-verify-0002","nickName":"x","clientTime":1}'
code=$(curl -s -X POST "$BASE_URL/player/init" -H 'Content-Type: application/json' --data-binary "@$TMP_DIR/bad1.json" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).code))')
if [ "$code" = "1003" ]; then ok "requestId 过短被拒（code=1003）"; else bad "requestId 过短应返回 1003，实际 $code"; fi

write_json bad2.json '{"requestId":"req-verify-0003","deviceId":"d","nickName":"x","clientTime":1}'
code=$(curl -s -X POST "$BASE_URL/player/init" -H 'Content-Type: application/json' --data-binary "@$TMP_DIR/bad2.json" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).code))')
if [ "$code" = "2002" ]; then ok "deviceId 过短被拒（code=2002）"; else bad "deviceId 过短应返回 2002，实际 $code"; fi

code=$(curl -s -X POST "$BASE_URL/player/init" -H 'Content-Type: application/json' -d '{这不是JSON' \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).code))')
if [ "$code" = "1001" ]; then ok "非法 JSON 返回 PARAM_INVALID（code=1001）而非 500"; else bad "非法 JSON 应返回 1001，实际 $code"; fi

# ---------- 时间校准 ----------
echo "[附加] POST /time/sync 返回 offset = serverNow - clientTime"
write_json sync.json '{"clientTime":1788000000000}'
curl -s -X POST "$BASE_URL/time/sync" -H 'Content-Type: application/json' \
  --data-binary "@$TMP_DIR/sync.json" -o "$TMP_DIR/sync-resp.json"
node -e '
  const fs = require("fs");
  const r = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  const s = r.data.sync;
  const assert = (cond, msg) => { if (!cond) { console.error("  [FAIL] " + msg); process.exitCode = 1; } else { console.log("  [PASS] " + msg); } };
  assert(r.code === 0, "code === 0");
  assert(s.offset === s.syncAt - 1788000000000, `offset === syncAt - clientTime（offset=${s.offset} syncAt=${s.syncAt}）`);
' "$TMP_DIR/sync-resp.json" || fail=$((fail + 1))

echo "=== 验收脚本结束：$fail 处失败 ==="
[ "$fail" -eq 0 ]
