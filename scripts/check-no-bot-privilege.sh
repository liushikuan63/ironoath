#!/usr/bin/env bash
# 职责：B11 验收 8「无特权捷径」+ §七 合规红线的静态检查。
#
# B11 的头号铁律是「Bot 必须走与真人完全相同的 service 层，禁止直改数据库、
# 禁止免资源/免冷却等特权捷径」，验收 8 明写这条要「静态检查（加 CI）」。
#
# 为什么必须是静态检查而不是单测：单测只能覆盖它想到的路径，
# 而特权捷径的特征恰恰是「某个人在某次赶工时加了一个 if (isBot) 提前 return」——
# 它不会让任何现有测试变红，只会让 Bot 的成长曲线悄悄与真人脱节，
# 于是 B11 存在的唯一理由（用 Bot 验证数值）就没了。
# 这种问题只能靠「代码里根本不允许出现这个模式」来防。
set -euo pipefail
cd "$(dirname "$0")/.."

FAIL=0

# 只扫生产代码：测试里出现 isBot 是正常的（它要断言这个字段不存在）
SERVER_DIRS=(server/game-common/src/main/java server/game-core/src/main/java
             server/game-config/src/main/java server/game-battle/src/main/java
             server/game-web/src/main/java)
CLIENT_DIR=client/assets/scripts

# ---------- 1. 除了 Bot 注册表本身，任何地方都不许问「这是不是 Bot」 ----------
# 排除注释行：文档里大量讨论「为什么不能有 isBot」，那些是理由而不是违规。
# 排除 BotRegistry：B11 的三条合规红线（Bot 不任国家官职、不占排行榜前 3 名的奖励坑位、
# 不出现在付费弹窗场景）本身就需要这个判定，所以问题不是「不许问」，而是「只许在一个地方问」。
# **白名单只放行定义处，所有调用点仍然被扫描** —— 游戏逻辑里写 if (registry.isBot(x))
# 去开特权分支，照样会在这里被抓住。这正是白名单只放一个文件的原因。
echo "[check-no-bot-privilege] 扫描 isBot 字段与分支…"
BOT_IDENTITY_SOURCE='web/bot/BotRegistry\.java'
HITS=$(grep -rn "isBot" "${SERVER_DIRS[@]}" "$CLIENT_DIR" 2>/dev/null \
       | grep -v '^[^:]*:[0-9]*: *\*' \
       | grep -v '^[^:]*:[0-9]*: *//' \
       | grep -v '^[^:]*:[0-9]*: */\*' \
       | grep -Ev "$BOT_IDENTITY_SOURCE" || true)
if [ -n "$HITS" ]; then
  echo "[check-no-bot-privilege][FAIL] 在 Bot 注册表之外发现了 isBot 的使用："
  echo "$HITS"
  echo "  B11 禁止项：不要为 Bot 写任何 if (isBot) 的特权分支；不要把 isBot 下发给客户端。"
  echo "  Bot 的差异必须表达为参数（成长系数 / 延迟 / 失误率），而不是代码路径。"
  echo "  合规判定（不任官职 / 不占奖励坑位 / 不进付费场景）请调 web/bot/BotRegistry，"
  echo "  那是唯一被允许回答这个问题的地方 —— 两处各判一次的话，其中一处迟早会漏。"
  FAIL=1
else
  echo "[check-no-bot-privilege] 除 BotRegistry 外无 isBot 字段或分支（注释里的讨论不计）。"
fi

# ---------- 2. 决策引擎不得依赖任何仓储 / 钱包 / service ----------
# 这是「Bot 想开捷径也没有入口」的类型层保证：BotDecisionTree 只依赖 Rng 与 FixedPoint，
# 拿不到仓储就拿不到「直接改数据库」的能力，拿不到钱包就拿不到「免消耗」的能力。
echo "[check-no-bot-privilege] 检查决策引擎的依赖面…"
TREE=server/game-core/src/main/java/com/ironoath/core/bot/BotDecisionTree.java
if [ ! -f "$TREE" ]; then
  echo "[check-no-bot-privilege][FAIL] 找不到 $TREE"
  FAIL=1
else
  BAD_IMPORTS=$(grep -E "^import" "$TREE" \
    | grep -Ei "Repository|Store|Wallet|Service|MongoTemplate|Redis|Resource[A-Z]" || true)
  if [ -n "$BAD_IMPORTS" ]; then
    echo "[check-no-bot-privilege][FAIL] BotDecisionTree 依赖了执行层类型："
    echo "$BAD_IMPORTS"
    echo "  决策与执行必须分开：执行走 BotActionPort，由 game-web 转调与真人相同的 service。"
    echo "  决策引擎里出现仓储或钱包，就意味着它可以绕过 service 层直接改数据。"
    FAIL=1
  else
    echo "[check-no-bot-privilege] BotDecisionTree 只依赖随机源与定点数，没有执行入口。"
  fi
fi

# ---------- 3. game-core 的 bot 包整体不得依赖框架与存储 ----------
echo "[check-no-bot-privilege] 检查 game-core/bot 的分层纯净性…"
BOT_PKG=server/game-core/src/main/java/com/ironoath/core/bot
if [ -d "$BOT_PKG" ]; then
  BAD=$(grep -rnE "^import (org\.springframework|com\.mongodb|org\.redisson|jakarta\.)" "$BOT_PKG" || true)
  if [ -n "$BAD" ]; then
    echo "[check-no-bot-privilege][FAIL] game-core/bot 引入了框架依赖："
    echo "$BAD"
    FAIL=1
  else
    echo "[check-no-bot-privilege] game-core/bot 无框架依赖（纯 Java，可脱离容器单测）。"
  fi
fi

# ---------- 4. 三条合规红线必须有可执行的判定点 ----------
# §七 的红线是「违反即视为任务失败」，所以不能只写在文档里：
# 必须有方法能被调用，而且这些方法必须真的被引用（否则就是死代码，等于没有）。
echo "[check-no-bot-privilege] 检查合规红线的可执行判定…"
TUNING=server/game-core/src/main/java/com/ironoath/core/bot/BotTuning.java
for METHOD in mayHoldOffice mayEnterRankTop mayAppearIn; do
  if ! grep -q "public boolean ${METHOD}" "$TUNING"; then
    echo "[check-no-bot-privilege][FAIL] BotTuning 缺少合规判定方法 ${METHOD}"
    echo "  §七 红线（不得任官职 / 不得占前 3 名 / 不得出现在付费场景）必须有可执行的判定点，"
    echo "  写在文档里的红线会在某次重构中被悄悄绕过。"
    FAIL=1
  fi
done
if [ "$FAIL" -eq 0 ]; then
  echo "[check-no-bot-privilege] 三条合规红线都有可执行判定（mayHoldOffice / mayEnterRankTop / mayAppearIn）。"
fi

# ---------- 5. 白名单必须默认拒绝 ----------
# mayAppearIn 用 switch + default:false 实现。若有人改成 default:true，
# 新增一个付费场景时它会静默放行 —— 而新增付费场景恰恰是最频繁的那类改动。
if grep -A 40 "public boolean mayAppearIn" "$TUNING" | grep -q "default:"; then
  if grep -A 40 "public boolean mayAppearIn" "$TUNING" | grep -A 1 "default:" | grep -q "return false"; then
    echo "[check-no-bot-privilege] 场景白名单默认拒绝（新增场景忘记登记时落在拒绝侧）。"
  else
    echo "[check-no-bot-privilege][FAIL] mayAppearIn 的 default 分支不是 return false"
    FAIL=1
  fi
fi

if [ "$FAIL" -ne 0 ]; then
  echo "[check-no-bot-privilege] 检查未通过。"
  exit 1
fi
echo "[check-no-bot-privilege] Bot 无特权捷径检查通过：无 isBot 分支、决策层无执行入口、合规红线可执行。"
