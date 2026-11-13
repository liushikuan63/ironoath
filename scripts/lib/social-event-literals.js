'use strict'
// 社交事件字面量的单一真源：服务端 event("X") 里出现的每个取值，都必须是契约
// SocialEventType 枚举的成员。
//
// 立论（#2531）：`SocialAppService#squadTransfer` 一直在推 SQUAD_LEADER_CHANGED，
// 而契约枚举里没有这个取值 —— 存进库的是字符串（Mongo 不查类型），只在**序列化**
// 那一刻炸（Jackson 抛 `No enum constant …SocialEventType.SQUAD_LEADER_CHANGED`），
// 于是新队长下一次读 /social/summary 整包回 1001、data 为 null：看不到小队、
// 看不到红点，而「把事件读掉」恰好也要经过那个面板。B10 明写绝不静默失败。
// 既有测试只断言「事件列表里有这个字符串」，字符串存得住，所以测试不绿不红都放过它。

const fs = require('fs')
const path = require('path')

/** 契约里的合法取值集合。缺失/空枚举一律抛错，由调用方判红（谓词失效不能判绿）。 */
function contractEventTypes(schemaPath) {
  const raw = fs.readFileSync(schemaPath, 'utf8')
  const doc = JSON.parse(raw)
  const defs = doc && doc.$defs
  const node = defs && defs.SocialEventType
  const values = node && node.enum
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error('契约里找不到 SocialEventType 枚举：' + schemaPath)
  }
  return values.slice()
}

/** 递归收集 .java 文件，跳过 generated 目录（那是生成物，不该被当成手写来源）。 */
function javaFilesUnder(dir) {
  const out = []
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === 'generated' || entry.name === 'target' || entry.name === 'node_modules') continue
        walk(full)
      } else if (entry.isFile() && entry.name.endsWith('.java')) {
        out.push(full)
      }
    }
  }
  walk(dir)
  return out
}

/**
 * 找出服务端手写代码里 event("X") / event('X') 用到的取值，并标出不在枚举里的。
 * 只认 `event(` 紧跟一个字符串字面量：`event(variable)` 这种动态拼的不做猜测。
 */
function eventLiterals(dirs) {
  const literals = new Map()
  const pattern = /\bevent\s*\(\s*"([A-Z][A-Z0-9_]*)"/g
  const patternSingle = /\bevent\s*\(\s*'([A-Z][A-Z0-9_]*)'/g
  for (const dir of dirs) {
    if (!fs.existsSync(dir)) continue
    for (const file of javaFilesUnder(dir)) {
      const text = fs.readFileSync(file, 'utf8')
      for (const re of [pattern, patternSingle]) {
        re.lastIndex = 0
        let match
        while ((match = re.exec(text)) !== null) {
          const name = match[1]
          if (!literals.has(name)) literals.set(name, [])
          literals.get(name).push(file)
        }
      }
    }
  }
  return literals
}

/** 返回 { literals, missing, allowed }；missing 为空才合格。 */
function audit(options) {
  const schemaPath = options.schemaPath
  const dirs = options.dirs
  const allowed = contractEventTypes(schemaPath)
  const allowedSet = new Set(allowed)
  const literals = eventLiterals(dirs)
  const missing = []
  for (const [name, files] of literals) {
    if (!allowedSet.has(name)) missing.push({ name, files })
  }
  missing.sort((a, b) => a.name.localeCompare(b.name))
  return { literals, missing, allowed }
}

module.exports = { contractEventTypes, eventLiterals, audit }
