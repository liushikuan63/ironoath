#!/usr/bin/env node
/**
 * 门：client/assets/scripts 下每个 .ts 必须有一份同名 .ts.meta 入库（项目红线）。
 * 用法：node scripts/check-ts-meta.js [--verbose] | --self-test
 *
 * <p><b>为什么这道门是补上的</b>：规则里明写着「新增 .ts 要连 .ts.meta 一起提交
 * （仓库里 187 个 .ts.meta 都是入库资产）」，但**当时没有任何机器在查这件事** ——
 * V17-G 的 `NationPolicyPanel.ts` 就是这么漏进去的（`.meta` 至今没入库，
 * 而 `check.sh` 全绿）。规则与机制不一致时，人是唯一的那道门，
 * 于是漏一次就等于没有规则。所以这一格把机制补上，而不是只把文件补上。
 *
 * <p><b>判据只查"配对"，不查内容</b>：`.meta` 里的 uuid 由 Cocos 生成，
 * 本门不生成也不校验 uuid（那要跑编辑器）；只查
 * ① 每个 `.ts` 都有同名 `.meta`；
 * ② 每个 `.meta` 都有同名 `.ts`（反方向同样要查 ——
 *    多出来的 `.meta` 会让 Cocos 把那个文件当资产处理，而源文件已经不在了）。
 *
 * <p><b>只看入库的那一份</b>（`git ls-files`）而不是磁盘：
 * 未入库的新文件正是这一门要抓的对象，而 `ls-files` 之外的东西还没进库。
 * 未入库的 .ts 会在提交那一刻变成"有 .ts 无 .meta"—— 那正是 #V17-G 的形状，
 * 所以这里用 `ls-files` + 「工作区里多出来的 .ts」双向比对。
 */
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = 'client/assets/scripts'
const verbose = process.argv.includes('--verbose')
const selfTest = process.argv.includes('--self-test')

function gitLines(args) {
  try {
    return execFileSync('git', args, { encoding: 'utf8' }).split('\n').filter(Boolean)
  } catch {
    return []
  }
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else out.push(full.replace(/\\/g, '/'))
  }
  return out
}

/**
 * 算一遍问题清单（拆出来是为了 self-test 能直接喂假数据）。
 *
 * @param files   磁盘上扫到的文件列表（工作区 ∪ 入库）
 * @param tracked 已入库的那一份（Set）。传它不是为了查有没有，而是为了查**入没入库** ——
 *                 只查磁盘的话 #V17-G 那个漏网的文件照样过门（磁盘上有、库里没有）。
 */
function scan(files, tracked = null) {
  const ts = files.filter(f => f.endsWith('.ts') && !f.endsWith('.d.ts') && !f.endsWith('.meta'))
  const meta = files.filter(f => f.endsWith('.ts.meta'))
  const tsSet = new Set(ts)
  const metaSet = new Set(meta.map(f => f.slice(0, -'.meta'.length)))
  const problems = []
  for (const file of ts) {
    if (!metaSet.has(file)) problems.push(`缺 .ts.meta：${file}`)
  }
  for (const file of metaSet) {
    if (!tsSet.has(file)) problems.push(`多出来的 .ts.meta（同名 .ts 已不在）：${file}.meta`)
  }
  if (tracked !== null) {
    // **规则要的是「一起提交」，不是「磁盘上有」**：没入库的 .meta 与没入库的 .ts
    // 同样会在别人 clone 之后变成"有 .ts 无 .meta"，症状一模一样。
    for (const file of meta) {
      if (!tracked.has(file)) problems.push(`未入库：${file}（磁盘上有但 git ls-files 里没有，别人 clone 之后这一对就散了）`)
    }
  }
  return problems
}

if (require.main === module && selfTest) {
  // 三种输入：齐的（过）、缺 meta 的（红）、多 meta 的（红）。
  // 「不该触发的对照组必须绿」是这道门自己的验收标准。
  const ok = scan(['a.ts', 'a.ts.meta', 'b.ts', 'b.ts.meta'])
  const missing = scan(['a.ts', 'a.ts.meta', 'b.ts'])
  const orphan = scan(['a.ts', 'a.ts.meta', 'b.ts.meta'])
  let bad = 0
  if (ok.length !== 0) { console.error('自测红：配齐了却报了问题 ' + JSON.stringify(ok)); bad += 1 }
  if (missing.length !== 1 || !missing[0].includes('b.ts')) {
    console.error('自测红：缺 meta 没被抓 ' + JSON.stringify(missing)); bad += 1
  }
  if (orphan.length !== 1 || !orphan[0].includes('b.ts.meta')) {
    console.error('自测红：多出来的 meta 没被抓 ' + JSON.stringify(orphan)); bad += 1
  }
  if (bad === 0) {
    console.log('[check-ts-meta] 自测通过（配齐过 / 缺 meta 红 / 多 meta 红）。')
    process.exit(0)
  }
  process.exit(1)
}

if (!fs.existsSync(ROOT)) {
  console.log('[check-ts-meta] 没有 client/assets/scripts，跳过。')
  process.exit(0)
}

const tracked = new Set(gitLines(['ls-files', ROOT]))
const onDisk = walk(ROOT)
const files = [...new Set([...tracked, ...onDisk])].sort()
const problems = scan(files, tracked)
module.exports = { scan }

if (problems.length === 0) {
  const count = files.filter(f => f.endsWith('.ts') && !f.endsWith('.d.ts')).length
  console.log(`[check-ts-meta] ${count} 个 .ts 全部有同名 .ts.meta，没有多出来的。`)
  if (verbose) {
    for (const f of files.filter(x => x.endsWith('.ts.meta'))) console.log('  ' + f)
  }
  process.exit(0)
}
console.error(`[check-ts-meta] ${problems.length} 处 .ts / .ts.meta 没配对：`)
for (const p of problems) console.error('  ' + p)
console.error('  修法：Cocos 编辑器里对该文件执行「重新导入」，或把已生成的 .meta 一起 git add。')
process.exit(1)