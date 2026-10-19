#!/usr/bin/env node
/**
 * 门：客户端不得把**配置表的显示名**（中文名 / 标题 / 标签）抄在自己代码里。
 *
 * <p><b>为什么已有 48 号门（check-client-config-copy）不够</b>：48 号门盯的是
 * `global` 表里「值本身是一段可被原样抄走的字符串」的**参数**（倍速档位 "1,2"、
 * 版本号 "1.0.0"、各类比例 "0.20"），判据是「这个值是**配置**而不是**标识符**」。
 * 而本门盯的是**另一个族**：表的**显示名列**（`name` / `title` / `label` /
 * `displayName` / `permissionName`）里的中文名 —— 玩家在屏上读到的那句话。
 * 抄它的症状与 48 号门同源：**改表只改一半**（服务端认了新名，客户端还在显示旧名），
 * 而两边各自自洽 ⇒ 单测 / 类型检查 / 构建全绿。
 *
 * <p><b>为什么按「列的形状」筛，而不是按「值像不像配置」筛</b>（这一条是本门能成立的关键）：
 * 全表扫描 + 按值筛的实测结果是 **468 条命中**，绝大多数是**枚举字面量**：
 * <ul>
 *   <li>`'social'` / `'army'` / `'city'` / `'hero'` / `'quest'` / `'world'`
 *       —— 那是 `guide` 表的 panelKey、`role_permission` 表的 scope，
 *       客户端按它们比较是**契约的一部分**（协议里就是这串英文），不是抄表。</li>
 *   <li>`'ALLIANCE'` / `'SQUAD'` / `'NATION'` / `'NONE'` —— 同上（作用域 / 限制枚举）。</li>
 * </ul>
 * 换成一个**列形状**判据 —— 只取**显示名列**的值 —— 命中降到 **13 条**，
 * 而这 13 条里真正的抄表只有 1 条（战令名）。
 * ⇒ **判据是「这个字面量等于某张表的显示名列的值」，不是「这个字面量看起来像配置」。**
 *
 * <p><b>为什么必须把跨行块注释里的内容也剥掉</b>：第一版探针只剥单行注释，
 * 结果 `SeasonPanel.ts` 里一句**注释**（「界面层要认出『哪一行是体力』…」）被判成命中 ——
 * 注释里写一个名字是**说明**不是抄。⇒ 本门按「块注释整段不算代码」处理。
 *
 * <p><b>边界必须写清（这条是本门最要紧的一行）</b>：
 * <ul>
 *   <li>本门只认「客户端字面量**恰好等于**某个显示名」这一种形状。
 *       抄了表里的名字但**改写过**（比如把「军资补给箱」拼成「军资箱」）**静态不可判定** ——
 *       那要靠「表 id 在 client/ 下是否零命中」这类**反向审计**补。
 *       **本门不假装能判语义。**</li>
 *   <li>「客户端自己本地化一个**枚举**」不算抄表（如商店四个货币页签的标签：
 *       四个里只有一个恰好与表里的资源重名，其余三个表里根本没有名字列）——
 *       这类用 display-name-exempt: 加理由的形式豁免，理由必填且会被原样打印。</li>
 * </ul>
 *
 * <p><b>为什么排除 generated 目录</b>：<code>npm run gen</code> 的产物，契约的一部分。
 *
 * <p><b>反空转</b>：一张表都没读到、一个文件都没扫到，都按失败处理。
 *
 * <p><b>用法</b>：<code>node scripts/check-client-display-name-copy.js</code>（默认判定）｜
 * <code>--probe</code>（只打印读数）｜<code>--self-test</code>（植入违规自检）。
 */
'use strict'

const fs = require('fs')
const path = require('path')

// 输入都可用环境变量换（验证这道门真的会红，而不必改仓库里的文件）——
// 与本仓其它门同一形状。**不设时逐字节等同改动前**（模式隔离）。
const ROOT = process.env.DISPLAYNAME_SCAN_DIR || 'client/assets/scripts'
const CONFIG_DIR = process.env.DISPLAYNAME_CONFIG_DIR || 'contract/config'

/**
 * 参与判定的**显示名列**。
 *
 * <p>为什么是这几列：它们是玩家在屏上读到的**那句话**（中文名 / 标题 / 标签）。
 * 标识符列（`id` / `panelKey` / `scope` / `type` / `effectKind` / `*Param`）里的值
 * 是**协议枚举原文** —— 客户端按它们比较是契约的一部分，抄它们没有症状。
 */
const NAME_FIELDS = ['name', 'title', 'label', 'displayName', 'permissionName']

/** 生成物目录不参与判定。 */
function isGenerated(file) {
  return file.split(path.sep).includes('generated')
}

function collectFiles(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) collectFiles(full, out)
    else if (entry.name.endsWith('.ts')) out.push(full)
  }
  return out
}

/**
 * 剥掉注释，返回**纯代码行**（与原行一一对应，注释处是空串）。
 *
 * <p><b>必须整段剥</b>：块注释开闭标记之间的每一行都不算代码 ——
 * 只剥单行的话，块注释中间那些提到表里名字的行会被判成「抄表」（第一版探针就栽在这）。
 */
function stripComments(src) {
  const out = []
  let inBlock = false
  for (const line of src.split(/\r?\n/)) {
    if (inBlock) {
      out.push('')
      if (line.includes('*/')) inBlock = false
      continue
    }
    const open = line.indexOf('/*')
    if (open >= 0 && line.indexOf('*/', open) < 0) {
      inBlock = true
      out.push(line.slice(0, open).replace(/\/\/.*$/, ''))
      continue
    }
    out.push(line.replace(/\/\/.*$/, '').replace(/\/\*.*?\*\//g, ''))
  }
  return out
}

/**
 * 全部配置表的显示名列取值 → 值 → [{table, id, field}]。
 */
function readDisplayNames() {
  const values = new Map()
  let tableCount = 0
  let entryCount = 0
  for (const entry of fs.readdirSync(CONFIG_DIR).filter(f => f.endsWith('.json'))) {
    let data
    try {
      data = JSON.parse(fs.readFileSync(path.join(CONFIG_DIR, entry), 'utf8'))
    } catch {
      continue
    }
    const rows = Array.isArray(data) ? data : (data.rows || [])
    if (!Array.isArray(rows)) continue
    tableCount++
    const table = entry.replace('.json', '')
    for (const row of rows) {
      if (!row || typeof row !== 'object') continue
      for (const field of NAME_FIELDS) {
        const value = row[field]
        if (typeof value !== 'string') continue
        const trimmed = value.trim()
        // 长度窗：1 个字（"铁"）与 30 个字（描述句）都不像「显示名」——
        // 前者容易与无关短词撞车，后者是描述不是名字。
        if (trimmed.length < 2 || trimmed.length > 12) continue
        entryCount++
        if (!values.has(trimmed)) values.set(trimmed, [])
        values.get(trimmed).push({ table, id: String(row.id), field })
      }
    }
  }
  return { values, tableCount, entryCount }
}

/**
 * 找豁免理由：display-name-exempt: 加理由（与 config-copy-exempt: 同一形状）。
 *
 * <p><b>往上找 8 行</b>，而不是只找紧邻的 1 行：豁免说明常写成**多行注释**，
 * 而真正要豁免的那一行可能是那个注释块的**下一行**（注释块结束后隔着一行声明）。
 * 窗口只开 3 行时那种写法会**静默漏掉**（实测第一版就是这样：两处豁免都没被认到，
 * 门判红而人看不出为什么）。
 *
 * <p><b>遇到非注释行就停</b>：那说明中间隔了别的语句 ⇒ 这条豁免说的是别的东西。
 */
function findExempt(rawLines, index) {
  for (let back = 1; back <= 8; back++) {
    const prev = rawLines[index - back]
    if (prev === undefined) break
    const match = /display-name-exempt:\s*([^\n*]+)/.exec(prev)
    if (match) return match[1].trim()
    const trimmed = prev.trim()
    if (trimmed === '') break
    if (!trimmed.startsWith('*') && !trimmed.startsWith('//')) break
  }
  return null
}

function scan(files, values) {
  const hits = []
  for (const file of files) {
    const raw = fs.readFileSync(file, 'utf8')
    const rawLines = raw.split(/\r?\n/)
    const codeLines = stripComments(raw)
    const literal = /(['"`])([^'"`\n]{2,20})\1/g
    codeLines.forEach((line, index) => {
      if (line.trim() === '') return
      literal.lastIndex = 0
      let m
      while ((m = literal.exec(line)) !== null) {
        const owners = values.get(m[2])
        if (!owners) continue
        hits.push({
          file, line: index + 1, value: m[2], owners: owners.slice(0, 3),
          exempt: findExempt(rawLines, index),
          text: line.trim().slice(0, 110),
        })
      }
    })
  }
  return hits
}

function main() {
  const args = process.argv.slice(2)
  const probe = args.includes('--probe')
  const selfTest = args.includes('--self-test')

  if (!fs.existsSync(ROOT)) {
    console.error(`[check-client-display-name-copy][FAIL] ${ROOT} 不存在 —— 路径过期了`)
    process.exit(1)
  }
  const files = collectFiles(ROOT, []).filter(f => !isGenerated(f))
  if (files.length === 0) {
    console.error(`[check-client-display-name-copy][FAIL] ${ROOT} 下一个非生成物的 .ts 都没扫到 —— 路径过期了`)
    process.exit(1)
  }
  const { values, tableCount, entryCount } = readDisplayNames()
  if (values.size === 0) {
    console.error('[check-client-display-name-copy][FAIL] 一张表的显示名都没读到 —— 判据坏了')
    process.exit(1)
  }

  const hits = scan(files, values)
  const offending = hits.filter(hit => hit.exempt === null)
  const exempted = hits.filter(hit => hit.exempt !== null)

  if (selfTest) {
    const probeValue = values.keys().next().value
    const tmp = path.join(ROOT, 'zz-displayname-selftest.ts')
    try {
      fs.writeFileSync(tmp, `export const copied = '${probeValue}'\n`, 'utf8')
      const after = scan(collectFiles(ROOT, []).filter(f => !isGenerated(f)), values)
      if (!after.some(hit => hit.file === tmp)) {
        console.error('[check-client-display-name-copy][FAIL] 自检：植入的违规没有被抓到 —— 判据不成立')
        process.exit(1)
      }
      console.log('[check-client-display-name-copy] 自检通过：植入的「字面量等于表里的显示名」被判红。')
    } finally {
      if (fs.existsSync(tmp)) fs.unlinkSync(tmp)
    }
    return
  }

  if (probe) {
    console.log(`表 ${tableCount} 张；显示名取值 ${entryCount} 条（去重 ${values.size}）；客户端非生成物 .ts ${files.length} 个；命中 ${hits.length} 条（其中豁免 ${exempted.length}）`)
    for (const hit of hits) {
      const owners = hit.owners.map(o => `${o.table}/${o.id}.${o.field}`).join(', ')
      console.log(`  ${hit.file}:${hit.line}  '${hit.value}'  <= ${owners}  ${hit.exempt ? '[豁免] ' : ''}${hit.text}`)
    }
    return
  }

  if (exempted.length > 0) {
    console.log(`[check-client-display-name-copy] 豁免 ${exempted.length} 项（理由见下）：`)
    for (const hit of exempted) {
      const owners = hit.owners.map(o => `${o.table}/${o.id}.${o.field}`).join(', ')
      console.log(`  ${hit.file}:${hit.line}  '${hit.value}'  <= ${owners}  —— ${hit.exempt}`)
    }
  }

  if (offending.length > 0) {
    console.error('[check-client-display-name-copy][FAIL] 客户端写死了配置表的显示名：')
    for (const hit of offending) {
      const owners = hit.owners.map(o => `${o.table}/${o.id}.${o.field}`).join(', ')
      console.error(`  ${hit.file}:${hit.line}  '${hit.value}'  <= ${owners}`)
      console.error(`      ${hit.text}`)
    }
    console.error('')
    console.error('  后果：改表只改一半 —— 服务端认了新名，客户端还在显示旧名，')
    console.error('        而两边各自自洽 ⇒ 单测 / 类型检查 / 构建全绿，没有一条断言会红。')
    console.error('  修法：中文名一律来自服务端下发（契约里已有字段就读它；没有就先补字段），')
    console.error('        确实是「客户端自己本地化一个枚举」就在紧邻上一行写')
    console.error('        display-name-exempt: <理由>')
    process.exit(1)
  }

  console.log(`[check-client-display-name-copy] 客户端没有写死配置表的显示名（${files.length} 个 .ts）。`)
}

main()
