#!/usr/bin/env node
/**
 * 门：客户端不得把**配置表里的数值**写死在自己代码里。
 *
 * <p><b>为什么字符串那一族已有门、数值这一族还要单独一道</b>：
 * `check-client-config-copy` 管的是「值本身就是表内容」的**字符串**（倍速档位 "1,2"、版本号 "1.0.0"）；
 * 而**数值型**的表值（重试次数 3、退避 500ms、队列上限 50、网格边长 6、对时系数 2000）
 * 抄在客户端里，症状更隐蔽：**改表只改一半** —— 服务端按新表跑，客户端还按旧数跑，
 * 而单测 / 类型检查 / 构建全绿（两边各自自洽，没有一条断言会红）。
 *
 * <p>实测抓到的两个真缺陷（2026-10-19）：
 * <ul>
 *   <li>`city_rule.city_rule_grid_size` = 6：客户端在**两个文件**各写死一份，
 *       且**与表零绑定**（全仓 grep 该 id 在 client/ 下零命中）⇒
 *       表里 6→8 那一改，服务端按 8×8 下发坐标（到 7），客户端还按 6×6 判越界
 *       ⇒ **玩家自己建的城被说成「坐标非法」**。</li>
 *   <li>`global.NET_RETRY_*` / `TIME_SYNC_*` / `CHAT_LOCAL_HISTORY_MAX`：
 *       客户端各写死一份，服务端**生产代码一次都不读这些行**（逐个 grep 确认 0 处）——
 *       它们在表里的 `source` 写的是「B01 客户端 NetModule 要求」⇒ **主人就是客户端**，
 *       却放在服务端的表里。写死的那份与服务端悄悄漂移。</li>
 * </ul>
 *
 * <p><b>怎么判「这个数是抄表来的」</b>：靠**代码里点名了表 id**这条线索 ——
 * 抄表的人会（有意或无意）在注释里写「来源 global.XXX」/「与 global.json 同值」，
 * 而那正是抄表的自认。本门据此判：**注释里点名了某个配置表 id，
 * 而代码里那个数是写死的** ⇒ 抄表。
 *
 * <p><b>边界必须写清（这条是本门最要紧的一行）**：
 * <ul>
 *   <li>只抓**注释明确点名了表 id**的那些。抄表但**不写注释**的静态不可判定 ——
 *       那要靠「表 id 在 client/ 下零命中」这类**反向审计**（人或子代理逐个查）补，
 *       不是本门能覆盖的。**本门不假装能判语义。**</li>
 *   <li>**兜底值不算抄表**：「先用默认值发第一个请求、拿到服务端下发后覆盖」
 *       里的那个默认值**是**写死的，但它**会被覆盖** —— 而抄表是永远不覆盖。
 *       两者怎么区分，靠的是「这条数在**可下发覆盖**的路径上」——
 *       本门用**显式豁免**（`numeric-copy-exempt: <理由>`）记录那些合法的兜底值，
 *       豁免必须写理由且会被打印出来（与仓库既有的 `identity-exempt:` / `mongo-save-exempt:` 同一形状）。</li>
 * </ul>
 *
 * <p><b>为什么排除 generated 目录</b>：`npm run gen` 的产物，契约的一部分。
 *
 * <p><b>反空转</b>：一个文件都没扫到、或表读不到，都按失败处理。
 *
 * <p><b>用法</b>：<code>node scripts/check-client-numeric-copy.js</code>（默认判定）｜
 * <code>--probe</code>（只打印读数）｜<code>--self-test</code>（植入违规自检）。
 */
'use strict'

const fs = require('fs')
const path = require('path')

// 两个输入都可用环境变量换（验证这道门真的会红，而不必改仓库里的文件）——
// 与本仓其它门同一形状。**不设时逐字节等同改动前**（模式隔离）。
const ROOT = process.env.NUMCOPY_SCAN_DIR || 'client/assets/scripts'
const GLOBAL_TABLE = process.env.NUMCOPY_GLOBAL_JSON || 'contract/config/global.json'
const CITY_RULE_TABLE = process.env.NUMCOPY_CITYRULE_JSON || 'contract/config/city_rule.json'
const CHEST_TABLE = process.env.NUMCOPY_CHEST_JSON || 'contract/config/chest.json'

/**
 * 参与判定的表：这些表里的行是「服务端会按它跑、客户端会按它判」的数。
 *
 * <p>⚠️ **两种表形状，判据不同**：
 * <ul>
 *   <li><b>「值在一列里」</b>（global / city_rule）：行有 `value` 列，值在那一列 ⇒
 *       判「客户端写死了这个 value」。</li>
 *   <li><b>「值是命名列」</b>（chest 的 `maxBatchCount`）：行没有 `value` 列，
 *       数值散在**有名字的列**里 ⇒ 判「客户端写死了这一列的值」，
 *       而列名（`maxBatchCount`）就是判据的线索。</li>
 * </ul>
 * 两种形状都收，因为它们是同一族缺陷（客户端抄了表里的数），而**只收一种就会漏掉另一种**
 * —— 本门存在的理由正是「不能靠人记得」（chat 表那次就是「同名但不同量纲」的教训）。
 */
const TABLES = [
  { file: GLOBAL_TABLE, label: 'global', shape: 'value' },
  { file: CITY_RULE_TABLE, label: 'city_rule', shape: 'value' },
  { file: CHEST_TABLE, label: 'chest', shape: 'named-column' },
]

/** 豁免的形状：紧邻上一行的 `numeric-copy-exempt: <理由>`。 */
// 理由可以跨行（写清楚一条理由常常不止一行）⇒ 理由取到**下一个空行或下一个声明**为止，
// 且只取第一段（够读懂即可，不把整块注释都搬过来）。
const EXEMPT_RE = /numeric-copy-exempt:\s*([^\n*]+)/

function collectFiles(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) collectFiles(full, out)
    else if (entry.name.endsWith('.ts')) out.push(full)
  }
  return out
}

/**
 * 表里所有行 id → 该行可被抄走的数值。
 *
 * <p>两种形状：
 * <ul>
 *   <li>`value` 形状：行有 `value` 列 ⇒ 线索是**行 id**（`global.NET_RETRY_MAX_ATTEMPTS`）。</li>
 *   <li>`named-column` 形状：行没有 `value` 列，数值在**有名字的列**里 ⇒
 *       线索是**列名**（`chest.maxBatchCount`）。</li>
 * </ul>
 * 键统一写成「表名.线索」，两种形状就能走同一套匹配。
 */
function readTableRows() {
  const rows = new Map()
  for (const table of TABLES) {
    if (!fs.existsSync(table.file)) continue
    let data
    try {
      data = JSON.parse(fs.readFileSync(table.file, 'utf8'))
    } catch {
      continue
    }
    const list = Array.isArray(data) ? data : (data.rows || [])
    for (const row of list) {
      if (!row || typeof row !== 'object') continue
      const id = row.id
      if (typeof id !== 'string' || id === '') continue
      if (table.shape === 'value') {
        const value = row.value
        if (typeof value !== 'string' && typeof value !== 'number') continue
        rows.set(`${table.label}.${id}`, { label: table.label, value: String(value) })
        continue
      }
      // named-column 形状：每一个「数值型命名列」都是一条可被抄走的线索
      for (const [column, value] of Object.entries(row)) {
        if (column === 'id') continue
        if (typeof value !== 'number' || typeof value === 'boolean') continue
        rows.set(`${table.label}.${column}`, { label: table.label, value: String(value) })
      }
    }
  }
  return rows
}

/**
 * 一段文本里点名的表 id（词边界，避免短 id 误伤）。
 *
 * <p>⚠️ **两种写法都要认**：客户端的注释里既有裸 id
 * （「来源：contract/config/global.json 的 FIXED_POINT_SCALE」）也有带表名的
 * （「来源 global.NET_RETRY_MAX_ATTEMPTS」）。⇒ 匹配时**裸 id 是主形态**，
 * 带表名的形态是它的子串 —— 只认一种就会漏（第一版把键改成「表名.id」，
 * 裸 id 那种写法全漏了，命中数从 7 掉到 4）。
 */
function mentionedIds(text, ids) {
  const found = []
  for (const key of ids.keys()) {
    const bare = key.includes('.') ? key.slice(key.indexOf('.') + 1) : key
    // 两种形态都试：带表名的完整键、以及裸 id
    for (const candidate of [key, bare]) {
      if (!text.includes(candidate)) continue
      const re = new RegExp(`(^|[^A-Za-z0-9_])${candidate.replace(/\./g, '\\.')}($|[^A-Za-z0-9_])`)
      if (re.test(text)) {
        found.push(key)
        break
      }
    }
  }
  return found
}

/**
 * 找出一个文件里所有「模块级常量声明块」。
 *
 * <p>一个块 = 它的 JSDoc/行注释 + 紧跟其后的 `const X = ...` 声明。
 * 抄表的形状是：**注释里点名了表 id，而声明里写死了那个数** ——
 * 两个信息在**同一个块**里，所以必须按块看，不能按行看
 * （按行看的话，注释在上一行、数在下一行，就永远配不上）。
 *
 * <p>判「写死了数」：声明体里出现**裸数字字面量**。
 * 对象字面量（`const X = { a: 3 }`）也算 —— 那正是本门要抓的形状。
 * 表达式里的数字（坐标、时长、累加器）不算。
 */
function findConstBlocks(rawLines) {
  const blocks = []
  for (let i = 0; i < rawLines.length; i++) {
    const line = rawLines[i]
    const decl = /^\s*(export\s+)?const\s+[A-Za-z_$][\w$]*\s*(?::[^=]+)?=\s*(.*)$/.exec(line)
    if (decl === null) continue
    const start = i
    // 往回找紧邻的注释块（连续的注释行）
    let commentStart = i
    for (let j = i - 1; j >= 0; j--) {
      const prev = rawLines[j].trim()
      if (prev === '') break
      if (prev.startsWith('//') || prev.startsWith('/*') || prev.startsWith('*')) {
        commentStart = j
        continue
      }
      break
    }
    // 声明体：单行，或 `{ ... }` 展开的多行
    let body = decl[2]
    let end = i
    if (body.trim().startsWith('{')) {
      const close = rawLines.slice(i).findIndex(l => /}\s*(;|,)?\s*$/.test(l))
      if (close < 0) continue
      end = i + close
      body = rawLines.slice(i, end + 1).join('\n')
    }
    blocks.push({
      declLine: start,
      endLine: end,
      comment: rawLines.slice(commentStart, start).join('\n'),
      body: rawLines.slice(start, end + 1).join('\n'),
    })
  }
  return blocks
}

/** 声明体里有没有「裸数字字面量」（排除下标与属性名里的数字）。 */
function hasBareNumber(body) {
  // 去掉字符串内容，再看有没有独立的数字
  const code = body.replace(/(['"`])(?:\\.|(?!\1)[^\\])*\1/g, '""')
  return /(^|[^A-Za-z0-9_$.])-?[0-9][0-9_]*(?![A-Za-z0-9_])/.test(code)
}

/** 声明块里点了名 ⇒ 抄表。 */
function scan(files, ids) {
  const findings = []
  for (const file of files) {
    const raw = fs.readFileSync(file, 'utf8')
    const rawLines = raw.split(/\r?\n/)
    for (const block of findConstBlocks(rawLines)) {
      const mentions = mentionedIds(block.comment, ids)
      if (mentions.length === 0) continue
      if (!hasBareNumber(block.body)) continue
      const exempt = findExempt(rawLines, block.declLine)
      findings.push({
        file, line: block.declLine + 1, ids: [...new Set(mentions)], exempt,
        text: rawLines[block.declLine].trim().slice(0, 110),
      })
    }
  }
  return findings
}

/**
 * 紧邻上一行的豁免理由。
 *
 * <p><b>理由可以跨行</b>：写清楚一条理由常常不止一行 ⇒ 取「从 `numeric-copy-exempt:` 那行
 * 起、往后连续的注释行」拼成一段（去掉每行的 `//` 与 `*` 前缀），并把换行折成空格。
 * 只取第一段就够读懂（不把整块 JSDoc 搬过来）。
 */
function findExempt(rawLines, index) {
  for (let back = 1; back <= 3; back++) {
    const prev = rawLines[index - back]
    if (prev === undefined) break
    const match = EXEMPT_RE.exec(prev)
    if (match) {
      const parts = [match[1].trim()]
      // 往后收连续的注释行（同一段豁免说明的后续行）
      for (let ahead = 1; ahead <= 6; ahead++) {
        const next = rawLines[index - back + ahead]
        if (next === undefined) break
        const trimmed = next.trim()
        if (trimmed === '') break
        if (!trimmed.startsWith('//') && !trimmed.startsWith('*')) break
        if (/numeric-copy-exempt:/.test(trimmed)) break
        parts.push(trimmed.replace(/^(\/\/|\*)\s?/, '').trim())
      }
      return parts.filter(part => part !== '').join(' ')
    }
    if (prev.trim() !== '' && !prev.trim().startsWith('*') && !prev.trim().startsWith('//')) break
  }
  return null
}

function main() {
  const args = process.argv.slice(2)
  const probe = args.includes('--probe')
  const selfTest = args.includes('--self-test')

  const ids = readTableRows()
  if (ids.size === 0) {
    console.error('[check-client-numeric-copy][FAIL] 一张表都没读到 —— 判据坏了')
    process.exit(1)
  }
  if (!fs.existsSync(ROOT)) {
    console.error(`[check-client-numeric-copy][FAIL] ${ROOT} 不存在 —— 路径过期了`)
    process.exit(1)
  }
  const files = collectFiles(ROOT, []).filter(f => !f.split(path.sep).includes('generated'))
  if (files.length === 0) {
    console.error(`[check-client-numeric-copy][FAIL] ${ROOT} 下一个非生成物的 .ts 都没扫到 —— 路径过期了`)
    process.exit(1)
  }

  const found = scan(files, ids)
  const offending = found.filter(h => h.exempt === null)
  const exempted = found.filter(h => h.exempt !== null)

  if (selfTest) {
    // 植入一条违规：注释里点名 global.NET_RETRY_MAX_ATTEMPTS，同一行写死那个数。
    const probeId = ids.has('NET_RETRY_MAX_ATTEMPTS') ? 'NET_RETRY_MAX_ATTEMPTS' : ids.keys().next().value
    const tmp = path.join(ROOT, 'zz-numcopy-selftest.ts')
    try {
      fs.writeFileSync(tmp, `/** 来源 global.${probeId} */\nconst X = 3\n`, 'utf8')
      const after = scan(collectFiles(ROOT, []).filter(f => !f.split(path.sep).includes('generated')), ids)
      if (!after.some(h => h.file === tmp)) {
        console.error('[check-client-numeric-copy][FAIL] 自检：植入的违规没有被抓到 —— 判据不成立')
        process.exit(1)
      }
      console.log('[check-client-numeric-copy] 自检通过：植入的「注释点名表 id + 写死数值」被判红。')
    } finally {
      if (fs.existsSync(tmp)) fs.unlinkSync(tmp)
    }
    return
  }

  if (probe) {
    console.log(`表 ${ids.size} 条行；客户端非生成物 .ts ${files.length} 个；命中 ${found.length} 条（其中豁免 ${exempted.length}）`)
    for (const hit of found) {
      console.log(`  ${hit.file}:${hit.line}  [${hit.ids.join(', ')}]  ${hit.exempt ? '[豁免] ' : ''}${hit.text}`)
    }
    return
  }

  if (exempted.length > 0) {
    console.log(`[check-client-numeric-copy] 豁免 ${exempted.length} 项（理由见下）：`)
    for (const hit of exempted) console.log(`  ${hit.file}:${hit.line}  ${hit.exempt}`)
  }

  if (offending.length > 0) {
    console.error('[check-client-numeric-copy][FAIL] 客户端写死了配置表里的数值：')
    for (const hit of offending) {
      console.error(`  ${hit.file}:${hit.line}  表 id：${hit.ids.join(', ')}`)
      console.error(`      ${hit.text}`)
    }
    console.error('')
    console.error('  后果：改表只改一半 —— 服务端按新表跑，客户端还按旧数跑，')
    console.error('        而单测 / 类型检查 / 构建全绿（两边各自自洽，没有一条断言会红）。')
    console.error('        实测过的最狠的一种：网格边长 6→8，服务端下发坐标到 7，')
    console.error('        客户端还按 6×6 判越界 ⇒ 玩家自己建的城被说成「坐标非法」。')
    console.error('  修法：数值走服务端下发（契约里已有字段就读它；没有就先补字段），')
    console.error('        实在拿不到就写清「这是客户端自己的数，不是表值」并去掉表 id 的点名。')
    console.error('  确实是「先发后覆盖」里的兜底默认值：在紧邻上一行写 numeric-copy-exempt: <理由>')
    process.exit(1)
  }

  console.log(`[check-client-numeric-copy] 客户端没有写死配置表里的数值（${files.length} 个 .ts）。`)
}

main()
