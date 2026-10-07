#!/usr/bin/env bash
# 职责：CI 静态检查 = 分层纯净性 + 双端契约一致性 + 契约 $defs 同名同形（生成物同包，后一份整份覆盖前一份）
#       + 禁止施舍机制（B08 验收 10）
#       + Bot 无特权捷径（B11 验收 8）+ 红点无散落（B12 验收 2）
#       + 无绕支付（B15 验收 10）+ 首包体积（B16 验收 4）
#       + 埋点覆盖率（B16 验收 3：UI 动作清单比对，漏埋点=看板那一环永远为 0）
#       + 端点路径一致性（客户端绑的路径服务端必须存在，否则点一下就是 404）
#       + 收口清单表格形状（清单是唯一活得过会话的待修载体，而多出来的单元格会被渲染整格丢掉）
#       + 错误码唯一性与段位归属（码值重了客户端分不开，跑出错段等于把两个系统的号段混一起）
#       + 配置表装配（每张表都要有生产代码读它 —— 没人读的表就是"改了不生效、也不报错"的装饰品）。
#       + 客户端不得对迭代器做 spread（Cocos 的转译不展开迭代器，而单测走 tsc 会真展开 ⇒ 
#       + 客户端逻辑层单测全量（项数**以现跑输出为准**，2026-10-06 实测 1029 项：类型门只保证"能编译"，不保证"算对了"）只在真机炸）。
#       + 服务端不得出现 @Scheduled（惰性驱动是本仓库的时间不变量，B00 陷阱 2）。
#       + 玩家可见文案禁工程黑话（这类字符串没有功能影响，只有玩家会读到 —— 见 #217）。
#       + 图标在背包显示尺寸 26px 下两两可辨（母版一眼能分的两张图缩到 26px 可能完全同形 —— 见 #228）。
#       + public 方法不许收了 playerId 却不用它（身份被静默丢弃 = 越权读改，编译与单测都看不见 —— 见 #256）。
#       + Mongo 存档的 save 必须覆盖 Document 的每个字段（白名单漏一个 = 只在真 Mongo 上静默丢档）。
#       + 微信小游戏产物（方向必须横屏；release 首包必须 ≤ 预算；没有产物则跳过）。
#       + 资源条顺序不变量（资源快照退回散列摆放的 map 时，排列跨进程变而进程内恒定 ⇒ 单测钉不住）。
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/check-layering.sh
bash scripts/check-contract-sync.sh
# 注释里「判据见 XxxTest」指向的类必须存在（台账 #439；这一族清完 31 处后最容易长回来）
bash scripts/check-dangling-test-refs.sh
bash scripts/check-ts-meta.sh
bash scripts/check-eol-policy.sh
bash scripts/check-contract-defs.sh
bash scripts/check-no-handout.sh
bash scripts/check-no-bot-privilege.sh
bash scripts/check-no-scattered-reddot.sh
bash scripts/check-no-payment-bypass.sh
bash scripts/check-package-size.sh
bash scripts/check-track-coverage.sh
bash scripts/check-endpoint-paths.sh
bash scripts/check-permission-bits.sh
bash scripts/check-config-refs.sh
bash scripts/check-checklist-append-only.sh
bash scripts/check-checklist-table.sh
bash scripts/check-probe-coordinate-space.sh
# 这道门自带「植入违规→判红」的自检开关，每轮跑一次 ⇒ 「门是绿的」才有意义
# （0 命中是"没触发"，不等于"判据在工作"；本会话在批跑上栽过同款：汇总全 0 ≠ 跑过了）
SELFTEST=1 bash scripts/check-probe-coordinate-space.sh
bash scripts/check-error-codes.sh
bash scripts/check-config-consumers.sh
bash scripts/check-balance-sim-config-sync.sh
bash scripts/check-balance-sim-tech-sync.sh
bash scripts/check-balance-sim-usage.sh
bash scripts/check-guide-no-copy.sh
bash scripts/check-client-iter-spread.sh
bash scripts/check-client-typecheck.sh
# 客户端逻辑层单测（941 项）：**类型门只保证"能编译"，不保证"算对了"**。CI 里这批用例本来就跑
# （`ci.yml` 跑 `build.sh`，`build.sh` 跑 `test.sh`，`test.sh:11` 才调本脚本），但**本地最常跑的入口是
# `check.sh`，而它以前不跑**——于是"改完公共算式只敲 check.sh"的人拿不到这层保护。这一道把它补在
# 类型门旁边，让两个入口的覆盖面一致（代价：CI 里多跑一遍，约 10 秒）。
# 要 Node 20（`node --test` 的目录形态只在 20 上成立）：CI 用 setup-node 钉死，本地默认 node 若是 24，
# 这一道会**明确报"量具没架对"并给出可粘贴的命令**，不是静默跳过也不是假红。
bash scripts/test-client.sh
bash scripts/check-player-copy-jargon.sh
bash scripts/check-track-dictionary.sh
bash scripts/check-track-params.sh
bash scripts/check-icon-legibility.sh
bash scripts/check-no-scheduled.sh
bash scripts/check-mongo-set-coverage.sh
bash scripts/check-identity-used.sh
bash scripts/check-rank-payload.sh
bash scripts/check-wechat-artifact.sh
bash scripts/check-search-radius.sh
bash scripts/check-web-artifact.sh
bash scripts/check-view-child-index.sh
# 资源条顺序（#616~#618）：PlayerSave.resources() 退回 Map.copyOf 时，顺序按 SALT 散列摆放、
# 跨进程变而进程内恒定 ⇒ 单测永远绿、玩家每次登录看到的排列却可能不同。#618 的单测钉不住这一维。
bash scripts/check-resource-order-invariant.sh
# 批跑覆盖率（#611 / #753 收尾）：批跑脚本此前只收 `tools/verify-*-runtime.mjs`，
# 而"文件名带不带 -runtime"与"要不要真跑"毫无关系 ⇒ 有 23~24 份真该跑的量具从没被批跑到
# （含整个 nation 族），且没有任何检查会红。收集规则已改成全收 + 显式排除名单，
# 这道门守住名单本身：条目必须存在、必须有理由、文件必须以换行结尾、收集规则不得退化回旧形态。
bash scripts/check-runtime-probe-coverage.sh
# 探针脚本的**语法**（#753 收尾）：上面那道门只守「收集与排除名单」，不查脚本本身写得对不对。
# 实测过：一份探针有语法错时 `check.sh` 仍是 EXIT=0「全部静态检查通过」——
# 而探针要起后端 + 产物才跑得到，那是最晚才发现的地方。语法错必须在这里就红。
syntax_bad=0
for f in tools/verify-*.mjs; do
  if ! node --check "$f" >/dev/null 2>&1; then
    echo "[check] 语法错：$f" >&2
    syntax_bad=$((syntax_bad + 1))
  fi
done
[ "$syntax_bad" -eq 0 ] || { echo "[check] $syntax_bad 份探针有语法错" >&2; exit 1; }
echo "[check] 探针脚本语法：$(ls tools/verify-*.mjs | wc -l) 份全部通过 node --check"
# Cocos 输入命中的坐标口径（#624~#633）：`UITransform.hitTest` 吃的是
# `(clientX - rect.left) * dpr` / `(rect.top + rect.height - clientY) * dpr`（见
# docs/cocos-3.8-输入命中备忘.md §8，条款是从本仓产物 cc.js 里抄的）。这条换算此前散在
# 探针里手算，三次返工（#610/#616/#624）都是因为把 CSS 像素当成了那个点。
# 这里把它连同「往返自检 + 引擎自命中 + 出画布」三道门一起钉住：改动 dpr、y 翻转或
# `worldToScreen` 参数顺序，**任一处都会变红**（已用三个变异实测过）。
node --test tools/lib/cocos-click.test.mjs
# 写死在量具里的 Java 包路径谓词必须命中真实文件/目录，且**每族谓词命中数必须 > 0**（2026-10-06 开）：
# 本仓有 17 条门禁字面量路径 + 2 条 exec.mainClass + 24 份契约的 x-java-package 指向 Java 源码树。
# 它们一旦与源码目录脱节（移动类、模块改名、真去改包名），门会去扫**不存在的目录** ⇒ 拿到 0 个文件
# ⇒ 判据空转 ⇒ **退 0 假绿**；假绿会被写成「N 道门全过」的交接证据，比编译红危险。
bash scripts/check-package-path-predicates.sh
# 客户端 GameApi 的每个发送口都必须有生产调用点（2026-10-06 升，报告器见底后进门）：
# connectSocket 整条是死的、出征与集结四条发送口没人调、商店货架玩家看不见 —— 这三笔都发生在
# 「445 条用例 + check.sh 全绿」期间，因为单测只测方法能不能跑通，不测有没有人点它。
bash scripts/check-client-send-paths.sh
# game-core 的每个主源码类都必须被外层主源码真的引用（2026-10-06 开）：
# B13 验收 10 长期挂 ✅，理由是单测 36 条全绿 —— 而现跑发现 WarScoreBoard 在 core 之外引用数为 0，
# 它自己的注释写着「由 game-web 载入、落盘」却没人执行 ⇒ 玩家完全不可达。客户端那一侧早有
# check-client-send-paths 管"有名字零读者"，服务端这一侧一直没有。
bash scripts/check-core-wiring.sh
# 客户端 game/ 与 scene/ 的每个 .ts 都必须被别的生产文件导入（或被场景按压缩 uuid 挂载）：
# B04 验收 8 的 ✅ 长期挂在 RewardToastQueue 的单测上，而生产提示出口 showHint 每次都往同一坐标
# add 一个 Label ⇒ 多条必然重叠，正是验收项禁止的行为。域类这一维此前没人守。
bash scripts/check-client-orphans.sh
# 视图声明的 on* 回调必须真的有人调用（2026-10-07 开，台账 #770）：`GiftPopupView.onBuy` 声明着、
# GameBootstrap 赋了值，但全仓没有一处调用 ⇒ 玩家点「立即购买」什么都不会发生，整条 B19 支付链
# 在玩家侧不可达，而当时 45 道门 + 1031 项单测全绿：发送口门数 GameApi 那一层、孤儿门数文件 import，
# 「契约有列 → 流程带出 → 视图不画/不接」这个第三层级此前没人守。
bash scripts/check-client-dead-callbacks.sh
# 文档里的门禁道数必须等于本脚本实际调用的道数（2026-10-06 开）：
# AGENTS.md 写「32 道」而这里实际 40 道是现跑抓到的 —— 计数一漂，后面每次会话都拿旧数当
# 「门全开」的证据。⚠️ 这道门自己也在被数之内：加/删门禁不改 AGENTS.md 的数字就会红。
bash scripts/check-doc-counts.sh
echo "[check] 全部静态检查通过。"
