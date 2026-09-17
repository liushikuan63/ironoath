#!/usr/bin/env bash
# 职责：B15 验收 10「无绕支付」的静态检查 —— 代码库里搜不到非米大师的支付实现。
#
# B15 的红线第 1 条是「必须使用微信虚拟支付（米大师），禁止任何绕支付方案」，
# 验收 10 明写这条要「静态检查（加 CI）」。
#
# 为什么必须是静态检查而不是单测：绕支付方案的典型形态是
# 「用购买实物赠游戏币」「用第三方充值卡兑换」「用广告解锁付费内容」，
# 它们每一个在功能测试里都是**通过**的 —— 玩家确实拿到了东西。
# 只有「代码里出现了米大师之外的支付入口」这个模式能被机械地识别出来。
set -euo pipefail
cd "$(dirname "$0")/.."

FAIL=0
SERVER_DIRS=(server/game-common/src/main/java server/game-core/src/main/java
             server/game-config/src/main/java server/game-battle/src/main/java
             server/game-web/src/main/java)
CLIENT_DIR=client/assets/scripts

# ---------- 0. 唯一允许的渠道从表里读，不在脚本里写第二份 ----------
echo "[check-no-payment-bypass] 读取 PAY_CHANNEL（唯一允许的支付渠道）…"
ALLOWED=$(node -e "
const rows = (require('./contract/config/global.json').rows) || [];
const r = rows.find(x => x.id === 'PAY_CHANNEL');
process.stdout.write(r ? String(r.value) : '');
" 2>/dev/null || true)
if [ -z "$ALLOWED" ]; then
  echo "[check-no-payment-bypass][FAIL] 从 contract/config/global.json 读不到 PAY_CHANNEL"
  echo "  这条参数就是本门的白名单来源（它的 why 明写「让 CI 检查能对着它比对」），"
  echo "  读不到时必须响亮失败，而不是退回脚本里写死的一份 —— 那等于表改了而门还按旧名单放行。"
  FAIL=1
fi
ALLOWED_UPPER=$(printf '%s' "${ALLOWED:-}" | tr '[:lower:]' '[:upper:]')
case "$ALLOWED_UPPER" in
  *MIDAS*|*WECHAT*VIRTUAL*)
    echo "[check-no-payment-bypass] PAY_CHANNEL=$ALLOWED（米大师 / 微信虚拟支付），符合 B15 红线 1。"
    ;;
  *)
    echo "[check-no-payment-bypass][FAIL] PAY_CHANNEL=$ALLOWED 不是米大师 / 微信虚拟支付"
    echo "  B15 红线 1：必须使用微信虚拟支付（米大师）。真要换渠道（如安卓官服），"
    echo "  要同时改这条参数、B15 红线与本门下面的名单 —— 不许让它悄悄放行。"
    FAIL=1
    ;;
esac

# ---------- 1. 第三方支付通道 ----------
echo "[check-no-payment-bypass] 扫描非米大师的支付实现…"
# 名单里含 midas，而唯一允许渠道的值本身就是 WECHAT_MIDAS —— 不剔掉它自己的名字，
# 任何一处把 PAY_CHANNEL 的值写进代码的尝试都会被判成绕支付（那条参数因此永远只能零引用）。
THIRD_PARTY_ALL='alipay|AliPay|ALIPAY|unionpay|UnionPay|applePay|ApplePay|googlePlay|GooglePlay|paypal|PayPal|stripe|Stripe|tenpay|TenPay|midas|以外|charge\.now|paymentwall'
THIRD_PARTY=''
OLD_IFS=$IFS
IFS='|'
for token in $THIRD_PARTY_ALL; do
  # 去掉正则转义再比子串（`charge\.now` 这种带反斜杠的本来就不可能是渠道名的一部分）
  token_upper=$(printf '%s' "$token" | tr '[:lower:]' '[:upper:]' | tr -d '\\')
  case "$ALLOWED_UPPER" in
    *"$token_upper"*) continue ;;
  esac
  THIRD_PARTY="${THIRD_PARTY:+$THIRD_PARTY|}$token"
done
IFS=$OLD_IFS
if [ -z "$THIRD_PARTY" ]; then
  echo "[check-no-payment-bypass][FAIL] 剔除允许渠道之后禁用名单空了 —— 名单写得不对，本门等于没在判。"
  FAIL=1
fi
HITS=$(grep -rniE "$THIRD_PARTY" "${SERVER_DIRS[@]}" "$CLIENT_DIR" 2>/dev/null \
       | grep -Ev '^[^:]*:[0-9]+: *(\*|//|/\*)' || true)
if [ -n "$HITS" ]; then
  echo "[check-no-payment-bypass][FAIL] 发现第三方支付通道："
  echo "$HITS"
  echo "  B15 红线 1：必须使用微信虚拟支付（米大师），禁止任何绕支付方案。"
  FAIL=1
else
  echo "[check-no-payment-bypass] 无第三方支付通道。"
fi

# ---------- 2. 绕支付的典型形态 ----------
echo "[check-no-payment-bypass] 扫描绕支付的典型形态…"
# 「购买实物赠游戏币」「充值卡兑换」「分享解锁付费内容」都是禁止项点名的形态
BYPASS='实物|充值卡|卡密|兑换码.*付费|礼品卡|giftcard|gift_card|voucherCode.*pay|分享解锁.*付费|诱导分享'
HITS2=$(grep -rnE "$BYPASS" "${SERVER_DIRS[@]}" "$CLIENT_DIR" contract/config 2>/dev/null \
        | grep -Ev '^[^:]*:[0-9]+: *(\*|//|/\*)' \
        | grep -v 'check-no-payment-bypass' || true)
if [ -n "$HITS2" ]; then
  echo "[check-no-payment-bypass][FAIL] 发现绕支付形态："
  echo "$HITS2"
  echo "  B15 禁止项：不要用「购买实物赠游戏币」等绕支付方案；不要做诱导分享解锁奖励。"
  FAIL=1
else
  echo "[check-no-payment-bypass] 无绕支付形态。"
fi

# ---------- 3. 金额不得用 double / float ----------
echo "[check-no-payment-bypass] 扫描金额相关的浮点运算…"
# 禁止项：不要用 double 表示金额（用 BigDecimal 或最小货币单位 long：分）
FLOAT_MONEY='(double|float|Double|Float)[^;]*(price|Price|amount|Amount|cents|Cents|fee|Fee|money|Money|cost.*[Cc]urrency)'
HITS3=$(grep -rnE "$FLOAT_MONEY" "${SERVER_DIRS[@]}" "$CLIENT_DIR" 2>/dev/null \
        | grep -Ev '^[^:]*:[0-9]+: *(\*|//|/\*)' || true)
if [ -n "$HITS3" ]; then
  echo "[check-no-payment-bypass][FAIL] 发现用浮点表示金额："
  echo "$HITS3"
  echo "  B15 禁止项：不要用 double 表示金额。0.1 + 0.2 != 0.3 在支付上是致命的："
  echo "  一次对账差一分钱，财务就无法证明账是平的。用 long（分）或 BigDecimal。"
  FAIL=1
else
  echo "[check-no-payment-bypass] 金额没有浮点表示。"
fi

# ---------- 4. 支付订单必须走唯一的幂等入口 ----------
echo "[check-no-payment-bypass] 检查支付订单的幂等入口…"
PAY_ORDER=server/game-core/src/main/java/com/ironoath/core/pay/PayOrder.java
if [ ! -f "$PAY_ORDER" ]; then
  echo "[check-no-payment-bypass][FAIL] 缺少 $PAY_ORDER"
  echo "  验收 2（回调幂等）与验收 3（补单队列）都依赖它。"
  FAIL=1
else
  if ! grep -q "public CallbackOutcome confirmCallback" "$PAY_ORDER"; then
    echo "[check-no-payment-bypass][FAIL] PayOrder 缺少 confirmCallback（回调幂等的唯一入口）"
    FAIL=1
  elif ! grep -q "public FulfillOutcome fulfill" "$PAY_ORDER"; then
    echo "[check-no-payment-bypass][FAIL] PayOrder 缺少 fulfill（发货与补单的唯一入口）"
    FAIL=1
  else
    echo "[check-no-payment-bypass] 回调与发货各有唯一入口（confirmCallback / fulfill）。"
  fi
  # 禁止项：不要在支付回调中做重逻辑。判据是 confirmCallback 里不得出现发货调用
  if grep -A 40 "public CallbackOutcome confirmCallback" "$PAY_ORDER" | grep -q "\.fulfill("; then
    echo "[check-no-payment-bypass][FAIL] confirmCallback 内部调用了 fulfill"
    echo "  B15 禁止项：不要在支付回调中做重逻辑（快速落库后异步发货，避免微信回调超时重试）。"
    echo "  回调里发货一旦超时，微信会重试，重试撞上幂等键，订单就永远停在已支付未发货。"
    FAIL=1
  else
    echo "[check-no-payment-bypass] 回调内不发货（快速落库 + 异步发货）。"
  fi
fi

# ---------- 5. Bot 不得出现在付费弹窗路径（B11 §七 红线，B15 禁止项重申） ----------
echo "[check-no-payment-bypass] 检查付费弹窗的 Bot 拦截…"
THROTTLE=server/game-core/src/main/java/com/ironoath/core/pay/PopupThrottle.java
if [ -f "$THROTTLE" ]; then
  # viewerIsBot 必须是 shouldShow 的必填参数：可选参数会被漏传，而漏传不会报错
  if grep -qE "shouldShow\(String playerId, String giftId, boolean viewerIsBot" "$THROTTLE"; then
    echo "[check-no-payment-bypass] shouldShow 强制要求 viewerIsBot（漏传会编译失败）。"
  else
    echo "[check-no-payment-bypass][FAIL] PopupThrottle.shouldShow 的 viewerIsBot 不是必填参数"
    echo "  B11 §七 / B15 禁止项：不要让 Bot 出现在任何付费弹窗场景。"
    echo "  这个参数必填的意义是让「忘了判断」在编译期就报错。"
    FAIL=1
  fi
fi

if [ "$FAIL" -ne 0 ]; then
  echo "[check-no-payment-bypass] 检查未通过。"
  exit 1
fi
echo "[check-no-payment-bypass] 无绕支付检查通过：通道白名单取自 global.PAY_CHANNEL=${ALLOWED}、金额为 long 分、回调幂等且不做重逻辑、Bot 被强制拦截。"
