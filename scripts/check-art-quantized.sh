#!/usr/bin/env bash
# 职责：`client/assets/resources/ui/generated/**` 下**没有一张大图是没量化过的**。
#       判据：单张 > 120KB 且 PNG 色彩模式不是调色板（IHDR color type ≠ 3）⇒ 点名并退 1。
#
# 为什么值得当门（规格 §二 包体预算）：这条原本只是文档里的一句话 ——
# 「小件已量化到 2.4~4.5KB 的 P 模式，`panel-kingdom-v1` 是 RGBA 170.9KB 的存量例外」。
# 「已经量化了」一旦没人核，就会长成"新素材照抄旧素材的 RGBA 交付"：
# `art-src/accept_to_runtime.py` 只在**收编那一步**调色，绕过它直接把图拷进包就没人管了。
# 素材是逐格往里加的（V25 一共 18 张），靠人记得跑管线必然漏。
#
# 白名单不是免责条：每条必须带 `路径|理由|#台账编号`。理由为空、编号缺、或**指向的文件已经不在了**
# 都判红 —— 白名单自己会烂（文件删了条目还留着 = 给下一个越界文件开的后门），所以它也在被检之内。
#
# 判据能失败的两条证明（每次跑都自证，走的是同一支 classify，不是另抄一遍逻辑）：
#   ① 取一张真彩色 PNG 用 truncate 撑到超阈值 ⇒ classify 必须给 VIOLATION；
#   ② 取一张调色板 PNG 同样撑到超阈值 ⇒ classify 必须放过。
# ①不红＝谓词空转（扫不到东西也退 0 的那种假绿）；②红＝把量化过的素材也判红了，门会误杀。
#
# 退出码约定（沿用「量具崩≠红」）：0=通过，1=点名违规，2=前置不满足（目录/样本缺失，**不是通过**）。
# ARTQ_ROOT / ARTQ_LIMIT_KB 是给取证与移植用的旋钮，**不设时逐字节等同默认行为**。
set -uo pipefail
cd "$(dirname "$0")/.."

ROOT="${ARTQ_ROOT:-client/assets/resources/ui/generated}"
LIMIT_KB="${ARTQ_LIMIT_KB:-120}"
LIMIT_BYTES=$((LIMIT_KB * 1024))

# 白名单：路径 | 为什么不能量化 | 台账编号。
# ⚠️ 三张的理由都带**现跑实测数**（uniqRGBA / uniqAlpha，PIL 量出来的），不是"软边所以别动"这种感觉值：
#    规格 §二 要的是"说明为什么不能量化"，而实测到的 alpha 档数才是那句话的证据。
ALLOWLIST=()
# 2026-10-10 统一母版重制：Kingdom 已与其他四种面板采用同一张量化薄框，
# 28573B / colorType=3；旧 RGBA 豁免失效，分类与两条自证继续原样执行。
# ⚠️ 名单只放"现在还做不到"的：icons-atlas 与 terrain-atlas 曾在名单里，2026-10-08 量化实测
#    （运行时截图对照：图标 0.00% 像素变化；地形按游戏内 64 格目视无差）通过后就删条目。
#    做不到的事一旦做成而条目留着 = 给下一个越界文件开的后门 ⇒ 下面"条目已不需要"那条判红管住它。

if [ ! -d "$ROOT" ]; then
  echo "[art-quantized][FAIL] 扫描根不存在：$ROOT —— 前置不满足，这**不是通过**（退出码 2）" >&2
  exit 2
fi

png_mode() {
  # PNG IHDR 的 color type 固定在第 25 字节（0 起）：签名 8 + chunk 长度 4 + "IHDR" 4
  # + width 4 + height 4 + bit depth 1 ⇒ 下一字节就是 color type。
  # 3 = 调色板（P）；其余（0 灰度 / 2 真彩色 / 4 灰+alpha / 6 真彩+alpha）都是逐像素，未经量化。
  od -An -tu1 -j25 -N1 "$1" 2>/dev/null | tr -d '[:space:]'
}

is_allowed() {
  local rel="$1" entry
  for entry in "${ALLOWLIST[@]}"; do
    [[ "${entry%%|*}" == "$rel" ]] && return 0
  done
  return 1
}

# classify <png 绝对/相对路径> ⇒ 输出一行：SKIP / PALETTE / VIOLATION <详情>
# 正式扫描与两条自证都走它，自证才真的在证这条门。
classify() {
  local f="$1" size mode kb rel
  size=$(stat -c %s "$f") || { echo "READFAIL $f"; return; }
  [ "$size" -gt "$LIMIT_BYTES" ] || { echo "SKIP"; return; }
  mode="$(png_mode "$f")"
  if [ -z "$mode" ]; then
    echo "READFAIL $f"
    return
  fi
  if [ "$mode" = "3" ]; then
    echo "PALETTE"
    return
  fi
  rel="${f#./}"
  kb=$((size / 1024))
  echo "VIOLATION $rel ${kb}KB colorType=$mode"
}

# ---------- 白名单自身的完整性 ----------
STATUS=0
for entry in "${ALLOWLIST[@]}"; do
  rel="${entry%%|*}"
  rest="${entry#*|}"
  reason="${rest%|*}"
  ticket="${rest##*|}"
  [ -f "$rel" ] || { echo "[art-quantized][FAIL] 白名单条目指向的文件已不存在：$rel（条目该删，留着就是后门）"; STATUS=1; }
  # 条目"已不需要"也要判红：这张图一旦被量化（或被换成小图），豁免就该跟着消失 ——
  # 否则下一个把同名文件重新导出成 RGBA 的人会直接通过。
  if [ -f "$rel" ]; then
    verdict="$(classify "$rel")"
    if [ "${verdict#VIOLATION}" = "$verdict" ]; then
      echo "[art-quantized][FAIL] 白名单条目已不需要（现跑判定＝$verdict）：$rel —— 删掉这条豁免"
      STATUS=1
    fi
  fi
  [ -n "$reason" ] || { echo "[art-quantized][FAIL] 白名单条目没有理由：$rel"; STATUS=1; }
  [[ "$ticket" =~ ^#[0-9]+$ ]] || { echo "[art-quantized][FAIL] 白名单条目没带台账编号（应为 #NNN，实际：$ticket）：$rel"; STATUS=1; }
done
[ "$STATUS" -eq 0 ] || exit 1

# ---------- 正式扫描 ----------
VIOLATIONS=()
SEEN=0
OVER_LIMIT=0
PASSED_PALETTE=0
READFAILS=()
while IFS= read -r f; do
  SEEN=$((SEEN + 1))
  verdict="$(classify "$f")"
  case "$verdict" in
    SKIP) ;;
    PALETTE) OVER_LIMIT=$((OVER_LIMIT + 1)); PASSED_PALETTE=$((PASSED_PALETTE + 1)) ;;
    VIOLATION\ *)
      OVER_LIMIT=$((OVER_LIMIT + 1))
      detail="${verdict#VIOLATION }"
      # find 的输出的就是仓库相对路径（ROOT 是相对路径传的），直接拿它比对白名单，
      # 不再自己拼层数 —— 拼 dirname/basename 只会在一目录变深时静默对不上（对不上＝白名单失效＝假红）。
      is_allowed "$f" || VIOLATIONS+=("$detail")
      ;;
    READFAIL\ *) READFAILS+=("$verdict") ;;
  esac
done < <(find "$ROOT" -type f -name '*.png' | sort)

if [ "${#READFAILS[@]}" -gt 0 ]; then
  printf '[art-quantized][FAIL] 读不到 PNG 头：%s —— 前置不满足，不是通过\n' "${READFAILS[*]}" >&2
  exit 2
fi
if [ "$SEEN" -eq 0 ]; then
  echo "[art-quantized][FAIL] 在 $ROOT 里一张 PNG 都没扫到 —— 谓词失效时判红而不是判绿" >&2
  exit 1
fi

# ---------- 两条自证（同一支 classify）----------
SAMPLE_RGBA=""
SAMPLE_PAL=""
while IFS= read -r f; do
  m="$(png_mode "$f")"
  if [ "$m" = "3" ]; then
    [ -z "$SAMPLE_PAL" ] && SAMPLE_PAL="$f"
  elif [ -n "$m" ]; then
    [ -z "$SAMPLE_RGBA" ] && SAMPLE_RGBA="$f"
  fi
  [ -n "$SAMPLE_RGBA" ] && [ -n "$SAMPLE_PAL" ] && break
done < <(find "$ROOT" -type f -name '*.png' | sort)
if [ -z "$SAMPLE_PAL" ]; then
  echo "[art-quantized][FAIL] 找不到调色板样本 ⇒ 自证跑不了，不算通过" >&2
  exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ -n "$SAMPLE_RGBA" ]; then
  cp "$SAMPLE_RGBA" "$TMP/rgba.png"
else
  # 统一采用版全部已量化，不能为了量具保留一张不量化的生产素材。
  # 使用合法1×1 RGBA PNG技术夹具（IHDR colorType=6），仍走下面同一支classify。
  # 原扫描/阈值/调色板样本与撑大反证不变，不将正确的全P目录判成前置不足。
  printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGOQ0zD6DwACWAF41TTQUQAAAABJRU5ErkJggg==' | base64 --decode > "$TMP/rgba.png"
fi
cp "$SAMPLE_PAL" "$TMP/palette.png"
truncate -s $((LIMIT_BYTES + 4096)) "$TMP/rgba.png" "$TMP/palette.png"
CANARY_RGBA="$(classify "$TMP/rgba.png")"
CANARY_PAL="$(classify "$TMP/palette.png")"
if [ "${CANARY_RGBA#VIOLATION}" = "$CANARY_RGBA" ]; then
  echo "[art-quantized][FAIL] 自证①红：撑到超阈值的真彩色样本 classify 给的是「$CANARY_RGBA」而不是 VIOLATION ⇒ 判据空转" >&2
  exit 1
fi
if [ "$CANARY_PAL" != "PALETTE" ]; then
  echo "[art-quantized][FAIL] 自证②红：调色板样本 classify 给的是「$CANARY_PAL」而不是放过 ⇒ 这道门会误杀量化过的素材" >&2
  exit 1
fi

# ---------- 汇报 ----------
if [ "${#VIOLATIONS[@]}" -gt 0 ]; then
  echo "[art-quantized][FAIL] 以下素材超过 ${LIMIT_KB}KB 且不是调色板（P）模式："
  for v in "${VIOLATIONS[@]}"; do
    echo "  - $v"
  done
  echo "  ⇒ 走 art-src/accept_to_runtime.py 收编（它会调色），或按 §二 在白名单里带理由与台账编号登记"
  exit 1
fi

echo "[art-quantized] 通过：扫 $SEEN 张，超 ${LIMIT_KB}KB 的 $OVER_LIMIT 张里 $PASSED_PALETTE 张已是调色板(P)、$((OVER_LIMIT - PASSED_PALETTE)) 张在白名单内（带理由与台账编号）；两条自证成立（真彩色样本判红、调色板样本放过）"
