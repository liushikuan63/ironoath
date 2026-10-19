#!/usr/bin/env node
/**
 * 门：客户端不得用**中文表值**做逻辑判断（只允许拿去显示）。
 *
 * <p><b>为什么这是红线</b>：项目红线「客户端不抄配置表」有一条更狠的推论 ——
 * 抄了表值还能靠改表同步，但**拿表值当逻辑判据**则是「表值一改，行为就静默变」：
 * 症状是玩家点某个东西没反应，而**所有测试都照绿**（因为测试读的是同一份硬编码）。
 *
 * <p>本门盯的形状（实测抓到的第一个真缺陷，2026-10-19）：
 * `CityPanelView.ts` 认「哪一行是体力」用 `line.startsWith('体力')` ——
 * 「体力」是 `resource.json` 的 `name` 列，客户端那份映射一改（或者表里改了），
 * 那一行就不再可点：**玩家点体力没反应，测试全绿**。
 * 修法是认协议枚举（`line.type === 'STAMINA'`），种类本来就在手边。
 *
 * <p><b>为什么用「字符串方法参数里含中文」而不是「整行含中文」</b>：
 * 界面文案里全是中文（那是**显示**，不是逻辑判据），按整行扫会判红几千处。
 * 真正危险的是**拿中文串去做判断**：传给 startsWith / endsWith / includes / indexOf /
 * split / match / replace 这类方法的第一个实参。显示用的模板字符串不在这条线上。
 *
 * <p><b>为什么排除注释</b>：注释里写「按中文名前缀去比」是**说明**（包括本门自己的
 * 判据说明），不是逻辑判据。块注释里的中文也会被扫到 ⇒ 必须先剥注释。
 *
 * <p><b>反空转</b>：一个文件都没扫到、或表读不到，都按失败处理。
 *
 * <p><b>用法</b>：<code>node scripts/check-client-text-logic.js</code>（默认判定）｜
 * <code>--probe</code>（只打印读数）｜<code>--self-test</code>（植入违规自检）。
 */
'use strict'

const fs = require('fs')
const path = require('path')

// 两个输入都可用环境变量换（验证这道门真的会红，而不必改仓库里的文件）——
// 与本仓其它门同一形状（DOCCOUNT_CHECK_SH / ORPHAN_SCAN_DIRS 等）。
// **不设时逐字节等同改动前**（模式隔离）。
const ROOT = process.env.TEXTLOGIC_SCAN_DIR || 'client/assets/scripts'

/**
 * 会把「字符串实参」变成逻辑判据的方法。
 *
 * <p>为什么是这七个：它们都在问「这个串**是不是** / **包含** / **以什么开头结尾**」，
 * 也就是「拿这个串做判断」。而 `replace('T', ' ')` 这种是**改写**（显示层的格式化），
 * 拿它当判据也说得过去但风险低、误报高（`RankBoard.ts` 的 ISO 时间格式化就是这一族），
 * 所以 `replace` **不收** —— 收进来第一天就会是一条常驻假红。
 */
const PREDICATE_METHODS = ['startsWith', 'endsWith', 'includes', 'indexOf', 'split', 'match']

/** 生成物目录不参与判定（`npm run gen` 的产物，契约的一部分）。 */
function isGenerated(file) {
  return file.split(path.sep).includes('generated')
}

/** 剥掉注释，保留换行（行号必须与原文件一致，否则判据指错地方）。 */
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

/** 一行里有没有「方法( '中文' 」这种形状。 */
function findOffenders(codeLine) {
  const hits = []
  for (const method of PREDICATE_METHODS) {
    // 形如 `.startsWith('体力')` / `.includes("体力")` —— 允许参数前有空格
    const re = new RegExp(`\\.${method}\\s*\\(\\s*(['"\`])([^'"\\\`]*[\\u4e00-\\u9fff][^'"\\\`]*)\\1`, 'g')
    let match
    while ((match = re.exec(codeLine)) !== null) {
      hits.push({ method, value: match[2] })
    }
  }
  return hits
}

function main() {
  const args = process.argv.slice(2)
  const probe = args.includes('--probe')
  const selfTest = args.includes('--self-test')

  if (!fs.existsSync(ROOT)) {
    console.error(`[check-client-text-logic][FAIL] ${ROOT} 不存在 —— 路径过期了`)
    process.exit(1)
  }
  const all = collectFiles(ROOT, [])
  const files = all.filter(f => !isGenerated(f))
  if (files.length === 0) {
    console.error(`[check-client-text-logic][FAIL] ${ROOT} 下一个非生成物的 .ts 都没扫到 —— 路径过期了`)
    process.exit(1)
  }

  const findings = []
  for (const file of files) {
    const raw = fs.readFileSync(file, 'utf8')
    const rawLines = raw.split(/\r?\n/)
    const codeLines = stripComments(raw).split(/\r?\n/)
    codeLines.forEach((codeLine, index) => {
      if (codeLine.trim() === '') return
      for (const hit of findOffenders(codeLine)) {
        findings.push({
          file,
          line: index + 1,
          method: hit.method,
          value: hit.value,
          text: rawLines[index].trim().slice(0, 110),
        })
      }
    })
  }

  if (selfTest) {
    // 植入一条违规：在临时 .ts 里拿中文串做 startsWith，判红；删掉，判绿。
    const tmp = path.join(ROOT, 'zz-text-logic-selftest.ts')
    try {
      fs.writeFileSync(tmp, "export const x = '体力'.startsWith('体力')\n", 'utf8')
      const after = []
      const scanFiles = collectFiles(ROOT, []).filter(f => !isGenerated(f))
      for (const file of scanFiles) {
        const codeLines = stripComments(fs.readFileSync(file, 'utf8')).split(/\r?\n/)
        codeLines.forEach((line, index) => {
          for (const hit of findOffenders(line)) {
            after.push({ file, line: index + 1, ...hit })
          }
        })
      }
      if (!after.some(h => h.file === tmp)) {
        console.error('[check-client-text-logic][FAIL] 自检：植入的违规没有被抓到 —— 判据不成立')
        process.exit(1)
      }
      console.log('[check-client-text-logic] 自检通过：植入的「中文串做 startsWith」被判红。')
    } finally {
      if (fs.existsSync(tmp)) fs.unlinkSync(tmp)
    }
    return
  }

  if (probe) {
    console.log(`客户端非生成物 .ts ${files.length} 个；中文串做逻辑判据命中 ${findings.length} 条`)
    for (const hit of findings) {
      console.log(`  ${hit.file}:${hit.line}  .${hit.method}('${hit.value}')   ${hit.text}`)
    }
    return
  }

  if (findings.length > 0) {
    console.error('[check-client-text-logic][FAIL] 客户端拿中文串做逻辑判断：')
    for (const hit of findings) {
      console.error(`  ${hit.file}:${hit.line}  .${hit.method}('${hit.value}')`)
      console.error(`      ${hit.text}`)
    }
    console.error('')
    console.error('  后果：中文名是配置表的值。表值一改（或者客户端那份映射一改），')
    console.error('        这条判断就**静默失效** —— 玩家点某个东西没反应，')
    console.error('        而单测 / 类型检查 / 构建全绿（测试读的是同一份硬编码）。')
    console.error('  修法：认协议枚举（种类本来就在手边，如 `line.type === \'STAMINA\'`），')
    console.error('        中文名只拿去显示。')
    process.exit(1)
  }

  console.log(`[check-client-text-logic] 客户端非生成物源码里没有拿中文串做逻辑判断（${files.length} 个 .ts）。`)
}

main()
