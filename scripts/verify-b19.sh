#!/usr/bin/env bash
# 职责：B19 付费发货的 HTTP 层验收 —— 对已启动的服务端走一遍三类商品的真流程。
# 前置：服务端已启动（npm run dev 或 bash scripts/dev.sh）
# 用法：bash scripts/verify-b19.sh [baseUrl]
#
# 与 JUnit 的分工：PayEntitlementTest 能在锁内拨时钟、能造"第一次失败"的桩，
# 但它跑的是测试上下文。本脚本证的是**真实启动的那个进程**：端点存在、路由前缀对、
# 错误码按名字回、以及"改表就是那份价格表"之外的一整条付费链路。
#
# 注意：请求体一律走 UTF-8 临时文件而不是 -d '...' 内联字符串
#       （Windows 的 Git Bash 会把中文按 GBK 发出，服务端会报 Invalid UTF-8 middle byte）。
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_ID="$(date +%s)-$$"

echo "=== B19 付费发货验收：$BASE_URL ==="

if ! curl -s --max-time 5 -o /dev/null "$BASE_URL/time/sync" -X POST \
     -H 'Content-Type: application/json' -d '{"clientTime":1}'; then
  echo "服务端未启动或不可达，请先运行：npm run dev" >&2
  exit 1
fi

# 错误码按名字取，不写数字：数字改了用例不会跟着改，那是最容易烂掉的一种断言
node -e '
const fs = require("fs");
const src = fs.readFileSync(process.argv[1] + "/server/game-common/src/main/java/com/ironoath/common/ErrorCode.java", "utf8");
const out = [];
for (const m of src.matchAll(/([A-Z][A-Z0-9_]+)\s*\(\s*(\d+)\s*,\s*"/g)) out.push(m[1] + "=" + m[2]);
fs.writeFileSync(process.argv[2], out.join("\n"));
' "$ROOT_DIR" "$TMP_DIR/codes.txt"

post() { # $1=path $2=playerId(可空) $3=bodyFile(可空)
  local headers=(-H 'Content-Type: application/json')
  [ -n "${2:-}" ] && headers+=(-H "X-Player-Id: $2")
  if [ -n "${3:-}" ]; then
    curl -s -X POST "$BASE_URL$1" "${headers[@]}" --data-binary "@$3"
  else
    curl -s -X POST "$BASE_URL$1" "${headers[@]}"
  fi
}

get() { # $1=path $2=playerId(可空)
  if [ -n "${2:-}" ]; then curl -s "$BASE_URL$1" -H "X-Player-Id: $2"; else curl -s "$BASE_URL$1"; fi
}

body() { printf '%s' "$2" > "$TMP_DIR/$1"; }

# ---------- 对照组：不存在的付费路径必须 404 ----------
CONTROL_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/pay/not-a-real-endpoint")
BODY_OF_CONTROL=$(get /pay/not-a-real-endpoint | head -c 200)
if [ "$CONTROL_CODE" = "404" ]; then
  echo "  [PASS] 不存在的付费路径回 404（下面所有「路径存在」的判定才有对照物）"
else
  echo "  [FAIL] 不存在的路径回了 HTTP $CONTROL_CODE，体=$BODY_OF_CONTROL —— 后面所有判定都不可信"
  exit 1
fi

# ---------- 建档 ----------
body init.json "{\"requestId\":\"req-b19-$RUN_ID\",\"deviceId\":\"dev-b19-$RUN_ID\",\"nickName\":\"付费验收号\",\"clientTime\":1788000000000}"
PLAYER=$(post /player/init "" "$TMP_DIR/init.json" | node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);
if(r.code!==0){console.error("建档失败："+r.code+" "+r.msg);process.exit(1);}process.stdout.write(r.data.playerId);})')
if [ -z "$PLAYER" ]; then echo "  [FAIL] 拿不到 playerId"; exit 1; fi
echo "  [PASS] 建档 playerId=$PLAYER"

FAILED=0
check() { # $1=说明 $2=期望码 $3=实际 json
  printf '%s' "$3" > "$TMP_DIR/last.json"
  if node -e '
const fs=require("fs");const codes=Object.fromEntries(fs.readFileSync(process.argv[1],"utf8").split("\n")
  .filter(Boolean).map(l=>l.split("=")));
const r=JSON.parse(fs.readFileSync(process.argv[3],"utf8"));
const want=codes[process.argv[2]];
if(want===undefined){console.error("    ErrorCode.java 里没有 "+process.argv[2]);process.exit(1);}
if(String(r.code)!==String(want)){console.error("    期望 "+process.argv[2]+"="+want+"，实际 code="+r.code+" msg="+(r.msg||""));process.exit(1);}
if(r.data!==undefined&&r.data!==null)fs.writeFileSync(process.argv[3],JSON.stringify(r.data));
' "$TMP_DIR/codes.txt" "$2" "$TMP_DIR/last.json"; then
    echo "  [PASS] $1"
  else
    echo "  [FAIL] $1"
    FAILED=1
  fi
}

# ---------- 月卡 ----------
echo "[月卡] 发货只延有效期，日包每天一份且同日只有一份"
body card.json "{\"requestId\":\"req-card-$RUN_ID\",\"productId\":\"monthly_card\",\"count\":1}"
ORDER=$(post /pay/order "$PLAYER" "$TMP_DIR/card.json" | node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);
if(r.code!==0){console.error("    下单失败 "+r.code+" "+r.msg+"　detail="+(r.detail||""));
if(/offer-id/.test(r.detail||"")){console.error("    这个进程没带支付部署参数，任何商品都下不了单。这样起：\n"
  + "      mvn -f server/pom.xml -pl game-web spring-boot:run -Dspring-boot.run.profiles=dev \\\n"
  + "        -Dspring-boot.run.arguments=--server.port=8391 --ironoath.pay.offer-id=dev-fake-offer\n"
  + "      （offerId 与商户密钥同类凭据，刻意不给它配置文件默认值：那样它会被静默带上生产）");}
process.exit(1);}process.stdout.write(r.data.orderId);})')
if [ -z "$ORDER" ]; then echo "  [FAIL] 下不出月卡订单，后面的判定无从做起"; exit 1; fi
body cb.json "{\"orderId\":\"$ORDER\",\"transactionId\":\"txn-card-$RUN_ID\",\"sign\":\"s\",\"success\":true}"
post /pay/callback "" "$TMP_DIR/cb.json" > /dev/null

get /pay/card "$PLAYER" > "$TMP_DIR/card-status.json"
if node -e '
const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).data;
const a=(c,m)=>{if(!c){console.error("    "+m);process.exit(1);}};
a(d.active===true,"active 应为 true");
a(d.adFree===true,"adFree 应为 true（月卡那一位是开着的）");
a(d.bonusQueues===1,"bonusQueues 应为 1（表里 extraQueues 就是 1）");
a(d.claimedToday===false,"买卡不该顺手把今天的日包结清");
a(d.claimableDays===1,"现在点应发 1 天");
a(d.expireAt>d.serverNow+29*86400000,"到期时刻应晚于 29 天后");
' "$TMP_DIR/card-status.json"; then
  echo "  [PASS] GET /pay/card：激活、免广告位开、队列 +1、今天还没领"
else echo "  [FAIL] GET /pay/card 读数不对"; FAILED=1; fi

body claim.json "{\"requestId\":\"req-claim-$RUN_ID\"}"
CLAIMED=$(post /pay/card/claim "$PLAYER" "$TMP_DIR/claim.json")
printf '%s' "$CLAIMED" | node -e '
let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);
if(r.code!==0){console.error("    领取失败："+r.code+" "+r.msg);process.exit(1);}
if(r.data.claimedDays!==1){console.error("    claimedDays 应为 1，实际 "+r.data.claimedDays);process.exit(1);}
if(!(r.data.rewards.length>=3)){console.error("    日包应含表里那三行，实际 "+r.data.rewards.length);process.exit(1);}
})' && echo "  [PASS] POST /pay/card/claim 发出日包（含建造令与木材包）" || { echo "  [FAIL] 日包领取不对"; FAILED=1; }

body claim2.json "{\"requestId\":\"req-claim2-$RUN_ID\"}"
check "同日再领被 PAY_ALREADY_CLAIMED 挡住" PAY_ALREADY_CLAIMED "$(post /pay/card/claim "$PLAYER" "$TMP_DIR/claim2.json")"

REPLAY=$(get "/pay/order?orderId=$ORDER" "$PLAYER")
printf '%s' "$REPLAY" > "$TMP_DIR/replay.json"
if node -e '
const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
const d=r.data||{};
const hit=(d.rewards||[]).filter(x=>x.type==="PRIVILEGE");
if(hit.length!==1){console.error("    订单应回放出一条 PRIVILEGE，实际 "+JSON.stringify(d.rewards));process.exit(1);}
if(d.retryQueued!==false){console.error("    已结清的订单不该还在补单队列里");process.exit(1);}
' "$TMP_DIR/replay.json"; then
  echo "  [PASS] GET /pay/order 回放发货清单（PRIVILEGE 一项在列）"
else echo "  [FAIL] 清单没回放"; FAILED=1; fi

# ---------- 成长基金 ----------
echo "[基金] 发货只登记买过，档位按主城等级分批"
body fund.json "{\"requestId\":\"req-fund-$RUN_ID\",\"productId\":\"growth_fund\",\"count\":1}"
FUND_ORDER=$(post /pay/order "$PLAYER" "$TMP_DIR/fund.json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);if(r.code!==0){console.error(r.code+" "+r.msg);process.exit(1);}process.stdout.write(r.data.orderId);})')
body cbf.json "{\"orderId\":\"$FUND_ORDER\",\"transactionId\":\"txn-fund-$RUN_ID\",\"sign\":\"s\",\"success\":true}"
post /pay/callback "" "$TMP_DIR/cbf.json" > /dev/null
get /pay/fund "$PLAYER" > "$TMP_DIR/fund-status.json"
if node -e '
const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).data;
const a=(c,m)=>{if(!c){console.error("    "+m);process.exit(1);}};
a(d.purchased===true,"买过之后 purchased 应为 true");
a(d.tiers.length===6,"六档，实际 "+d.tiers.length);
a(d.tiers.every(t=>!t.claimed),"一档都没领过");
a(d.tiers.every(t=>!t.claimable),"主城 1 级 ⇒ 没有任何一档可领");
a(d.tiers[0].requireMainLevel<d.tiers[5].requireMainLevel,"档位按等级升序");
' "$TMP_DIR/fund-status.json"; then
  echo "  [PASS] GET /pay/fund：六档都看得见，但一级主城一档也领不到"
else echo "  [FAIL] GET /pay/fund 读数不对"; FAILED=1; fi

TIER=$(node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(r.data.tiers[0].tierId)' "$TMP_DIR/fund-status.json")
body t1.json "{\"requestId\":\"req-t1-$RUN_ID\",\"tierId\":\"$TIER\"}"
check "等级不够时领档位回 PAY_TIER_LOCKED" PAY_TIER_LOCKED "$(post /pay/fund/claim "$PLAYER" "$TMP_DIR/t1.json")"
body t2.json "{\"requestId\":\"req-t2-$RUN_ID\",\"tierId\":\"pr_not_a_tier\"}"
check "不存在的档位回 PAY_NOT_ENTITLED" PAY_NOT_ENTITLED "$(post /pay/fund/claim "$PLAYER" "$TMP_DIR/t2.json")"

# ---------- 首充 ----------
echo "[首充] 选将随下单提交、只送一次"
body fc0.json "{\"requestId\":\"req-fc0-$RUN_ID\",\"productId\":\"first_charge\",\"count\":1}"
check "不带 heroChoice 的首充单在下单处就拒" PARAM_INVALID "$(post /pay/order "$PLAYER" "$TMP_DIR/fc0.json")"

body fc.json "{\"requestId\":\"req-fc-$RUN_ID\",\"productId\":\"first_charge\",\"count\":1,\"heroChoice\":\"hero_sr_02\"}"
FC_ORDER=$(post /pay/order "$PLAYER" "$TMP_DIR/fc.json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const r=JSON.parse(s);if(r.code!==0){console.error(r.code+" "+r.msg);process.exit(1);}process.stdout.write(r.data.orderId);})')
body cffc.json "{\"orderId\":\"$FC_ORDER\",\"transactionId\":\"txn-fc-$RUN_ID\",\"sign\":\"s\",\"success\":true}"
post /pay/callback "" "$TMP_DIR/cffc.json" > /dev/null
get "/pay/order?orderId=$FC_ORDER" "$PLAYER" > "$TMP_DIR/fc-status.json"
if node -e '
const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).data;
const ids=d.rewards.map(r=>r.id);
const a=(c,m)=>{if(!c){console.error("    "+m+"；实收 "+JSON.stringify(d.rewards));process.exit(1);}};
a(ids.includes("hero_sr_02"),"应发给玩家挑的那位武将");
a(ids.includes("GOLD"),"应发出金币");
a(d.status==="SUCCESS","钱与货都结清才是 SUCCESS");
a(d.retryQueued===false,"结清了就不该在补单队列里");
' "$TMP_DIR/fc-status.json"; then
  echo "  [PASS] 首充发货 = 金币 + 玩家挑的武将，订单状态结清"
else echo "  [FAIL] 首充发货不对"; FAILED=1; fi

body fc2.json "{\"requestId\":\"req-fc2-$RUN_ID\",\"productId\":\"first_charge\",\"count\":1,\"heroChoice\":\"hero_sr_01\"}"
check "第二次首充在下单处被拒" PAY_NOT_ENTITLED "$(post /pay/order "$PLAYER" "$TMP_DIR/fc2.json")"

# ---------- 没买过的人领日包 ----------
body init2.json "{\"requestId\":\"req-b19b-$RUN_ID\",\"deviceId\":\"dev-b19b-$RUN_ID\",\"nickName\":\"未付费号\",\"clientTime\":1788000000000}"
PLAYER2=$(post /player/init "" "$TMP_DIR/init2.json" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{process.stdout.write(JSON.parse(s).data.playerId);})')
body nc.json "{\"requestId\":\"req-nc-$RUN_ID\"}"
check "未付费的号领日包回 PAY_CARD_INACTIVE" PAY_CARD_INACTIVE "$(post /pay/card/claim "$PLAYER2" "$TMP_DIR/nc.json")"

echo
if [ "$FAILED" = "0" ]; then
  echo "=== B19 付费发货验收通过（$BASE_URL）==="
else
  echo "=== B19 付费发货验收有失败项，见上面 [FAIL] ==="
fi
exit $FAILED
