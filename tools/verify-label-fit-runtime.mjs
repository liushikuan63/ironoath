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
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs'
import { startPreviewServer } from './lib/preview-server.mjs'
import { decodePng, diffRegion } from './lib/png-diff.mjs'

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
const LABEL_FLOORS = { mail: 2 * 4, reports: 2 * 3, social: 2 * 4 }

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
          // 字的下沿按"盒中心 − 半个字号"估（盒子在 SHRINK 下是 27，比字高，拿盒子量会假红）
          const glyphBottom = bb.y + bb.height / 2 - (lb.fontSize * scale) / 2
          if (glyphBottom < navTop + ${PLANT}) {
            underNav.push(str.slice(0, 8) + '(字底' + Math.round(glyphBottom) + '<导航上沿' + Math.round(navTop) + ')')
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
  return { seen, shrink, out, stretched, crowd, underNav, navTop, texts }
})()`

/**
 * 把新手引导那块板藏起来，好量它背后那一屏自己的排版。
 *
 * <p>为什么不是"点掉它"：`GuideNext` 的 `touch-start` 会发一次推进引导的写请求，
 * 而 dev 新号那一步的前置没满足 ⇒ 写失败，屏幕上换成"网络不稳定，正在重试（第 1 次）"，
 * 板子还在、还多了一条重试提示（实测过）。引导是玩家可关的**覆盖层**，藏掉它不改被量那一屏的几何。
 */
function hideGuideBoard() {
  const scene = window.cc.director.getScene()
  const found = []
  const find = (n) => {
    if (!n.activeInHierarchy) return
    if (n.name === 'GuideNext') found.push(n)
    for (const c of n.children) find(c)
  }
  find(scene)
  if (found.length === 0) return false
  let top = found[0]
  // 爬到 Game 的直接子节点（引导自己的那一层），别把 Game 整块关掉
  while (top.parent !== null && top.parent.name !== 'Game' && top.parent.name !== 'Canvas') {
    top = top.parent
  }
  if (top.name === 'Game' || top.name === 'Canvas') return false
  top.active = false
  return true
}

/**
 * 切到一个页签。两个面板的处理器签名都是 `(_event: EventTouch) => ...`（不读那个参数），
 * 所以按节点名找到壳直接 `emit` 就能换页 —— 换页签是纯客户端动作，不发写请求
 * （与"覆盖层要点掉就别点"那条相反：#391 那块一点就发引导推进，这一族点了什么都不发）。
 *
 * <p>⚠ 这**绕过了命中测试**：量的是"切过去之后那一屏的排版"，不是"页签点不点得动"。
 * 后者要真鼠标坐标 + 画布缩放换算，是另一格的活，别把这里的绿读成"页签一定能按"。
 */
function clickTabNode(name) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  let found = null
  const find = (n) => {
    if (found !== null) return
    if (n.name === name && n.activeInHierarchy) {
      found = n
      return
    }
    for (const c of n.children) find(c)
  }
  for (const panel of game?.children ?? []) find(panel)
  if (found === null) return false
  found.emit('touch-start', null)
  return true
}

/**
 * 像素法"底板压字"的规划：算出每颗 Label 的**字形带**（屏幕像素矩形），以及
 * "DFS 次序排在它之后、且世界盒与字形带相交"的 `Graphics` 底板清单。
 *
 * <p>为什么必须按次序筛：卡片背景本来就压在字**底下**，藏掉它当然也会变像素 —— 只有**后画**的那层才叫"盖住"。
 * <p>为什么不能按"子树里有文字就排除"筛纯底板：页签的底板就长在带页签文字的节点上，那样筛会把真缺陷亲手滤掉
 * （台账 #399 第一轮就是这么假阴性的）。藏的方式是**只禁 `Graphics` 组件**，节点与它自己的文字照常画。
 * <p>标定过的结论：植入 #389 那处老缺陷时报 192/822/181 像元变化，修好后同一批底板几何仍相交但变化 0/0/0
 * ⇒ 判据不需要阈值。助手挂在 window 上（`evaluate` 只能带函数源码，节点引用传不出来）。
 */
function planPlateCoverage(panelKey) {
  const game = window.cc.director.getScene().getChildByName('Canvas')?.getChildByName('Game')
  const panel = game?.children.find((c) => c.name === panelKey)
  if (!panel) return null
  const order = []
  const walkOrder = (n) => {
    order.push(n)
    for (const c of n.children) walkOrder(c)
  }
  walkOrder(panel)
  const vis = window.cc.view.getVisibleSize()
  const can = window.cc.view.getCanvasSize()
  const scale = can.width / vis.width
  const bands = []
  const plates = []
  order.forEach((n, i) => {
    const lb = n.getComponent('cc.Label')
    const str = lb ? (lb.string ?? '') : ''
    if (str.length === 0 || !n.activeInHierarchy) return
    const bb = n.getComponent('cc.UITransform').getBoundingBoxToWorld()
    let units = 0
    for (const ch of str) units += ch.charCodeAt(0) < 128 ? 0.55 : 1
    const est = Math.min(units * lb.fontSize, bb.width)
    const align = lb.horizontalAlign
    const gx0 = align === 0 ? bb.x : (align === 2 ? bb.x + bb.width - est : bb.x + (bb.width - est) / 2)
    const band = {
      x: Math.round(gx0 * scale),
      y: Math.round((vis.height - (bb.y + bb.height / 2 + lb.fontSize / 2)) * scale),
      w: Math.max(1, Math.round(est * scale)),
      h: Math.max(1, Math.round(lb.fontSize * scale)),
    }
    const bi = bands.length
    bands.push({ text: str.slice(0, 10), rect: band })
    order.forEach((m, j) => {
      if (j <= i) return
      const g = m.getComponent('cc.Graphics')
      if (g === null || g === undefined || !m.activeInHierarchy || !g.enabled) return
      const pr = m.getComponent('cc.UITransform').getBoundingBoxToWorld()
      const gy0 = bb.y + bb.height / 2 - lb.fontSize / 2
      if (pr.x >= gx0 + est || gx0 >= pr.x + pr.width) return
      if (pr.y >= gy0 + lb.fontSize || gy0 >= pr.y + pr.height) return
      let slot = plates.find((p) => p.handle === j)
      if (slot === undefined) {
        slot = { handle: j, name: m.name, bands: [] }
        plates.push(slot)
      }
      slot.bands.push(bi)
    })
  })
  window.__plateNodes = order
  return { bands, plates }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 全客户端"字被盒子压小"清单：产物经 ${preview.origin}，后端 ${BACKEND} ===`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
await context.addInitScript((value) => {
  localStorage.setItem('ironoath.deviceId', value)
}, `labelfit-${Date.now()}`)

const cors = (request) => ({
  'access-control-allow-origin': request.headers()['origin'] ?? '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
})
const reply = async (route, data) => route.fulfill({
  status: 200,
  headers: { ...cors(route.request()), 'content-type': 'application/json' },
  body: JSON.stringify({ code: 0, msg: '成功', data, serverNow: Date.now() }),
})
/**
 * 挂一份读接口夹具：OPTIONS 先回 204，其余用 `reply` 包成 `{code:0,data}`。
 * 必须在 `page.goto` **之前**挂上（深链进面板就发请求，晚挂等于这一格读到空态）。
 * `data` 给函数时按请求 URL 现算 —— 同一个端点带不同参数（`/social/permissions?scope=`）要能各回各的。
 */
const stubRead = (pattern, data) => context.route(pattern, async (route) => {
  if (route.request().method() === 'OPTIONS') {
    await route.fulfill({ status: 204, headers: cors(route.request()) })
    return
  }
  await reply(route, typeof data === 'function' ? data(route.request().url()) : data)
})
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
 * 社交夹具。这一格与邮件/战报不同：**空态本来就有 11 颗 Label**（页签条 + 那一行「创建小队」），
 * 光数 Label 挡不住"桩掉线"，所以另配一条正向断言 `STUB_MARKS`（夹具里的队名必须真画出来）。
 * 摘要给一支 6 人小队（`SquadView.required` 那 14 个字段一个不少），联盟留 null ——
 * 非 null 会让 `AppRoot.refresh('social')` 追加一次 `/alliance/sync`，那是另一条链路，不属这一格。
 */
const SQUAD_MEMBERS = [
  { name: '铁砧·玛尔达', role: 'LEADER', power: 128400, mainCityLevel: 18 },
  { name: '石锤·乌尔', role: 'MEMBER', power: 74200, mainCityLevel: 14 },
  { name: '灰隼·雷恩', role: 'MEMBER', power: 69850, mainCityLevel: 13 },
  { name: '柳岸·希达', role: 'MEMBER', power: 51200, mainCityLevel: 12 },
  { name: '守誓者贝尔', role: 'MEMBER', power: 47600, mainCityLevel: 11 },
  { name: '麦田·奥登', role: 'MEMBER', power: 33100, mainCityLevel: 9 },
].map((m, i) => ({ id: `probe_squad_p${i}`, lastActiveAt: Date.now() - i * 600_000, ...m }))
// 事件三条：带坐标的（被打）、不带坐标的（收到互助）、过期的一条 ——
// `SocialEventView.required` 那 8 个字段一个不少，`body` / `coord` / `relatedId` 允许 null 但**必须出现**
const SOCIAL_EVENTS = [
  { eventId: 'probe_ev_0', type: 'MEMBER_ATTACKED', title: '石锤·乌尔的城被攻打',
    body: '守军损失 1 240， attacker 已撤退。', coord: { x: 118, y: 64 }, relatedId: 'probe_battle_0',
    occurredAt: Date.now() - 600_000, expired: false },
  { eventId: 'probe_ev_1', type: 'HELP_RECEIVED', title: '你的兵营加速已获 3 次互助',
    body: null, coord: null, relatedId: 'probe_help_0',
    occurredAt: Date.now() - 1_800_000, expired: false },
  { eventId: 'probe_ev_2', type: 'RALLY_INVITED', title: '集结邀请：西关（已过期）',
    body: '没有在你手上响应，队伍已经出发了。', coord: { x: 203, y: 88 }, relatedId: 'probe_rally_1',
    occurredAt: Date.now() - 7_200_000, expired: true },
]
await stubRead('**/social/summary*', {
  squad: {
    id: 'probe_squad_1', name: '铁砧前哨', leaderId: 'probe_squad_p0', members: SQUAD_MEMBERS,
    level: 6, exp: 1240, expToNext: 2000, memberCap: 10, shopLevel: 3, squadCoin: 4820,
    allianceId: null, isSubSquad: false, dailyQuestProgress: 3, dailyQuestTarget: 8,
    serverNow: Date.now(),
  },
  alliance: null, nationId: null,
  pendingInvites: 0, pendingHelps: 2, helpRemainingToday: 3,
  events: SOCIAL_EVENTS, serverNow: Date.now(),
})
await stubRead('**/social/helpRequests*', {
  requests: [
    { requestId: 'probe_help_0', fromPlayerId: 'probe_squad_p1', fromPlayerName: '石锤·乌尔',
      kind: 'BUILDING', targetDesc: '兵营 Lv12 升级中', remainingSeconds: 1500, helpedCount: 2, alreadyHelped: false },
    { requestId: 'probe_help_1', fromPlayerId: 'probe_squad_p2', fromPlayerName: '灰隼·雷恩',
      kind: 'TRAINING', targetDesc: '重步兵 ×400', remainingSeconds: 600, helpedCount: 5, alreadyHelped: true },
  ],
  pendingHelps: 2, helpRemainingToday: 3, serverNow: Date.now(),
})
// 权限按 scope 各问一次：响应里的 `scope` 必须跟着请求走，写死一份等于让两页共用同一套权限位
await stubRead('**/social/permissions*', (url) => ({
  scope: url.includes('ALLIANCE') ? 'ALLIANCE' : 'SQUAD',
  role: 'LEADER',
  permissions: ['KICK_MEMBER', 'START_RALLY', 'DISBAND', 'DONATE'],
  serverNow: Date.now(),
}))
// 发现型列表与聊天：这一格不量它们（要点页签才画行），但桩住才不会让 dev 新号的真实空响应混进读数 ——
// `/alliance/list` 对没入盟的号回 10010，客户端会把那句「稍后会自动重试」画到屏幕上，量具就读成了另一屏
await stubRead('**/alliance/list*', { alliances: [], total: 0, limit: 20, serverNow: Date.now() })
await stubRead('**/squad/list*', { squads: [], total: 0, limit: 20, serverNow: Date.now() })
await stubRead('**/alliance/applications*', { applicants: [], total: 0, limit: 20, serverNow: Date.now() })
await stubRead('**/chat/list*', {
  messages: [
    { messageId: 'probe_chat_0', channel: 'SQUAD', senderId: 'probe_squad_p1',
      senderName: '石锤·乌尔', content: '集合点定在河谷渡口，我先过去了', sentAt: Date.now() - 240_000 },
    { messageId: 'probe_chat_1', channel: 'SQUAD', senderId: 'probe_squad_p0',
      senderName: '铁砧·玛尔达', content: '等你到整点，路上把侦察发一份过来', sentAt: Date.now() - 120_000 },
  ],
  hasMore: false, serverNow: Date.now(),
})
// 集结页签的数据也走 `refresh('rallies')` → `GET /rally/list`（`GameBootstrap` 把它转手给
// `social.attachRallies`）。**三条全是 PREPARING**：服务端这个端点只回进行中的集结
// （`RallyListResp` 文档原话「已出发或已取消的集结留在面板上没有意义」），
// 给一条 DEPARTED 就会造出一个"产品缺陷"假象 —— 上一版正是这样量出「即将出发」配 DEPARTED，
// 判真假读到服务端才结案（台账 #397）。三条各取 `remainTextOf` 的一个分支 + 一条满员。
await stubRead('**/rally/list*', {
  rallies: [
    { rallyId: 'probe_rally_0', scope: 'SQUAD', groupId: 'probe_squad_1', initiatorId: 'probe_squad_p0',
      targetCoord: { x: 118, y: 64 }, targetType: 'MONSTER', maxMembers: 5, joinedCount: 3,
      totalTroops: 12400, prepareUntil: Date.now() + 20 * 60_000, departAt: Date.now() + 25 * 60_000,
      status: 'PREPARING', members: ['probe_squad_p0', 'probe_squad_p1', 'probe_squad_p2'],
      heroSlots: [], serverNow: Date.now() },
    // 不足一分钟那一支（「准备还剩 40 秒」）
    { rallyId: 'probe_rally_1', scope: 'SQUAD', groupId: 'probe_squad_1', initiatorId: 'probe_squad_p1',
      targetCoord: { x: 96, y: 141 }, targetType: 'RESOURCE', maxMembers: 4, joinedCount: 2,
      totalTroops: 28600, prepareUntil: Date.now() + 40_000, departAt: Date.now() + 45_000,
      status: 'PREPARING', members: ['probe_squad_p1', 'probe_squad_p2'],
      heroSlots: [], serverNow: Date.now() },
    // 已过准备时刻但服务端还没把它 tick 成 DEPARTED（「即将出发」）+ 满员（不给「加入」键）
    { rallyId: 'probe_rally_2', scope: 'SQUAD', groupId: 'probe_squad_1', initiatorId: 'probe_squad_p2',
      targetCoord: { x: 203, y: 88 }, targetType: 'PLAYER_CITY', maxMembers: 5, joinedCount: 5,
      totalTroops: 41200, prepareUntil: Date.now() - 60_000, departAt: Date.now() - 55_000,
      status: 'PREPARING', members: ['probe_squad_p1', 'probe_squad_p2', 'probe_squad_p3',
        'probe_squad_p4', 'probe_squad_p5'],
      heroSlots: [], serverNow: Date.now() },
  ],
  serverNow: Date.now(),
})

const offenders = []
const stretched = []
const crowded = []
const underNavAll = []
/** 像素法报出来的"字被后画的底板盖住"（>0 的都列出来，便于逐张目视）。 */
const covered = []
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
      + `压进导航条 ${(read.underNav ?? []).length} 颗`)
  }
  // 像素法：底板压字。**只在默认相量**（切页签后那一屏另说），且只报不判红。
  // 一张全图基线 + 每块候选底板一张（按底板分组，不是一颗 Label 一张），差在内存里裁字形带算。
  if (process.env.LABELFIT_PLATES !== '0') {
    const plan = await page.evaluate(planPlateCoverage, key)
    if (plan !== null && plan.plates.length > 0) {
      const base = decodePng(await page.screenshot())
      for (const plate of plan.plates) {
        await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = false }, plate.handle)
        await page.waitForTimeout(120)
        const after = decodePng(await page.screenshot())
        await page.evaluate((h) => { window.__plateNodes[h].getComponent('cc.Graphics').enabled = true }, plate.handle)
        await page.waitForTimeout(80)
        for (const bi of plate.bands) {
          const b = plan.bands[bi]
          const d = diffRegion(base, after, b.rect, 24)
          if (d.changed > 0) {
            const line = `${key}/${b.text}←「${plate.name}」变了 ${d.changed}/${d.total} 像元`
            covered.push(line)
            if (d.changed >= PLATE_MIN_RED) coveredRed.push(line)
          }
        }
      }
      console.log(`  PLATE ${key}: 候选底板 ${plan.plates.length} 块、字形带 ${plan.bands.length} 条，`
        + `报出 ${covered.filter((x) => x.startsWith(`${key}/`)).length} 处`)
    }
  }
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
      + `压进导航条 ${(pr.underNav ?? []).length} 颗`)
    for (const x of pr.out) offenders.push(`${tag}/${x.text}(${x.h}<${x.floor},字${x.want})`)
    for (const x of pr.stretched ?? []) stretched.push(`${tag}/${x.text}(${x.h}>${x.floor}+8,字${x.want},估宽${x.est}/盒${x.boxW})`)
    for (const x of pr.crowd ?? []) crowded.push(`${tag}/${x}`)
    for (const x of pr.underNav ?? []) underNavAll.push(`${tag}/${x}`)
    mergeRead(read, pr)
    await page.screenshot({ path: shot })
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
// 颗数只证明"画了点什么"，这一条证明"画的是夹具那份数据"（社交页空态本来就有 11 颗）
for (const [panel, mark] of Object.entries(STUB_MARKS)) {
  check(`${panel} 的夹具文字真的画到了屏幕上（找「${mark}」）`,
    (textsBy.get(panel) ?? []).some((t) => t.includes(mark)), true)
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
