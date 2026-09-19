#!/usr/bin/env node
/**
 * 数一遍 `GameApi` 的每个方法在**生产代码**里有没有调用点。
 *
 * 这一族缺陷已经咬过三次（`connectSocket` 推送整条是死的 / 出征与集结四条发送口零调用点 /
 * 商店货架玩家看不见），而 445 条用例与 `check.sh` 全绿期间一条都不报 —— 因为它们只测
 * "方法本身能不能跑通"，不测"有没有人点它"。本脚本是**报告器不是门**（永远退 0）：
 * 现在缺口还有几十个，做成门会把仓库刷成红的，等清单见底再进门。
 *
 * 用法：node tools/report-client-send-paths.mjs
 * 判据口径与两类假阳性见 客户端发送口缺口.md。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CLIENT = path.join(HERE, '..', 'client', 'assets', 'scripts')
const TESTS = path.join(HERE, '..', 'client', 'tests')
const TOOLS = HERE
const API = path.join(CLIENT, 'game', 'session', 'GameApi.ts')

/** `switch (` / `if (` 这类语句会被"两空格 + 名字 + 左括号"的形状误认成方法。 */
const KEYWORDS = new Set(['switch', 'if', 'for', 'while', 'catch', 'return', 'function',
  'new', 'else', 'do', 'try', 'constructor', 'get', 'set'])

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(p, out)
    else if (/\.(ts|mjs)$/.test(entry.name)) out.push(p)
  }
  return out
}

function methodNames(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  const found = []
  lines.forEach((line, i) => {
    const m = /^  (?:private |readonly )?([a-zA-Z][\w]*)\s*\(/.exec(line)
    if (!m || KEYWORDS.has(m[1])) return
    // 方法签名要能在同一行或下一行看到 `): 类型` / `) {`；语句行不会有返回类型。
    const head = line.slice(m[0].length)
    const tail = lines[i + 1] ?? ''
    if (!/\)\s*:/.test(line + head) && !/\)\s*\{/.test(line) && !/\)\s*:\s*$/.test(line) &&
        !/^\s*\)\s*:/.test(tail) && !/^\s*\)\s*\{/.test(tail)) return
    found.push({ name: m[1], at: i + 1 })
  })
  return found
}

const read = (file) => ({ file, text: fs.readFileSync(file, 'utf8') })
const prod = walk(CLIENT).filter((f) => !/[\\/]generated[\\/]/.test(f)).map(read)
const test = walk(TESTS).map(read)
const tool = walk(TOOLS).filter((f) => f !== fileURLToPath(import.meta.url)).map(read)

/** 一个方法在某个语料里被 `\.name(` 或 `\.name?.(` 命中，返回命中文件名。 */
function calledIn(corpus, name, opts = {}) {
  const re = new RegExp(`\\.${name}\\s*[\\(?]`)
  return corpus.filter((c) => re.test(c.text) && (opts.excludeSelf ? c.file !== API : true))
    .map((c) => opts.relative ? path.relative(CLIENT, c.file) : c.file)
}

const rows = methodNames(API).map((m) => {
  const external = [...new Set(calledIn(prod, m.name, { excludeSelf: true }))]
  const anywhereInApi = calledIn(prod.filter((c) => c.file === API), m.name).length > 0
  return {
    name: m.name, at: m.at, external,
    selfOnly: external.length === 0 && anywhereInApi,
    tests: [...new Set(calledIn(test, m.name))].map((f) => path.basename(f)),
    probes: [...new Set(calledIn(tool, m.name))].map((f) => path.basename(f)),
  }
})

const unwired = rows.filter((r) => r.external.length === 0 && !r.selfOnly)
const unreachable = unwired.filter((r) => r.tests.length === 0 && r.probes.length === 0)
const selfOnly = rows.filter((r) => r.selfOnly)

console.log(`GameApi 方法：${rows.length}｜生产零调用点：${unwired.length}｜` +
  `其中连测试与探针也没碰过：${unreachable.length}｜只在 GameApi 内部被调（要顺入口方法复验）：${selfOnly.length}`)
if (selfOnly.length) console.log(`  内部调用 > ${selfOnly.map((r) => r.name).join(', ')}`)
for (const r of unwired) {
  const tag = r.tests.length || r.probes.length ? '只有测试/探针在用' : '完全没人用'
  console.log(`  ${tag} > ${r.name} (GameApi.ts:${r.at})` +
    (r.tests.length || r.probes.length ? ` [${[...new Set([...r.tests, ...r.probes])].join(' ')}]` : ''))
}
