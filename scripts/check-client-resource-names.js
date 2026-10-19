#!/usr/bin/env node
/**
 * 门：客户端对**每个资源**只能有一种中文名，且那一种必须与表一致。
 *
 * <p><b>为什么这是红线</b>：项目红线「客户端不抄配置表：数值与中文名一律来自服务端下发」
 * 说了一半；另一半是**客户端自己也不得有第二份** —— 玩家看到两个不同的名字时，
 * 会以为那是两样东西：战报里写「铁 1200」、资源条上写「铁矿 1200」，而 `iron` 只有一个。
 * 这类缺陷是**纯显示层**的：没有任何单测会红（断言钉的是同一份硬编码），
 * 玩家却在两处看到同一样东西的两个名字。
 *
 * <p>本门有三条规则：
 * <ol>
 *   <li><b>规则 A（第二份家）</b>：资源中文名（**精确等于**表里的名字）作为**独立字面量**，
 *       不得出现在唯一那份（`game/ui/ResourceNames.ts`）之外。
 *       「独立字面量」= 整个字符串就是那个名字；嵌在句子里（「铁矿不足」）不算 ——
 *       那是**句子**，不是名字表。</li>
 *   <li><b>规则 B（唯一那份与表一致）</b>：唯一那份里的每个资源名必须与表逐字相同。</li>
 *   <li><b>规则 C（同一个键只能有一个名字）</b>：客户端任何「标识符键 → 中文名」的映射里，
 *       若键（忽略大小写）等于某个资源枚举，值必须是表里的那个名字。
 *       —— 这一条才抓得住「同一个资源换了种说法」：
 *       实测抓到的第一个真缺陷（2026-10-19）就是 `iron: '铁'`（表里是「铁矿」），
 *       而规则 A 抓不到它（「铁」不是「铁矿」的拼写变体，静态不可判定）。</li>
 * </ol>
 *
 * <p><b>边界要说清</b>：「哪句中文算资源名」静态不可判定 ——
 * 「铁」是 iron 的简称、「铁矿不足」是句子，机器分不出来。
 * 所以**规则 A 只管独立字面量**，而「换种说法」靠规则 C（键对得上）与
 * **场景层单测**（屏上到底显示了什么）共同守住。本门不假装能判语义。
 *
 * <p><b>豁免</b>：确实不是抄表的地方（例：商店页签自己写「金币」），
 * 在紧邻上一行写 `resource-name-exempt: <理由>`，理由会被打印出来
 * —— 与本仓既有 `identity-exempt:` / `mongo-save-exempt:` 同一形状。
 *
 * <p><b>反空转</b>：一个文件都没扫到、资源表读不到、唯一那份不在，都按失败处理。
 *
 * <p><b>用法</b>：<code>node scripts/check-client-resource-names.js</code>（默认判定）｜
 * <code>--probe</code>（只打印读数）｜<code>--self-test</code>（植入违规自检）。
 */
'use strict'

const fs = require('fs')
const path = require('path')

// 两个输入都可用环境变量换（验证这道门真的会红，而不必改仓库里的文件）——
// 与本仓其它门同一形状。**不设时逐字节等同改动前**（模式隔离）。
const ROOT = process.env.RESNAME_SCAN_DIR || 'client/assets/scripts'
const TABLE = process.env.RESNAME_TABLE || 'contract/config/resource.json'

/**
 * 客户端唯一那份资源中文名的位置（被本门认可的那一份）。
 *
 * <p>⚠️ 这条路径是**约定**不是自动发现：改了它就要同批改本门。
 * 那份文件自己的注释已经写明「客户端**唯一一份**」，与本门是同一件事的两面。
 */
const CANONICAL_REL = path.join('game', 'ui', 'ResourceNames.ts')

/** 资源表 → 「枚举 → 中文名」。 */
function readTableNames() {
  if (!fs.existsSync(TABLE)) return null
  let data
  try {
    data = JSON.parse(fs.readFileSync(TABLE, 'utf8'))
  } catch {
    return null
  }
  const rows = Array.isArray(data) ? data : (data.rows || [])
  const out = new Map()
  for (const row of rows) {
    if (!row || typeof row !== 'object') continue
    const id = row.id
    const name = row.name
    if (typeof id !== 'string' || typeof name !== 'string') continue
    if (name.trim() === '') continue
    out.set(id, name.trim())
  }
  return out
}

/**
 * 唯一那份里的「枚举 → 中文名」映射。
 *
 * <p>⚠️ 那份文件里有**两张表**：`RESOURCE_NAMES`（资源）与 `BUILDING_TYPE_NAMES`（建筑类型）。
 * 本门只认**资源那一张** —— 建筑类型名（核心/资源/民生/军事/防御/科技）是**枚举本地化**，
 * 表里根本没有这些中文名，不是「表值第二份家」；混进来会误判
 * （`RESOURCE: '资源'` 会被当成资源 `RESOURCE` 的名字）。
 */
function readCanonical(file) {
  const src = fs.readFileSync(file, 'utf8')
  const map = new Map()
  const block = /RESOURCE_NAMES[^=]*=\s*\{([\s\S]*?)\}/.exec(src)
  if (block === null) return map
  const re = /([A-Z][A-Z0-9_]*)\s*:\s*'([^']+)'/g
  let match
  while ((match = re.exec(block[1])) !== null) {
    map.set(match[1], match[2])
  }
  return map
}

/** 剥掉注释，保留换行（行号必须与原文件一致）。 */
function stripComments(src) {
  const blank = match => match.replace(/[^\n]/g, ' ')
  return src
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/\/\/[^\r\n]*/g, blank)
}

function collectFiles(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) collectFiles(full, out)
    else if (entry.name.endsWith('.ts')) out.push(full)
  }
  return out
}

/** 紧邻上一行的豁免理由（与仓库既有豁免形状一致）。 */
function findExempt(rawLines, index) {
  for (let back = 1; back <= 3; back++) {
    const prev = rawLines[index - back]
    if (prev === undefined) break
    const match = /resource-name-exempt:\s*([^\n*]+)/.exec(prev)
    if (match) return match[1].trim()
    if (prev.trim() !== '' && !prev.trim().startsWith('*') && !prev.trim().startsWith('//')) break
  }
  return null
}

function scan(files, canonical, tableNames) {
  const canonicalPath = path.join(ROOT, CANONICAL_REL)
  const byId = new Map(tableNames)
  const result = { duplicates: [], keyConflicts: [] }

  for (const file of files) {
    if (file === canonicalPath) continue
    const raw = fs.readFileSync(file, 'utf8')
    const rawLines = raw.split(/\r?\n/)
    const codeLines = stripComments(raw).split(/\r?\n/)
    codeLines.forEach((codeLine, index) => {
      if (codeLine.trim() === '') return
      const exempt = findExempt(rawLines, index)

      // 规则 A：独立字面量恰好是某个资源名
      for (const [id, name] of canonical) {
        const literalRe = new RegExp(`(['"\`])${name}\\1`, 'g')
        if (!literalRe.test(codeLine)) continue
        // ⚠️ **值的位置要分清**：键位置（`'GOLD': '金币'`）是「资源枚举 → 中文名」的映射，
        // 那属于唯一那份的职责；而 **值位置**（`label: '金币'`）是「某个具体标签用了这个名字」——
        // 那是**用**名字，不是**定义**名字。规则 A 只判后者。
        const isKeyPosition = new RegExp(`['"\`]?[A-Za-z][A-Za-z0-9_]*['"\`]?\\s*:\\s*(['"\`])${name}\\1`).test(codeLine)
        if (isKeyPosition) continue
        result.duplicates.push({
          file, line: index + 1, id, name, exempt,
          text: rawLines[index].trim().slice(0, 110),
        })
      }

      // 规则 C：键（忽略大小写）等于资源枚举时，值必须是表里的名字
      // ⚠️ **值是资源枚举原文（WOOD/STONE/…）不算违规** —— 那是「协议指标键 → 资源枚举」的
      // **标识符对标识符**映射（两个命名空间之间的桥），不是中文名。
      // 误判成违规会让这道门第一天就红，而红在那儿没人会去读原因。
      const pairRe = /([A-Za-z][A-Za-z0-9_]*)\s*:\s*'([^']+)'/g
      let match
      while ((match = pairRe.exec(codeLine)) !== null) {
        const key = match[1].toUpperCase()
        const value = match[2]
        const tableName = byId.get(key)
        if (tableName === undefined) continue
        if (value === tableName) continue
        if (value.toUpperCase() === key) continue
        result.keyConflicts.push({
          file, line: index + 1, key, value, tableName, exempt,
          text: rawLines[index].trim().slice(0, 110),
        })
      }
    })
  }
  return result
}

function main() {
  const args = process.argv.slice(2)
  const probe = args.includes('--probe')
  const selfTest = args.includes('--self-test')

  const tableNames = readTableNames()
  if (tableNames === null) {
    console.error('[check-client-resource-names][FAIL] 读不到资源表 —— 判据无从进行（宁可失败也不空转通过）')
    process.exit(1)
  }
  if (tableNames.size === 0) {
    console.error('[check-client-resource-names][FAIL] 资源表里一条名字都没读到 —— 判据坏了')
    process.exit(1)
  }
  if (!fs.existsSync(ROOT)) {
    console.error(`[check-client-resource-names][FAIL] ${ROOT} 不存在 —— 路径过期了`)
    process.exit(1)
  }
  const files = collectFiles(ROOT, []).filter(f => !f.split(path.sep).includes('generated'))
  if (files.length === 0) {
    console.error(`[check-client-resource-names][FAIL] ${ROOT} 下一个非生成物的 .ts 都没扫到 —— 路径过期了`)
    process.exit(1)
  }
  const canonicalPath = path.join(ROOT, CANONICAL_REL)
  if (!fs.existsSync(canonicalPath)) {
    console.error(`[check-client-resource-names][FAIL] 客户端唯一那份资源名映射不在 ${CANONICAL_REL} —— 判据的前提没了`)
    process.exit(1)
  }
  const canonical = readCanonical(canonicalPath)
  if (canonical.size === 0) {
    console.error('[check-client-resource-names][FAIL] 唯一那份里读不到 RESOURCE_NAMES —— 判据坏了')
    process.exit(1)
  }

  // 规则 B：唯一那份与表逐字一致
  const drift = []
  for (const [id, tableName] of tableNames) {
    const local = canonical.get(id)
    if (local === undefined) continue
    if (local !== tableName) drift.push({ id, tableName, local })
  }

  const found = scan(files, canonical, tableNames)
  const duplicates = found.duplicates.filter(h => h.exempt === null)
  const keyConflicts = found.keyConflicts.filter(h => h.exempt === null)
  const exempted = [...found.duplicates, ...found.keyConflicts].filter(h => h.exempt !== null)

  if (selfTest) {
    // 植入两条违规：① 独立字面量重复表名；② 键是资源枚举但值是另一种说法。
    const id = tableNames.keys().next().value
    const tableName = tableNames.get(id)
    const tmp = path.join(ROOT, 'zz-resname-selftest.ts')
    try {
      fs.writeFileSync(tmp, `export const a = '${tableName}'\nexport const b = { ${id}: '另一种说法' }\n`, 'utf8')
      const after = scan(collectFiles(ROOT, []).filter(f => !f.split(path.sep).includes('generated')), canonical, tableNames)
      const dupHit = after.duplicates.some(h => h.file === tmp)
      const keyHit = after.keyConflicts.some(h => h.file === tmp)
      if (!dupHit || !keyHit) {
        console.error(`[check-client-resource-names][FAIL] 自检：植入的违规没被抓到（重复=${dupHit} 键冲突=${keyHit}）—— 判据不成立`)
        process.exit(1)
      }
      console.log('[check-client-resource-names] 自检通过：重复字面量与键冲突两条规则都判红。')
    } finally {
      if (fs.existsSync(tmp)) fs.unlinkSync(tmp)
    }
    return
  }

  if (probe) {
    console.log(`资源表 ${tableNames.size} 条；客户端非生成物 .ts ${files.length} 个；唯一那份 ${CANONICAL_REL}（${canonical.size} 条）`)
    console.log(`\n规则 A（独立字面量重复表名）：${found.duplicates.length} 条`)
    for (const h of found.duplicates) console.log(`  ${h.file}:${h.line} '${h.name}' (${h.id}) ${h.exempt ? '[豁免] ' : ''}${h.text}`)
    console.log(`\n规则 C（键是资源枚举但值另一种说法）：${found.keyConflicts.length} 条`)
    for (const h of found.keyConflicts) console.log(`  ${h.file}:${h.line} ${h.key}: '${h.value}'（表里是 '${h.tableName}'） ${h.exempt ? '[豁免] ' : ''}${h.text}`)
    console.log(`\n规则 B（唯一那份与表不一致）：${drift.length} 条`)
    for (const d of drift) console.log(`  ${d.id}: 表 '${d.tableName}' vs 客户端 '${d.local}'`)
    return
  }

  if (exempted.length > 0) {
    console.log(`[check-client-resource-names] 豁免 ${exempted.length} 项（理由见下）：`)
    for (const h of exempted) console.log(`  ${h.file}:${h.line}  ${h.exempt}`)
  }

  if (duplicates.length > 0 || keyConflicts.length > 0 || drift.length > 0) {
    if (duplicates.length > 0) {
      console.error('[check-client-resource-names][FAIL] 资源名在唯一那份之外另有第二份（独立字面量）：')
      for (const h of duplicates) {
        console.error(`  ${h.file}:${h.line}  '${h.name}'（${h.id}）`)
        console.error(`      ${h.text}`)
      }
    }
    if (keyConflicts.length > 0) {
      console.error('[check-client-resource-names][FAIL] 同一个资源键在客户端有两种中文名：')
      for (const h of keyConflicts) {
        console.error(`  ${h.file}:${h.line}  ${h.key}: '${h.value}'  —— 表里是 '${h.tableName}'`)
        console.error(`      ${h.text}`)
      }
    }
    if (drift.length > 0) {
      console.error('[check-client-resource-names][FAIL] 客户端唯一那份资源名与表不一致：')
      for (const d of drift) console.error(`  ${d.id}: 表 '${d.tableName}' vs 客户端 '${d.local}'  （${CANONICAL_REL}）`)
    }
    console.error('')
    console.error('  后果：玩家看到两样东西的名字，其实是一样东西 ——')
    console.error('        战报里写「铁 1200」、资源条上写「铁矿 1200」，而 iron 只有一个。')
    console.error('        纯显示层缺陷：**没有任何测试会红**（断言钉的是同一份硬编码）。')
    console.error(`  修法：资源名一律走 ${CANONICAL_REL}（客户端唯一那份），别处不许另抄。`)
    console.error('  确实不是抄表的话，在紧邻上一行写：resource-name-exempt: <理由>')
    process.exit(1)
  }

  console.log(`[check-client-resource-names] 客户端对每个资源只有一种中文名（${files.length} 个 .ts，唯一那份 ${CANONICAL_REL}）。`)
}

main()
