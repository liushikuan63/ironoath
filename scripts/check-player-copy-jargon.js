// 职责：见同名 check-player-copy-jargon.sh —— 玩家看得见的文案里不得出现内部机制词汇。
//
// 为什么这条只能靠静态检查：这类字符串**没有任何功能影响**，单测、类型检查、构建、真跑全绿，
// 唯一的症状是玩家读到「属编辑器资产」这种话。它一旦出现过一次就会照着抄 ——
// 收口清单 #221 数到同一句话在 7 个视图里各写一遍。
'use strict'

const fs = require('fs')
const path = require('path')

// 这些词属于"我们怎么实现"，不属于"玩家看到什么"。
// 例外：`隐私协议` 是微信官方要求玩家读到的词，先抹掉再判。
// `configId` 是本轮（#255）新加的：内城的建筑名一路是 `${building.configId} Lv${level}` 拼出来的，
// 屏幕上就印成 "main_city Lv1"。显示名的正确来源是服务端下发的 `name`（客户端没有表数据）。
// 它和"编辑器"那类不同 —— 不是措辞问题而是**取错了字段**，所以更要靠门：单测当时是绿的，
// 而且绿的那条断言写的就是 `'building_wood Lv6'`，等于给缺陷盖了章。
const JARGON = ['编辑器', 'ScrollView', '服务端', '客户端', '下发', '落库', '字段',
  '幂等', '契约', '不变量', '定点数', '占位', '未实现', 'TODO', '协议', '接口', 'configId']
const ROOTS = ['client/assets/scripts/scene', 'client/assets/scripts/game']

// 已知债务：文件里确实有工程术语进了玩家文案，但**这个文件正被并发会话改**，不由本门代改。
// 与台账编号豁免清单同一条纪律：**这个清单应当长期为空** —— 留着已经失效的条目，
// 等于给下一次"顺手写句黑话"开后门。清掉时连注释里的日期一起删。
const KNOWN_DEBT = [
  // 2026-09-19：曾挂过一条 `AppRoot.sellItem` 的「服务端还没有出售接口」——
  // B24 裁决④把出售整条撤下（按钮与表列一起删），那句话随之消失，豁免当场清空。
  // 留空不是形式：这一栏一旦长期为空，说明"文件正被别人改"不再是留下黑话的理由。
]

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      yield* walk(full)
    } else if (entry.name.endsWith('.ts')) {
      yield full
    }
  }
}

const problems = []
let scanned = 0
// 玩家看不到的字符串有两类：日志（下面按行豁免）与 throw 的异常消息（按语句豁免）。
// 异常消息是给开发者与日志看的，硬要它"说人话"只会丢掉诊断信息 —— 门只管真正会印到屏幕上的那些。
let inThrowStatement = false
let inConsoleStatement = false

for (const root of ROOTS) {
  if (!fs.existsSync(root)) {
    problems.push(`扫描路径不存在：${root}（门失效了，不是没问题）`)
    continue
  }
  for (const file of walk(root)) {
    scanned++
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
    let inBlockComment = false
    lines.forEach((line, index) => {
      const trimmed = line.trim()
      if (inBlockComment) {
        if (trimmed.includes('*/')) {
          inBlockComment = false
        }
        return
      }
      if (trimmed.startsWith('/*')) {
        if (!trimmed.includes('*/')) {
          inBlockComment = true
        }
        return
      }
      if (trimmed.startsWith('//') || trimmed.startsWith('*')) {
        return
      }
      // 日志与告警是给开发者看的，不算玩家文案。多行的 console 语句要一路吃到收尾括号，
      // 否则续行里的字符串会被当成玩家文案（首跑就在 BagPanel / RewardToastQueue 上误报过）。
      if (inConsoleStatement) {
        if (trimmed.endsWith(')') || trimmed.endsWith('),')) {
          inConsoleStatement = false
        }
        return
      }
      const consoleMatch = /console\.(log|warn|error|info|debug)\s*\(/.exec(line)
      if (consoleMatch !== null) {
        const openCount = (line.match(/\(/g) || []).length
        const closeCount = (line.match(/\)/g) || []).length
        inConsoleStatement = openCount > closeCount
        return
      }
      if (inThrowStatement) {
        if (trimmed.endsWith(';') || trimmed.endsWith('}')) {
          inThrowStatement = false
        }
        return
      }
      if (/^throw\b/.test(trimmed)) {
        inThrowStatement = !trimmed.endsWith(';')
        return
      }
      const literals = line.match(/`[^`]*`|"[^"]*"|'[^']*'/g) || []
      for (const literal of literals) {
        const probe = literal.replace(/隐私协议/g, '')
        const hit = JARGON.find((word) => probe.includes(word))
        if (hit !== undefined) {
          const relative = file.split(path.sep).join('/')
          const exempt = KNOWN_DEBT.some((debt) => relative.endsWith(debt.file) && debt.word === hit)
          if (!exempt) {
            problems.push(`${relative}:${index + 1} 玩家可见文案里有「${hit}」：${literal.slice(0, 70)}`)
          }
        }
      }
    })
  }
}

if (scanned === 0) {
  console.error('[check-player-copy-jargon] 一个 .ts 都没扫到 —— 判据失效，不算通过')
  process.exit(1)
}
if (problems.length > 0) {
  console.error(`[check-player-copy-jargon] ${scanned} 个文件里有 ${problems.length} 处工程术语进了玩家可见文案：`)
  problems.forEach((p) => console.error('  ' + p))
  console.error('  改成玩家读得懂的话；跨面板共用的句子收进 game/ui/ 下的纯函数（见 TruncatedList.ts）。')
  process.exit(1)
}
console.log(`[check-player-copy-jargon] ${scanned} 个视图/展示层文件的字符串字面量里没有工程术语。`)
