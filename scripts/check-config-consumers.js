// 职责：CI 静态检查 —— 每张配置表必须有**生产代码**读它，否则这条表就是装饰品。
// 症状族：运营改了 bot_archetype 的 aggression，游戏里 Bot 的行为一动不动，而服务端一行日志都没有。
//   这类"改表不生效"是本项目反复防的东西（B02 的铁律、global 表的 todo 机制都是为它）。
// 判定：生成物 cfg/XxxCfg 的类型名要出现在至少一个非测试文件里（server 主源码 / 客户端脚本 / tools / scripts）。
//   用类型名而不是反射：反射读表的代码也一定写了 `XxxCfg.class`，所以覆盖得到；
//   词边界匹配避免 AllianceTechCfg 被当成 TechCfg 的误报。
// 例外：必须写成 `零装配：<出处>`，而**出处会被核对** —— 出处是收口清单行号（那行必须真的存在）
//   或某个批次文件（该文件必须真的提到这张表对应的 snake_case 名）。出处编不出来。
// 依赖：node。
const fs = require('fs')
const path = require('path')

const CFG_DIR = 'server/game-config/src/main/java/com/ironoath/config/cfg'
const CHECKLIST = '收口清单.md'

/* 已知"表做了、机制没做"的族。每条都给得出出处，出处错了卡口就红，所以这张表不会烂成借口。 */
const UNWIRED = {
  TechCfg: '零装配：#6（个人科技没有规格 —— B12 六个子系统不含科技，见收口清单该行）',
  ActivityCfg: '零装配：#60（活动与七日登录只有表，没有推进与领取路径，B12 §5）',
  QuestCfg: '零装配：#60（任务只有表与 core 领域模型，没有装配与端点，B12 §1）',
  MatchRuleCfg: '零装配：#60（匹配规则表没读，圈层与集结校验走 global 参数那条路，B08）',
  // BotArchetypeCfg 与 BotNameCfg 曾挂在这里（#60「Bot 四张表零消费」）：2026-09-11 的孵化档
  // （收口清单 #84）让 BotRulesAssembler 真的读它们了，例外条目随之删除 —— 别照着 #60 加回来。
  BotChatCfg: '零装配：#60（同上）',
  BotScheduleCfg: '零装配：#60（同上）',
}

function walk(dir, out) {
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (/target|node_modules|build-test|\.git/.test(p)) continue
      walk(p, out)
    } else if (/\.(java|ts)$/.test(e.name)) out.push(p)
  }
  return out
}

/* 生成物镜像必须排除：`client/assets/scripts/config/generated/ConfigTypes.ts` 会为**每张表**声明一个
   interface，`web/dto/generated` 与 `config/cfg` 同理 —— 把它们算进消费者，32 张表就全部"有人读"，
   检查当场变成空转。**这个 bug 真的上线过一次**：卡口报"生产在读 32 张"，而直接查引用是零。
   教训与前几处同一条：一条检查在宣称"全都合规"之前，必须先确认它看得见违规。 */
const MIRROR = /[\\/]generated[\\/]|[\\/]cfg[\\/]/
const consumers = walk('server', [])
  .concat(walk('client/assets/scripts', []), walk('tools', []), walk('scripts', []))
  .filter((f) => !MIRROR.test(f))
  .map((f) => ({ f, src: fs.readFileSync(f, 'utf8'), test: /[/\\]src[/\\]test[/\\]/.test(f) }))
if (consumers.length < 400) {
  console.error('[check-config-consumers][FAIL] 消费者文件只有 ' + consumers.length
    + ' 个（预期 400 以上）—— 扫描范围一坏，这条卡口就只剩"全绿"可报了')
  process.exit(1)
}

const tables = fs.readdirSync(CFG_DIR).filter((f) => f.endsWith('.java')).map((f) => f.slice(0, -5))
if (tables.length < 25) {
  console.error('[check-config-consumers][FAIL] 只看到 ' + tables.length
    + ' 张表的生成物（预期 25 张以上）—— 目录或生成物坏了会让整条检查空转')
  process.exit(1)
}

const checklist = fs.existsSync(CHECKLIST) ? fs.readFileSync(CHECKLIST, 'utf8') : ''
const problems = []
const unwiredHit = []
const read = []

for (const typeName of tables) {
  const re = new RegExp('\\b' + typeName + '\\b')
  const touched = consumers.filter((s) => re.test(s.src))
  const mainTouched = touched.filter((s) => !s.test)
  if (mainTouched.length > 0) {
    read.push(typeName)
    continue
  }
  const note = UNWIRED[typeName]
  if (!note) {
    problems.push(`${typeName} 没有任何生产代码读它，也不在例外表里。`
      + '要么接上装配，要么在 UNWIRED 里写明出处（出处会被核对）；新表静默地没人读就是"改表不生效"')
    continue
  }
  unwiredHit.push(typeName)
  /* 核对出处 */
  const row = note.match(/#(\d+)/)
  if (row && !new RegExp('\\n\\|\\s*' + row[1] + '\\s*\\|').test('\n' + checklist)) {
    problems.push(`${typeName} 的例外出处指向 收口清单 #${row[1]}，但那一行不存在（清单被回滚或改号了）`)
  }
  const md = note.match(/([BC]\d\d_[^\s，。）)]+\.md)/)
  if (md && !fs.existsSync(md[1])) {
    problems.push(`${typeName} 的例外出处指向 ${md[1]}，但这个文件不存在`)
  }
}

console.log('[check-config-consumers] 生成物 ' + tables.length + ' 张表：生产在读 ' + read.length
  + ' 张，登记为零装配 ' + unwiredHit.length + ' 张')
if (unwiredHit.length > 0) {
  console.log('[check-config-consumers] 零装配清单：' + unwiredHit.join(', '))
}
/* 第二维：global 参数也一样 —— 参数存在但没人读，就等于"改了不生效"。 */
const GLOBAL_ROWS = JSON.parse(fs.readFileSync('contract/config/global.json', 'utf8')).rows || []
if (GLOBAL_ROWS.length < 200) {
  console.error('[check-config-consumers][FAIL] global 表只读到 ' + GLOBAL_ROWS.length + ' 行（预期 200+）')
  process.exit(1)
}

/* 零引用的 7 个参数，逐个给出处（出处会被核对，与 UNWIRED 同一套）。 */
const UNREFERENCED = {
  WAR_SERVER_GOAL_GOLD: '零引用：#47（国战与全服目标整块未开工，没有承载代码）',
  NATION_VOTE_DURATION_HOURS: '零引用：#47（国策投票同上，B13 §4 没有实现）',
  POWER_DROP_ALERT_RATIO: '零引用：#26（B08 §8 的"标记观察"没有风控归属，标记了给谁看未定）',
  PRODUCT_GROWTH_FUND_RETURN_RATIO: '零引用：#64（成长基金这件商品还没进货架，B15 §六 开放问题 2）',
  PAY_CHANNEL: '零引用：#64（渠道写死在支付适配器上，这条参数只起记录作用）',
  // MINOR_PAY_SINGLE_LIMIT_CENTS 与 MINOR_PAY_MONTHLY_LIMIT_CENTS 曾在这里挂着：
  // 那两条不是"设计如此"，而是**没接线**（#64）。现在下单路径
  // （PayAppService#requireWithinMinorLimit）会读它们，例外条目随之删除 ——
  // 别照着 #64 把它们加回来。
}

let paramHits = 0
const deadParams = []
for (const row of GLOBAL_ROWS) {
  const re = new RegExp('\\b' + row.id + '\\b')
  if (consumers.some((s) => re.test(s.src))) { paramHits++; continue }
  deadParams.push(row.id)
  const note = UNREFERENCED[row.id]
  if (!note) {
    problems.push(`${row.id} 这个 global 参数没有任何代码读它，也不在例外表里。`
      + '要么接上消费方，要么在 UNREFERENCED 里写明出处（出处会被核对）')
    continue
  }
  const citeRow = note.match(/#(\d+)/)
  if (citeRow && !new RegExp('\\n\\|\\s*' + citeRow[1] + '\\s*\\|').test('\n' + checklist)) {
    problems.push(`${row.id} 的例外出处指向 收口清单 #${citeRow[1]}，但那一行不存在`)
  }
}
if (paramHits === 0) {
  console.error('[check-config-consumers][FAIL] 254 个参数一个都没匹配上 —— 这是探针坏了（扫描范围或正则），不是"全都干净"')
  process.exit(1)
}
console.log('[check-config-consumers] global 参数 ' + GLOBAL_ROWS.length + ' 行：被引用 ' + paramHits
  + ' 个，登记为零引用 ' + deadParams.length + ' 个')
if (deadParams.length > 0) {
  console.log('[check-config-consumers] 零引用参数：' + deadParams.join(', '))
}

if (problems.length > 0) {
  console.error('[check-config-consumers][FAIL] ' + problems.length + ' 条：')
  problems.forEach((p) => console.error('   - ' + p))
  process.exit(1)
}
console.log('[check-config-consumers] 每张表要么被生产代码读，要么带着可核对的出处。')
