#!/usr/bin/env bash
# 职责：跑"像素法底板压字"这一维的**两端对照** —— 不透明植入必须被检出、完全透明必须不被检出。
# 依赖：node、**已启动的 dev 后端**、已构建的 `client/build/web-mobile`。
#
# 为什么不挂进 scripts/check.sh（2026-09-22 裁定）：
# 静态门是秒级的、不依赖环境；这一维要后端 + 产物 + 约 3 分钟，挂进去会把"环境没就绪"
# 变成"代码有问题"，反而逼人跳过 check.sh。所以它单独一条，按需或定时跑。
#
# 为什么必须跑两端（台账 #413 的直接教训）：
# 只跑"不透明要检出"这一端时，alpha 旋钮整个失效（`?? {}` 把 Color 类退化成 Object）
# 也照样 23/23 全绿，看起来像"这一维极敏感"。加上"透明必须报 0"这一端，
# 旋钮一坏就会露出来 —— 一条不会失败的判据不配当判据。
#
# 用法：BACKEND_ORIGIN=http://localhost:8199 bash scripts/check-plate-plant.sh
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND="${BACKEND_ORIGIN:-}"
if [ -z "$BACKEND" ]; then
  echo "[check-plate-plant] 缺 BACKEND_ORIGIN（dev 约定 http://localhost:8199）：不给就退 2" >&2
  echo "  静默回落到别的后端 = 打到另一台机器上读数（同 LABELFIT_BACKEND 那条纪律，台账 #371/#372）" >&2
  exit 2
fi
if [ ! -f client/build/web-mobile/index.html ]; then
  echo "[check-plate-plant] 没有 web-mobile 产物，先 bash scripts/build-webmobile.sh" >&2
  exit 2
fi
if ! curl -s -o /dev/null --max-time 5 "$BACKEND/"; then
  echo "[check-plate-plant] 后端 $BACKEND 没应答，先起 dev 后端（见本文件头那行 mvn 命令）" >&2
  exit 2
fi

echo "[check-plate-plant] 第一端：不透明底板（alpha 255）必须被检出"
LABELFIT_BACKEND="$BACKEND" PLANT_ALPHA=255 node tools/verify-plate-plant.mjs | tail -3

echo "[check-plate-plant] 第二端：完全透明（alpha 0）必须**不被**检出"
# 这一端期望的是"0 处"，所以脚本自身的退出码不参与判定，改判它打印的计数：
# 只要有任何一相在透明板上报告了变化，就是量具自己出问题（旋钮失效 / 阈值形同虚设）。
TRANSPARENT_OUT="$(LABELFIT_BACKEND="$BACKEND" PLANT_ALPHA=0 node tools/verify-plate-plant.mjs || true)"
DETECTED="$(printf '%s\n' "$TRANSPARENT_OUT" | grep -oE '合格 [0-9]+ / [0-9]+' | tail -1 | grep -oE '[0-9]+' | head -1)"
if [ -z "$DETECTED" ]; then
  echo "[check-plate-plant] 第二端没读到计数行 ⇒ 判红（拿不到数就是没验）" >&2
  printf '%s\n' "$TRANSPARENT_OUT" | tail -5 >&2
  exit 1
fi
if [ "$DETECTED" != "0" ]; then
  echo "[check-plate-plant] 第二端判红：完全透明的植入被 $DETECTED 个相位当成'盖住'" >&2
  echo "  ⇒ 植入旋钮失效或阈值形同虚设，第一端的绿不可信（台账 #413）" >&2
  exit 1
fi
echo "[check-plate-plant] 两端都对：不透明全检出、透明零误报 ⇒ 这一维的绿是可以信的"
