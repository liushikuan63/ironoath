#!/usr/bin/env node
/**
 * 数一遍 `GameApi` 的每个方法在**生产代码**里有没有调用点。
 *
 * 这一族缺陷已经咬过三次（`connectSocket` 推送整条是死的 / 出征与集结四条发送口零调用点 /
 * 商店货架玩家看不见），而 445 条用例与 `check.sh` 全绿期间一条都不报 —— 因为它们只测
 * "方法本身能不能跑通"，不测"有没有人点它"。**本脚本自己是报告器**（永远退 0，给人读），
 * 门在 `scripts/check-client-send-paths.sh`：它解析本脚本的「生产零调用点」读数，> 0 就判红。
 * 2026-10-06 现跑到 **0** ⇒ 缺口见底，按 `客户端发送口缺口清单.md:126` 自述的条件（"降到 0 才进门"）
 * 升成第 43 道静态门。之前不做门是因为缺口有几十个 —— 做成门会把仓库刷成红的，把真缺陷埋进噪声里。
 *
 * 用法：node tools/report-client-send-paths.mjs
 *   可选输入口 SENDPATHS_CLIENT / SENDPATHS_TESTS / SENDPATHS_TOOLS / SENDPATHS_API
 *   （**不设时逐字节等同改动前**，harness 靠这四个口做零污染三读数）。
 * 判据口径与两类假阳性见 客户端发送口缺口.md。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
// 可选输入口（**不设时逐字节等同改动前**）：check-gates-can-fail.sh 靠这四个口做零污染三读数，
// 不必往仓库里植死方法，也不必读客户端源码。
//   SENDPATHS_CLIENT（生产语料根）/ SENDPATHS_TESTS / SENDPATHS_TOOLS / SENDPATHS_API
const CLIENT = process.env.SENDPATHS_CLIENT || path.join(HERE, '..', 'client', 'assets', 'scripts')
const TESTS = process.env.SENDPATHS_TESTS || path.join(HERE, '..', 'client', 'tests')
const TOOLS = process.env.SENDPATHS_TOOLS || HERE
const API = process.env.SENDPATHS_API || path.join(CLIENT, 'game', 'session', 'GameApi.ts')

/** `switch (` / `if (` 这类语句会被"两空格 + 名字 + 左括号"的形状误认成方法。 */
const KEYWORDS = new Set(['switch', 'if', 'for', 'while', 'catch', 'return', 'function',
  'new', 'else', 'do', 'try', 'constructor', 'get', 'set', 'async'])

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
    // 方法形状：两空格缩进 + 可选修饰符（private/readonly/public/static）+ 可选 async + 名字 + 左括号。
    // ⚠️ async 必须显式吃掉：否则 `  async playerPower(): ...` 里的 "async" 会被当成方法名收进来
    // （GameApi.ts 当时有 16 个 async 方法 ⇒ 报告器把它们收成 16 个名叫 "async" 的假方法，
    //  而那 16 个真发送口一个都没被扫 ⇒「生产零调用点 0」不覆盖它们。判据：tools/verify-send-paths-indirect.mjs）。
    const m = /^  (?:private |readonly |public |static )*(?:async )?([a-zA-Z][\w]*)\s*\(/.exec(line)
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

// 间接可达分析（**可选口 SENDPATHS_VERBOSE=1**；不设时上面那段输出逐字节等同改动前，
// harness 的零污染三读数依赖这一点，所以这段只能追加、不能改上面任何一行）。
//
// 为什么要有它：「只在 GameApi 内部被调」这一类里既有真缺口，也有**经动态注入可达**的假阳性
// —— 例如 worldExile / worldRecall / worldCollectGather 是 GameApi 自己 bindWorldRequester
// 注入到 WorldRequester 的，静态看不见调用点，只能靠人工顺入口复验（2026-10-19 已复验：三个都通）。
// 这里把那一跳补上：谁在 GameApi 内部调用了它、那个内部调用者有没有生产调用点。
if (process.env.SENDPATHS_VERBOSE === '1' && selfOnly.length) {
  const apiText = fs.readFileSync(API, 'utf8')
  const apiLines = apiText.split(/\r?\n/)
  const apiMethods = methodNames(API)
  // 每个方法体的行区间：签名行起，到下一个方法签名行前。
  const rangeOf = (index) => {
    const from = apiMethods[index].at
    const to = index + 1 < apiMethods.length ? apiMethods[index + 1].at - 1 : apiLines.length
    return { name: apiMethods[index].name, from, to }
  }
  const byName = new Map(rows.map((r) => [r.name, r]))
  console.log('  间接可达分析（GameApi 内部调用者是否有生产调用点）：')
  for (const r of selfOnly) {
    const re = new RegExp(`\\.${r.name}\\s*[\\(?]`)
    const callers = new Set()
    apiLines.forEach((line, i) => {
      if (!re.test(line)) return
      const owner = apiMethods.findIndex((m, idx) => rangeOf(idx).from <= i + 1 && i + 1 <= rangeOf(idx).to)
      if (owner >= 0 && apiMethods[owner].name !== r.name) callers.add(apiMethods[owner].name)
    })
    const via = [...callers].filter((c) => (byName.get(c)?.external ?? []).length > 0)
    if (via.length) {
      const where = via.map((c) => `${c}（${byName.get(c).external.map((f) => path.relative(CLIENT, f)).join(' ')}）`)
      console.log(`    ${r.name} ← 经 ${where.join(' / ')} 可达（已接生产入口）`)
    } else {
      console.log(`    ${r.name} ← 需人工复验（内部调用者也没有生产调用点）`)
    }
  }
}
for (const r of unwired) {
  const tag = r.tests.length || r.probes.length ? '只有测试/探针在用' : '完全没人用'
  console.log(`  ${tag} > ${r.name} (GameApi.ts:${r.at})` +
    (r.tests.length || r.probes.length ? ` [${[...new Set([...r.tests, ...r.probes])].join(' ')}]` : ''))
}
