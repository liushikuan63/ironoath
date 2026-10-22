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
/**
 * 配置表目录（**全部 46 张表**都参与）。
 *
 * <p><b>为什么从「三张表」扩到「全部」</b>：先前只收 global / city_rule / chest，
 * 因为**那三张是客户端确实从表里镜像过数的地方**；而「其余 43 张有没有被抄」当时只有
 * **反向审计**（人工逐表查）这一层证据 ⇒ 覆盖是「查过的」，不是「机器守着的」。
 * 收进本门之后，那层证据变成机器判据。
 *
 * <p><b>实测：扩到全部表之后命中只有 3 条</b>（逐条判完都是**非抄表**）⇒
 * 扩表的误报面是可控的，不需要为每张表写例外。
 */
const CONFIG_DIR = process.env.NUMCOPY_CONFIG_DIR || 'contract/config'

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

/** 「长得像数字的字符串」：逗号分隔的数字、比率、点分版本号（与 48 号门的「配置值」同一形状）。 */
const NUMERIC_STRING_RE = /^[0-9]+([.,][0-9]+)*$|^\d+(\.\d+){1,3}$/

/**
 * 读**全部**配置表：每张表的行 id + 数值列名，并统计**每个数值列名出现在几张表里**。
 *
 * <p>两种表形状都收（#2253 已踩过一次这个坑）：
 * <ul>
 *   <li><b>「值在一列里」</b>（global / city_rule）：行有 `value` 列 ⇒ 线索是**行 id**。</li>
 *   <li><b>「值是命名列」</b>（chest 的 `maxBatchCount`）：数值散在有名字的列里 ⇒ 线索是**列名**。</li>
 * </ul>
 *
 * <p><b>列名只在「全局唯一」时才算线索</b>：122 个数值列名里有 95 个是全局唯一的
 * （`maxBatchCount` / `costCount` / `staminaCost` …），剩下的是 `maxLevel` / `count` / `level` /
 * `tier` / `durationDays` 这种**普通英文词** —— 它们在多张表里都有，孤零零出现在代码里
 * 只能说明「代码里有个同名词」，不能说明「抄了表」（实测：按「列名出现过」统计，
 * 命中从 3 条撑到 24 条，全是 `hero_rarity` 的行 id `N` 撞上 `COLOR_SSR` 这类噪音）。
 */
function readTables() {
  const tables = []
  const colOwner = new Map()
  const files = fs.existsSync(CONFIG_DIR)
    ? fs.readdirSync(CONFIG_DIR).filter(f => f.endsWith('.json')).sort()
    : []
  for (const file of files) {
    let data
    try {
      data = JSON.parse(fs.readFileSync(path.join(CONFIG_DIR, file), 'utf8'))
    } catch {
      continue
    }
    const rows = Array.isArray(data) ? data : (data.rows || [])
    if (!Array.isArray(rows)) continue
    const table = file.replace('.json', '')
    const ids = []
    const cols = new Set()
    for (const row of rows) {
      if (!row || typeof row !== 'object') continue
      if (typeof row.id === 'string') ids.push(row.id)
      for (const [column, value] of Object.entries(row)) {
        if (column === 'id') continue
        // ⚠️ **字符串型数值列也要算**（2026-10-20 补）：有些表把数字**写成字符串**
        // （`bot_archetype.aggression` = "0.05"、`alliance_config.techCapBonus` = "0.0"）。
        // 之前只收 `typeof value === 'number'` ⇒ **这一族在门 51 的视野之外**
        // （门 48 只扫 global 的字符串参数，也看不见它们）⇒ 那就是一处**没人守着的缺口**。
        // ⇒ 现在按「长得像数字的字符串」一并收进来。
        const numeric = (typeof value === 'number' && typeof value !== 'boolean')
          || (typeof value === 'string' && NUMERIC_STRING_RE.test(value.trim()))
        if (!numeric) continue
        cols.add(column)
        if (!colOwner.has(column)) colOwner.set(column, new Set())
        colOwner.get(column).add(table)
      }
    }
    tables.push({ table, ids, cols })
  }
  const uniqueCols = new Set()
  for (const [column, owners] of colOwner) {
    if (owners.size === 1) uniqueCols.add(column)
  }
  return { tables, uniqueCols }
}

/**
 * 一段文本里出现的「表数据线索」。
 *
 * <p><b>线索只有两种形态，且各自有它的理由</b>：
 * <ul>
 *   <li><b>表行 id</b>（`season_01_phase_1`）：必须**连表名一起**出现才算线索 ——
 *       表的行 id 可能是 `N` / `R` / `SR` / `SSR` 这种短 token（`hero_rarity` 的行），
 *       孤零零出现会撞上一堆普通标识符（`COLOR_SSR`、`const R = …`）。
 *       而人自认抄表时写的正是「来源 global.json 的 XXX」「对应 chest 的 maxBatchCount」这种
 *       **带表名**的形态。</li>
 *   <li><b>全局唯一的数值列名</b>（`maxBatchCount` / `costCount` / `staminaCost`）：
 *       可以**单独**出现 —— 复合领域词在 46 张表里只出现一次 ⇒ 本身就是有辨识度的线索。</li>
 * </ul>
 *
 * <p>⚠️ **「裸 id 是主形态」那条旧结论要分清**：全局表里参数 id（`NET_RETRY_MAX_ATTEMPTS`）
 * 本身就是自认的形态（「来源：global.json 的 NET_RETRY_MAX_ATTEMPTS」——**表名与 id 之间
 * 隔着 `.json`**，不是紧挨着）⇒ 「行 id 要连表名」这条若按「紧挨着」判会漏掉它。
 * ⇒ 这里按「**同一段注释里同时出现表名与该 id**」来判，两者之间隔什么都不影响。
 */
function cluesIn(text, tables, uniqueCols) {
  const found = []
  for (const { table, ids, cols } of tables) {
    const esc = table.replace(/\./g, '\\.')
    const tableNamed = new RegExp(`(^|[^A-Za-z0-9_])${esc}($|[^A-Za-z0-9_])`).test(text)
    if (tableNamed) {
      for (const id of ids) {
        const re = new RegExp(`(^|[^A-Za-z0-9_])${id.replace(/\./g, '\\.')}($|[^A-Za-z0-9_])`)
        if (re.test(text)) {
          found.push(`${table}.${id}`)
          break
        }
      }
    }
    for (const column of cols) {
      if (!uniqueCols.has(column)) continue
      // x/y 是通用坐标轴，即使暂时只有一张表声明，也不能把 UI 像素注释当抄表。
      // 显式点名 world_target.y 仍判；具名数值列保持原有的独立线索判定。
      if (column.length === 1 && !tableNamed) continue
      const re = new RegExp(`(^|[^A-Za-z0-9_])${column.replace(/\./g, '\\.')}($|[^A-Za-z0-9_])`)
      if (re.test(text)) {
        found.push(`${table}.${column}（全局唯一的数值列名）`)
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
function scan(files, tables, uniqueCols) {
  const findings = []
  for (const file of files) {
    const raw = fs.readFileSync(file, 'utf8')
    const rawLines = raw.split(/\r?\n/)
    for (const block of findConstBlocks(rawLines)) {
      const mentions = cluesIn(block.comment, tables, uniqueCols)
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
  // ⚠️ **窗口是 8 行不是 3 行**：豁免说明常写成**多行注释**，而要豁免的那一行可能在那个
  // 注释块**之后隔一行**（中间还夹着一行 JSDoc）。只开 3 行时那种写法会**静默漏掉**
  // —— 门判红而人看不出为什么（#2258 实测踩到：GachaPanel 那条豁免就漏了）。
  // 与 `check-client-display-name-copy` 同一条教训、同一个窗口。
  for (let back = 1; back <= 8; back++) {
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
    // ⚠️ `/**` 也是注释行：只认 `*` 与 `//` 的话，**一行 JSDoc 就会把查找截断**
    // （实测：GachaPanel 那条豁免写在 JSDoc 之上 ⇒ 中间隔了一行 `/** … */`
    //  ⇒ 查找在那一行 break ⇒ 豁免没被认到 ⇒ 门判红而人看不出为什么）。
    if (prev.trim() !== '' && !prev.trim().startsWith('*') && !prev.trim().startsWith('//')
        && !prev.trim().startsWith('/*')) break
  }
  return null
}

function main() {
  const args = process.argv.slice(2)
  const probe = args.includes('--probe')
  const selfTest = args.includes('--self-test')

  const { tables, uniqueCols } = readTables()
  if (tables.length === 0) {
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

  const found = scan(files, tables, uniqueCols)
  const offending = found.filter(h => h.exempt === null)
  const exempted = found.filter(h => h.exempt !== null)

  if (selfTest) {
    // 单字符坐标列全局唯一，也不足以证明 UI 注释中的 y 来自某张配置表。
    const coordinateTables = [{ table: 'world_target', ids: ['target_fixture'], cols: ['x', 'y', 'effectRadius'] }]
    const coordinateColumns = new Set(['x', 'y', 'effectRadius'])
    if (cluesIn('UI 名牌 y 坐标，像素单位', coordinateTables, coordinateColumns).length !== 0
      || !cluesIn('来源 world_target.y', coordinateTables, coordinateColumns).some(s => s.includes('world_target.y'))
      || !cluesIn('服务端 effectRadius', coordinateTables, coordinateColumns).some(s => s.includes('effectRadius'))) {
      throw new Error('自检：单字符列必须带表名，具名列的原判据仍须生效')
    }
    // 植入一条违规：注释里点名 global.NET_RETRY_MAX_ATTEMPTS，同一行写死那个数。
    const probeId = 'NET_RETRY_MAX_ATTEMPTS'
    const tmp = path.join(ROOT, 'zz-numcopy-selftest.ts')
    try {
      fs.writeFileSync(tmp, `/** 来源 global.${probeId} */\nconst X = 3\n`, 'utf8')
      const after = scan(collectFiles(ROOT, []).filter(f => !f.split(path.sep).includes('generated')), tables, uniqueCols)
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
    console.log(`表 ${tables.length} 张；全局唯一的数值列名 ${uniqueCols.size} 个；客户端非生成物 .ts ${files.length} 个；命中 ${found.length} 条（其中豁免 ${exempted.length}）`)
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
