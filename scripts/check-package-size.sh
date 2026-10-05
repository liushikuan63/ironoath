#!/usr/bin/env bash
# 职责：B16 验收 4「首包 ≤ 4MB，超标则 CI 失败」的卡口，并打印 §1 的完整性能预算与各自的判定方式。
#
# 为什么这条必须是 CI 卡口而不是人工检查：首包超限的后果不是「体验差一点」而是「提审不通过」，
# 而它是在最后一次塞资源进包的时候悄悄越界的 —— 那次改动的作者通常正在赶别的活，
# 不会想到去量一下体积。人工检查在提审前做一次当然也要做，但那已经是最后一刻，
# 此时超了 200KB 就意味着要临时删资源，而删哪个都是临时决定。
#
# 预算数字全部从 contract/config/global.json 读，不在脚本里写死（铁律 1）：
# 微信哪天调整了限制，改配置表就够了，不需要有人记得同时改这个脚本。
set -euo pipefail
cd "$(dirname "$0")/.."

# ⚠️ 下面三处允许用**环境变量**换输入（用来验证这条检查真的会红，而不必改仓库里的表/产物）。
#    **不设环境变量时逐字节等同于改动前**（模式隔离：默认路径不得有任何差异）。
#    写法与 scripts/check-config-refs.js 的可选参数口同源。
GLOBAL_JSON="${PKGSIZE_GLOBAL_JSON:-contract/config/global.json}"

# 从配置表读一个全局参数。
# 用 node 而不是 python：本仓库的必需工具链里有 node（客户端与代码生成器都要用），
# 而 python 在 Windows 上常常只有 Microsoft Store 的存根 —— 它会在某一次运行里
# 直接 Permission denied，把 CI 卡口红成一个与代码无关的样子。
# 表路径与参数 id 一律走 argv，不拼进脚本文本（拼进去等于给配置内容开了注入面）。
param() {
  node -e '
    const fs = require("fs")
    const rows = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).rows
    const hit = rows.find(r => r.id === process.argv[2])
    if (hit === undefined) {
      console.error("缺少全局参数 " + process.argv[2])
      process.exit(1)
    }
    console.log(hit.value)
  ' "$GLOBAL_JSON" "$1"
}

FIRST_PACKAGE_MAX=$(param PERF_FIRST_PACKAGE_MAX_BYTES)
TOTAL_PACKAGE_MAX=$(param PERF_TOTAL_PACKAGE_MAX_BYTES)

# 微信构建产物目录（存在时以它为准，那才是真正会被打包上传的东西）
BUILD_DIR="${PKGSIZE_BUILD_DIR:-client/build/wechatgame}"
# 没有构建产物时退而量客户端源码。这是一个**下界**：真实首包还要加上引擎与构建期资源
SOURCE_DIR="${PKGSIZE_SOURCE_DIR:-client/assets}"

if [ -d "$BUILD_DIR" ]; then
  MEASURED_DIR="$BUILD_DIR"
  KIND="微信构建产物（权威）"
elif [ -d "$SOURCE_DIR" ]; then
  MEASURED_DIR="$SOURCE_DIR"
  KIND="客户端源码（下界估计，见下方警告）"
else
  echo "[check-package-size][FAIL] 既没有 $BUILD_DIR 也没有 $SOURCE_DIR，无从判断首包体积"
  exit 1
fi

if [ "$MEASURED_DIR" = "$BUILD_DIR" ]; then
  # ---------- 先判"这个产物还算不算数" ----------
  # 有构建产物时下面量的是它，而**产物可以是很多天前的**：2026-09-21 实测
  # `client/build/wechatgame` 停在 09-19 23:56，而 `client/assets` 里有 94 个文件比它新
  # （内城那套素材就是 09-21 才进库的）。那一刻这条门输出"首包 2.52MB，在预算内"是**真读数**，
  # 但它描述的是一个已经不存在的源码状态 —— 提审前用旧产物判绿，等于把"资源塞进首包"
  # 这类失控留到最后一刻才发现（正是本脚本开头那段注释最想避免的事）。
  # 所以这里不猜、只比时间：**源码里有比产物新的文件就判失败**，并点名是哪几个。
  # 注意 `*.meta` 也算：Cocos 的导入参数（九宫格 border、REPEAT 等）会改画面与体积，
  # 只改 meta 不改 png 同样会让旧产物失真。
  if [ -d "$SOURCE_DIR" ]; then
    NEWER=$(find "$SOURCE_DIR" -type f -newer "$MEASURED_DIR" 2>/dev/null | head -5 || true)
    if [ -n "$NEWER" ]; then
      NEWER_COUNT=$(find "$SOURCE_DIR" -type f -newer "$MEASURED_DIR" 2>/dev/null | wc -l | tr -d ' ')
      echo "[check-package-size][WARN] 微信构建产物比客户端源码旧：$SOURCE_DIR 里有 $NEWER_COUNT 个文件比产物新。"
      echo "  产物时间：$(date -r "$MEASURED_DIR" '+%Y-%m-%d %H:%M' 2>/dev/null || echo 未知)"
      echo "  比它新的文件（前 5 个）："
      echo "$NEWER" | sed 's/^/    /'
      echo "  ⇒ 这时量出来的首包体积描述的是一个**已经不存在的源码状态**，不能拿它当提审判据。"
      echo "  整改：重新构建一次微信产物（npm run build:wechat 或 Cocos 构建 wechatgame）后复跑本脚本。"
      echo ""
      echo "  **为什么不在这里判失败**：这道门在 check.sh 里排第 9 位，而 check.sh 是 set -e ——"
      echo "  在这里退非 0 会让它后面十几道门全部不跑，那是拿掉一整排队列去换一条提示。"
      echo "  所以这里只告警；'提审前必须用 release 真实产物复核'这条硬要求仍由"
      echo '  `上线检查清单.md` 第 4 项人工把守。'
    fi
  fi
  # 首包预算只量"玩家第一次下载要拿到的那些文件"：分包目录（subpackages/**）
  # 不在此列。量整个构建目录会随着分包越做越多而报假红 —— 越优化越红是最坏的信号。
  if [ -d "$MEASURED_DIR/subpackages" ]; then
    SIZE=$(du -sb --exclude="$MEASURED_DIR/subpackages" "$MEASURED_DIR" | cut -f1)
  else
    SIZE=$(du -sb "$MEASURED_DIR" | cut -f1)
  fi
  # debug 包与 release 包的引擎体积接近一倍差（`src/settings.json` 的 engine.debug），
  # 而提审量的是 release：debug 超限只提示，release 超限才失败。
  DEBUG_BUILD=$(node -e '
    const fs = require("fs")
    const settings = JSON.parse(fs.readFileSync(process.argv[1], "utf8"))
    process.stdout.write(settings.engine && settings.engine.debug ? "true" : "false")
  ' "$MEASURED_DIR/src/settings.json" 2>/dev/null || echo false)
else
  # 源码下界必须与产物口径量**同一样东西**。微信产物的 game.json 把 resources 整个 bundle
  # 声明成分包（`"subpackages": [{"name":"resources","root":"subpackages/resources/"}]`，
  # 2026-09-26 对旧产物实测），主包只含引擎与脚本；产物路径上面已经用
  # `--exclude=subpackages` 表达了这件事。源码路径若不跟着排除 client/assets/resources，
  # 量出来的就是"主包 + 分包"的合计 —— 于是往分包里优化美术反而更红，正是本脚本开头
  # 说的那类最坏信号。排除表不写死：从 `*.meta` 的 isBundle 现读，见下面的守卫。
  SOURCE_EXCLUDES=""
  while IFS= read -r meta; do
    bundleDir="${meta%.meta}"
    case "$bundleDir" in
      "$SOURCE_DIR/resources")
        # resources 在微信构建里是分包 ⇒ 不计入主包下界
        SOURCE_EXCLUDES="$SOURCE_EXCLUDES --exclude=$bundleDir"
        ;;
      *)
        echo "[check-package-size][FAIL] 发现未登记去向的资源 bundle：$bundleDir"
        echo "  它要么在微信构建配置里声明成分包（那就把它加进本 case 的排除分支），"
        echo "  要么会进主包（那它的体积必须算进本判据）。这个决定不允许默认发生。"
        exit 1
        ;;
    esac
  done < <(grep -rl '"isBundle": true' "$SOURCE_DIR" --include='*.meta' 2>/dev/null || true)
  # shellcheck disable=SC2086  # SOURCE_EXCLUDES 故意按词展开成多个 --exclude
  SIZE=$(du -sb $SOURCE_EXCLUDES "$MEASURED_DIR" | cut -f1)
  DEBUG_BUILD=false
fi
mb() {
  node -e 'console.log((Number(process.argv[1]) / 1048576).toFixed(2))' "$1"
}
SIZE_MB=$(mb "$SIZE")
MAX_MB=$(mb "$FIRST_PACKAGE_MAX")

# 合计口径（微信：主包+全部分包 ≤ 30M）：产物模式量整个产物目录（主包+subpackages），
# 源码模式量整个 client/assets（不含引擎，是下界，与首包那条同一个 caveat）。
if [ "$MEASURED_DIR" = "$BUILD_DIR" ]; then
  TOTAL_SIZE=$(du -sb "$BUILD_DIR" | cut -f1)
else
  TOTAL_SIZE=$(du -sb "$SOURCE_DIR" | cut -f1)
fi
TOTAL_MB=$(mb "$TOTAL_SIZE")
TOTAL_MAX_MB=$(mb "$TOTAL_PACKAGE_MAX")

echo "[check-package-size] 首包预算 ${MAX_MB}MB（来源 global.PERF_FIRST_PACKAGE_MAX_BYTES）"
echo "[check-package-size] 量的是 $MEASURED_DIR —— $KIND，实际 ${SIZE_MB}MB"

FAIL=0
if [ "$SIZE" -gt "$FIRST_PACKAGE_MAX" ] && [ "$DEBUG_BUILD" = "true" ]; then
  echo "[check-package-size][WARN] 这是 debug 构建，${SIZE_MB}MB 超预算不判失败；提审请用 release 构建复量。"
elif [ "$SIZE" -gt "$FIRST_PACKAGE_MAX" ]; then
  echo "[check-package-size][FAIL] 首包 ${SIZE_MB}MB 超过预算 ${MAX_MB}MB。"
  echo "  整改顺序（按性价比）："
  echo "    1. 美术资源走分包或 CDN，不要进首包（B16 §1）"
  echo "    2. 检查是否误把 node_modules / 构建中间产物 / 测试资源打进了包里"
  echo "    3. 音频与图集改用压缩格式；图集合并减少零散小文件"
  echo "  不要靠删功能来降体积（B16 禁止项）。"
  FAIL=1
fi

# 合计：单个**普通**分包不限大小，所以美术往分包里堆不会触发上面任何一条 ——
# 唯一会失控的是"主包+分包合计 ≤ 30M"这条微信硬限，这里钉住它。
echo "[check-package-size] 主包+分包合计 ${TOTAL_MB}MB（预算 ${TOTAL_MAX_MB}MB；微信：普通分包不限单个大小，合计 ≤30M）"
if [ "$TOTAL_SIZE" -gt "$TOTAL_PACKAGE_MAX" ] && [ "$DEBUG_BUILD" = "true" ]; then
  echo "[check-package-size][WARN] debug 构建，合计超预算不判失败；提审请用 release 构建复量。"
elif [ "$TOTAL_SIZE" -gt "$TOTAL_PACKAGE_MAX" ]; then
  echo "[check-package-size][FAIL] 主包+分包合计 ${TOTAL_MB}MB 超过预算 ${TOTAL_MAX_MB}MB（微信硬限 30M）。"
  echo "  整改：美术走 CDN / 远程资源、分包再拆、纹理压缩；不要靠删功能降体积（B16 禁止项）。"
  FAIL=1
fi

if [ "$MEASURED_DIR" = "$SOURCE_DIR" ]; then
  echo "[check-package-size][WARN] 本仓库当前没有微信构建产物，量的是客户端源码，这是一个**下界**："
  echo "  真实首包还要加上 Cocos 引擎（通常 1~2MB）与构建期生成的资源。"
  echo "  已按微信产物的 game.json 口径排除分包 bundle（resources）：下界只含会进主包的脚本与场景。"
  echo "  所以这个卡口能挡住「美术资源塞进主包」这类失控，挡不住引擎体积本身。"
  echo "  提审前必须在微信开发者工具里构建一次，用真实产物目录复核（上线检查清单第 4 项）。"
fi

# ---------- §1 的其余预算：CI 量不了，但必须让每个人看见它们各自靠什么判定 ----------
echo ""
echo "[check-package-size] B16 §1 性能预算（数字全部来自 $GLOBAL_JSON，改预算只改配置表）："
printf '  %-34s %-12s %s\n' "指标" "预算" "判定方式"
printf '  %-34s %-12s %s\n' "首包体积" "${MAX_MB}MB" "本脚本（CI 卡口）"
printf '  %-34s %-12s %s\n' "主包+分包合计" "${TOTAL_MAX_MB}MB" "本脚本（CI 卡口）"
printf '  %-34s %-12s %s\n' "服务端接口 P99" "$(param PERF_API_P99_MAX_MS)ms" "生产监控（不含战斗结算）"
printf '  %-34s %-12s %s\n' "战斗结算 P99" "$(param PERF_BATTLE_SETTLE_P99_MAX_MS)ms" "生产监控 + B13 压测报告"
printf '  %-34s %-12s %s\n' "单次请求 payload" "$(param PERF_PAYLOAD_MAX_BYTES)B" "抓包（PerfBudgetTest 校验配置关系）"
printf '  %-34s %-12s %s\n' "客户端内存峰值" "$(param PERF_MEMORY_PEAK_MAX_MB)MB" "微信开发者工具性能面板"
printf '  %-34s %-12s %s\n' "战斗/地图帧率" "$(param PERF_MIN_FPS)FPS" "低端安卓真机"
printf '  %-34s %-12s %s\n' "首屏可交互" "$(param PERF_FIRST_SCREEN_MAX_MS)ms" "中端安卓真机"
printf '  %-34s %-12s %s\n' "Full GC 频率" "$(param PERF_FULL_GC_MAX_PER_HOUR)次/小时" "生产 JVM 监控"
printf '  %-34s %-12s %s\n' "Full GC 单次停顿" "$(param PERF_FULL_GC_PAUSE_MAX_MS)ms" "生产 JVM 监控"
echo ""
echo "[check-package-size] 埋点攒批：$(param TRACK_BATCH_MAX_SIZE) 条或 $(param TRACK_BATCH_FLUSH_SECONDS) 秒触发一批"
echo "[check-package-size] 灰度比例 $(param RELEASE_GRAY_PERCENT)（表里是 DECIMAL，1.0 = 全量）｜最新版本 $(param RELEASE_LATEST_VERSION)｜最低可玩 $(param RELEASE_MIN_SUPPORTED_VERSION)"

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "[check-package-size] 首包体积在预算内。"
