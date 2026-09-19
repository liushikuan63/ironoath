'use strict'
/* 临时统计 v2：把「编辑器缓存 / 会话导出 / 生成物」从手写代码里剥出来。
   判据可复跑；每类都给 文件数 / 总行数 / 非空行数。 */
const fs = require('fs')
const path = require('path')

const SKIP_DIR = /(^|[\\/])(\.git|node_modules|target|build|dist|out|\.verify-head)([\\/]|$)/
/* Cocos 编辑器缓存：库缓存、临时构建、本机设置、原生工程 —— 与 target/ 同性质，不是源码 */
const EDITOR_CACHE = /client[\\/](temp|library|local|profiles|native)[\\/]/
const EXPORT_DUMP = /分批次实现项目_.*\.md$/
const EXT = { '.java': 1, '.ts': 1, '.js': 1, '.mjs': 1, '.sh': 1, '.json': 1, '.md': 1 }

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (SKIP_DIR.test(p)) continue
    if (e.isDirectory()) walk(p, out)
    else if (EXT[path.extname(e.name)]) out.push(p)
  }
  return out
}

const GENERATED = /[\\/]generated[\\/]|[\\/]cfg[\\/]/
const TEST = /[\\/]test[\\/]|Test\.java$|\.test\.ts$/

function areaOf(s) {
  if (s.startsWith('server/game-common')) return 'server·game-common'
  if (s.startsWith('server/game-config')) return 'server·game-config'
  if (s.startsWith('server/game-core')) return 'server·game-core'
  if (s.startsWith('server/game-battle')) return 'server·game-battle'
  if (s.startsWith('server/game-web')) return 'server·game-web'
  if (s.startsWith('server/tools')) return 'server·tools'
  if (s.startsWith('server/')) return 'server·其它'
  if (s.startsWith('client/assets/scripts')) return 'client·scripts'
  if (s.startsWith('client/')) return 'client·其它(非缓存)'
  if (s.startsWith('tools/')) return 'tools(代码生成器)'
  if (s.startsWith('scripts/')) return 'scripts(CI/验收)'
  if (s.startsWith('contract/proto')) return 'contract·proto(契约)'
  if (s.startsWith('contract/config')) return 'contract·config(配置表)'
  if (s.startsWith('contract/')) return 'contract·其它'
  return 'root·文档'
}

const files = walk('.', []).map((f) => ({ f, s: f.split(path.sep).join('/') }))
const agg = new Map()
let cache = { files: 0, lines: 0 }
let dump = { files: 0, lines: 0 }
for (const { f, s } of files) {
  const text = fs.readFileSync(f, 'utf8')
  const lines = text.split('\n').length
  const code = text.split('\n').filter((l) => l.trim().length > 0).length
  if (EDITOR_CACHE.test(s)) { cache.files++; cache.lines += lines; continue }
  if (EXPORT_DUMP.test(s)) { dump.files++; dump.lines += lines; continue }
  const kind = GENERATED.test(s) ? 'generated' : TEST.test(s) ? 'test' : 'hand'
  const key = areaOf(s) + ' | ' + kind
  const cur = agg.get(key) || { files: 0, lines: 0, code: 0 }
  cur.files++; cur.lines += lines; cur.code += code
  agg.set(key, cur)
}

const kinds = ['hand', 'test', 'generated']
const areas = [...new Set([...agg.keys()].map((k) => k.split(' | ')[0]))]
console.log('== 按区域（缓存与会话导出已剔除）==')
for (const a of areas.sort()) {
  for (const k of kinds) {
    const v = agg.get(a + ' | ' + k)
    if (!v) continue
    console.log(`${a.padEnd(22)} ${k.padEnd(10)} ${String(v.files).padStart(4)} 个 ${String(v.lines).padStart(7)} 行 ${String(v.code).padStart(7)} 非空`)
  }
}
const sum = (pred) => [...agg.entries()].filter(([k]) => pred(k)).map(([, v]) => v)
  .reduce((a, b) => ({ files: a.files + b.files, lines: a.lines + b.lines, code: a.code + b.code }), { files: 0, lines: 0, code: 0 })
console.log('== 合计 ==')
for (const k of kinds) {
  const v = sum((key) => key.endsWith('| ' + k))
  console.log(`${k.padEnd(10)} ${String(v.files).padStart(4)} 个 ${String(v.lines).padStart(7)} 行 ${String(v.code).padStart(7)} 非空`)
}
const hand = sum((k) => k.endsWith('| hand'))
const test = sum((k) => k.endsWith('| test'))
const gen = sum((k) => k.endsWith('| generated'))
console.log(`手写+测试 ${hand.lines + test.lines} 行 → 测试占比 ${(100 * test.lines / (hand.lines + test.lines)).toFixed(1)}%`)
console.log(`生成物 ${gen.lines} 行（契约 DTO + 配置表类型 + 客户端类型声明）`)
console.log(`Cocos 编辑器缓存 ${cache.files} 个 ${cache.lines} 行（未计入）`)
console.log(`会话导出转储 ${dump.files} 个 ${dump.lines} 行（未计入，非项目文档）`)
const srv = (k) => (k.startsWith('server') && k.endsWith('| hand')) ? 1 : 0
const srvTest = (k) => (k.startsWith('server') && k.endsWith('| test')) ? 1 : 0
const sh = sum((k) => srv(k) === 1)
const st = sum((k) => srvTest(k) === 1)
console.log(`服务端：手写 ${sh.lines} 行 / 测试 ${st.lines} 行 → 测试占 ${(100 * st.lines / (sh.lines + st.lines)).toFixed(1)}%`)
const cl = sum((k) => k.startsWith('client') && k.endsWith('| hand'))
const ct = sum((k) => k.startsWith('client') && k.endsWith('| test'))
console.log(`客户端脚本：手写 ${cl.lines} 行 / 测试 ${ct.lines} 行 → 测试占 ${(100 * ct.lines / (cl.lines + ct.lines)).toFixed(1)}%`)
