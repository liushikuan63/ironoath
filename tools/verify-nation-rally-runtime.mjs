#!/usr/bin/env node
/**
 * 职责：把「国家层集结对玩家真的可见、点得动、发得出去，而且界面上那套数就是服务端那一套」
 *       钉成能失败的判据（V22-c，配 V22-b 的客户端入口）。
 * 依赖：node、playwright、**开着 dev 提速档的后端**（`IRONOATH_DEV_CITY_LEVEL=16`，建国前置）、
 *       已构建的 `client/build/web-mobile`。
 *
 * <p><b>用法</b>（变量名不统一会静默回落到 8080，读数错得像产品缺陷 —— 台账 #371/#372）：
 *   BACKEND_ORIGIN=http://127.0.0.1:8198 node tools/verify-nation-rally-runtime.mjs
 * 可选：
 *   NR_PORT   预览端口（默认 8113；**绝不能等于后端端口**，撞了是 EADDRINUSE 不是判据红）
 *   NR_SHOTS  截图目录（默认 client/build/nation-rally-verify）
 *   NR_OPS_TOKEN 本地自 mint 的运维令牌（默认 art-verify-local，**不是凭据**，见台账 #776）
 *   NR_TARGET_X / NR_TARGET_Y 集结目标格（默认 400,400 = 空地）
 *
 * <p><b>四相</b>：
 * ① 两个国家 + 官职差（`starter` 领大将军 = OFFICER 档、`plain` 是普通国民 = MEMBER 档），
 *    两者的 `/rally/policy` 那份 nation 视图先各给一次读数；
 * ② 浏览器里以 **无权限的那位**打开编成面板：三档层级都在、国家那颗是灰的、理由是服务端那句原话，
 *    点它**一个请求都不发**（真数 `/rally/nation` 的个数，不只看按钮颜色）；
 * ③ 以**有官职的那位**真发一次国家层集结（写请求打到活后端）⇒ 集结页签里看得见那一行；
 * ④ 边界两条：maxMembers 先读 `/rally/policy` 的 nation 再 ±1（**界值不写死**，V24 会抬这个数字），
 *    越界是被夹住而不是被拒（协议注释定的口径），且读口说的数与写口夹的数必须是同一个数。
 *
 * <p><b>目标格为什么是空地而不是城</b>：攻击闸门按 B13 §二「同国联盟不得互攻」会对同胞回 13009，
 * 而 dev 后端里能凑出的"他国城"要的是首都坐标（撞国 id 那一族坑）。`AttackGuardService` 明写
 * 「空地 / 野怪 / 资源点不构成 PVP，直接放行」—— 台账 #786 记的就是这件事。
 * 客户端只有"搜索结果里的玩家城"这一条真实入口（`beginMarchCompose` 只认 `searchResp` 里的 id），
 * 所以这一相的**读侧**（搜索结果列表）用 `tools/lib/route-stub.mjs` 挂一份生产形状的空地行，
 * **写侧（POST /rally/nation）打的是真后端**，不是桩。
 *
 * <p><b>不验的</b>：多人真加入（要给另一个号练到能带兵、还得躲过圈层与护盾）——
 * 加入通路的服务端形状由 `NationRallyEndpointTest.memberCanJoinTheNationalRally` 钉着（V22-a 植入②红过）。
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { makeStubRead } from './lib/route-stub.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[nation-rally] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端')
  process.exit(2)
})()
const PORT = Number(process.env.NR_PORT ?? 8113)
const SHOT_DIR = path.resolve(process.env.NR_SHOTS ?? 'client/build/nation-rally-verify')
const OPS_TOKEN = process.env.NR_OPS_TOKEN ?? 'art-verify-local'
const TARGET_X = Number(process.env.NR_TARGET_X ?? 400)
const TARGET_Y = Number(process.env.NR_TARGET_Y ?? 400)
const T1 = 'unit_infantry_t1'
const HERO_ID = process.env.NR_HERO ?? 'hero_ssr_01'

if (PORT === Number(new URL(BACKEND).port)) {
  console.error(`[nation-rally] 探针端口 NR_PORT=${PORT} 与后端端口相同 —— 预览服务起不来，这不是判据红`)
  process.exit(2)
}

const failures = []
const lines = []
function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}
/** 前提不足 = 量具没架对，退 2 而不是判红（与 verify-nation-live 同一口径）。 */
function prereq(msg) {
  console.error('\n=== 前提不足（不是功能红）：' + msg)
  // 已收集的读数必须吐出来：否则"退 2"这一趟等于什么都没跑（第一版就是这么瞎掉的）
  if (lines.length > 0) {
    console.error('--- 退 2 之前的读数 ---')
    for (const line of lines) console.error(`  ${line}`)
  }
  process.exit(2)
}

let seq = 0
const rid = (t) => `nr-${t}-${Date.now()}-${(seq += 1)}`
const TAG = Date.now() % 100000

async function call(method, url, body, player, extraHeaders) {
  const headers = { 'content-type': 'application/json', ...(extraHeaders ?? {}) }
  if (player !== undefined) {
    headers['X-Player-Id'] = player.id
    headers.authorization = `Bearer ${player.token}`
  }
  const response = await fetch(`${BACKEND}${url}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let parsed = null
  try {
    parsed = JSON.parse(text)
  } catch {
    parsed = null
  }
  return {
    http: response.status, code: parsed?.code ?? -1, data: parsed?.data ?? null,
    detail: parsed?.detail ?? null, msg: parsed?.msg ?? text.slice(0, 160),
  }
}

async function initPlayer(nick) {
  const deviceId = `nation-rally-${nick}-${Date.now()}`
  const outcome = await call('POST', '/player/init', {
    requestId: rid('init'), deviceId, nickName: nick, avatarId: 1, clientTime: Date.now(),
  })
  if (outcome.code !== 0) {
    prereq(`建号 ${nick} 失败：${outcome.code} ${outcome.msg} ${outcome.detail ?? ''}`)
  }
  return { id: outcome.data.playerId, token: outcome.data.authToken, nick, deviceId,
    cityLevel: outcome.data.cityLevel }
}

async function waitUntil(fn, timeoutMs, everyMs, what) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const got = await fn()
    if (got !== null && got !== undefined && got !== false) {
      return got
    }
    if (Date.now() >= deadline) {
      return null
    }
    await new Promise(r => setTimeout(r, everyMs))
  }
}

/** 建盟（名字与 tag 每轮唯一；撞 10016 就换尾数重试 —— dev 内存跨轮不清）。 */
async function createAlliance(player, label) {
  let last = null
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const tail = `${TAG + attempt * 7}`.slice(-4)
    last = await call('POST', '/alliance/create', {
      requestId: rid('alliance'), name: `NR${label}盟${TAG}${attempt}`, tag: `${tail}`,
    }, player)
    if (last.code === 0) {
      return last.data.alliance.id
    }
  }
  return prereq(`建盟 ${label} 八次都被拒（最后一条：${last.code} ${last.msg} ${last.detail ?? ''}）`)
}

/** 造一支两人小队（编成面板进"集结"态的门就卡在小队档的 canStart）。名字每支唯一，撞了就换尾数重试。 */
async function createSquadOf2(leader, mate, label) {
  let created = null
  for (let attempt = 0; attempt < 8; attempt += 1) {
    created = await call('POST', '/squad/create', {
      requestId: rid('squad'), name: `NR${label}队${TAG}${attempt}`,
    }, leader)
    if (created.code === 0) break
  }
  if (created.code !== 0) {
    prereq(`建小队 ${label} 失败：${created.code} ${created.msg} ${created.detail ?? ''}`)
  }
  const squadId = created.data.squad?.id
  const joined = await call('POST', '/squad/join', { requestId: rid('squad'), squadId }, mate)
  if (joined.code !== 0) {
    prereq(`第二人入队失败（squadId=${squadId}）：${joined.code} ${joined.msg} ${joined.detail ?? ''}`)
  }
  return squadId
}

/** 练到真有几只兵（集结必须带兵；提速档不开时钟倍速，所以这里是真等）。 */
async function equipAndTrain(player, nick, count) {
  const mail = await call('POST', '/ops/mail/send', {
    requestId: rid('hero-mail'), playerId: player.id, title: '国家集结量具用武将',
    text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-nation-rally-runtime',
    rewards: [{ type: 'HERO', id: HERO_ID, count: 1, name: HERO_ID }],
  }, undefined, { 'X-Ops-Token': OPS_TOKEN })
  verdict(mail.code === 0, `${nick} 补发武将被受理（仓库唯一凭空发奖励口，dev 限定）`,
    `${mail.code} ${mail.detail ?? mail.msg}`)
  await call('POST', '/mail/claimAll', { requestId: rid('claim') }, player)
  const lineup = await call('POST', '/hero/lineup', {
    requestId: rid('lineup'), presetIndex: 0, main: HERO_ID, sub1: null, sub2: null,
  }, player)
  verdict(lineup.code === 0, `${nick} 上阵 ${HERO_ID}（带兵上限=${lineup.data?.troopCap ?? '-'}）`,
    `${lineup.code} ${lineup.detail ?? lineup.msg}`)
  let placed = null
  for (const [gx, gy] of [[3, 3], [4, 4], [2, 2], [5, 5], [0, 0], [6, 6]]) {
    const tryOne = await call('POST', '/city/upgrade', { requestId: rid('barracks'), configId: 'barracks', gridX: gx, gridY: gy }, player)
    if (tryOne.code === 0) {
      placed = [gx, gy]
      break
    }
  }
  verdict(placed !== null, `${nick} 兵营开建（练兵前置）`, JSON.stringify(placed))
  const unlocked = await waitUntil(async () => {
    const list = await call('GET', '/army/list', undefined, player)
    const unit = (list.data?.units ?? []).find(u => u.unitId === T1)
    return unit?.unlocked === true ? true : null
  }, 180_000, 3_000, 'T1 解锁')
  verdict(unlocked === true, `${nick} T1 已解锁（兵营建完）`, `unlocked=${unlocked}`)
  const trained = await call('POST', '/army/train', { requestId: rid('train'), unitId: T1, count }, player)
  verdict(trained.code === 0, `${nick} 练兵下单 ${count} 只`, `${trained.code} ${trained.detail ?? trained.msg}`)
  const startedAt = Date.now()
  const got = await waitUntil(async () => {
    const list = await call('GET', '/army/list', undefined, player)
    const unit = (list.data?.units ?? []).find(u => u.unitId === T1)
    return (unit?.count ?? 0) >= count ? unit.count : null
  }, 600_000, 5_000, `${nick} 兵力到 ${count}`)
  const tookSec = Math.round((Date.now() - startedAt) / 1000)
  verdict(got !== null, `${nick} 兵力到齐（读到 ${got ?? '超时'}，目标 ${count}，等 ${tookSec}s）`,
    `count=${got ?? '-'}（提速档不开时钟倍速，练兵是真等：15 只在 420s 档没到齐，所以这一相只练用得到的 ${count} 只）`)
  return got
}

/** 浏览器里读编成弹层：节点名 + 屏上逐字文案 + 那颗键此刻的底色（选中=金）。 */
function readComposeOverlay() {
  const out = { found: false, active: false, texts: [], chips: [], numbers: [], bandBoxes: [] }
  const scene = window.cc.director.getScene()
  const game = scene.getChildByName('Canvas')?.getChildByName('Game')
  const overlay = game?.getChildByName('MarchCompose')
  if (overlay === undefined || overlay === null) {
    return out
  }
  out.found = true
  out.active = overlay.activeInHierarchy !== false
  const labelOf = (node) => {
    const label = node.getComponent && node.getComponent('cc.Label')
    return label !== null && label !== undefined && label.string !== '' ? label.string : null
  }
  const walk = (node) => {
    // 隐藏节点不算"屏上"：Cocos 的 Label 在没显式给字时默认串是引擎写的 "label"，
    // 收进来会让"屏上文案"这一维读到根本没画的东西（植入趟实测抓到一次）
    if (node.activeInHierarchy === false) {
      return
    }
    const text = labelOf(node)
    if (text !== null) {
      out.texts.push(text)
    }
    for (const child of node.children ?? []) {
      walk(child)
    }
  }
  walk(overlay)
  const band = overlay.getChildByName('rallyBand')
  for (const child of band?.children ?? []) {
    const text = labelOf(child) ?? (child.children ?? []).map(labelOf).find(t => t !== null) ?? ''
    if (child.name.startsWith('层级-')) {
      const graphics = child.getComponent('cc.Graphics')
      const fill = graphics?.fillColor
      out.chips.push({
        scope: child.name.slice('层级-'.length), label: text,
        active: child.activeInHierarchy !== false,
        fill: fill === undefined || fill === null ? null : [fill.r, fill.g, fill.b],
        box: [child.position.x - 24, child.position.x + 24],
      })
    }
    if (child.name.startsWith('数-') && text !== '') {
      out.numbers.push({ name: child.name, text })
    }
    out.bandBoxes.push({ name: child.name, x: child.position.x,
      half: (child.getComponent('cc.UITransform')?.contentSize?.width ?? 0) / 2,
      active: child.activeInHierarchy !== false })
  }
  return out
}

/**
 * 把编成弹层的集结那一条读到"Cocos 真的量过文字"为止。
 *
 * <p>Label 的 contentSize 在首帧前是引擎默认的 100 宽，直接拿它算盒子会得到
 * "表头占 -160..-60"这种假相交（第一版就是这么误判层级键压住表头的）。
 * 等不到稳定值就照实报未稳定，不静默放过。
 */
async function settleBand(page, tries = 12) {
  for (let i = 0; i < tries; i += 1) {
    const view = await page.evaluate(readComposeOverlay)
    const labels = view.bandBoxes.filter(b => b.name.startsWith('数-') && b.active !== false)
    const settled = labels.length > 0 && labels.every(b => b.half <= 40)
    if (settled) {
      return { view, settled: true }
    }
    await page.waitForTimeout(500)
  }
  return { view: await page.evaluate(readComposeOverlay), settled: false }
}

/** 拍照前把盖住内容的弹窗藏掉（限时礼包是全屏遮罩，不藏就拍不到被测物）。 */
async function hideCoveringPopups(page) {
  const hidden = await page.evaluate(() => {
    const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
    const popup = game.getChildByName('giftPopup')
    if (popup === null || popup === undefined) {
      return 'no-popup-node'
    }
    popup.active = false
    return popup.activeInHierarchy === false ? 'hidden' : 'still-visible'
  })
  return hidden
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  console.log(`[nation-rally] 后端：${BACKEND}｜预览端口：${PORT}｜目标格：(${TARGET_X},${TARGET_Y})`)

  // ---------- 相①：两个国家 + 官职差 ----------
  const kingA = await initPlayer('nrKingA')
  const starter = await initPlayer('nrStarter')
  const mateS = await initPlayer('nrMateS')
  const kingB = await initPlayer('nrKingB')
  const plain = await initPlayer('nrPlain')
  const plainMate = await initPlayer('nrPlainMate')
  if (kingA.cityLevel !== 16) {
    prereq(`新号主城等级 = ${kingA.cityLevel}，建国要 16 级 —— 请用 IRONOATH_DEV_CITY_LEVEL=16 起后端`)
  }
  const probePolicy = await call('GET', '/rally/policy', undefined, kingA)
  if (probePolicy.data?.nation === undefined || probePolicy.data?.nation === null) {
    prereq(`/rally/policy 没有 nation 那一份 —— 这台后端是 V22-a 之前的旧 jar（先重打再跑）`)
  }

  const a1 = await createAlliance(kingA, '甲一')
  const a2 = await createAlliance(starter, '甲二')
  const b1 = await createAlliance(kingB, '乙一')
  const apply = await call('POST', '/alliance/apply', { requestId: rid('apply'), allianceId: b1 }, plain)
  verdict(apply.code === 0, `plain 申请入 ${b1}`, `${apply.code} ${apply.detail ?? apply.msg}`)
  const apps = await call('GET', '/alliance/applications', undefined, kingB)
  const pending = (apps.data?.applicants ?? apps.data?.applications ?? []).find(i => i.playerId === plain.id)
  if (pending !== undefined) {
    const reviewed = await call('POST', '/alliance/review', {
      requestId: rid('review'), applicantId: plain.id, approve: true,
    }, kingB)
    verdict(reviewed.code === 0, '国王批准 plain 入盟', `${reviewed.code} ${reviewed.detail ?? reviewed.msg}`)
  } else {
    verdict(false, '国王批准 plain 入盟', '申请列表里找不到那条（applications 形状变了？）')
  }
  const mateApplied = await call('POST', '/alliance/apply', { requestId: rid('apply'), allianceId: b1 }, plainMate)
  const apps2 = await call('GET', '/alliance/applications', undefined, kingB)
  const pending2 = (apps2.data?.applicants ?? apps2.data?.applications ?? []).find(i => i.playerId === plainMate.id)
  if (mateApplied.code === 0 && pending2 !== undefined) {
    await call('POST', '/alliance/review', { requestId: rid('review'), applicantId: plainMate.id, approve: true }, kingB)
  }

  const foundA = await call('POST', '/nation/found', {
    requestId: rid('found'), name: `NR甲国${TAG}`, capitalX: 120, capitalY: 88,
  }, kingA)
  if (foundA.code !== 0) {
    prereq(`国家 A 建国失败：${foundA.code} ${foundA.detail ?? foundA.msg}`)
  }
  const nationA = foundA.data.nation.nationId
  const foundB = await call('POST', '/nation/found', {
    requestId: rid('found'), name: `NR乙国${TAG}`, capitalX: 140, capitalY: 88,
  }, kingB)
  if (foundB.code !== 0) {
    prereq(`国家 B 建国失败：${foundB.code} ${foundB.detail ?? foundB.msg}`)
  }
  const nationB = foundB.data.nation.nationId
  const joinA = await call('POST', '/nation/join', { requestId: rid('join'), nationId: nationA }, starter)
  verdict(joinA.code === 0, 'starter 的联盟入籍国家 A（他从此是 A 国国民）', `${joinA.code} ${joinA.detail ?? joinA.msg}`)

  await createSquadOf2(starter, mateS, '发起')
  await createSquadOf2(plain, plainMate, '平民')

  const beforeAppoint = (await call('GET', '/rally/policy', undefined, starter)).data?.nation
  const appointed = await call('POST', '/nation/appoint', {
    requestId: rid('appoint'), playerId: starter.id, office: 'GENERAL',
  }, kingA)
  verdict(appointed.code === 0, '国王授 starter 大将军（OFFICER 档）', `${appointed.code} ${appointed.detail ?? appointed.msg}`)
  const starterNation = (await call('GET', '/rally/policy', undefined, starter)).data?.nation
  const starterAlliance = (await call('GET', '/rally/policy', undefined, starter)).data?.alliance
  const plainNation = (await call('GET', '/rally/policy', undefined, plain)).data?.nation

  verdict(beforeAppoint?.canStart === false,
    '授官前 starter 的国家层也发不起（MEMBER/议员档都是 MEMBER 档）',
    `canStart=${beforeAppoint?.canStart} reason="${beforeAppoint?.reason}"`)
  verdict(starterNation?.canStart === true,
    '授官后 starter 的国家层能发起（读口与写口同一张 role_permission）',
    `canStart=${starterNation?.canStart} reason="${starterNation?.reason}"`)
  verdict(plainNation?.canStart === false && (plainNation?.reason ?? '').length > 0,
    'plain 是普通国民：国家层发不起，而且服务端给了一句人话',
    `canStart=${plainNation?.canStart} reason="${plainNation?.reason}"`)
  verdict(!/MEMBER|OFFICER|LEADER|NATION|SQUAD|ALLIANCE|START_RALLY|perm_|pool_/.test(plainNation?.reason ?? ''),
    '理由里不出现枚举/权限位/表 id 原文（红线：不许把内部 id 印给玩家）',
    `reason="${plainNation?.reason}"`)

  // "两套数"的前提：国家上界与联盟上界必须不同，否则后面那条判据钉不住任何东西
  const nationCap = starterNation?.maxMembers
  const allianceCap = starterAlliance?.maxMembers
  verdict(typeof nationCap === 'number' && nationCap !== allianceCap,
    '夹具成立：国家上界与联盟上界是两个不同的数（否则"界面亮哪一份"这条判据无判别力）',
    `nation.maxMembers=${nationCap} alliance.maxMembers=${allianceCap}（都是现读的，没写死）`)

  // 练 7 只：界面那一相用 5，相④ 的两条边界各 1（上一相锁走的兵不会自己回来，练刚好会饿死后面那一相）
  const starterTroops = await equipAndTrain(starter, 'starter', 7)
  if (starterTroops === null) {
    prereq('starter 练不出兵：这一相之后要有真兵力才能发集结')
  }

  // ---------- 浏览器：先跑无权限那一相 ----------
  const preview = await startPreviewServer({ root: path.resolve('client/build/web-mobile'), backend: BACKEND, port: PORT })
  const browser = await chromium.launch({ headless: true })
  const requests = []
  const errors = []

  async function openSession(player) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
    const page = await context.newPage()
    page.on('pageerror', error => errors.push(String(error)))
    page.on('request', req => {
      if (req.url().includes('/rally/')) {
        requests.push(`${req.method()} ${new URL(req.url()).pathname}`)
      }
    })
    // 读侧夹具：客户端进编成只有"搜索结果里的行"这一条路（beginMarchCompose 只认 searchResp 的 id）。
    // 这一行是空地（不构成 PVP，见头注），写侧不打桩。
    makeStubRead(context)(/\/world\/searchTargets/, {
      targets: [{ id: 'T-plain', name: '野外怪', coord: { x: TARGET_X, y: TARGET_Y },
        matchPower: 100, powerRatio: 100, distanceBand: 'NEAR', resourceHint: 'NORMAL',
        isShielded: false, tyrannyLevel: null }],
      selfMatchPower: 100, lowerBound: 50, upperBound: 200, serverNow: Date.now(),
    })
    await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), player.deviceId)
    const boot = { value: null }
    page.on('console', (msg) => {
      const text = msg.text()
      if (text.startsWith('[boot] ')) {
        try { boot.value = JSON.parse(text.slice(7)) } catch { /* 非结构化那条不算数 */ }
      }
    })
    await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
    await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
      null, { timeout: 90_000 })
    await hideGuideOverlay(page)
    preview.assertRewritten()
    // [boot] 自检行是异步打出来的（素材加载完才轮到），立刻读会得到 null —— 那不是"没启动"，
    // 但也不能没有下限地等：等不到就退 2（照 verify-rally-runtime 的 45 秒档）。
    const bootDeadline = Date.now() + 45_000
    while (boot.value === null && Date.now() < bootDeadline) {
      await page.waitForTimeout(500)
    }
    if (boot.value === null) {
      throw new Error('等 45 秒没捕获到 [boot] 自检行，读数会是假的')
    }
    if (boot.value.started !== true || boot.value.missingPanels !== '') {
      throw new Error(`产物自检没过：boot=${JSON.stringify(boot.value)}`)
    }
    await page.waitForTimeout(1_000)
    return { context, page }
  }

  /** 把编成面板开到集结态（编排层入口，与玩家点搜索行的那一条同函数）。 */
  async function openComposeRally(page) {
    await page.evaluate(async () => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      const root = game.getComponent('GameBootstrap').root
      await root.refresh('army')
      await root.searchTargets(48)
      root.beginMarchCompose('T-plain')
      await root.toggleComposeRally()
    })
    await page.waitForTimeout(800)
  }

  // ----- 相②：无权限的那位，国家那颗键是灰的且零请求 -----
  {
    const { context, page } = await openSession(plain)
    await openComposeRally(page)
    let view = await page.evaluate(readComposeOverlay)
    // 只数**画出来的**那颗：chip 节点是三颗一起建的，层级行少一档时那顆是被 active=false 藏掉的 ——
    // 把隐藏的也算进"三档"，植入变异（删掉 rowOf('NATION')）就只红一条 label，判据等于半瞎（趟 B 实测）
    const drawn = view.chips.filter(c => c.active !== false)
    const scopeNames = drawn.map(c => c.scope)
    verdict(view.active === true && view.found === true, '编成弹层真的画出来了', `active=${view.active}`)
    verdict(scopeNames.includes('SQUAD') && scopeNames.includes('ALLIANCE') && scopeNames.includes('NATION'),
      '召集范围选择器有三档（V22-b 的第三档画到了屏上）', `屏上的档=${JSON.stringify(scopeNames)}`)
    const nationChip = drawn.find(c => c.scope === 'NATION')
    verdict(nationChip?.label === '国家', '第三档的文案是中文"国家"，不是枚举原文',
      `label="${nationChip?.label}"`)
    // 那颗键的"灰"在本仓的口径里 = **点下去不切层，并把服务端那句原因写到提示行**
    // （同一族既有注释写死了"置灰反而把原因一起藏了"，所以先点、再验屏上出现那句原话）。
    const beforeCount = requests.filter(r => r.includes('/rally/nation')).length
    await page.evaluate(() => {
      const band = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
        .getChildByName('MarchCompose').getChildByName('rallyBand')
      for (const child of band.children ?? []) {
        if (child.name === '层级-NATION') {
          child.emit('touch-start')
          return
        }
      }
    })
    await page.waitForTimeout(900)
    view = await page.evaluate(readComposeOverlay)
    verdict(view.texts.some(t => t === plainNation?.reason),
      '点下去之后屏上出现 /rally/policy 那句原话（理由只有一个出处，客户端不自己按职位判）',
      `服务端句="${plainNation?.reason}" 屏上文案数=${view.texts.length}`)
    const afterCount = requests.filter(r => r.includes('/rally/nation')).length
    verdict(afterCount === beforeCount,
      '点灰着的那一颗：一个 /rally/nation 请求都没发（真数个数，不只看按钮颜色）',
      `点击前=${beforeCount} 点击后=${afterCount} 全部集结请求=${JSON.stringify(requests)}`)
    view = await page.evaluate(readComposeOverlay)
    const goldAfterClick = view.chips.filter(c => c.active !== false)
      .find(c => c.scope === 'NATION')?.fill
    // 正向 + 否定一起判：只断言"不是金底"，那颗键根本不存在时也会通过（空转的假绿，同族第三处）
    verdict(Array.isArray(goldAfterClick) && !(goldAfterClick[0] > 150 && goldAfterClick[1] > 100),
      '国家那颗键画着、且点完仍不是选中态（选中=金底：184,134,11）', `fill=${JSON.stringify(goldAfterClick)}`)
    // 版式那两条放在相③（数字真画出来的那一屏）：这一相的表头节点是 active=false，
    // Cocos 不给隐藏节点排版 ⇒ contentSize 停在默认 100 宽，量出来的盒子是假的（实测踩到一次）。
    const hiddenBeforeShot = await hideCoveringPopups(page)
    verdict(hiddenBeforeShot === 'hidden' || hiddenBeforeShot === 'no-popup-node',
      '拍照前限时礼包弹窗已藏（全屏遮罩不藏就拍不到被测物）', `state=${hiddenBeforeShot}`)
    const shotNo = path.join(SHOT_DIR, 'nation-rally-grey.png')
    await page.screenshot({ path: shotNo })
    lines.push(`SHOT  ${shotNo}`)
    verdict(!view.texts.some(t => /pool_|unit_infantry|NATION_|OFFICER|hero_ssr/.test(t)),
      '这一屏不出现裸 id / 表键名', `文案=${JSON.stringify(view.texts.slice(0, 6))}`)
    await context.close()
  }

  // ----- 相③：有官职的那位真发一次国家层集结，且列表里看得见 -----
  let nationRallyId = null
  {
    const { context, page } = await openSession(starter)
    await openComposeRally(page)
    await page.evaluate(() => {
      const band = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
        .getChildByName('MarchCompose').getChildByName('rallyBand')
      for (const child of band.children ?? []) {
        if (child.name === '层级-NATION') {
          child.emit('touch-start')
          return
        }
      }
    })
    await page.waitForTimeout(900)
    let view = await page.evaluate(readComposeOverlay)
    const nationChip = view.chips.find(c => c.scope === 'NATION' && c.active !== false)
    verdict(Array.isArray(nationChip?.fill) && nationChip.fill[0] > 150 && nationChip.fill[1] > 100,
      '授官那位点得动国家档（切成选中态 = 金底）', `fill=${JSON.stringify(nationChip?.fill)}`)
    const peopleText = view.numbers.find(n => n.name === '数-0-数')?.text ?? ''
    verdict(peopleText.includes(`${nationCap}/${nationCap}人`),
      `屏上人数上界 = 政策里 nation 那一份（${nationCap}），不是联盟那一份（${allianceCap}）`,
      `屏上="${peopleText}"`)
    verdict(!view.numbers.some(n => n.text.includes(`${allianceCap}/${allianceCap}人`)),
      `反证：屏上不出现联盟那一份的 ${allianceCap}/${allianceCap}人（原 rallyPolicyOf 对非 SQUAD 一律回联盟，这一条就是它的靶）`,
      `画出来的数=${JSON.stringify(view.numbers.map(n => n.text))}`)
    // 版式（台账 #774 同一族）：三颗层级键不能压住同一行上的表头/数字/加减键。
    // 这一相才有真画出来的数字（隐藏节点不排版 ⇒ 盒子读数不可信，所以判据放在这里）。
    const settled = await settleBand(page)
    const boxesText = () => JSON.stringify(settled.view.bandBoxes.map(b => [b.name, Math.round(b.x), b.half]))
    verdict(settled.settled === true, '层级条上的文字已被 Cocos 量过（盒子读数是可信的）',
      `settled=${settled.settled} 盒子=${boxesText()}`)
    const chips = settled.view.bandBoxes.filter(b => b.name.startsWith('层级-'))
    const others = settled.view.bandBoxes.filter(b => !b.name.startsWith('层级-') && b.active !== false)
    const overlaps = []
    for (const chip of chips) {
      for (const other of others) {
        if (chip.x - chip.half < other.x + other.half && chip.x + chip.half > other.x - other.half) {
          overlaps.push(`${chip.name} × ${other.name}`)
        }
      }
    }
    verdict(overlaps.length === 0, '三颗层级键与同一行上的其它盒子互不相交',
      `相交=${JSON.stringify(overlaps)} 盒子=${boxesText()}`)
    // 这张是 V22-b 核心必修项（`rallyPolicyOf` 三分支）的**视觉**证据：国家档选中 + 屏上亮的是国家那一份数
    await hideCoveringPopups(page)
    const shotScope = path.join(SHOT_DIR, 'nation-rally-scope-nation.png')
    await page.screenshot({ path: shotScope })
    lines.push(`SHOT  ${shotScope}`)
    // 勾选兵力并确认
    await page.evaluate(() => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      game.getComponent('GameBootstrap').root.pickMarchUnit('unit_infantry_t1', 5)
    })
    const beforeWrite = requests.filter(r => r.includes('/rally/nation')).length
    await page.evaluate(() => {
      const overlay = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
        .getChildByName('MarchCompose')
      // 按节点名找那颗壳（页脚四颗键的名字是中文，不是 'confirm'）；监听挂在节点自身
      const walk = (node) => {
        if (node.name === '编成出征') {
          node.emit('touch-start')
          return true
        }
        for (const child of node.children ?? []) {
          if (walk(child)) return true
        }
        return false
      }
      return walk(overlay)
    })
    await page.waitForTimeout(2_500)
    const afterWrite = requests.filter(r => r.includes('/rally/nation')).length
    verdict(afterWrite > beforeWrite,
      '确认键真的把 POST /rally/nation 打到活后端（不是桩）', `请求=${JSON.stringify(requests.slice(beforeWrite))}`)
    // 成功回执：`confirmNationRally` 把 `已发起国家集结：X` 写进 composeNotice 的**同一帧**
    // 把 composeTarget 置空，弹层 render 见到空 target 直接 `node.active=false` 早退 ⇒ 那句写在
    // 一个已经关掉的层上。轮询 3 秒抓不到就是抓不到，但这是**三层共有的既有形状**（小队/联盟同），
    // 不是 V22-b 引入的 —— 已登记台账 #790，不判红（判红了下一次没人分得清是新缺陷还是旧账）。
    let receipt = null
    for (let i = 0; i < 15 && receipt === null; i += 1) {
      const snapshot = await page.evaluate(readComposeOverlay)
      receipt = snapshot.texts.find(t => /已发起国家集结/.test(t)) ?? null
      if (receipt === null) {
        await page.waitForTimeout(200)
      }
    }
    lines.push(`NOTE  成功回执${receipt === null ? '未上屏（面板同帧关闭，三层共有，见台账 #790）' : `上屏："${receipt}"`}`)
    await hideCoveringPopups(page)
    const shotSent = path.join(SHOT_DIR, 'nation-rally-compose.png')
    await page.screenshot({ path: shotSent })
    lines.push(`SHOT  ${shotSent}`)

    // 列表可见：preparingRallies 的国家那一支真的通到了界面
    const listed = await page.evaluate(async () => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      const root = game.getComponent('GameBootstrap').root
      await root.refresh('social')
      const panel = game.getChildByName('social')
      for (const child of panel?.children ?? []) {
        if (child.name === 'Tab_rally') {
          child.emit('touch-start')
          return true
        }
      }
      return false
    })
    await page.waitForTimeout(2_000)
    const social = await page.evaluate(() => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      const panel = game.getChildByName('social')
      const texts = []
      const labelOf = (node) => {
        const label = node.getComponent && node.getComponent('cc.Label')
        return label !== null && label !== undefined && label.string !== '' ? label.string : null
      }
      const walk = (node) => {
        const text = labelOf(node)
        if (text !== null) texts.push(text)
        for (const child of node.children ?? []) walk(child)
      }
      if (panel !== null && panel !== undefined) walk(panel)
      return texts
    })
    verdict(listed === true, '社交面板切到了「集结」页签（点了 Tab_rally）', `switched=${listed}`)
    verdict(social.some(t => /国家集结/.test(t)),
      '进行中的那一次在集结页签里标着「国家集结」（scope 没被静默降成联盟）',
      `屏上含"集结"的文案=${JSON.stringify(social.filter(t => /集结/.test(t)).slice(0, 5))}`)
    verdict(social.some(t => t.includes(`(${TARGET_X},${TARGET_Y})`) || t.includes(`${TARGET_X},${TARGET_Y}`)),
      '行的坐标来自那次真发起的目标格', `目标=(${TARGET_X},${TARGET_Y})`)
    const shotList = path.join(SHOT_DIR, 'nation-rally-list.png')
    await hideCoveringPopups(page)
    await page.screenshot({ path: shotList })
    lines.push(`SHOT  ${shotList}`)
    nationRallyId = await page.evaluate(() => {
      const game = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
      const root = game.getComponent('GameBootstrap').root
      const list = root.rallyResp?.rallies ?? []
      const mine = list.find(r => r.scope === 'NATION')
      return mine === undefined ? null : mine.rallyId
    })
    verdict(typeof nationRallyId === 'string' && nationRallyId.length > 0,
      '响应里那一支的 scope 就是 NATION（不是被折叠成 ALLIANCE）', `rallyId=${nationRallyId}`)
    await context.close()
  }

  // ----- 相④：界值两侧（现读 ±1，绝不写死 49/50） -----
  {
    const fresh = (await call('GET', '/rally/policy', undefined, starter)).data?.nation
    const cap = fresh?.maxMembers
    if (typeof cap !== 'number') {
      prereq('政策没给出国家层人数上界，边界两条无从计算')
    }
    const atCap = await call('POST', '/rally/nation', {
      requestId: rid('edge-cap'), targetCoord: { x: TARGET_X, y: TARGET_Y }, targetType: 'MONSTER',
      maxMembers: cap, prepareMinutes: fresh.minPrepareMinutes,
      troops: [{ unitId: T1, count: 1 }], heroes: [],
    }, starter)
    const overCap = await call('POST', '/rally/nation', {
      requestId: rid('edge-over'), targetCoord: { x: TARGET_X, y: TARGET_Y }, targetType: 'MONSTER',
      maxMembers: cap + 1, prepareMinutes: fresh.minPrepareMinutes,
      troops: [{ unitId: T1, count: 1 }], heroes: [],
    }, starter)
    verdict(atCap.code === 0 && atCap.data?.rally?.maxMembers === cap,
      `界内那一条（maxMembers = 现读的 ${cap}）：发得起且落的就是 ${cap}`,
      `code=${atCap.code} 落地=${atCap.data?.rally?.maxMembers ?? atCap.detail ?? atCap.msg}`)
    verdict(overCap.code === 0 && overCap.data?.rally?.maxMembers === cap,
      `越界那一条（${cap + 1}）：不拒绝而是夹回 ${cap}（协议注释的口径，也是"读口说什么=写口夹什么"）`,
      `code=${overCap.code} 落地=${overCap.data?.rally?.maxMembers ?? overCap.detail ?? overCap.msg}`)
    const denied = await call('POST', '/rally/nation', {
      requestId: rid('plain-deny'), targetCoord: { x: TARGET_X, y: TARGET_Y }, targetType: 'MONSTER',
      maxMembers: 2, prepareMinutes: 10, troops: [{ unitId: T1, count: 1 }], heroes: [],
    }, plain)
    verdict(denied.code !== 0,
      '普通国民从写口硬发也要被拒（灰键不是唯一那道闸，服务端不靠客户端自觉）',
      `code=${denied.code} ${denied.detail ?? denied.msg}`)
    for (const outcome of [atCap, overCap]) {
      const id = outcome.data?.rally?.rallyId
      if (typeof id === 'string') {
        await call('POST', '/rally/cancel', { requestId: rid('cancel'), rallyId: id }, starter)
      }
    }
    const stillThere = await call('GET', '/rally/list', undefined, starter)
    const caps = (stillThere.data?.rallies ?? []).map(r => r.scope)
    verdict(caps.includes('NATION'),
      '取消那两支之后，列表里仍留着界面那一次国家集结（preparingRallies 的第三支不是摆设）',
      `scopes=${JSON.stringify(caps)}`)
  }

  verdict(errors.length === 0, '全程零页面异常',
    `errors=${errors.length}${errors.length > 0 ? ' → ' + errors[0] : ''}`)

  await browser.close()
  await preview.close()

  console.log('\n[nation-rally] 判定：')
  for (const line of lines) console.log(`  ${line}`)
  if (failures.length > 0) {
    console.error(`\n[nation-rally] ${failures.length} 条判据失败：${failures.join('；')}`)
    process.exit(1)
  }
  console.log('\n[nation-rally] 全部判据通过。截图见上面 SHOT 行。')
  process.exit(0)
}

main().catch((error) => {
  console.error('[nation-rally] 探针自身崩了（不是判据失败）:', error)
  process.exit(2)
})
