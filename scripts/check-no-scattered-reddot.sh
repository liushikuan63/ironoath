#!/usr/bin/env bash
# 职责：B12 验收 2「红点无散落」的静态检查 —— 全局搜索不到业务模块里手写的红点判断。
#
# B12 把红点列为最容易做砸的一块，理由是「后面每加一个功能都要回去改一堆散落的判断，
# 维护成本会指数上升」。而散落的判断不会让任何测试变红：
# 漏改的表现是「有红点点进去却没东西」或「明明有事可做却没有红点」，
# 两种都只会被玩家当成 bug 报上来。所以这条只能靠静态检查兜住。
#
# 判据是「红点相关的标识符只允许出现在红点模块与它的注册点里」。
set -euo pipefail
cd "$(dirname "$0")/.."

FAIL=0

# 红点相关的标识符模式。刻意写得宽：宁可误报让人来看一眼，也不要漏掉一个散落的判断
PATTERN='showRedDot|hasRedDot|setRedDot|redDotVisible|isRedDotOn|reddotVisible|showBadge|hasBadge'

# 允许出现这些标识符的位置：红点模块自身、注册表、以及本检查脚本
ALLOWED_PATHS='core/reddot/|game/reddot/|ReddotRegistr|reddotRegistry|check-no-scattered-reddot'

echo "[check-no-scattered-reddot] 扫描业务代码里的手写红点判断…"
HITS=$(grep -rnE "$PATTERN" \
         server/game-web/src/main/java server/game-core/src/main/java \
         client/assets/scripts 2>/dev/null \
       | grep -Ev "$ALLOWED_PATHS" || true)

if [ -n "$HITS" ]; then
  echo "[check-no-scattered-reddot][FAIL] 业务模块里出现了手写的红点判断："
  echo "$HITS"
  echo ""
  echo "  B12 禁止项：不要在每个业务模块里手写红点判断（统一走红点树）。"
  echo "  正确做法是把条件注册到红点树，而不是在业务代码里自己决定亮不亮："
  echo "    reddotRegistry.register(\"city/building\", playerId -> buildingService.hasUpgradable(playerId));"
  echo "  注册点集中在一处（ReddotRegistrations），业务 Service 只暴露「有没有可做的事」，"
  echo "  不暴露「该不该亮红点」—— 后者是红点树的职责。"
  FAIL=1
else
  echo "[check-no-scattered-reddot] 业务代码里没有手写的红点判断。"
fi

# ---------- 红点树必须存在且被真的用 ----------
TREE=server/game-core/src/main/java/com/ironoath/core/reddot/ReddotTree.java
CLIENT_TREE=client/assets/scripts/game/reddot/ReddotTree.ts
for FILE in "$TREE" "$CLIENT_TREE"; do
  if [ ! -f "$FILE" ]; then
    echo "[check-no-scattered-reddot][FAIL] 缺少红点树实现：$FILE"
    echo "  B12 §4 要求双来源（服务端下发 + 客户端本地计算），两侧都要有树。"
    FAIL=1
  fi
done

# ---------- 客户端不得自己做业务判断来决定红点 ----------
# B12 §4 的分工：客户端只消费红点树。判据是客户端红点模块里不出现任何 Service/Store 的数值比较
echo "[check-no-scattered-reddot] 检查客户端红点模块是否越界做业务判断…"
if [ -f "$CLIENT_TREE" ]; then
  BAD=$(grep -nE "current >=|current <|level >=|power >|\.cap\b" "$CLIENT_TREE" || true)
  if [ -n "$BAD" ]; then
    echo "[check-no-scattered-reddot][FAIL] 客户端红点模块里出现了数值比较："
    echo "$BAD"
    echo "  铁律 2：客户端不做数值判定。红点的条件由服务端注册并下发，"
    echo "  客户端只做「两个来源取或 + 沿父链聚合」。"
    FAIL=1
  else
    echo "[check-no-scattered-reddot] 客户端红点模块没有数值判定，只做聚合。"
  fi
fi

if [ "$FAIL" -ne 0 ]; then
  echo "[check-no-scattered-reddot] 检查未通过。"
  exit 1
fi
echo "[check-no-scattered-reddot] 红点无散落检查通过：判断集中在红点树，业务模块只注册条件。"
