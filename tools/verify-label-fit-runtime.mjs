#!/usr/bin/env node
/**
 * 职责：把「哪一行字正被 SHRINK 的盒子按比例压小」变成一次跑完的清单，而不是逐屏肉眼读码。
 * 依赖：node、playwright、**已启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * 用法：
 * 必填：LABELFIT_BACKEND=http://localhost:8199 —— 不给会立刻退 2 并点名这个变量：静默回落到别的后端，读数错得像产品缺陷（台账 #371/#372）；端口 LABELFIT_PORT（默认 8191，同机并发时换一个）
 *   LABELFIT_BACKEND=http://localhost:8199 LABELFIT_PORT=8191 node tools/verify-label-fit-runtime.mjs
 *   修完一批后用 `--print-baseline` 重生成 BASELINE，别手抄。
 *
 * <p><b>为什么要这么一份量具</b>：台账 #366 页内量出 `overflow = SHRINK` 的**盒高就是字形的缩放
 * 系数**——盒高低于"装得下一行"就把整行字按比例缩小（战令表头五行落地 17/13/10/10/9 对设定
 * 20/17/15/15/14，最小的只剩 64%），给高了还会反向放大。而 `grep Overflow.SHRINK` 在客户端里
 * 有二十多处，逐屏判断"这屏的文案会不会长到顶出面板"要人看，但**哪些行正在被压小**是可以机器量的。
 * 这份量具就做这一件：把全客户端每格里被压小的行点名点齐。
 *
 * <p><b>下限为什么是 max(字号+14, 30)</b>：迁移曲线实测 14~20 号字都要盒高 **30** 才等于设定字号，
 * 且这个 30 不随字号走（`lineHeight` 没设时引擎拿默认 40 参与）；更大字号按比例走。取保守界。
 *
 * <p><b>棘轮基线</b>：判据是"实测集合 == 基线集合"，两个方向都会红 —— 新出现一处压字立刻红；
 * 修好一处却忘了删基线行也红（逼着每格把成果落进这张表）。清零之后这份量具就恒绿，
 * 并继续挡住"再拿 SHRINK 猜盒高"这条回头路。
 *
 * <p><b>覆盖边界</b>：只量"这一格进得去的那些行"。**挂了读接口夹具的格**（背包、邮件 #394）连
 * "有数据之后才画出来的行"一起量 —— 没桩的那些（战报 / 社交 …）在 dev 新号上是空态，只有几颗
 * Label，那里的"零缺陷"是读空集合读来的，别当成"没问题"。每一格读到几颗 Label 由 `READ` 行如实
 * 打印，`LABEL_FLOORS` 再钉一条下限挡住"夹具静默掉线"。
 */
import { mkdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideBoard } from './lib/guide-overlay.mjs'
import { decodePng, diffRegion } from './lib/png-diff.mjs'
import { planPlateCoverage } from './lib/plate-coverage.mjs'
import { socialFixtures } from './lib/social-fixtures.mjs'
import { makeStubRead } from './lib/route-stub.mjs'
import { clickTabNode, clickRowAction } from './lib/panel-clicks.mjs'

// 必须显式给后端：静默回落到 8080 等于"打到另一台机器上读数"（同一条教训见 march 探针第 25 行）
const BACKEND = process.env.LABELFIT_BACKEND ?? (() => {
  console.error('[label-fit] 缺 LABELFIT_BACKEND：不给就退回 http://localhost:8080，'
    + '那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.LABELFIT_PORT ?? 8191)
const OUT = path.resolve(process.cwd(), 'client/build/label-fit-verify')
mkdirSync(OUT, { recursive: true })
const KEYS = ['city', 'army', 'hero', 'gacha', 'bag', 'stage', 'reports', 'quest', 'battlePass',
  'mail', 'social', 'power', 'shop', 'avatarFrames', 'targets', 'world', 'settings']

/**
 * 页签相位：这些面板"点进去才画行"，#395/#396 的桩已挂上，但默认页签量不到那几屏。
 * `node` 是页签节点名（战报是 Label 本体、社交是 `Tab_xxx` 壳），`label` 只用于日志与截图文件名。
 */
const TAB_PHASES = {
  reports: [{ node: 'TabScout', label: 'scout' }],
  social: [
    { node: 'Tab_alliance', label: 'alliance' },
    { node: 'Tab_help', label: 'help' },
    { node: 'Tab_events', label: 'events' },
    { node: 'Tab_chat', label: 'chat' },
    { node: 'Tab_rally', label: 'rally' },
  ],
}

/**
 * 已知仍在被压小的行（`面板/文本前缀(盒高<下限,字号)`）。
 *
 * <p>**现在是空的**：#367 建表时 32 条，#368 还掉内城 2 + 设置 10，#369 还掉关卡 14，
 * #370 还掉战令档位行 4 处与外观页 3 处 ⇒ 全客户端清零。这张表从此是**只增不许有**的闸门：
 * 谁再拿 SHRINK + 猜的盒高压一行字，这里就会多出一条，量具当场红。
 */
const BASELINE = new Set([
  "city/Lv1(14<27,字10)"
])

/**
 * 夹具反空转下限：某格"有数据才画行"，读口的桩一旦掉线就退回空态，那时"零缺陷"是读空集合得来的。
 * 下限**取自面板自己的行结构**（不是照抄某次实测）：`MailPanelView.createRow` 每颗 Label 都算上文本的
 * 有 4 个（Title/Detail/Status/Expiry），夹具给 8 封而面板按可视高截断 ⇒ 取"至少画满 2 行"= 8 颗；
 * `BattleReportPanelView.createRow` 每行 3 颗（Title/Detail/Outcome）⇒ 战报取 2 行 = 6 颗。
 * 空态实测邮件 2 颗 / 战报 3 颗，差 3~10 倍，所以桩掉了这一条一定红；
 * 上限故意不设：画几行随视口高度变，钉死会把量具变成"只能在这台机器上绿"。
 */
const LABEL_FLOORS = {
  mail: 2 * 4, reports: 2 * 3, social: 2 * 4, 'social/alliance-joined': 2 * 10,
  // 翻页相实测 27 颗 ⇒ 下限取 16：够挡住"翻页静默失效退回第一屏"（第一屏 34 颗会**高于**下限，
  // 所以这一相真正靠的是下面那条独有串），但也挡住"翻过去读到了半屏/空屏"
  'social/alliance-joined#p2': 2 * 8,
}

/**
 * 正向断言（比数颗数更硬）：这些串**只在夹具数据里**，空态画不出来。
 * 社交那一格尤其需要 —— 它空态本来就有 11 颗 Label（页签条 + 「创建小队」那一行），
 * 光看颗数会把"桩掉线"读成"覆盖还在"。
 */
const STUB_MARKS = {
  mail: '开服庆', reports: '野匪', social: '铁砧前哨',
  // 页签相位各有一条：桩掉线时那一屏会安静地退回空态，而"相位走到位"只证明点开了、不证明有数据。
  // 敌情那条故意取**显示名**「总兵力」而不是坐标 —— 它只有指标名命中 `METRIC_LABEL` 才画得出来，
  // 编错指标名（把裸枚举印给玩家）会被这条抓住。
  'reports/scout': '总兵力', 'social/help': '兵营 Lv12 升级中',
  'social/events': '集结邀请：西关', 'social/chat': '河谷渡口', 'social/rally': '28600',
  // 「已入盟」相的独有串：这一屏与未入盟那一屏的颗数差得开（32 对 11），但**光看颗数**仍挡不住
  // "摘要给了个别的联盟" 这类错读，所以取联盟名本身 —— 它只有 `alliance` 字段真被画出来才存在。
  'social/alliance-joined': '黑石渡口',
  // 翻页相取**只在第二页出现**的那颗：成员列表里排在第二位的「铁砧·瓦拉」——
  // 第一屏 34 颗比第二屏 27 颗还多，所以"没翻过去"这种失效**只有这条能抓**（颗数下限抓不到）。
  // 第二条「铁砧前哨」是 #422 的正向半边：裸 id 不印了还不够，得证明印上去的确实是服务端给的名字
  'social/alliance-joined#p2': ['铁砧·瓦拉', '铁砧前哨'],
}

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) {
    pass += 1
    console.log(`  PASS  ${msg}（${String(actual)}）`)
  } else {
    fail += 1
    console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
const checkTrue = (msg, actual) => check(msg, actual, true)

/** 植入用：把阈值抬高 N 像素，验证"压进导航条"这一支真的会红（0 = 正常阈值）。 */
const PLANT = Number(process.env.LABELFIT_PLANT ?? 0)

const WALK = `(() => {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === KEY_PLACEHOLDER)
  if (panel === undefined || !panel.activeInHierarchy) return null
  const out = []
  const stretched = []
  const geo = []
  const underNav = []
  /** 有文本但世界盒是 0x0 的颗数（从没被布局过）：不判红，只报数，见下面"压进导航条"那一支的注释。 */
  let unlaid = 0
  /** 这一屏画出来的每一句原文（正向断言读它：夹具的桩掉线时这里就没有夹具的字）。 */
  const texts = []
  let shrink = 0
  let seen = 0
  // 底部导航条的真实上沿（不是抄常量）：#389 那一处"第 8 行被导航条盖住"是看截图才发现的，
  // 因为量具没有这一条，而 BOTTOM_RESERVED 在各面板各写一遍。
  let navTop = null
  const findNav = (n) => {
    if (navTop !== null) return
    if (n.name === 'NavBar' && n.activeInHierarchy) {
      const bb = n.getComponent('cc.UITransform').getBoundingBoxToWorld()
      navTop = bb.y + bb.height
      return
    }
    for (const c of n.children) findNav(c)
  }
  findNav(window.cc.director.getScene())
  const walk = (n) => {
    if (n.activeInHierarchy) {
      const lb = n.getComponent('cc.Label')
      const str = lb ? (lb.string ?? '') : ''
      if (str.length > 0) {
        seen += 1
        texts.push(str)
        // **全程用世界矩形**：面板按视口缩放过，拿"世界坐标 − 本地盒宽"会混两套单位
        // （第一版就是这么把内城资源条报成互相压上的）。缩放比用盒子高之比反推。
        const ut = n.getComponent('cc.UITransform')
        const bb = ut.getBoundingBoxToWorld()
        const scale = ut.height > 0 ? bb.height / ut.height : 1
        if (navTop !== null) {
          // 0x0 的世界盒 = 这一颗从没被布局过（本轮抓到一颗路径为 Value←SocialRow←social 的
          // 「联盟资金 12800」量在 (0,0) 0x0，屏幕上并没有那行字）。它既没挡住别人也没被挡住，
          // 判红就是量具自己造的假红；但**必须报个数**，不然哪天真有一屏没布局也只会安静地少几颗。
          if (bb.width <= 0 || bb.height <= 0) {
            unlaid += 1
          } else {
            // 字的下沿按"盒中心 − 半个字号"估（盒子在 SHRINK 下是 27，比字高，拿盒子量会假红）
            const glyphBottom = bb.y + bb.height / 2 - (lb.fontSize * scale) / 2
            if (glyphBottom < navTop + ${PLANT}) {
              // 带上节点与祖先名：只印文本的话，"这一颗到底是谁"要再猜一轮
              const chain = []
              for (let p = n; p !== null && chain.length < 4; p = p.parent) chain.push(p.name)
              underNav.push(str.slice(0, 8) + '(字底' + Math.round(glyphBottom) + '<导航上沿' + Math.round(navTop)
                + '，盒' + Math.round(bb.x) + ',' + Math.round(bb.y) + ' ' + Math.round(bb.width) + 'x' + Math.round(bb.height)
                + '，路径 ' + chain.join('<') + ')')
            }
          }
        }
        // 字形横向范围：宽度按字符类别加权（汉字 1.0 em、ASCII 约 0.55 em），再乘缩放比；
        // SHRINK 保证画出来不宽于盒子，所以估宽按盒宽截断。
        let units = 0
        for (let k = 0; k < str.length; k++) units += str.charCodeAt(k) < 128 ? 0.55 : 1
        let est = units * lb.fontSize * scale
        if (lb.overflow === 2 && bb.width > 0) est = Math.min(est, bb.width)
        const align = lb.horizontalAlign
        const x0 = align === 0 ? bb.x : (align === 2 ? bb.x + bb.width - est : bb.x + (bb.width - est) / 2)
        geo.push({ text: str.slice(0, 8), x0, x1: x0 + est, y: bb.y + bb.height / 2, font: lb.fontSize * scale })
        // 2 = Label.Overflow.SHRINK；盒高用本地值（字号也是本地单位）
        if (lb.overflow === 2) {
          shrink += 1
          const t = n.getComponent('cc.UITransform')
          const h = Math.round(t.height)
          const floor = 27
          if (h < floor) {
            out.push({ text: str.slice(0, 10), h, want: lb.fontSize, floor,
              wrap: lb.enableWrapText !== false })
          }
          // 反方向也真实存在：#366 的迁移曲线上 20 号字给盒高 36 时落地 24（**被放大**）。
          // 只报"单行、且文本宽度根本用不满盒子"的那几颗 —— 多行文案给高盒子是正当需求，
          // 混进来会把正常排版报成缺陷。
          // 这里必须写「反斜杠 + n」：整段是模板字符串，直接写单反斜杠加 n 会被 Node 先转成
          // 真实换行 —— 落在字符串里就是语法错，落在注释里就是把注释截断成代码（本轮两次都踩了）。
          if (h > floor + 8 && str.indexOf('\\n') < 0 && lb.enableWrapText !== false) {
            const est = Math.round(str.length * lb.fontSize * 0.95)
            if (est < t.width) {
              stretched.push({ text: str.slice(0, 10), h, want: lb.fontSize, floor, est, boxW: Math.round(t.width) })
            }
          }
        }
      }
    }
    for (const c of n.children) walk(c)
  }
  walk(panel)
  // 相碰 = 字形横向真的压上 + 纵向字形带相交（带 = 两字号均值再留 4px）。
  const crowd = []
  for (let i = 0; i < geo.length; i++) {
    for (let j = i + 1; j < geo.length; j++) {
      const a = geo[i]
      const b = geo[j]
      if (a.x0 >= b.x1 || b.x0 >= a.x1) continue
      const band = (a.font + b.font) / 2 + 4
      const dy = Math.abs(a.y - b.y)
      if (dy >= band) continue
      crowd.push(a.text + '×' + b.text + '(Δy' + Math.round(dy) + '<带' + Math.round(band) + ')')
    }
  }
  return { seen, shrink, out, stretched, crowd, underNav, navTop, texts, unlaid }
})()`


// 页内点击助手在 tools/lib/panel-clicks.mjs（与植入正例量具共用一份，两份的失效方式不一样）

/**
 * 「翻页相」：读到「第 N/M 页」那一行 = 这一屏还有第二页从没量过
 * （#420 目视时发现的：一屏只画得下六行内容，成员行只量到第一条）。
 * 记数与判据与页签相同一条路：声明了就要走到，走不到那条覆盖门判红。
 *
 * <p>"走到"不只是"点到了按钮"：置灰那颗键的壳照样 `emit` 得动（`renderRow` 只在 `enabled` 为真时
 * 才挂 `touch-start`），点了屏幕没换就是**把第一屏量两遍**（与植入正例的 `pageToNext` 同一条洞，台账 #447）。
 */
async function pageTwoPass(page, key, tag) {
  const p2tag = `${tag}#p2`
  phasesTotal += 1
  if (!await page.evaluate(clickRowAction, '下一页')) {
    console.log(`  SKIP  ${p2tag}：那一屏有翻页行，但按文本「下一页」没点到按钮`)
    return
  }
  const r = await walkPhase(page, key)
  if (r === null) {
    console.log(`  SKIP  ${p2tag}：翻过去之后读不到那一屏`)
    return
  }
  const here = r.texts ?? []
  const parent = textsBy.get(tag) ?? []
  if (here.every((t) => parent.includes(t)) && parent.every((t) => here.includes(t))) {
    console.log(`  SKIP  ${p2tag}：点了「下一页」但屏幕没换（同一屏 ${here.length} 颗字）⇒ 不计入"走到位"`)
    return
  }
  phasesReached += 1
  labelsBy.set(p2tag, r.seen)
  textsBy.set(p2tag, r.texts ?? [])
  console.log(`  READ  ${p2tag}: Label ${r.seen} 颗，SHRINK ${r.shrink} 颗，被压小 ${r.out.length} 颗，`
    + `疑似被放大 ${(r.stretched ?? []).length} 颗，字形相碰 ${(r.crowd ?? []).length} 对，`
    + `压进导航条 ${(r.underNav ?? []).length} 颗`
    + (r.unlaid ? `，未布局 ${r.unlaid} 颗（0x0 盒，不判红）` : ''))
  for (const x of r.out) offenders.push(`${p2tag}/${x.text}(${x.h}<${x.floor},字${x.want})`)
  for (const x of r.stretched ?? []) stretched.push(`${p2tag}/${x.text}(${x.h}>${x.floor}+8,字${x.want},估宽${x.est}/盒${x.boxW})`)
  for (const x of r.crowd ?? []) crowded.push(`${p2tag}/${x}`)
  for (const x of r.underNav ?? []) underNavAll.push(`${p2tag}/${x}`)
  await platePass(page, key, p2tag)
  await page.screenshot({ path: path.join(OUT, `${p2tag.replace(/[/#]/g, '-')}.png`) })
}


const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 全客户端"字被盒子压小"清单：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `labelfit-${Date.now()}`)

const stubRead = makeStubRead(context)
/**
 * 背包夹具：新号一进这一格只有 3 颗 Label（空态），量不到"有货之后才画出来的行"。
 * 字段逐条对着 `contract/proto/bag.schema.json` 的 `BagItem.required` 给
 * （itemId/name/type/rarity/count/stackMax/sortKey/effectKind）—— 少一个就是"fixture 没镜像真实接线"，
 * 那条假红比缺覆盖更坑（记忆 [[tooling-windows-sandbox-orphan-lock]]）。
 */
const BAG_ITEMS = ['木材箱(1万)', '强化石 ×12', '限时头像框体验卡'].map((name, i) => ({
  itemId: `probe_item_${i}`, name, type: 'RESOURCE', rarity: i === 2 ? 'SR' : 'N',
  count: i + 1, stackMax: 99, sortKey: i, effectKind: 'NONE',
}))
await stubRead('**/bag/list*', {
  items: BAG_ITEMS, capacityUsed: BAG_ITEMS.length, capacityMax: 60,
})
/**
 * 邮件夹具：`MailPanelView` 每封画 Title/Detail/Status/Expiry 四颗 Label，空态整块不画
 * —— 实测（台账 #394 前）这一格只有 2 颗，"零缩字缺陷"是读空集合读出来的。
 * 字段照 `contract/proto/mail.schema.json` 的 `MailView.required` 给全：
 * mailId/kind/title/text/rewards/claimed/read/createdAt/expireAt/sourceRef。
 * 三态都要有，因为 `MailPanel.buildRow` 对它们是三条不同的文案：可领（未 claimed）、
 * 已领（claimed 且有附件）、纯通知（rewards 为空 ⇒ 状态写「纯通知」）。
 */
const DAY = 86_400_000
const HOUR = 3_600_000
const MAIL_ITEMS = [
  { mailId: 'probe_mail_0', kind: 'SYSTEM', title: '开服庆：全体统帅补给', text: '感谢你在这个王国扎根，这份补给不必客气。',
    rewards: [{ type: 'RESOURCE', id: 'wood', count: 100000, name: '木材' }, { type: 'ITEM', id: 'stamina_potion', count: 5, name: '体力药剂' }],
    claimed: false, read: false },
  { mailId: 'probe_mail_1', kind: 'OVERFLOW', title: '背包满了，先给你存着', text: '仓库容量已满，本次发奖溢出的 3 项已暂存于此，清理背包后再领即可。',
    rewards: [{ type: 'ITEM', id: 'stone', count: 12, name: '强化石' }, { type: 'HERO_FRAGMENT', id: 'hero_ye', count: 30, name: '叶将军残卷' }],
    claimed: false, read: true },
  { mailId: 'probe_mail_2', kind: 'SYSTEM', title: '赛季政策已生效', text: '「屯田令」已开始影响你的内城产出。',
    rewards: [], claimed: true, read: true },
  { mailId: 'probe_mail_3', kind: 'SYSTEM', title: '联盟互助奖励已发放，请在三天内领取以免过期', text: '你为盟军提供的支援已结算。',
    rewards: [{ type: 'PRIVILEGE', id: 'march_banner', count: 1, name: '行军旗（三日）' }],
    claimed: true, read: false },
  { mailId: 'probe_mail_4', kind: 'OVERFLOW', title: '关卡补给溢出补发', text: '第三关首通奖励中的 2 项未能入库。',
    rewards: [{ type: 'STAMINA', id: 'stamina', count: 60, name: '体力' }],
    claimed: false, read: false },
  // 再多三封，是为了把行数顶到面板的写死上限（`MailPanelView.MAX_VISIBLE_ROWS = 7`）：
  // 只画 5 封时"最后一行落在哪里"永远量不到，而 #389 那处缺陷正是第 8 行被导航条盖住。
  { mailId: 'probe_mail_5', kind: 'SYSTEM', title: '每日补给已到账', text: '今日登录补给已发放，连续七日另有阶梯奖励。',
    rewards: [{ type: 'RESOURCE', id: 'coin', count: 5000, name: '银币' }],
    claimed: false, read: false },
  { mailId: 'probe_mail_6', kind: 'SYSTEM', title: '联盟集结：你被选为支援者', text: '盟军发起了一次集结，你的部队被编入支援序列。',
    rewards: [], claimed: true, read: false },
  { mailId: 'probe_mail_7', kind: 'OVERFLOW', title: '赛季商店兑换溢出', text: '仓库已满，兑换到的 1 项暂存于此。',
    rewards: [{ type: 'ITEM', id: 'banner', count: 1, name: '王旗涂装' }],
    claimed: true, read: true },
].map((mail, i) => ({
  ...mail,
  createdAt: Date.now() - (i + 1) * 3_600_000,
  expireAt: Date.now() + (i === 3 ? 2 * DAY : 7 * DAY),
  sourceRef: `probe:${mail.kind.toLowerCase()}_${i}`,
}))
await stubRead('**/mail/list*', {
  mails: MAIL_ITEMS,
  unreadCount: MAIL_ITEMS.filter((m) => !m.read).length,
  claimedCount: MAIL_ITEMS.filter((m) => m.claimed || m.rewards.length === 0).length,
})
/**
 * 战报夹具（两块页签一起，`AppRoot.refresh('reports')` 同一次刷新发两个读口）。
 * 字段照 `contract/proto/battle.schema.json` 的 `BattleReportBrief.required` 与
 * `world.schema.json` 的 `ScoutReportView.required` 给全，`serverNow` 也在 data 里 —— 契约写了必填。
 * 四种 `BattleType` 都来一条：`TYPE_LABEL` 缺项时会直接把枚举名印给玩家（`?? r.battleType`）。
 * 敌情那三条各覆盖一条分支：有效 / `expired=true`（不藏起来）/ `metrics` 为空（「没有观测项」）。
 */
const BATTLE_REPORTS = [
  { battleType: 'PVE', opponentName: '野匪·断粮队', won: true, totalRounds: 6, attackerLoss: 120, defenderLoss: 980 },
  { battleType: 'PVP_SOLO', opponentName: '铁砧营·玛尔达', won: false, totalRounds: 11, attackerLoss: 2450, defenderLoss: 1870 },
  { battleType: 'PVP_RALLY', opponentName: '灰隼同盟主力', won: true, totalRounds: 18, attackerLoss: 9600, defenderLoss: 15200 },
  { battleType: 'SIEGE', opponentName: '西关守军', won: false, totalRounds: 24, attackerLoss: 12800, defenderLoss: 4300 },
  { battleType: 'PVE', opponentName: '屯粮仓护卫', won: true, totalRounds: 4, attackerLoss: 0, defenderLoss: 640 },
  { battleType: 'PVP_SOLO', opponentName: '流浪骑士雷恩', won: true, totalRounds: 9, attackerLoss: 780, defenderLoss: 1240 },
  // 再多三条，是为了让"画不下"这一支真的走到：6 场时这个视口装得下，截断文案与导航条余量都量不到。
  { battleType: 'PVE', opponentName: '山道劫掠者', won: true, totalRounds: 3, attackerLoss: 40, defenderLoss: 420 },
  { battleType: 'SIEGE', opponentName: '东关守军', won: true, totalRounds: 27, attackerLoss: 9800, defenderLoss: 6100 },
  { battleType: 'PVP_RALLY', opponentName: '铁砧营集结', won: false, totalRounds: 15, attackerLoss: 5300, defenderLoss: 2900 },
].map((r, i) => ({
  reportId: `probe_report_${i}`, winner: r.won ? 'ATTACKER' : 'DEFENDER',
  opponentId: `probe_opp_${i}`, createdAt: Date.now() - (i + 1) * 1_800_000,
  expiresAt: Date.now() + (6 - i) * HOUR, ...r,
}))
await stubRead('**/battle/reports*', { reports: BATTLE_REPORTS, serverNow: Date.now() })
const SCOUT_REPORTS = [
  // 指标名必须是服务端真发的那一套（`totalUnits` / `infantry` / …，见 `core/scout/ScoutReport.java`）：
  // 客户端 `METRIC_LABEL` 查不到就 `?? metric.name` 把裸枚举名印给玩家 —— 上一版编了
  // `DEFENDER_TROOPS` 这种不存在的名字，于是截图里是一行黑话，而所有判据全绿（台账 #397）。
  { target: { x: 118, y: 64 }, targetLevel: 21, expired: false,
    metrics: [{ name: 'totalUnits', value: 12400 }, { name: 'infantry', value: 9000 }, { name: 'cavalry', value: 3400 }] },
  { target: { x: 96, y: 141 }, targetLevel: 7, expired: true,
    metrics: [{ name: 'power', value: 860 }] },
  { target: { x: 203, y: 88 }, targetLevel: 30, expired: false, metrics: [] },
].map((s, i) => ({
  reportId: `probe_scout_${i}`, targetId: `probe_target_${i}`,
  createdAt: Date.now() - (i + 1) * 900_000, expiresAt: Date.now() + (i + 2) * HOUR,
  remainingMs: (i + 2) * HOUR, errorFixed: 800, seed: 1000 + i, serverNow: Date.now(), ...s,
}))
await stubRead('**/world/reports*', { reports: SCOUT_REPORTS, serverNow: Date.now() })
/**
 * 社交夹具在 `tools/lib/social-fixtures.mjs`（与植入正例量具共用一份，理由写在那份首行）。
 * `state.joinedAlliance` 由下面「已入盟」那一相翻开关。
 */
const social = socialFixtures()
await social.install(stubRead)
/**
 * 「屏上不出现裸 id」这一维（台账 #422）。**字段名从契约现取**，不写死字符串清单 ——
 * 照 `scripts/check-player-copy-jargon.js` 那份"词表从生成物现取枚举名"的路子：契约里再多一个
 * `xxxId`，这一门自动跟着变宽，不需要有人记得加词。
 *
 * <p>为什么那份静态门挡不住这里：屏幕上的字是 `` `分队 ${member.squadId}` `` 拼出来的，
 * 插值之外的部分一个工程术语都没有，扫字面量的门看不见它（#421 翻页相第一次读到第二屏才现形，
 * 几何判据全绿、只有截图看得见）。只有**跑起来的屏幕上**才量得到。
 */
const RAW_ID_PHASES = {
  'social/alliance-joined': 'AllianceMember',
  'social/alliance-joined#p2': 'AllianceMember',
}
/** 每一相那批类型对应的夹具行：id 的**取值**只从这些行里拿，不手写。 */
const RAW_ID_ROWS = {
  AllianceMember: social.members,
}

/**
 * 契约某类型下算"内部 id"的键名：`id` 本身，或以大写 `Id` 收尾的键。
 * 取不到该类型时返回空数组 —— 下面那条"一条取值都没取到"的反空转判据会当场红，不会静默恒真。
 */
function contractIdFields(def) {
  const file = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..',
    'contract/proto/social.schema.json')
  const props = JSON.parse(readFileSync(file, 'utf8'))?.$defs?.[def]?.properties
  return props === undefined ? [] : Object.keys(props).filter((k) => k === 'id' || /[A-Za-z]Id$/.test(k))
}

const offenders = []
const stretched = []
const crowded = []
const underNavAll = []
/** 像素法报出来的"字被后画的底板盖住"（>0 的都列出来，便于逐张目视）。 */
const covered = []
/** 像素法真的量过几相（每一相都要过，漏接会静默不量） */
let plateAttempts = 0
/** 其中达到判红下限的那些 —— 这一维**判红**，标定见台账 #399/#400。 */
const coveredRed = []
/**
 * 判红下限取 8 像元：植入 #389 那处老缺陷时最小的一条是 **181** 像元，修好后同一批底板是 **0** ——
 * 两个量级之间取 8，隔开的是抗锯齿噪声，不是"把阈值调到全绿"（那条纪律见 #384/#392）。
 */
const PLATE_MIN_RED = 8
let navFound = 0
const reached = []
/** 每一格读到的 Label 颗数：夹具反空转判据读它（见 `LABEL_FLOORS`）。 */
const labelsBy = new Map()
/** 每一格画出来的原文：正向断言读它（见 `STUB_MARKS`）。 */
const textsBy = new Map()
let totalLabels = 0
let totalShrink = 0
/** 页签相位：走到位了几个 / 一共声明了几个（漏一个就是"那一屏从没量过"，见末尾判据）。 */
let phasesReached = 0
let phasesTotal = 0

/** 把一趟读数并进另一趟：颗数取最大值、清单取并集（同一份产物连跑读数会跳，见 #378 那条教训）。 */
function mergeRead(target, src) {
  target.seen = Math.max(target.seen, src.seen)
  target.shrink = Math.max(target.shrink, src.shrink)
  for (const list of ['out', 'stretched']) {
    const have = new Set(target[list].map((x) => x.text + '@' + x.h))
    for (const x of src[list] ?? []) {
      if (!have.has(x.text + '@' + x.h)) target[list].push(x)
    }
  }
  target.crowd = target.crowd ?? []
  target.underNav = target.underNav ?? []
  target.texts = [...new Set([...(target.texts ?? []), ...(src.texts ?? [])])]
  for (const x of src.crowd ?? []) {
    if (!target.crowd.includes(x)) target.crowd.push(x)
  }
  for (const x of src.underNav ?? []) {
    if (!target.underNav.includes(x)) target.underNav.push(x)
  }
  if (typeof src.navTop === 'number') target.navTop = src.navTop
}

/**
 * 走完"计数稳定 + 再三轮取并集"这一趟，返回这一相的读数（没画出来返回 null）。
 * 提成函数是因为**页签相位要用同一套收敛**：切过去同样要等渲染，读数同样会跳。
 */
async function walkPhase(page, key) {
  let read = null
  // 不能"见到 >0 就停"：空态本来就有几颗 Label，那样永远读不到"有数据之后才画出来的行"。
  // 改成**计数稳定**才收（连续两次一样），最多 24 次 ×250ms。
  let prevSeen = -1
  for (let i = 0; i < 24; i += 1) {
    await page.waitForTimeout(250)
    read = await page.evaluate(WALK.replace('KEY_PLACEHOLDER', JSON.stringify(key)))
    if (read !== null && read.seen > 0 && read.seen === prevSeen) break
    prevSeen = read?.seen ?? -1
  }
  if (read === null) return null
  // 计数稳定 ≠ 覆盖完整：实测同一份产物连跑两遍，SHRINK 总数会 91 / 71 跳（列表虚拟化 + 渲染时机），
  // 那意味着"基线"不可复现、门会随机红。所以再补三轮，**取并集与最大值**让覆盖单调收敛。
  for (let round = 0; round < 3; round += 1) {
    await page.waitForTimeout(400)
    // 板是异步挂上来的：每轮都藏一次，最后一轮之后才截图，截图里才可能没有它
    await page.evaluate(hideGuideBoard)
    const again = await page.evaluate(WALK.replace('KEY_PLACEHOLDER', JSON.stringify(key)))
    if (again !== null) mergeRead(read, again)
  }
  return read
}

/**
 * 像素法"底板压字"一趟：一张全图基线 + 每块候选底板一张（**按底板分组，不是一颗 Label 一张**），
 * 差在内存里裁字形带算。`tag` 让默认相与页签相位共用同一份实现（#409 把这一维推到那六屏上）。
 * 调用时机必须在该相已经渲染完、且在留档截图之前 —— 它会逐块禁用再还原 `Graphics`，
 * 中途截的图不是玩家看到的样子。
 */
async function platePass(page, key, tag) {
  if (process.env.LABELFIT_PLATES === '0') return
  const plan = await page.evaluate(planPlateCoverage, key)
  if (plan === null) return
  plateAttempts += 1
  if (plan.plates.length === 0) {
    // 0 候选也要打一行：不然"这一相根本没有后画底板"和"这一相没被像素法量过"在输出里长得一样
    console.log(`  PLATE ${tag}: 候选底板 0 块、字形带 ${plan.bands.length} 条，报出 0 处`)
    return
  }
  const base = decodePng(await page.screenshot())
  let hits = 0
  for (const plate of plan.plates) {
    await page.evaluate((h) => {
      window.__plateNodes[h].getComponent('cc.Graphics').enabled = false
    }, plate.handle)
    await page.waitForTimeout(120)
    const after = decodePng(await page.screenshot())
    await page.evaluate((h) => {
      window.__plateNodes[h].getComponent('cc.Graphics').enabled = true
    }, plate.handle)
    await page.waitForTimeout(80)
    for (const bi of plate.bands) {
      const b = plan.bands[bi]
      const d = diffRegion(base, after, b.rect, 24)
      if (d.changed > 0) {
        const line = `${tag}/${b.text}←「${plate.name}」变了 ${d.changed}/${d.total} 像元`
        covered.push(line)
        hits += 1
        if (d.changed >= PLATE_MIN_RED) coveredRed.push(line)
      }
    }
  }
  console.log(`  PLATE ${tag}: 候选底板 ${plan.plates.length} 块、字形带 ${plan.bands.length} 条，报出 ${hits} 处`)
}

for (const key of KEYS) {
  const page = await context.newPage()
  const url = new URL(`${preview.origin}/`)
  url.searchParams.set('panel', key)
  await page.goto(url.toString(), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  // 新手引导那块板会盖住被引导的那一屏（内城顶部资源条就是被它挡了三轮，#388 的 6 条候选一条都没目视过）。
  await page.evaluate(hideGuideBoard)
  const read = await walkPhase(page, key)
  if (read === null) {
    console.log(`  SKIP  ${key}：这一格没画出来（深链没生效或面板名不是节点名）`)
  } else {
    reached.push(key)
    labelsBy.set(key, read.seen)
    textsBy.set(key, read.texts ?? [])
    totalLabels += read.seen
    totalShrink += read.shrink
    for (const x of read.out) offenders.push(`${key}/${x.text}(${x.h}<${x.floor},字${x.want})`)
    for (const x of read.stretched ?? []) stretched.push(`${key}/${x.text}(${x.h}>${x.floor}+8,字${x.want},估宽${x.est}/盒${x.boxW})`)
    for (const x of read.crowd ?? []) crowded.push(`${key}/${x}`)
    for (const x of read.underNav ?? []) underNavAll.push(`${key}/${x}`)
    console.log(`  READ  ${key}: Label ${read.seen} 颗，SHRINK ${read.shrink} 颗，被压小 ${read.out.length} 颗，`
      + `疑似被放大 ${(read.stretched ?? []).length} 颗，字形相碰 ${(read.crowd ?? []).length} 对，`
      + `压进导航条 ${(read.underNav ?? []).length} 颗`
      + (read.unlaid ? `，未布局 ${read.unlaid} 颗（0x0 盒，不判红）` : ''))
  }
  // 像素法：底板压字（默认相一趟；页签相位各一趟，见下面 phase 循环）
  await platePass(page, key, key)
  // 每格落一张图：这一族改的是"盒高 + 对齐"，判据全绿也可能把字挪位，必须目视
  await page.screenshot({ path: path.join(OUT, `${key}.png`) })
  // 页签相位：切过去再量一趟，读数**并进本格**（同一块面板的另一相），同时单独打一行便于比对涨幅
  for (const phase of TAB_PHASES[key] ?? []) {
    phasesTotal += 1
    const tag = `${key}/${phase.label}`
    const shot = path.join(OUT, `${key}-${phase.label}.png`)
    if (read === null) {
      console.log(`  SKIP  ${tag}：面板本身没画出来，页签无从谈起`)
      continue
    }
    if (!await page.evaluate(clickTabNode, phase.node)) {
      console.log(`  SKIP  ${tag}：没找到页签节点「${phase.node}」（改名了？）`)
      continue
    }
    const pr = await walkPhase(page, key)
    if (pr === null) {
      console.log(`  SKIP  ${tag}：切过去之后读不到那一屏`)
      continue
    }
    phasesReached += 1
    labelsBy.set(tag, pr.seen)
    textsBy.set(tag, pr.texts ?? [])
    console.log(`  READ  ${tag}: Label ${pr.seen} 颗，SHRINK ${pr.shrink} 颗，被压小 ${pr.out.length} 颗，`
      + `疑似被放大 ${(pr.stretched ?? []).length} 颗，字形相碰 ${(pr.crowd ?? []).length} 对，`
      + `压进导航条 ${(pr.underNav ?? []).length} 颗`
      + (pr.unlaid ? `，未布局 ${pr.unlaid} 颗（0x0 盒，不判红）` : ''))
    for (const x of pr.out) offenders.push(`${tag}/${x.text}(${x.h}<${x.floor},字${x.want})`)
    for (const x of pr.stretched ?? []) stretched.push(`${tag}/${x.text}(${x.h}>${x.floor}+8,字${x.want},估宽${x.est}/盒${x.boxW})`)
    for (const x of pr.crowd ?? []) crowded.push(`${tag}/${x}`)
    for (const x of pr.underNav ?? []) underNavAll.push(`${tag}/${x}`)
    mergeRead(read, pr)
    // 这一维也推到页签相位：那六屏的桩早就挂上了，之前只有默认相被像素法看过（#409）
    await platePass(page, key, tag)
    await page.screenshot({ path: shot })
  }
  // 「已入盟」那一相要**另开一张页**：摘要在面板打开那一刻就落进 `attach()` 了，
  // 中途翻旗标再点页签不会重取一次 —— 同页翻只会读到上一相的残影（读数没变、还以为是空态）。
  if (key === 'social' && read !== null) {
    const vtag = 'social/alliance-joined'
    phasesTotal += 1
    social.state.joinedAlliance = true
    const vpage = await context.newPage()
    const vurl = new URL(`${preview.origin}/`)
    vurl.searchParams.set('panel', key)
    await vpage.goto(vurl.toString(), { waitUntil: 'networkidle' })
    await vpage.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await vpage.evaluate(hideGuideBoard)
    // 先按主循环的顺序走一趟（`walkPhase` 才是"面板画出来了"的那个等待）：
    // 直接点页签会在面板还没建出来时找不到节点（第一次实跑就是这么 SKIP 的）。
    if (await walkPhase(vpage, key) === null) {
      console.log(`  SKIP  ${vtag}：这一相连面板都没画出来`)
    } else if (!await vpage.evaluate(clickTabNode, 'Tab_alliance')) {
      console.log(`  SKIP  ${vtag}：没找到页签节点「Tab_alliance」（改名了？）`)
    } else {
      const vr = await walkPhase(vpage, key)
      if (vr === null) {
        console.log(`  SKIP  ${vtag}：切过去之后读不到那一屏`)
      } else {
        phasesReached += 1
        labelsBy.set(vtag, vr.seen)
        textsBy.set(vtag, vr.texts ?? [])
        console.log(`  READ  ${vtag}: Label ${vr.seen} 颗，SHRINK ${vr.shrink} 颗，被压小 ${vr.out.length} 颗，`
          + `疑似被放大 ${(vr.stretched ?? []).length} 颗，字形相碰 ${(vr.crowd ?? []).length} 对，`
          + `压进导航条 ${(vr.underNav ?? []).length} 颗`
          + (vr.unlaid ? `，未布局 ${vr.unlaid} 颗（0x0 盒，不判红）` : ''))
        for (const x of vr.out) offenders.push(`${vtag}/${x.text}(${x.h}<${x.floor},字${x.want})`)
        for (const x of vr.stretched ?? []) stretched.push(`${vtag}/${x.text}(${x.h}>${x.floor}+8,字${x.want},估宽${x.est}/盒${x.boxW})`)
        for (const x of vr.crowd ?? []) crowded.push(`${vtag}/${x}`)
        for (const x of vr.underNav ?? []) underNavAll.push(`${vtag}/${x}`)
        mergeRead(read, vr)
        await platePass(vpage, key, vtag)
        await vpage.screenshot({ path: path.join(OUT, 'social-alliance-joined.png') })
        // 这一相是 `social` 那一格**最后**做的动作，所以可以放心就地翻页：面板的 `page`
        // "换页签不重置"，在别的相上翻会把后面的相留在第二页，读数就变成另一屏了。
        await pageTwoPass(vpage, key, vtag)
      }
    }
    await vpage.close()
    social.state.joinedAlliance = false
  }
  if (typeof read?.navTop === 'number') navFound += 1
  await page.close()
}
console.log(`  截图目录：${OUT}`)
// 自检放在**遍历之后**：改写计数是按"服务出去的字节"累加的，goto 刚返回就断言会在
// 首个资源还没记完时误抛（2026-09-21 实测：连跑到第 4 次抛"一个字都没换到"，红得莫名其妙）。
preview.assertRewritten()

console.log('\n=== 被盒子压小的行 ===')
if (offenders.length === 0) console.log('  （无）')
for (const line of offenders) console.log('  ' + line)

/**
 * `--calibrate`：把"落地尺寸 == 设定尺寸"的那个盒高**逐字号量出来**，用来检验 `oneLineFloorHeight` 的外推边界。
 *
 * <p>下限 `max(字号+14, 30)` 是台账 #366 在 14~20 号字上量出来的，而基线里有 9/10 号的小字（内城建筑角标）——
 * 小字号上照 30 去钉可能反向**放大**，那就是判据自己造的假红。所以先量曲线再决定要不要动那两行。
 * 做法：每个字号挑一颗 SHRINK Label，从 `字号+2` 逐档抬盒高（每档等两帧），报出落地尺寸；
 * 落地尺寸第一次等于设定值的那一档就是它的"自然一行高"（#366 的曲线上没有平台段，只有交点）。
 */
if (process.argv.includes("--calibrate")) {
  const CAL = `(async () => {
    const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
    const panel = game?.children.find((c) => c.name === KEY_PLACEHOLDER)
    if (panel === undefined) return null
    const twoFrames = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
    const bySize = new Map()
    const collect = (n) => {
      const lb = n.getComponent('cc.Label')
      if (lb !== null && lb !== undefined && (lb.string ?? '').length > 0 && lb.overflow === 2
          && n.activeInHierarchy && !bySize.has(lb.fontSize)) bySize.set(lb.fontSize, n)
      for (const c of n.children) collect(c)
    }
    collect(panel)
    const out = []
    for (const [size, node] of [...bySize.entries()].sort((a, b) => a[0] - b[0])) {
      const t = node.getComponent('cc.UITransform')
      const lb = node.getComponent('cc.Label')
      const saved = { w: t.width, h: t.height }
      const curve = []
      let cross = -1
      // 起点不能是「字号+2」：交点实测是个与字号无关的常数（约 27），大字号从 28 起扫会直接跳过它
      // （26 号字上一轮就返回 -1）。步长必须是 1：落地尺寸是整数，步长 2 会跳过交点。
      for (let h = 20; h <= size + 40; h += 1) {
        t.setContentSize(saved.w, h)
        await twoFrames()
        const a = lb.actualFontSize ?? -1
        curve.push(h + ":" + a)
        // 交点 = 落地尺寸回到**设定字号**的那一档（不是 1.5×：那是 NONE 模式下的光栅尺寸口径）
        if (cross < 0 && Math.abs(a - size) < 0.51) cross = h
      }
      t.setContentSize(saved.w, saved.h)
      await twoFrames()
      out.push({ size, cross, text: (lb.string ?? "").slice(0, 6), curve: curve.join(" ") })
    }
    return out
  })()`
  for (const key of KEYS) {
    const page = await context.newPage()
    const url = new URL(`${preview.origin}/`)
    url.searchParams.set('panel', key)
    await page.goto(url.toString(), { waitUntil: 'networkidle' })
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 60_000 })
    await page.waitForTimeout(1500)
    const rows = await page.evaluate(CAL.replace("KEY_PLACEHOLDER", JSON.stringify(key)))
    for (const r of rows ?? []) {
      console.log(`  CAL ${key}/${r.text} 字号${r.size} 交点盒高=${r.cross}  |  ${r.curve}`)
    }
    await page.close()
  }
  await browser.close()
  await preview.close()
  process.exit(0)
}
// 修完一批之后用 `--print-baseline` 重生成上面那张表，别手抄
if (stretched.length > 0) {
  console.log('\n=== 疑似被盒子放大的行（只报不改，等逐屏判断：见台账 #377） ===')
  for (const line of stretched) console.log('  ' + line)
}
if (crowded.length > 0) {
  /**
   * 这一维**故意只报不判红**（标定过，见台账 #392）：把带里的 +4 余量去掉、改用"字形墨迹"阈值
   * 0.9×(f1+f2)/2，今天这 6 条候选都不再命中（city 16 vs 10.8、gacha 19 vs 13.5），
   * 但 #389 那处**真**缺陷（quest 表头 Δy21）同样也不会命中（21 vs 17.1）——
   * 它当时是"字被页签的底板压住"，不是字压字。文本 vs 图形底板是另一种量法，没有它就没有能判红的规则。
   */
  console.log('\n=== 字形相碰的对（只报不判红，理由见本段注释与台账 #392） ===')
  for (const line of [...new Set(crowded)]) console.log('  ' + line)
}
if (underNavAll.length > 0) {
  console.log('\n=== 字压进底部导航条的（这一条判红；列出来是为了定位是哪一屏） ===')
  for (const line of [...new Set(underNavAll)]) console.log('  ' + line)
}
if (covered.length > 0) {
  /**
   * 这一维**判红**（标定与"植入会红"的实测见台账 #399/#400）：几何法在"已修好"和"有缺陷"两种情况下
   * 都报相交（#393 的假阳性来源），像素法报出的是 192/822/181 对 0/0/0。
   * 全部 >0 的都列出来（不到下限的当噪声看），判红只看 `>= PLATE_MIN_RED` 的那些。
   */
  console.log('\n=== 字被后画的图形底板盖住的（像素法；达下限的判红） ===')
  for (const line of [...new Set(covered)]) console.log('  ' + line)
}
if (process.argv.includes('--print-baseline')) {
  console.log('\n// --- BASELINE 片段（贴进源文件替换 BASELINE 的构造）---')
  for (const x of [...new Set(offenders)].sort()) console.log(`  ${JSON.stringify(x)},`)
  console.log(`// 共 ${new Set(offenders).size} 条（去重后）`)
  await browser.close()
  await preview.close()
  process.exit(0)
}

// 反空转前置：每格都要走到、且真的读到过 Label —— 否则"零缺陷"是在读空集合
check('每一格都走到位（漏格会让这份清单假绿）', reached.length, KEYS.length)
checkTrue('这些格里确实读到过 Label（读到 0 颗说明遍历写错了）', totalLabels > 100)
// 「有夹具的格必须真的画出数据行」：`--print-baseline` 与 `--calibrate` 提前退出，够不到这里，
// 所以"桩掉线了"只能由这一条挡（否则它会安静地退回空态、报一片绿）。
for (const [panel, floor] of Object.entries(LABEL_FLOORS)) {
  check(`${panel} 的读接口夹具还画出 ${floor} 颗以上 Label（掉线会退回空态）`,
    (labelsBy.get(panel) ?? 0) >= floor, true)
}
// 颗数只证明"画了点什么"，这一条证明"画的是夹具那份数据"（社交页空态本来就有 11 颗）。
// 一相可以给多条独有串（值写成数组），少一条就红 —— 见 `social/alliance-joined#p2` 那两条的理由
for (const [panel, want] of Object.entries(STUB_MARKS)) {
  for (const mark of Array.isArray(want) ? want : [want]) {
    check(`${panel} 的夹具文字真的画到了屏幕上（找「${mark}」）`,
      (textsBy.get(panel) ?? []).some((t) => t.includes(mark)), true)
  }
}
// 屏上不出现裸 id（台账 #422）：显示名一律服务端下发，客户端只有类型、没有表数据。
// 实际取值列在 FAIL 里 —— 只报"有没有"的话，红了还得再跑一趟才知道是哪一颗。
for (const [phase, def] of Object.entries(RAW_ID_PHASES)) {
  const leaked = [...new Set(contractIdFields(def)
    .flatMap((f) => RAW_ID_ROWS[def].map((row) => row[f]).filter((v) => typeof v === 'string')))]
  // 反空转前置：取值取空说明契约改了键名或夹具不再有该字段，这一门就成了恒真 —— 不许交绿
  // （同静态门那条「一个枚举名都没取到 —— 判据失效，不算通过」）
  check(`${phase} 的裸 id 门真的从契约取到 ${def} 的 id 取值（取空=门失效）`, leaked.length > 0, true)
  const shown = leaked.filter((v) => (textsBy.get(phase) ?? []).some((t) => t.includes(v)))
  check(`${phase} 屏上不出现 ${def} 的裸 id（显示名要服务端下发）`, JSON.stringify(shown), '[]')
}
// 恒真的"totalShrink >= 0"不写：清单不能靠一个不会失败的条件交差。
// 这条要能失败：基线里有点名行、却一颗 SHRINK 都没量到 ⇒ 遍历或枚举值变了，读的是空集合。
checkTrue('基线不是在读空集合（有基线行就必须量到 SHRINK 行）',
  totalShrink > 0 || BASELINE.size === 0)
// "没有字压进导航条"只有在**真的量到导航条**时才算结论，否则这一支是空跑的。
check('每一格都读到导航条上沿（读不到就说明"压进导航条"这一支在空跑）', navFound, reached.length)
// 页签相位与"漏格"同一条理由：声明了 6 个相位却只走到 4 个，剩下那两屏从没量过，
// 而输出里只有 SKIP 一行 —— 不判红就会一路绿到下一次有人改名。
check('声明的页签相位全部走到位（改名或没画出来会红）', phasesReached, phasesTotal)
// 这一条是 #389 那一处（第 8 行被导航条盖住，量具当时全绿）的通用版。
// 阈值就取 0：实测最紧的一屏（战力）字底离导航上沿还有 18px，不会因抖动误红。
check('没有一屏把字画进底部导航条（写死行数那一族的通用兜底）',
  new Set(underNavAll).size, 0)
// 像素法这一维的判红。它**有前置条件**：`LABELFIT_PLATES=0` 会整段跳过，那时这一条是空跑的恒真 ——
// 所以连"跑没跑"一起断言（同一份 covered 数组，跳过时长度必为 0）。
if (process.env.LABELFIT_PLATES !== '0') {
  // 覆盖判据：默认相 17 格 + 页签相位若干，每一相都要真过一遍像素法。
  // 没有这条，将来谁把 phase 循环里那句 platePass 删掉，输出只是少几行，判据照绿。
  check('每一相都过了像素法（默认相 + 页签相位；漏接会静默不量）',
    plateAttempts, reached.length + phasesReached)
  check('没有一处字被后画的图形底板盖住（像素法，达 ' + PLATE_MIN_RED + ' 像元判红）',
    new Set(coveredRed).size, 0)
} else {
  // 显式跳过时不判红，但要把"这一维没量"喊出来，别让它静默变成恒真
  console.log('  ⚠ 像素法这一维被 LABELFIT_PLATES=0 跳过了：本轮**没有**验过"底板压字"')
}
const measured = new Set(offenders)
check('被压小的行**恰好**等于基线（新增会红；修好没删基线行也会红）',
  JSON.stringify([...measured].sort()) === JSON.stringify([...BASELINE].sort()), true)

console.log(`\n=== 通过 ${pass} 项，失败 ${fail} 项；SHRINK 行共 ${totalShrink} 颗（默认相那一趟的和；页签相位看各自 READ 行）===`)
await browser.close()
await preview.close()
process.exit(fail === 0 ? 0 : 1)
