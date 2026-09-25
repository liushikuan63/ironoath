// 职责：CI 静态检查 —— **`this.track(...)` 实际打出去的关键参数，必须逐个出现在 `数据看板需求.md` §七
//   那一行"关键参数"里**。方向与第 27 道门一致（代码 → 文档）。
//
// 为什么要单独一道（2026-09-21，#334 那条"下一格可做的同族"）：
//   第 27 道门 `check-track-dictionary.sh` 只查"**有没有这一行**"。于是字典里把参数写错、写少、
//   照注释编而不是照 `this.track` 的实数写，**不会有任何东西变红** —— 而看板侧真正用的就是这几个参数名：
//   名字错了，那一列数据在数仓里根本不存在，半年后看起来像"没人上报"。
//   #334 本轮就错过一次：`scout_send` 第一版写了三个代码里根本没打的参数。
//
// 判什么：事件 X 在代码里打了 `this.track(TRACK_EVENTS.x, { a, b })` ⇒ §七 那一行的第三列必须同时
//   含 `` `a` `` 与 `` `b` ``（多个调用点取**并集**，同一事件常常有"正常一条 + 被挡住一条"两种参数集）。
// 放过什么（都是刻意的）：
//   ① 字典里写了、代码没打的参数 —— 只报告不失败：那是文档欠账，且"为了变绿删一行"会让运营侧
//      丢掉一段可能还在收的历史数据定义（与第 27 道门同一条理由）；
//   ② 值是什么不判（`blocked: 'picker'` 与 `blocked: trackParam(x)` 只看键名）；
//   ③ 参数名从变量展开（`{ ...extra }`）今天代码里没有；真出现时本门会**报"解析不出键"**而不是静默放过。
//
// 会失败的前提：一条事件都解析不出来 ⇒ 直接红（多半是 `this.track` 的形状变了、正则要跟着改），
//   不许因为读不到就静默通过。
'use strict'

const fs = require('fs')
const path = require('path')

const EVENTS_FILE = 'client/assets/scripts/game/track/TrackEvents.ts'
const DOC = '数据看板需求.md'
const SCRIPTS_DIR = 'client/assets/scripts'

const read = (file) => fs.readFileSync(file, 'utf8')

/** TRACK_EVENTS 的常量名 → 事件字符串（表里的值才是看板用的那个名字）。 */
function eventNamesByConstant() {
  const source = read(EVENTS_FILE)
  const block = source.slice(source.indexOf('TRACK_EVENTS'))
  const map = new Map()
  for (const m of block.matchAll(/^ {2}([A-Za-z][A-Za-z0-9_]*):\s*'([a-z0-9_]+)'/gm)) {
    map.set(m[1], m[2])
  }
  return map
}

function tsFiles(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...tsFiles(full))
    else if (entry.name.endsWith('.ts')) out.push(full)
  }
  return out
}

/** 按深度 0 的逗号切开对象字面量的成员（`trackParam(a, b)` 里的逗号不算分隔）。 */
function splitMembers(body) {
  const parts = []
  let depth = 0
  let current = ''
  for (const ch of body) {
    if (ch === '(' || ch === '[' || ch === '{') depth += 1
    else if (ch === ')' || ch === ']' || ch === '}') depth -= 1
    if (ch === ',' && depth === 0) {
      parts.push(current)
      current = ''
      continue
    }
    current += ch
  }
  parts.push(current)
  return parts
}

/** 一个成员 → 它的键名：`a: 1` 与 `a`（简写）都算 `a`；其它形状返回 null。 */
function memberKey(member) {
  const m = /^\s*(?:\.\.\.|[A-Za-z_$][A-Za-z0-9_$]*\s*:)/.exec(member)
  if (m !== null) {
    return member.trim().startsWith('...') ? null : member.trim().split(':')[0].trim()
  }
  return /^\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*$/.test(member) ? member.trim() : null
}

function paramsSentByEvent(constToEvent) {
  const sent = new Map()
  const unparsed = []
  for (const file of tsFiles(SCRIPTS_DIR)) {
    const source = read(file)
    for (const m of source.matchAll(/this\.track\(\s*TRACK_EVENTS\.([A-Za-z0-9_]+)\s*,\s*\{([^}]*)\}/g)) {
      const event = constToEvent.get(m[1])
      if (event === undefined) {
        unparsed.push(`${file}: TRACK_EVENTS.${m[1]} 不在表里`)
        continue
      }
      const keys = sent.get(event) ?? new Set()
      for (const member of splitMembers(m[2])) {
        const key = memberKey(member)
        if (key === null) {
          if (member.trim() !== '') unparsed.push(`${file}: ${event} 的成员「${member.trim()}」解析不出键名`)
          continue
        }
        keys.add(key)
      }
      sent.set(event, keys)
    }
  }
  return { sent, unparsed }
}

/** §七 那张表：事件名 → 第三列里反引号括起来的参数名集合。 */
function documentedParams() {
  const doc = read(DOC)
  const section = doc.slice(doc.indexOf('## 七、事件字典'))
  const rows = new Map()
  for (const m of section.matchAll(/^\|\s*`([a-z0-9_]+)`\s*\|([^|]*)\|(.*)$/gm)) {
    const params = new Set()
    for (const p of m[3].matchAll(/`([A-Za-z][A-Za-z0-9_]*)`/g)) params.add(p[1])
    rows.set(m[1], params)
  }
  return rows
}

function main() {
  const constToEvent = eventNamesByConstant()
  if (constToEvent.size === 0) {
    console.error('[check-track-params] 一条事件常量都没解析出来 —— TrackEvents.ts 的形状变了，本门的正则要跟着改')
    process.exit(1)
  }
  const { sent, unparsed } = paramsSentByEvent(constToEvent)
  if (sent.size === 0) {
    console.error('[check-track-params] 一个带参数的 `this.track(...)` 调用点都没解析出来 —— '
      + '多半是调用形状变了（比如参数改成从变量展开），那正是本门该红而不是该沉默的情形')
    process.exit(1)
  }
  const documented = documentedParams()

  let failures = 0
  for (const line of unparsed) console.log(`[check-track-params] 提示：${line}（不挡，但值得看一眼）`)

  for (const [event, keys] of [...sent].sort()) {
    const row = documented.get(event)
    if (row === undefined) continue // "有没有这一行"由第 27 道门管，这里不重复报
    const missing = [...keys].filter((k) => !row.has(k)).sort()
    if (missing.length > 0) {
      failures += 1
      console.error(`[check-track-params] \`${event}\` 实际打了 ${missing.map((k) => `\`${k}\``).join('、')} `
        + `—— 这些参数名没写进 ${DOC} §七 那一行的"关键参数"`)
    }
  }
  if (failures > 0) {
    console.error(`  补法：对着调用点 grep \`this.track(TRACK_EVENTS.…\` 的实数写，别照注释编。`)
    process.exit(1)
  }

  const untested = []
  for (const [event, keys] of [...sent].sort()) {
    const row = documented.get(event)
    if (row === undefined) continue
    for (const key of [...row].sort()) if (!keys.has(key)) untested.push(`${event}.${key}`)
  }
  for (const name of untested) {
    console.log(`[check-track-params] 提示：字典写了 \`${name}\` 而代码里没打（只报告，不挡 —— `
      + '可能是别的端打的、历史定义，或那一行本来就是编的）')
  }
  console.log(`[check-track-params] ${sent.size} 个事件的实参都在字典里`
    + `（另有 ${untested.length} 个"字典写了但代码没打"的参数逐条列在上面）。`)
}

main()
