#!/usr/bin/env bash
# 职责：图标在**背包真实显示尺寸 26px** 下两两可辨（判据本体在 art-src/check_icon_legibility.py）。
# 为什么值得当门：图标只在 512px 母版上比过是假的 —— 三张加速令的母版一眼能分，
# 缩到 26px 后 Δ=11.9，玩家看到的就是三张一样的羊皮纸卷。这类缺陷不会让任何测试变红，
# 只有玩家会拿错道具。收口清单里 A10 那格记的第一次跑就抓到了这条。
# 退出码沿用"量具崩≠红"的约定：1=撞脸（逐条打印），2=前置不满足（缺 Pillow / 目录不对）。
set -uo pipefail
cd "$(dirname "$0")/.."

STATUS=0
for DIR in items equip heroes activities; do
  PYTHONIOENCODING=utf-8 python art-src/check_icon_legibility.py \
    --dir "client/assets/resources/ui/generated/$DIR" || STATUS=$?
  if [ "$STATUS" -ne 0 ]; then
    echo "[icon-legibility] $DIR 族未通过（退出码 $STATUS）"
    break
  fi
done
exit "$STATUS"
