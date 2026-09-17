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
  // TechCfg 曾挂在这里（#6「个人科技没有规格 —— B12 六个子系统不含科技」）：B20 块① 的 TechAppService
  // 现在真的读它（整棵树、等级上限、学院前置、每级消耗与时长全部来自这张表），例外随之删除
  // —— 与下面 PayProductCfg / GuideCfg 同一条规矩：接上读它的人之后，例外必须跟着删，别照着 #6 加回来。
  // PayProductCfg 与 ProductRewardCfg 曾挂在这里（B19-S1 只落表与参数）：S2 的 PaidProducts
  // （价格 / 发货内容 / 当前权益）与 ProductFulfilment、PaidClaimsAppService 让它们真的被生产代码读了，
  // 例外随之删除 —— 与下面 GuideCfg 那条同一条规矩：接上读它的人之后，例外必须跟着删。
  // GuideCfg 曾挂在这里（B18-S1 只落了表与契约）：S2 的 GuideRulesAssembler + /guide/script
  // + /guide/progress 让它真的被读了，例外随之删除 —— 与下面 ActivityCfg 那条同一条规矩。
  // ActivityCfg 曾挂在这里（#60「活动与七日登录只有表，没有推进与领取路径」）：
  // 2026-09-16 的 B17（ActivityRulesAssembler + ActivityEventListener + /activity/*）让它真的被读了，
  // 例外条目随之删除 —— 与下面 BotArchetypeCfg 那条同一条规矩，别照着 #60 加回来。
  QuestCfg: '零装配：#60（任务只有表与 core 领域模型，没有装配与端点，B12 §1）',
  MatchRuleCfg: '零装配：#60（匹配规则表没读，圈层与集结校验走 global 参数那条路，B08）',
  // 与上面几条同一条规矩：接上读它的人之后必须删除，别把例外当借口。
  // S3-i（#169）只落了表、发货内容与价格参数；读它的是 S3-ii 的弹窗判定
  // （事件源接线 + PopupThrottle + GET /gift/popup），那时本行随之删除。
  // 注意本表只到"列"以上：礼包的 trigger 与 offerTtlMinutes 在 S3-ii 之前无人读，
  // 而这张卡口看不见那一级（#165 ⑦ 同一条盲区）。
  GiftCfg: '零装配：#169（B19-S3-i 只落 gift.json 与三档商品的发货内容，弹窗判定与事件源在 S3-ii）',
  // NationTechCfg 曾挂在这里（B20 块③ 的 S1 只落了表与契约）：S2 的 Nation.techLevels 账本 +
  // researchTech 走 sink:NATIONAL_TECH 核销 + /nation/tech 两个端点 + NationTechBonuses 那一个读取口
  // 让它真的被生产代码读了，例外随之删除 —— 与上面 TechCfg / GuideCfg / PayProductCfg 同一条规矩。
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
  // 这四条是 B19 §五①a 定的换算基准与倍率。<b>运行时没有人读它们，这是设计而不是欠账</b>：
  // 它们回答的是"这一行该写 50 还是 60"，而算好的结果已经进了 `product_reward`，
  // 运行时读的是那张表（PaidProducts）。再让运行时乘一遍就是给同一份数字两个家。
  // 钉住"表里的值确实等于 价格 × 基准 × 倍率"的是 ProductRewardConsistencyTest（改价时它会红）。
  PRODUCT_GROWTH_FUND_RETURN_RATIO: '零引用：B19_付费发货与礼包弹窗.md §五③（算价输入，运行时读 product_reward 行，一致性由一致性测试现算）',
  PAY_BASE_GOLD_PER_YUAN: '零引用：B19_付费发货与礼包弹窗.md §五①a（同上：换算基准只在算价时用）',
  FIRST_CHARGE_MULTIPLIER: '零引用：B19_付费发货与礼包弹窗.md §五①a（同上：首充倍率只在算价时用）',
  MONTHLY_CARD_DAILY_MULTIPLIER: '零引用：B19_付费发货与礼包弹窗.md §五①a（同上：月卡倍率只在算价时用）',
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
