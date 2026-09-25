#!/usr/bin/env bash
# 职责：`TrackEvents.ts` 里每一个事件名都必须在 `数据看板需求.md` §七 的事件字典里有一行。
# 依赖：node（本项目必需工具链），不依赖 python（同 check-track-coverage.sh 的理由）。
#
# 为什么要这道门（2026-09-21，#332 现跑补）：
#   第 12 道门 `check-track-coverage.sh` 查的是"面板动作有没有打点"，它**不查打出来的事件
#   有没有进字典**。本轮加了七条事件（`stamina_buy` / `tech_research` / `tech_cancel` /
#   `tech_speed_up` / `chest_open` 等），当时是**人记得去补表**才补上的 —— 而"记得"正是
#   本仓库反复抓的那类失效：漏一行不会有任何东西变红，看板上那一环只是永远查不到定义，
#   半年后没人分得清"这个事件没人上报"和"上报了但字典没写"。
#
# 方向刻意是**单向**的（代码 → 文档）：
#   字典里多出行（事件已下线但表里还留着）只报告不失败 —— 那是文档欠账，
#   而"为了让门变绿就删字典行"会让运营侧丢掉一段还在收的历史数据定义。
set -euo pipefail
cd "$(dirname "$0")/.."

node -e '
const fs = require("fs")

const EVENTS = "client/assets/scripts/game/track/TrackEvents.ts"
const DOC = "数据看板需求.md"

// 事件名 = `export const TRACK_EVENTS = { ... } as const` 里每个属性的**值**（字符串字面量）
const source = fs.readFileSync(EVENTS, "utf8")
const block = source.slice(source.indexOf("TRACK_EVENTS"))
const coded = new Set()
for (const m of block.matchAll(/^\s{2}[A-Za-z][A-Za-z0-9_]*:\s*.([a-z0-9_]+).,$/gm)) {
  coded.add(m[1])
}
if (coded.size === 0) {
  console.error("[check-track-dictionary] 一条事件名都没解析出来 —— 多半是 TrackEvents.ts 的形状变了，"
    + "本门的正则要跟着改；不许因为读不到就静默通过")
  process.exit(1)
}

// 字典行 = §七 那张表第一列里的 `反引号名`
const doc = fs.readFileSync(DOC, "utf8")
const section = doc.slice(doc.indexOf("## 七、事件字典"))
const listed = new Set()
for (const m of section.matchAll(/^\|\s*`([a-z0-9_]+)`/gm)) {
  listed.add(m[1])
}

const missing = [...coded].filter((name) => !listed.has(name)).sort()
const stale = [...listed].filter((name) => !coded.has(name)).sort()

for (const name of stale) {
  console.log(`[check-track-dictionary] 提示：字典里的 \`${name}\` 在代码里已没有这个事件名（只报告，不挡）`)
}
if (missing.length > 0) {
  console.error(`[check-track-dictionary] ${coded.size} 个事件里有 ${missing.length} 个没进字典：`)
  for (const name of missing) console.error(`  ${name}`)
  console.error(`  补进 ${DOC} §七 那张表：一行"触发时机 + 关键参数"。`
    + "参数要对着 `this.track(...)` 实际打的数写，不照着注释编。")
  process.exit(1)
}
console.log(`[check-track-dictionary] ${coded.size} 个事件全部在 ${DOC} §七 有定义`
  + `（字典另有 ${stale.length} 行是已下线事件的留档）。`)
'
