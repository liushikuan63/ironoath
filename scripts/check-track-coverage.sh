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
// refresh 是数据拉取而不是玩家动作（登录与首屏各拉一次），不该算按钮
const SKIP = new Set(["refresh"])

const lines = fs.readFileSync(ROOT, "utf8").split("\n")
const methods = []
let current = null
let body = []
const decl = /^  (?!private |get |set |constructor)([a-z][A-Za-z0-9]*)\(/
for (const line of lines) {
  const m = line.match(decl)
  // 只有以 { 收尾的才是实现；接口成员声明（如 Tracker 里的 track(...)）不是动作
  if (m && line.trimEnd().endsWith("{")) {
    if (current) methods.push([current, body.some(l => l.includes("this.track("))])
    current = m[1]
    body = []
    continue
  }
  if (current && /^  \}/.test(line)) {
    methods.push([current, body.some(l => l.includes("this.track("))])
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
