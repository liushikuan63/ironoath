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
#       + 客户端逻辑层单测全量（941 项：类型门只保证"能编译"，不保证"算对了"）只在真机炸）。
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
bash scripts/check-checklist-table.sh
bash scripts/check-error-codes.sh
bash scripts/check-config-consumers.sh
bash scripts/check-balance-sim-config-sync.sh
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
echo "[check] 全部静态检查通过。"
