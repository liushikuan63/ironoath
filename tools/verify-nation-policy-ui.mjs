/**
 * 国策页的真机证据（V17-G 视觉半）：真后端 + 真产物 + 无头浏览器。
 *
 * <p><b>为什么单独一份而不是并进 verify-nation-live.mjs 的可选段</b>：那一段要开关
 * （`NATION_LIVE_UI=1`）而且只截国库页。国策页要「切页签 → 提一条 → 再切一次看结果」三段动作，
 * 塞进去会让那份已经 71 条的探针读不下去。形状（场景树遍历、面板打开路径、清理）全部照
 * 照既有那一份写，**不另发明一套按图索骥的找法**。
 *
 * <p>判据：① 面板能开且国名/国库是真数据；② 第 5 个页签在、切过去看得到页眉与槽位说明；
 * ③ 提案键在且点了之后提案真的出现；④ 投票键在、而它灰的理由印在屏上。
 */
import fs from 'node:fs'
import path from 'node:path'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const ARTIFACT = 'client/build/web-mobile'
const OUT = process.env.POLICY_SHOT_DIR ?? 'tmp/nation-policy'
const NOW = Date.now()
const rid = prefix => `${prefix}-${NOW}-${Math.random().toString(36).slice(2, 8)}`
const runTag = String(NOW % 100000)

let pass = 0
let fail = 0
function check(name, actual, expected) {
  if (actual === expected) {
    pass += 1
    console.log(`  PASS  ${name}（${actual}）`)
  } else {
    fail += 1
    console.log(`  FAIL  ${name}：实际=${actual} 期望=${expected}`)
  }
}
function checkThat(name, cond, detail) {
  if (cond) {
    pass += 1
    console.log(`  PASS  ${name}`)
  } else {
    fail += 1
    console.log(`  FAIL  ${name}${detail === undefined ? '' : `：${detail}`}`)
  }
}

async function call(method, p, body, playerId, token) {
  const headers = { 'content-type': 'application/json' }
  if (playerId !== undefined) headers['X-Player-Id'] = playerId
  if (token !== undefined) headers.authorization = `Bearer ${token}`
  const res = await fetch(`${BACKEND}${p}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  })
  return res.json()
}

/** 建号并把 deviceId 一起返回（页面要注入的就是它，注入错会打开别人的号）。 */
async function initPlayer(tag) {
  const deviceId = `policy-ui-${tag}-${NOW}`
  const resp = await call('POST', '/player/init', {
    requestId: rid('init'), deviceId, nickName: `国策屏${tag}`, avatarId: 1, clientTime: NOW,
  })
  if (resp.code !== 0) {
    throw new Error(`建号失败：${resp.code} ${resp.msg} ${resp.detail ?? ''}`)
  }
  return { playerId: resp.data.playerId, token: resp.data.token, deviceId }
}

fs.mkdirSync(OUT, { recursive: true })

// ---------- 造一个在位国家 ----------
const king = await initPlayer('king')
await call('POST', '/alliance/create', {
  requestId: rid('alliance'), name: `国策屏盟${runTag}`, tag: `G${runTag.slice(-4)}`,
}, king.playerId, king.token)
const founded = await call('POST', '/nation/found', {
  requestId: rid('found'), name: `国策屏国${runTag}`, capitalX: 141, capitalY: 83,
}, king.playerId, king.token)
if (founded.code !== 0) {
  throw new Error(`建国被拒：${founded.code} ${founded.msg} ${founded.detail ?? ''}`)
}
const nationName = founded.data.nation.name
const treasury = founded.data.nation.treasury
console.log(`\n=== 国策页真机证据（真后端 + 真产物，零夹具）===`)
console.log(`  真数据：${nationName}｜国库 ${treasury}｜${BACKEND}`)

const { chromium } = await import('file:///D:/Java/nodejs/node_cache/_npx/31e32ef8478fbf80/node_modules/playwright/index.mjs')
const { startPreviewServer } = await import('./lib/preview-server.mjs')
const { hideGuideOverlay } = await import('./lib/guide-overlay.mjs')
const port = Number(process.env.NATION_LIVE_PORT ?? 8237)
const preview = await startPreviewServer({ root: ARTIFACT, backend: BACKEND, port })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript(v => localStorage.setItem('ironoath.deviceId', v), king.deviceId)
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))

/** 打开国家面板（联盟概况行的第二颗键，按文案找 —— 与既有那份探针同一条路）。 */
const OPEN_PANEL = `(() => {
  const scene = window.cc.director.getScene()
  const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
  if (!social || !social.activeInHierarchy) return 'no-social'
  social.getChildByName('Tab_alliance').emit('touch-start')
  let target = null
  const walk = (n) => {
    if (target) return
    if (/^ActionButton[23]?$/.test(n.name) && n.activeInHierarchy) {
      const caption = n.getComponentInChildren('cc.Label')
      if (caption !== null && caption !== undefined && String(caption.string) === '国家') { target = n; return }
    }
    for (const child of n.children) walk(child)
  }
  walk(social)
  if (target === null) return 'no-button'
  target.emit('touch-start')
  return 'ok'
})()`

/**
 * 面板里按**节点名**点一颗键。
 *
 * <p><b>为什么不按文案找</b>：按钮的 caption 是子节点上的 `cc.Label`，
 * 而同一段文字在屏上可能出现好几次（页签行 + 卡片标题），
 * 按文案找会点到「长得一样但不是那颗」的节点上。第一版就是这么写的，结果 12 条全红而
 * 其中 4 条（① 面板能开）明明是绿的 —— 判据指错了对象。
 * 面板里每一颗键的节点名都是代码里给的（`Tab_POLICY` / `PolicyPropose-*` / `PolicyYes-*`），
 * 按名字找是确定的。
 */
const clickByName = predicateSource => `(() => {
  const panel = window.cc.director.getScene()
    .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
  if (!panel || !panel.activeInHierarchy) return 'no-panel'
  const match = ${predicateSource}
  let target = null
  const walk = (n) => {
    if (target) return
    if (n.activeInHierarchy && match(n.name)) { target = n; return }
    for (const child of n.children) walk(child)
  }
  walk(panel)
  if (target === null) return 'not-found'
  // **灰键的判据是「有没有挂 touch-start 监听」**，不是 cc.Button.interactable ——
  // 面板的 button() 对灰键根本不挂监听（NationPanelView.ts:784-786），那才是
  // 「点下去零请求」的真正机制；用 interactable 判会永远读到 true。
  if (typeof target.hasEventListener === 'function' && !target.hasEventListener('touch-start')) {
    return 'greyed'
  }
  target.emit('touch-start')
  return 'ok'
})()`

const clickNamed = name => clickByName(`n => n === ${JSON.stringify(name)}`)
/** 前缀匹配：提案键带 policyId 尾巴（`PolicyPropose-np_harvest`）。 */
const clickPrefixed = prefix => clickByName(`n => n.startsWith(${JSON.stringify(prefix)})`)

/** 面板当前所有非空文字。 */
const READ_TEXTS = `(() => {
  const panel = window.cc.director.getScene()
    .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
  const texts = []
  if (panel && panel.activeInHierarchy) {
    const walk = (n) => {
      if (n.activeInHierarchy) {
        const label = n.getComponent('cc.Label')
        if (label && String(label.string ?? '').trim() !== '') texts.push(String(label.string))
      }
      for (const child of n.children) walk(child)
    }
    walk(panel)
  }
  return texts.join(' ')
})()`

try {
  await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
  preview.assertRewritten()
  await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
    null, { timeout: 60_000 })
  await page.waitForTimeout(3500)
  await hideGuideOverlay(page)
  await page.waitForTimeout(400)

  // ---------- ① 面板能开 ----------
  const opened = await page.evaluate(OPEN_PANEL)
  check('① 点得开国家面板', opened, 'ok')
  await page.waitForTimeout(2000)
  const base = await page.evaluate(READ_TEXTS)
  checkThat(`① 屏上的国名是刚建的那个（${nationName}）`, base.includes(nationName))
  checkThat('① 屏上的国库是服务端那一份',
    base.includes(String(treasury).replace(/\B(?=(\d{3})+(?!\d))/g, ',')))
  checkThat('① 屏上没有 nation_ 这类内部 id', !base.includes('nation_'))

  // ---------- ② 切到国策页 ----------
  const tabbed = await page.evaluate(clickNamed('Tab_POLICY'))
  check('② 切到「国策」页签', tabbed, 'ok')
  await page.waitForTimeout(2200)
  const empty = await page.evaluate(READ_TEXTS)
  console.log(`  屏上文字：${empty}`)
  checkThat('② 页眉带生效槽位数', empty.includes('生效槽位'))
  checkThat('② 本轮还没有提案那一行在（此刻确实一条都没有）',
    empty.includes('本轮还没有提案') || empty.includes('本轮提案（0）'))
  checkThat('② 槽位竞争规则那句也在（服务端下发的那一句，不是客户端自己写的）',
    empty.includes('四级排序') || empty.includes('占住前'))
  checkThat('② 提案键在（表里 8 行，各一颗）', (empty.match(/提案/g) ?? []).length >= 2)
  checkThat('② 屏上没有 np_ / unit_ 这类内部 id', !empty.includes('np_') && !empty.includes('unit_cavalry'))
  await page.screenshot({ path: path.join(OUT, '01-policy-empty.png') })
  console.log(`  截图：${path.join(OUT, '01-policy-empty.png')}`)

  // ---------- ③ 真点一次提案 ----------
  const proposed = await page.evaluate(clickPrefixed('PolicyPropose-'))
  checkThat('③ 提案键点得动（不是灰的）', proposed === 'ok', `返回=${proposed}`)
  await page.waitForTimeout(2200)
  const after = await page.evaluate(READ_TEXTS)
  console.log(`  屏上文字：${after}`)
  checkThat('③ 提案之后「本轮提案（1）」出现', after.includes('本轮提案（1）'))
  // **名字从服务端回的那一轮里取**，不写死：第一版写死「丰收时代」，
  // 而探针点的其实是候选区第一颗（骑兵时代 T1），断言与动作对不上，红的是我的假设。
  const roundResp = await call('GET', '/nation/policy', undefined, king.playerId, king.token)
  const proposedName = roundResp?.data?.proposals?.[0]?.policy?.name ?? ''
  checkThat(`③ 屏上的名字与服务端那一条一致（${proposedName}）`,
    proposedName !== '' && after.includes(proposedName))
  checkThat('③ 两份公示名单都在（此刻都写着「还没有人投票」）',
    after.includes('赞成：还没有人投票') && after.includes('反对：还没有人投票'))
  checkThat('③ 票数那行是 0/0（票数与名单自证）', after.includes('赞成 0') && after.includes('反对 0'))
  checkThat('③ 投票键在', after.includes('赞成') && after.includes('反对'))
  checkThat('③ 灰键的理由印在屏上（玩家要读得出为什么现在不能投）',
    after.includes('现在不是投票时间'))
  checkThat('③ 屏上没有内部 id', !after.includes('np_'))

  // ---------- ⑤ 三条只有靠截图 / 几何才抓得到的缺陷（第一版 21 条文本断言全绿，屏上还有三处坏） ----------
  // ⑤-a 倒计时方向：`elapsedText` 反着用会得到「距开票 23 小时前」，读起来是通顺的错话。
  const countdown = (after.match(/距开票还有[^\s]*|本轮投票还剩[^\s]*/) ?? [])[0] ?? ''
  checkThat(`⑤-a 倒计时是「还剩」而不是「…前」（屏上是「${countdown}」）`,
    countdown !== '' && !countdown.endsWith('前') && /还有|还剩/.test(countdown))
  // ⑤-b 槽位说明不得印工程术语（服务端那句原文里带出来的「国策 id」「N = 」）
  const slotLine = (after.match(/同轮多条提案都通过时[^。]*。/) ?? [])[0] ?? ''
  checkThat(`⑤-b 槽位说明里没有内部 id 与工程符号（屏上是「${slotLine}」）`,
    slotLine !== '' && !slotLine.includes('国策 id') && !slotLine.includes('N = '))
  // ⑤-c 候选区的「提案」键不许压住效果说明，**而且必须真的画出来**（几何判据，文本断言抓不到）
  const geom = await page.evaluate(`(() => {
    const panel = window.cc.director.getScene()
      .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
    if (!panel || !panel.activeInHierarchy) return { hits: -1, buttons: -1, texts: -1, lefts: [] }
    const texts = []
    const buttons = []
    const walk = (n) => {
      if (n.activeInHierarchy) {
        const t = n.getComponent('cc.UITransform')
        if (t === null || t === undefined) { return }
        const p = n.worldPosition
        if (n.name.startsWith('PolicyPropose-')) {
          buttons.push({ l: p.x - t.contentSize.width / 2, n: n.name, x: p.x, y: p.y, w: t.contentSize.width, a: n.activeInHierarchy })
        } else {
          const label = n.getComponent('cc.Label')
          if (label && /^.+ (攻击|防御|资源产出|行军速度) [+-]/.test(String(label.string))) {
            texts.push({ x: p.x })
          }
        }
      }
      for (const child of n.children) walk(child)
    }
    walk(panel)
    let hits = 0
    for (const b of buttons) for (const t of texts) if (t.x > b.l) hits += 1
    // **键数与文字数一起回传**：第一版只回传"违例数"，而那个判据写成了两条互相矛盾的
    // 条件（b.l < t.x 与 t.x > b.l 是同一句），恒不成立 —— 于是一个键都没画出来也报 0，
    // 而截图上确实一个键都没有。判据要么能失败，要么就是装饰。
    return { hits, buttons: buttons.length, texts: texts.length, lefts: buttons.map(b => b.l) }
  })()`)
  // 只钉「文字起点在键左边」这一条：Label 的 anchor 语义是"节点在左端"还是"节点在框中心"
  // 两种都说得通，而 `text.x < buttonLeft` 在两种下都成立 ——
  // 判据写成"两框不相交"会在语义不确定时报假红。
  checkThat(`⑤-c1 候选区的「提案」键真的画出来了（键数=${geom.buttons}，左边缘=${JSON.stringify(geom.lefts)}）`,
    geom.buttons === 4)
  checkThat(`⑤-c2 每一条效果说明的起点都在键的左边（文字数=${geom.texts} 违例=${geom.hits}）`,
    geom.texts === 4 && geom.hits === 0)
  await page.screenshot({ path: path.join(OUT, '02-policy-proposed.png') })
  console.log(`  截图：${path.join(OUT, '02-policy-proposed.png')}`)

  // ---------- ④ 同一轮里那一条不该再能提 ----------
  const twice = await page.evaluate(clickPrefixed('PolicyPropose-'))
  checkThat('④ 提过的那一条那颗键已经灰了（返回 greyed，不发请求）', twice === 'greyed' || twice === 'not-found',
    `返回=${twice}`)
  check('④ 零页面错误', errors.join(' | ') || '无', '无')
} finally {
  await page.screenshot({ path: path.join(OUT, '03-final.png') })
  await browser.close()
  await preview.close()
}

// 清掉本轮建的国家：不清的话每跑一轮就多占一个名额（上限只有 4）
const cleaned = await call('POST', '/nation/disband', { requestId: rid('disband') }, king.playerId, king.token)
check(`清掉本轮建的国家（${cleaned.detail ?? cleaned.msg}）`, cleaned.code, 0)

console.log(`\n=== 国策页真机证据：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
