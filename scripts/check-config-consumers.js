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
  // QuestCfg 与 MatchRuleCfg 曾挂在这里：#87 的 QuestRulesAssembler / QuestAppService 让任务表真的被读了；
  // MatchRuleCfg 由 #262 接上（PowerService 的战力区间改成只认 mr_scenario_normal_attack 那一行的引用）。
  // 与下面 BotArchetypeCfg 那条同一条规矩：接上读它的人之后必须删除，别把例外当借口。
  // S3-i（#169）只落了表、发货内容与价格参数；读它的是 S3-ii 的弹窗判定
  // NationTechCfg 曾挂在这里（B20 块③ 的 S1 只落了表与契约）：S2 的 Nation.techLevels 账本 +
  // researchTech 走 sink:NATIONAL_TECH 核销 + /nation/tech 两个端点 + NationTechBonuses 那一个读取口
  // 让它真的被生产代码读了，例外随之删除 —— 与上面 TechCfg / GuideCfg / PayProductCfg 同一条规矩。
  // BotArchetypeCfg 与 BotNameCfg 曾挂在这里（#60「Bot 四张表零消费」）：2026-09-11 的孵化档
  // （收口清单 #84）让 BotRulesAssembler 真的读它们了，例外条目随之删除 —— 别照着 #60 加回来。
  // NationPolicyCfg 曾挂在这里（#477，B13 §4 国策 V17 的 B 格：表与 A 格内核通路都交付了）：
  // 2026-09-30 的 F 格让 NationPolicyBonuses（战斗侧乘区 G/H + 产出 + 行军速度）真的读它了，
  // 例外随之删除 —— 与上面 NationTechCfg 那条同一条规矩。
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
  /* 核对出处：**必须**含 `#数字`，否则判红。
     原先是 `if (row && …)` —— 没有 `#数字` 就整段跳过、**静默通过**，
     于是「零引用：因为还没做」这类无锚点出处能过这道门。
     例外机制的全部价值在于「出处可核对」，而不可核对的出处等于没有出处。 */
  const row = note.match(/#(\d+)/)
  if (!row) {
    problems.push(`${typeName} 的例外出处里没有 \`#数字\`，无法核对：${note.slice(0, 60)}`
      + ' —— 例外必须指向收口清单里真实存在的一行（`\n| N |`）')
  } else if (!new RegExp('\\n\\|\\s*' + row[1] + '\\s*\\|').test('\n' + checklist)) {
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
  // NATION_VOTE_DURATION_HOURS 曾挂在这里（#47「国策投票同上，B13 §4 没有实现」）：
  // 2026-09-30 的 V17-D 格让 NationRulesAssembler 真的读它了（换算成 policyVoteMillis 与
  // policyRoundMillis，轮次时间旋钮只有这一个），例外随之删除 —— 与下面 MINOR_PAY_* 那条同一条规矩。
  POWER_DROP_ALERT_RATIO: '零引用：#26（B08 §8 的"标记观察"没有风控归属，标记了给谁看未定）',
  // 2026-10-01 的一批：**规格已定、出处齐全，但生产零消费** —— 由 #501 暴露。
  // 门禁原先把 src/test 的引用算成生产引用，所以这 23 个一直免于例外登记。
  // 四类，分类依据是「为什么生产读不到」，不是按前缀随手分：
  //   ① 装配层缺读取口（领域层备好了、没人调）
  //   ② 值住在另一张表里（global 这一对是聚合/上限，权威值在别处）
  //   ③ 整块玩法未开工
  //   ④ 判据是压测/口径而不是代码
  STAGE_DIFFICULTY_BASE: 'zero-ref: #501①（**生成期输入**：stage 表那 50 行是由这四条推导物化的，运行期读的是物化后的行 —— 改难度要改这四条再重跑生成器）',
  STAGE_DIFFICULTY_RATIO_EARLY: 'zero-ref: #501①（同上：生成期输入）',
  STAGE_DIFFICULTY_EARLY_THROUGH: 'zero-ref: #501①（同上：生成期输入）',
  STAGE_DIFFICULTY_RATIO_LATE: 'zero-ref: #501①（同上：生成期输入）',
  BOT_SHARE_LINJU: 'zero-ref: #502⑥（**动态拼接的参数名**：BotRulesAssembler.shareOf 按 `BOT_SHARE_` + 原型 id 后缀拼出名字再 `configs.longParam(param)`，静态扫字面量看不见它。不是零引用）',
  BOT_SHARE_MENGYOU: 'zero-ref: #502⑥（**动态拼接的参数名**：BotRulesAssembler.shareOf 按 `BOT_SHARE_` + 原型 id 后缀拼出名字再 `configs.longParam(param)`，静态扫字面量看不见它。不是零引用）',
  BOT_SHARE_JIELUE: 'zero-ref: #502⑥（**动态拼接的参数名**：BotRulesAssembler.shareOf 按 `BOT_SHARE_` + 原型 id 后缀拼出名字再 `configs.longParam(param)`，静态扫字面量看不见它。不是零引用）',
  BOT_SHARE_JUNFA: 'zero-ref: #502⑥（**动态拼接的参数名**：BotRulesAssembler.shareOf 按 `BOT_SHARE_` + 原型 id 后缀拼出名字再 `configs.longParam(param)`，静态扫字面量看不见它。不是零引用）',
  BOT_SHARE_YINGZI: 'zero-ref: #502⑥（**动态拼接的参数名**：BotRulesAssembler.shareOf 按 `BOT_SHARE_` + 原型 id 后缀拼出名字再 `configs.longParam(param)`，静态扫字面量看不见它。不是零引用）',
  BOT_FULL_ROUND_BUDGET_MS: 'zero-ref: #501④（验收 6 的压测门槛，不是代码判据；要接的是压测脚本）',
  BOT_REACTION_DELAY_MIN_SEC: 'zero-ref: #501②（权威值在 bot_archetype 表的 reactionDelayMinSec/MaxSec，这一对是全局上下界）',
  BOT_REACTION_DELAY_MAX_SEC: 'zero-ref: #501②（同上）',
  BOT_HELP_DELAY_MIN_SEC: 'zero-ref: #501②（同类：求助延迟的权威值不在这一对）',
  BOT_HELP_DELAY_MAX_SEC: 'zero-ref: #501②（同上）',
  BOT_MISTAKE_RATE_MIN: 'zero-ref: #501②（权威值在 bot_archetype 表）',
  BOT_MISTAKE_RATE_MAX: 'zero-ref: #501②（同上）',
  BOT_MARK_AFTER_DAYS: 'zero-ref: #501③（标识策略属未开工的那半：B11 开放问题 3 的建议值）',
  NATION_DIPLOMACY_RELATIONS: 'zero-ref: #501③（四种外交关系的枚举清单，B13 §5 定；消费方按关系名硬判，没有读这个清单的地方）',
  PRODUCT_BATTLE_PASS_CENTS: 'zero-ref: #501③（战令商品未上货架，B24 裁决②已定价）',
  PRODUCT_GROWTH_FUND_CENTS: 'zero-ref: #501③（成长基金未上货架）',
  PRODUCT_FIRST_CHARGE_CENTS: 'zero-ref: #501③（首充档位未上货架）',
  PRODUCT_GIFT_CENTS: 'zero-ref: #501③（礼包档价格未上货架）',
  GIFT_VALUE_MULTIPLIER: 'zero-ref: #501①（**换算期输入**：B19 §五① 的倍率只在算价时用，运行期读 product_reward 的逐行 rewardValue）',
  // 2026-09-30: #19 rally bonus wiring is DONE (PlayerCityBattleService.rallyBonus).
  //   Amplitude comes from the measured balance-sim --rally curve (A10).
  //   The gate is march.isRallyMarch(), not a second headcount check --
  //   Rally.depart() already rejects departure below minMembersRequired.
  // Exception removed: this row can go because the gate counts production call sites.
  // 这四条是 B19 §五①a 定的换算基准与倍率。<b>运行时没有人读它们，这是设计而不是欠账</b>：
  // 它们回答的是"这一行该写 50 还是 60"，而算好的结果已经进了 `product_reward`，
  // 运行时读的是那张表（PaidProducts）。再让运行时乘一遍就是给同一份数字两个家。
  // 钉住"表里的值确实等于 价格 × 基准 × 倍率"的是 ProductRewardConsistencyTest（改价时它会红）。
  PRODUCT_GROWTH_FUND_RETURN_RATIO: '零引用：#501⑤（原例外只写文档锚点 §五③，而门禁**不核对文档锚点、只核对 `#数字` 行** —— 所以这四条一直免于核对。B19 §五①a 的换算基准/倍率实际读的是 product_reward 行，global 这几个数字从没进过算价）',
  PAY_BASE_GOLD_PER_YUAN: '零引用：#501⑤（同上）',
  FIRST_CHARGE_MULTIPLIER: '零引用：#501⑤（同上）',
  MONTHLY_CARD_DAILY_MULTIPLIER: '零引用：#501⑤（同上）',
  // 2026-09-18 更正：先前那条理由（"渠道写死在支付适配器上，只起记录作用"）是**不成立的** ——
  // 全仓支付代码里没有任何 channel 字面量（`grep -rni channel game-*/src/main | grep -v ChatChannel` 零命中），
  // 所以它从来不是"记录用的副本"，而是一条**没人读的白名单来源**。
  // 现在它的消费者是 scripts/check-no-payment-bypass.sh（B15 红线 1 从表里读它来推禁用名单）。
  // 但本门的 walk() 只收 .java 与 .ts，**看不见被 shell 卡口消费的参数** —— 这是 #165 ⑦
  // "只看表不看列"之外另一条盲区，同一条道理：绿不代表有人读，红也不代表没人读。
  // 因此这条留在例外表里，理由改成"消费者是 CI 脚本"，别当"仍未接线"再加新例外。
  PAY_CHANNEL: '零引用（运行时代码）：#175（消费者是 scripts/check-no-payment-bypass.sh，本门只扫 java/ts 看不见 shell）',
  // MINOR_PAY_SINGLE_LIMIT_CENTS 与 MINOR_PAY_MONTHLY_LIMIT_CENTS 曾在这里挂着：
  // 那两条不是"设计如此"，而是**没接线**（#64）。现在下单路径
  // （PayAppService#requireWithinMinorLimit）会读它们，例外条目随之删除 ——
  // 别照着 #64 把它们加回来。
}

let paramHits = 0
const deadParams = []
for (const row of GLOBAL_ROWS) {
  const re = new RegExp('\\b' + row.id + '\\b')
  if (consumers.some((s) => !s.test && re.test(s.src))) { paramHits++; continue }
  deadParams.push(row.id)
  const note = UNREFERENCED[row.id]
  if (!note) {
    problems.push(`${row.id} 这个 global 参数没有任何代码读它，也不在例外表里。`
      + '要么接上消费方，要么在 UNREFERENCED 里写明出处（出处会被核对）')
    continue
  }
  const citeRow = note.match(/#(\d+)/)
  if (!citeRow) {
    problems.push(`${row.id} 的例外出处里没有 #数字，无法核对：${note.slice(0, 60)}`
      + ' —— 例外必须指向收口清单里真实存在的一行')
  } else if (!new RegExp('\\n\\|\\s*' + citeRow[1] + '\\s*\\|').test('\n' + checklist)) {
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
