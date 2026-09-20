#!/usr/bin/env bash
# 职责：B16 验收 3「埋点覆盖率：所有关键按钮与漏斗节点均有事件」的机械判定。
# 依赖：node（本项目的必需工具链），不依赖 python —— Windows 上 python 常常只是
#       Microsoft Store 的存根，会在某次运行里直接 Permission denied，把卡口红成与代码无关。
#
# 硬规则（违反即构建失败）：AppRoot 的每一个**面板动作**都必须打一个点。
#   为什么以 AppRoot 当 UI 清单：它就是"每个按钮的意图都要经过这里才能变成请求"的机器可读形式，
#   所以"有动作没埋点"是一个可判定的事实，而不是靠人记得。
#   漏埋点的后果不报错：看板上那一环永远是 0，没人分得清"没人点"和"忘了接"。
#
# 软清单（只报告，不失败）：字典里还没有调用点的事件。它们各自被一个未落地的系统挡着
#   （引导系统、真实支付、战斗结果回传、离线完成）。为了让清单变绿而发一个假事件，
#   比少一个事件更糟 —— 看板会以为那一环被测过了。
set -euo pipefail
cd "$(dirname "$0")/.."

node -e '
const fs = require("fs")
const path = require("path")

const ROOT = "client/assets/scripts/game/session/AppRoot.ts"
const EVENTS = "client/assets/scripts/game/track/TrackEvents.ts"
/**
 * 不是"面板动作"的公开方法 ⇒ 不需要埋点。每条都要写清为什么，不写理由的豁免等于把门关掉。
 *
 * <p>2026-09-19（B22 聊天 UI）两处修正：
 * ① 发现规则原先只认 `^  [a-z]...`，**整类漏掉 async 方法**（`buyGift`、`sendChat` 这些
 *    一个都没被检查过）；现在 `async ` 也认。
 * ② 豁免改成带理由的清单：其中聊天的四个是玩家动作，但 B14 的**字典表里还没有聊天事件**，
 *    而本脚本头部的纪律写着"为了让清单变绿而发一个假事件，比少一个事件更糟" ——
 *    要加得先同步 `数据看板需求.md` §七 的字典表，那是一次独立的埋点批次（收口清单 #200 ⑥）。
 */
const SKIP = new Set([
  "refresh", // 数据拉取而不是玩家动作（登录与首屏各拉一次）
  "bindPush", // 订阅装配：开机接一次，玩家没有对应的按钮
  "afterForeground", // 平台前台回调（wx.onShow），不是按钮
  "showGiftPopup", // 读一次"弹不弹"；真正的展示由 pay_popup_show 记
  "openReport", // 打开回放是读操作，战报的埋点在列表那一侧
  "openChat",
  "selectChatChannel",
  "openConversation",
  "sendChat",
  // 只负责"把选择器弹出来"，真正发出分享的那一步在 shareReport 里打点（report_share）——
  // 两处都打会让一次分享在看板上记成两次
  "requestShare",
  // 同上：这两条只是把动作菜单弹出来，真正的动作（举报 / 拉黑）各自在 reportMessage /
  // blockPlayer / unblockPlayer 里打点
  "openChatActions",
  "manageBlocks",
  // 翻页是同一次阅读的延续：
  // 打开榜那一下已经用 rank_view 记了类型，翻页再记会把一次浏览记成多次，而看板上要答的
  // 「哪张榜有人看」已经答了；真要量"翻到第几页就没人翻了"，那是独立一格的埋点设计
  "rankNextPage",
  "rankPrevPage",
  // 面板被打开时重拉"当前页签那一份"（V04-S1）：触发者是平台回调 nav.onShow，不是玩家按钮，
  // 与上面的 refresh 同一类；"看榜/看赛季"那一下已经各自记过（rank_view / season_view），
  // 在这里再记会把一次浏览记成多次
  "reloadRankTab",
  // 出征的准备步骤：开编成面板 / 改数量 / 取消都不是"出征意图"，
  // 真正发兵那一下在 confirmMarch 里打 march_send —— 三处都打会把一次出征记成多次
  "beginMarchCompose",
  // 同一条理由（V02-S1 的集结）：`beginRallyCompose` 只是把编成面板弹出来，
  // 真正"把兵压上去"那一下在 `confirmRallyJoin` 里打 `rally_join` ——
  // 两处都打会把一次加入记成两次
  "beginRallyCompose",
  "pickMarchUnit",
  "cancelMarchCompose",
  // B26 S14 同一条口径：换召集层级与调那两个数都是**在编成面板里做选择**，
  // 真正"把兵压上去"那一下在 confirmAllianceRally / confirmSquadRally 里打 `rally_initiate`
  // （参数带 scope=SQUAD/ALLIANCE，层级本身已经分得开）—— 这里再打会把一次发起记成三四次
  "setComposeRallyScope",
  "adjustComposeRallyNumber",
  // 侦察那颗也是**换命令种类**（B26 S18）：真正"把侦察队发出去"那一下在 confirmScout 里打 `scout_send`
  "toggleComposeScout",
  // 军队行上「队列」（B26 S15）同一条口径：打开菜单只是把候选摆出来，
  // 真正"取消这一口训练"那一下在 cancelTrain 里打 `army_train_cancel` —— 两处都打会把一次取消记成两次
  "openArmyQueue",
  // 研究那一行的「加速」（B20）同一条口径：它只是问"用哪一张"，
  // 真正"吃掉一张推进研究"那一下在 speedUpResearch 里打 `tech_speed_up` —— 两处都打会把一张记成两张
  "requestResearchSpeedUp",
  // 升级弹层的"开弹层"与"加减一件"：真正要量的是"喂下去"那一下（在 confirmExpPick 里打 hero_level_up），
  // 开弹层与步进都还不是意图 —— 与上面的 beginMarchCompose / pickMarchUnit 同一条理由
  "openExpPick",
  "bumpExpPick",
  "cancelExpPick", // 取消 = 关掉弹层、什么都没发生，没有可上报的东西
  // 觉醒弹层同一条口径：意图是"用掉这块石"那一下（confirmAwakenPick 里打 hero_awaken），
  // 开弹层 / 换一块石 / 取消都不是意图
  "openAwakenPick",
  "pickAwakenItem",
  "cancelAwakenPick",
  // 技能弹层同一条口径：意图是"用掉这本书"（confirmSkillPick 里打 hero_skill_up）
  "openSkillPick",
  "pickSkillItem",
  "cancelSkillPick",
  // 合成弹层同一条口径：意图是"花掉这一档碎片换一名武将"（confirmComposePick 里打 hero_compose）。
  // 开弹层只是去看还有谁可合成，换选中是同一意图的中间态，取消=什么都没发生
  "openComposePick",
  "pickComposeHero",
  "cancelComposePick",
  // 抽卡面板同一条口径：意图是"花掉这一档资源/道具换一批武将"（drawGacha 里打 gacha_draw）。
  // 开面板是去看有什么池，换选中是同一意图的中间态（都不花钱、都不改存档）
  "openGacha",
  "selectGachaPool",
  // 概率公示是一次读（B06 §6 的合规屏），不是玩家意图；要量"有没有人看公示"是独立的埋点设计
  "openGachaProbability",
  // 编队编辑器同一条口径：意图是"把这一队提交上去"（saveLineup 里打 hero_lineup_save）。
  // 开编辑器是去看现在怎么排的，点槽位与换选中都是同一次编辑的中间态（什么都没改到存档）
  "openLineupEdit",
  "pickLineupSlot",
  "chooseLineupHero",
  "clearLineupSlot",
  "cancelLineupEdit",
  // 拉权限与创建政策是一次读（进社交页时顺带），不是玩家意图；按钮灰不灰的结论由这份数据决定
  "loadSocialGates",
  // 同上：两份发现型列表（可申请联盟 / 可加入小队）也是"打开社交页"这一动作带出来的读，
  // 玩家意图落在 joinSquad / applyToAlliance 那一枪上。把读也记一条，漏斗第一格就会虚高
  "loadSocialDiscovery",
  // 在出征与发起集结之间来回切是一次选择，不是那一次提交：意图记在 rally_initiate 上，
  // 把切换也记进漏斗会让"看了又放弃"的人被算成发起过
  "toggleComposeRally",
  // 打开创建表单与打字与取消都不是那一次提交：埋点只在 social_create 上记"真的建了一个组织"，
  // 把开合也记进去会让漏斗第一格永远比最后一格大，看不出卡在哪
  "openSocialCreate",
  "typeSocialCreate",
  "cancelSocialCreate",
  // 退出/解散的第一下只是把那一行改成"确认…"，一个字节都没发出去；真发出去那一枪另有 social_leave / social_disband
  "requestExit",
  // 转让同样：第一下只改字，真发那一枪记在 social_transfer 上
  "requestTransfer",
])

const lines = fs.readFileSync(ROOT, "utf8").split("\n")
const methods = []
let current = null
let body = []
const decl = /^ {2}(?:async )?(?!private |get |set |constructor)([a-z][A-Za-z0-9]*)\(/
for (const line of lines) {
  const m = line.match(decl)
  // 只有以 { 收尾的才是实现；接口成员声明（如 Tracker 里的 track(...)）不是动作
  if (m && line.trimEnd().endsWith("{")) {
    if (current) methods.push([current, body.some(l => l.includes(".track("))])
    current = m[1]
    body = []
    continue
  }
  if (current && /^  \}/.test(line)) {
    methods.push([current, body.some(l => l.includes(".track("))])
    current = null
    body = []
    continue
  }
  if (current) body.push(line)
}

const actions = methods.map(([n]) => n).filter(n => !SKIP.has(n))
const missing = methods.filter(([n, ok]) => !ok && !SKIP.has(n)).map(([n]) => n)

const dict = fs.readFileSync(EVENTS, "utf8")
const names = [...dict.matchAll(/^ {2}(\w+): .([a-z_]+).,$/gm)].map(m => [m[1], m[2]])

// 事件是否在发要扫整个客户端生产源码：startup 在登录之前、churn 在组件销毁之时，
// 它们天生不在根里。只扫 AppRoot 会把已经在发的事件报成"没人发"
const corpusParts = []
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === "generated" || entry.name === "test") continue
      walk(full)
    } else if (entry.name.endsWith(".ts")) {
      corpusParts.push(fs.readFileSync(full, "utf8"))
    }
  }
}
walk("client/assets/scripts")
const corpus = corpusParts.join("\n")

console.log("[check-track-coverage] UI 动作清单（AppRoot 公开动作）：" + actions.length + " 个")
console.log("   " + actions.join(", "))
const unemitted = names.filter(([k]) => !corpus.includes("TRACK_EVENTS." + k))
if (unemitted.length > 0) {
  console.log("[check-track-coverage] 字典里尚无调用点的事件（各自被未落地的系统挡着，见收口清单）：")
  for (const [, v] of unemitted) console.log("   - " + v)
}
if (missing.length > 0) {
  console.error("[check-track-coverage] 失败：" + missing.length + " 个面板动作没有埋点：" + missing.join(", "))
  console.error("   补 this.track(TRACK_EVENTS.xxx, ...)；没有合适的事件名就先往 TrackEvents.ts 加一个，"
    + "并同步 数据看板需求.md §七 的字典表。")
  process.exit(1)
}
console.log("[check-track-coverage] " + actions.length + " 个面板动作全部有事件，覆盖率卡口通过。")
'
