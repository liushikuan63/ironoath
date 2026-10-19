#!/usr/bin/env node
/**
 * 自测：`tools/report-client-send-paths.mjs` 的**间接可达分析**（SENDPATHS_VERBOSE=1）能不能
 * 把「经 GameApi 内部调用者动态接入」的那一类从「真缺口」里分出来。
 *
 * 为什么要有这道自测：报告器默认输出被 `scripts/check-client-send-paths.sh` 与
 * `scripts/check-gates-can-fail.sh` 当作**逐字节基线**（harness 的零污染三读数依赖它），
 * 所以 verbose 段只能是**追加**。而「追加的那段判得对不对」没有别的门管着 ——
 * 段写错了就会把真缺口说成已接（假绿）。本脚本用**合成语料**做正反两面：
 *   正例 alpha ← beta（beta 有生产调用点）⇒ 必须判「可达」
 *   反例 gamma ← delta（delta 没有生产调用点）⇒ 必须判「需人工复验」
 *   守底：不设 VERBOSE 时输出里**不得**出现 verbose 段（否则破坏逐字节基线）
 *
 * 用法：node tools/verify-send-paths-indirect.mjs   （永远退 0；判红由本脚本自己做）
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPORT = path.join(HERE, 'report-client-send-paths.mjs')

const failures = []
const check = (name, ok, detail) => {
  if (ok) console.log(`ok - ${name}`)
  else { console.log(`not ok - ${name}${detail ? ' :: ' + detail : ''}`); failures.push(name) }
}

// ---- 合成语料 ----
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sendpaths-'))
const clientDir = path.join(root, 'client')
const testsDir = path.join(root, 'tests')
const toolsDir = path.join(root, 'tools')
fs.mkdirSync(path.join(clientDir, 'game'), { recursive: true })
fs.mkdirSync(testsDir, { recursive: true })
fs.mkdirSync(toolsDir, { recursive: true })

// GameApi 合成文件：alpha 被 beta 内部调用（beta 另有生产调用点 ⇒ alpha 应判「可达」）；
// gamma 被 delta 内部调用，而 delta 自己没有生产调用点 ⇒ gamma 应判「需人工复验」。
const api = [
  'export class GameApi {',
  '  alpha(x) { return x }',
  '  beta(x) { return this.alpha(x) }',
  '  async gamma(x) { return x }',
  '  async delta(x) { return this.gamma(x) }',
  '}',
  '',
].join('\n')
const apiFile = path.join(clientDir, 'game', 'GameApi.ts')
fs.writeFileSync(apiFile, api)

// 生产语料：只调 beta，不调 delta。
fs.writeFileSync(path.join(clientDir, 'prod.ts'), [
  'import { GameApi } from "./game/GameApi"',
  'export function useBeta(api: GameApi) { return api.beta(1) }',
  '',
].join('\n'))

const run = (verbose) => {
  const env = { ...process.env }
  env.SENDPATHS_CLIENT = clientDir
  env.SENDPATHS_TESTS = testsDir
  env.SENDPATHS_TOOLS = toolsDir
  env.SENDPATHS_API = apiFile
  if (verbose) env.SENDPATHS_VERBOSE = '1'
  else delete env.SENDPATHS_VERBOSE
  const out = spawnSync(process.execPath, [REPORT], { env, encoding: 'utf8' })
  return { code: out.status, text: out.stdout + out.stderr }
}

const plain = run(false)
const verbose = run(true)

check('报告器默认退出码为 0', plain.code === 0, `code=${plain.code}`)
check('默认输出里不得出现 verbose 段（逐字节基线）',
  !plain.text.includes('间接可达分析'), plain.text.split('\n').slice(0, 3).join(' | '))
check('verbose 输出里必须出现间接可达分析段', verbose.text.includes('间接可达分析'))
check('alpha 判为经 beta 可达',
  /alpha ← 经 beta/.test(verbose.text), verbose.text)
check('beta 的生产调用点被列出来',
  /beta（.*prod\.ts/.test(verbose.text), verbose.text)
check('gamma 判为需人工复验',
  /gamma ← 需人工复验/.test(verbose.text), verbose.text)
check('delta 不被判为可达',
  !/delta ← 经/.test(verbose.text), verbose.text)
check('verbose 段只追加在末尾（默认输出是 verbose 输出的前缀）',
  verbose.text.startsWith(plain.text.trimEnd().split('\n').slice(0, 2).join('\n')),
  'first lines differ')
// 2026-10-19 补：async 方法必须被**当成方法**收进来，而不是收成一个名叫 "async" 的假方法
// （GameApi.ts 当时有 16 个 async 方法被漏扫 ⇒「生产零调用点 0」不覆盖它们）。
check('方法数是 4（alpha/beta/gamma/delta，gamma 与 delta 是 async）',
  /GameApi 方法：4｜/.test(plain.text), plain.text.split('\n')[0])
check('不得出现名叫 async 的假方法',
  !/(^|[\s,>])async[（(]/.test(plain.text) && !/内部调用 > async/.test(plain.text),
  plain.text)
check('async 方法被当成方法收进来了（gamma 在内部调用列表里）',
  /内部调用 > [^\n]*gamma/.test(plain.text), plain.text)
check('async 方法的内部调用关系也被判出来',
  /gamma ← 需人工复验/.test(verbose.text), verbose.text)

fs.rmSync(root, { recursive: true, force: true })

if (failures.length) {
  console.log(`\n[send-paths-indirect][FAIL] ${failures.length} 条判据未过：${failures.join(' / ')}`)
  process.exit(1)
}
console.log('\n[send-paths-indirect] 全部判据通过（正例 + 反例 + 逐字节基线）')
