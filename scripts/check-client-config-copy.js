#!/usr/bin/env node
/**
 * 门：客户端不得**抄** `global` 表里那些「值本身就是表内容」的字符串参数。
 *
 * <p><b>为什么只盯 global 表的这几条</b>（而不是全 46 张表）：全表扫描的误报面量过
 * （实测 796 条命中，绝大多数是枚举字面量与生成物里的类型声明，见本门 `--probe` 读数）。
 * 真正会咬人的是这一族形状 —— **值就是一段可被原样抄走的字符串**：
 * <ul>
 *   <li>`BATTLE_PLAYBACK_SPEEDS` = "1,2"（倍速档位）</li>
 *   <li>`RELEASE_LATEST_VERSION` = "1.0.0"（版本号）</li>
 *   <li>各类比例（"0.20" / "0.15" …）</li>
 * </ul>
 * 客户端把这种值抄一份，症状是**改表只改一半**：服务端认了新值，客户端还在用旧值，
 * 而所有单测 / 类型检查 / 构建都照绿（因为两边各自自洽）。
 * 这与「客户端不抄配置表」这条项目红线是同一件事。
 *
 * <p><b>为什么排除注释行</b>：注释里写 `（形如 "1,2"）` 是**说明**不是抄；
 * 注释里若藏一个真常量，那属于另一道门（死代码/影子常量）的形状，不在这里混判。
 *
 * <p><b>为什么排除 generated 目录</b>：<code>client/assets/scripts</code> 下的
 * <code>generated</code> 子目录是 <code>npm run gen</code> 的产物（项目红线：生成物不许手改），
 * 它里面的枚举字面量是契约的一部分，不是抄表。
 *
 * <p><b>反空转</b>：一条都没扫到、或表读不到，都按失败处理（路径过期 / 表被移走 ≠ "没问题"）。
 *
 * <p><b>用法</b>：<code>node scripts/check-client-config-copy.js</code>（默认判定）｜
 * <code>--probe</code>（只打印读数不判红）｜<code>--self-test</code>（植入违规自检）。
 */
'use strict'

const fs = require('fs')
const path = require('path')

// 两个输入都可用环境变量换（验证这道门真的会红，而不必改仓库里的文件）——
// 与本仓其它门同一形状（DOCCOUNT_CHECK_SH / ORPHAN_SCAN_DIRS 等）。
// **不设时逐字节等同改动前**（模式隔离）。
const ROOT = process.env.CONFIGCOPY_SCAN_DIR || 'client/assets/scripts'
const GLOBAL_TABLE = process.env.CONFIGCOPY_GLOBAL_JSON || 'contract/config/global.json'

/**
 * 只检查「值是一串可被原样抄走的字符串」的 global 参数。
 *
 * <p>为什么不是「global 表全部字符串值」：像 `RANK_BOT_SUPPRESSION` = "TOP_N" 这种枚举值，
 * 客户端按枚举字面量比较是**契约的一部分**（协议里就是这串英文），不是抄表 ——
 * 把它们也判红，这道门第一天就会变成 6 条误报，然后被人加白名单关掉。
 * 判据是「这个值是**配置**而不是**标识符**」：
 * 纯数字 / 逗号分隔数字 / 点分版本号 = 配置；全大写单词 = 标识符。
 */
function isConfigValue(value) {
  return /^[0-9]+([.,][0-9]+)*$/.test(value) || /^\d+(\.\d+){1,3}$/.test(value)
}

/** 生成物目录不参与判定（`npm run gen` 的产物，契约的一部分，不是抄表）。 */
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

function readGlobalValues() {
  if (!fs.existsSync(GLOBAL_TABLE)) {
    return null
  }
  let data
  try {
    data = JSON.parse(fs.readFileSync(GLOBAL_TABLE, 'utf8'))
  } catch {
    return null
  }
  const rows = Array.isArray(data) ? data : (data.rows || [])
  const out = []
  for (const row of rows) {
    if (!row || typeof row !== 'object') continue
    const value = row.value
    if (typeof value !== 'string') continue
    const trimmed = value.trim()
    if (trimmed === '' || !isConfigValue(trimmed)) continue
    out.push({ id: String(row.id || ''), value: trimmed })
  }
  return out
}

/**
 * 去掉源码里的注释（行注释 + 块注释），只留可执行代码。
 *
 * <p><b>为什么必须去块注释</b>：JSDoc 里写 `（形如 "1,2"）` 是**说明**，不是抄表。
 * 早期版本只去掉了 `//` 行注释，于是 JSDoc 里的举例文字全被当成命中 ——
 * 判据第一次跑就 4 条假红，其中 3 条是注释。
 */
function stripComments(src) {
  // 用等长的「空格 + 保留换行」替换：行数必须与原文件一致，否则行号会错位
  // （第一版直接用空格填，跨行块注释被压成一行，行号全错位 —— 判据指错了地方）。
  const blank = match => match.replace(/[^\n]/g, ' ')
  return src
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/\/\/[^\r\n]*/g, blank)
}

/** 扫出客户端源码里的字符串字面量。 */
function scan(files, values) {
  const byValue = new Map(values.map(v => [v.value, v]))
  const hits = []
  const literalRe = /(['"`])([^'"`\n]{1,40})\1/g
  for (const file of files) {
    const raw = fs.readFileSync(file, 'utf8')
    const rawLines = raw.split(/\r?\n/)
    const code = stripComments(raw).split(/\r?\n/)
    code.forEach((codeLine, index) => {
      if (codeLine.trim() === '') return
      literalRe.lastIndex = 0
      let match
      while ((match = literalRe.exec(codeLine)) !== null) {
        const owner = byValue.get(match[2])
        if (owner) {
          hits.push({
            file,
            line: index + 1,
            value: match[2],
            id: owner.id,
            text: rawLines[index].trim().slice(0, 100),
            // 豁免形状与仓库既有约定一致（identity-exempt: / mongo-save-exempt:）：
            // 写在**紧邻上一行**的注释里，理由必填，理由会被原样打印出来。
            exempt: findExempt(rawLines, index),
          })
        }
      }
    })
  }
  return hits
}

/**
 * 找紧邻上一行的豁免理由：`config-copy-exempt: <理由>`。
 * 与 `identity-exempt:` / `mongo-save-exempt:` 同一形状 —— 豁免必须写理由，
 * 且理由会被打印出来（这样「有豁免」这件事本身是可见的，不是悄悄放过）。
 */
function findExempt(rawLines, index) {
  for (let back = 1; back <= 3; back++) {
    const prev = rawLines[index - back]
    if (prev === undefined) break
    const match = /config-copy-exempt:\s*([^\n*]+)/.exec(prev)
    if (match) return match[1].trim()
    // 上一行不是豁免说明就停（只认紧邻的）
    if (prev.trim() !== '' && !prev.trim().startsWith('*') && !prev.trim().startsWith('//')) break
  }
  return null
}

function main() {
  const args = process.argv.slice(2)
  const probe = args.includes('--probe')
  const selfTest = args.includes('--self-test')

  const values = readGlobalValues()
  if (values === null) {
    console.error(`[check-client-config-copy][FAIL] 读不到 ${GLOBAL_TABLE} —— 判据无从进行（宁可失败也不空转通过）`)
    process.exit(1)
  }
  if (values.length === 0) {
    console.error(`[check-client-config-copy][FAIL] ${GLOBAL_TABLE} 里一条「配置型字符串值」都没读到 —— 判据坏了`)
    process.exit(1)
  }

  if (!fs.existsSync(ROOT)) {
    console.error(`[check-client-config-copy][FAIL] ${ROOT} 不存在 —— 路径过期了`)
    process.exit(1)
  }
  const all = collectFiles(ROOT, [])
  const files = all.filter(f => !isGenerated(f))
  if (files.length === 0) {
    console.error(`[check-client-config-copy][FAIL] ${ROOT} 下一个非生成物的 .ts 都没扫到 —— 路径过期了`)
    process.exit(1)
  }

  const hits = scan(files, values)

  if (selfTest) {
    // 植入一条违规：把某个真实表值抄进一个临时 .ts，判红；删掉，判绿。
    const probeValue = values[0]
    const tmp = path.join(ROOT, 'zz-config-copy-selftest.ts')
    try {
      fs.writeFileSync(tmp, `export const copied = '${probeValue.value}'\n`, 'utf8')
      const after = scan(collectFiles(ROOT, []).filter(f => !isGenerated(f)), values)
      if (!after.some(h => h.file === tmp)) {
        console.error('[check-client-config-copy][FAIL] 自检：植入的违规没有被抓到 —— 判据不成立')
        process.exit(1)
      }
      console.log(`[check-client-config-copy] 自检通过：植入 '${probeValue.value}'（<= ${probeValue.id}）被判红。`)
    } finally {
      if (fs.existsSync(tmp)) fs.unlinkSync(tmp)
    }
    return
  }

  if (probe) {
    console.log(`global 配置型字符串值 ${values.length} 条；客户端非生成物 .ts ${files.length} 个；命中 ${hits.length} 条`)
    for (const hit of hits) {
      console.log(`  ${hit.file}:${hit.line}  '${hit.value}'  <= ${hit.id}   ${hit.exempt ? '[豁免] ' : ''}${hit.text}`)
    }
    return
  }

  const offending = hits.filter(hit => hit.exempt === null)
  const exempted = hits.filter(hit => hit.exempt !== null)
  if (exempted.length > 0) {
    console.log(`[check-client-config-copy] 豁免 ${exempted.length} 项（理由见下）：`)
    for (const hit of exempted) {
      console.log(`  ${hit.file}:${hit.line}  '${hit.value}'  <= global.${hit.id}  —— ${hit.exempt}`)
    }
  }

  if (offending.length > 0) {
    console.error('[check-client-config-copy][FAIL] 客户端源码里抄了 global 表的值：')
    for (const hit of offending) {
      console.error(`  ${hit.file}:${hit.line}  '${hit.value}'  <= global.${hit.id}`)
      console.error(`      ${hit.text}`)
    }
    console.error('')
    console.error('  后果：改表只改一半 —— 服务端认了新值，客户端还在用旧值，')
    console.error('        而单测 / 类型检查 / 构建全绿（两边各自自洽）。')
    console.error('  修法：数值走服务端下发（契约里已有字段就读它），')
    console.error('        没有下发通道就把它记进台账 —— 不要在客户端留第二份家。')
    console.error('  确实不是抄表的话，在紧邻上一行写：config-copy-exempt: <理由>')
    process.exit(1)
  }

  console.log(`[check-client-config-copy] global 表 ${values.length} 条配置型字符串值在客户端非生成物源码里零命中。`)
}

main()
