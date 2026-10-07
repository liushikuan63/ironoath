#!/usr/bin/env node
/**
 * 真链路打真仗：宣战 → 出兵攻城 → 结算 → 击杀进国战账（B13 承载 3h 验收的"有仗态"那一维）。
 *
 * <p><b>这一份在补什么</b>：国战击杀的入账漏斗（`BattleReportService.recordKills`）此前没有任何
 * 探针护栏 —— `verify-march-runtime.mjs` 全打桩、`verify-nation-live.mjs` 只到"双国+宣战"，
 * 攻城那一段是"判定写了没接上"最爱的藏身处。本探针走**完整真实链路**，一步都不注入假数据：
 * 真练兵（POST /army/train，等待真完成）、真行军（POST /world/march）、真结算
 * （GET /world/marches 跨过 arriveAt 的那次读触发）、再回读国战账与 WAR 榜；末段含 #769 相位：
 * 连发 SCOUT 到顶（每次 +5）后，**新发起必须被 13026 拒**（验收 7 的服务端执行者）。
 *
 * <p><b>为什么不是"作弊发兵"</b>：本仓把"给探针塞兵"划成作弊端点（那会让"账目正确"变成
 * 兵不需要练、仗不需要打的假承诺）。这里所有兵力都经兵营训练生产出来 —— 慢，但真的。
 *
 * <p><b>前置（dev 后端，内存存储；时间加速档把等待压到秒级）</b>：
 * <pre>
 *   IRONOATH_DEV_CITY_LEVEL=16 IRONOATH_DEV_START_AMOUNT=2000000 IRONOATH_DEV_TIME_SPEED=100 \
 *     SERVER_PORT=8299 nohup bash scripts/dev.sh > /tmp/war-real-backend.log 2>&1 &
 *   BACKEND_ORIGIN=http://localhost:8299 node tools/verify-war-real-battle.mjs
 * </pre>
 * 时间倍速由 `DevClockSpeed`（@Profile("dev")）从环境变量读：练兵 60s×数量、行军、国战 3h 窗口
 * 全部走同一套 serverNow，没有第二个时间源。倍速太低（<50×）时本探针退 2 并说明 —— 那是量具
 * 没架对，不是功能坏了。倍速过高也不行：国战窗口 WAR_DURATION_HOURS=3 会被压到几秒，
 * 出战、行军还没落地仗就过期了（记录会被 EXPIRED 挡住）。
 *
 * <p><b>退出码</b>：0 = 全过；1 = 有断言失败（逐条打印）；2 = 前置不满足（后端/提速档/倍速）。
 *
 * <p><b>重复跑</b>：dev 是内存存储、跨轮不清，每轮用新号（deviceId 带时间戳），并尽量在收尾
 * 解散两国（有活跃战事时解散会被拒 —— 那是预期形状之一，只提示不判错；名额占满时重启后端即清）。
 */
import path from 'node:path'
import process from 'node:process'

const BACKEND = process.env.BACKEND_ORIGIN ?? 'http://localhost:8299'
const T1 = 'unit_infantry_t1'
/** 练兵量：B 守 100、A 攻 160（A/B ≈1.6×，同时在战力圈层的匹配带上）—— 期望击杀 ≥15。 */
const B_TROOPS = 100
const A_TROOPS = 160
/** 期望击杀下限：与 WAR 榜的最小上榜击杀（global.WAR_SEASON_POINT_MIN_KILLS=10）同一条线。 */
const MIN_KILLS = 10
/** 可接受的最低时间倍速：低于它本探针的等待会超过 5 分钟。 */
const MIN_SPEED = 50

let pass = 0
let fail = 0
let seq = 0
const ok = (m) => { pass += 1; console.log(`  PASS  ${m}`) }
const bad = (m) => { fail += 1; console.log(`  FAIL  ${m}`) }
const check = (m, actual, expected) => {
  if (actual === expected) ok(`${m}（${actual}）`)
  else bad(`${m}：期望 ${expected}，实际 ${actual}`)
}
const checkThat = (m, actual) => {
  if (actual) ok(m)
  else bad(m)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 一次真请求。返回 `{ http, code, data, detail, msg }`，**不抛**：业务拒绝也是读数。 */
async function call(method, url, body, player, extraHeaders) {
  const headers = { 'content-type': 'application/json' }
  if (extraHeaders !== undefined) Object.assign(headers, extraHeaders)
  if (player !== undefined) {
    headers['X-Player-Id'] = player.playerId
    headers.authorization = `Bearer ${player.token}`
  }
  let response
  try {
    response = await fetch(`${BACKEND}${url}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (e) {
    return { http: 0, code: -1, data: null, detail: String(e?.cause ?? e).slice(0, 160), msg: 'connect-failed' }
  }
  const text = await response.text()
  let parsed = null
  try { parsed = JSON.parse(text) } catch { parsed = null }
  return {
    http: response.status,
    code: parsed?.code ?? -1,
    data: parsed?.data ?? null,
    detail: parsed?.detail ?? null,
    msg: parsed?.msg ?? text.slice(0, 200),
  }
}

const rid = (tag) => `war-real-${tag}-${Date.now()}-${++seq}`

/** 建号（或登录）。返回 { playerId, token, deviceId, cityLevel }。 */
async function initPlayer(nick) {
  const deviceId = `war-real-${nick}-${Date.now()}-${++seq}`
  const outcome = await call('POST', '/player/init', {
    requestId: rid('init'), deviceId, nickName: nick, avatarId: 1, clientTime: Date.now(),
  })
  if (outcome.code !== 0) {
    console.error(`[war-real] 建号失败：${outcome.code} ${outcome.msg} ${outcome.detail ?? ''}`)
    return null
  }
  return { playerId: outcome.data.playerId, token: outcome.data.authToken, deviceId,
    cityLevel: outcome.data.cityLevel }
}

/** 轮询直到 fn 返回真值；超时返回 null。 */
async function waitUntil(fn, timeoutMs, intervalMs = 2000, label = '') {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const value = await fn()
    if (value) return value
    if (Date.now() > deadline) {
      if (label) console.log(`  （等待超时：${label}）`)
      return null
    }
    await sleep(intervalMs)
  }
}

console.log(`  后端：${BACKEND}`)
console.log(`  用途：真练兵 / 真行军 / 真结算 → 击杀进国战账（不做发兵作弊）`)

const exitCode = await (async () => {

// ---------- 0. 前置：时钟倍速（量具架没架对，先于一切业务断言） ----------
const t0 = Date.now()
const s0 = await call('POST', '/time/sync', { clientTime: t0 })
if (s0.code !== 0) {
  console.error(`[war-real] 后端不可达或 /time/sync 失败（http=${s0.http} code=${s0.code} detail=${s0.detail ?? s0.msg}）`)
  console.error(`  先起 dev 后端：见本文件头注的启动命令（端口要与 BACKEND_ORIGIN 一致）`)
  return 2
}
await sleep(900)
const t1 = Date.now()
const s1 = await call('POST', '/time/sync', { clientTime: t1 })
const virtualDelta = (s1.data?.sync?.syncAt ?? 0) - (s0.data?.sync?.syncAt ?? 0)
const measuredSpeed = Math.round(virtualDelta / Math.max(1, t1 - t0))
checkThat(`时钟倍速可测（实测 ≈${measuredSpeed}×）`, measuredSpeed >= MIN_SPEED)
if (measuredSpeed < MIN_SPEED) {
  console.error(`[war-real] 倍速不足（实测 ${measuredSpeed}× < ${MIN_SPEED}×）：请带 IRONOATH_DEV_TIME_SPEED=100 重启后端 —— 这是量具没架对，不是功能坏了`)
  return 2
}

// ---------- 1. 前置：两号 + dev 提速档 ----------
const attacker = await initPlayer('warbattle-a')
if (attacker === null) return 2
const defender = await initPlayer('warbattle-b')
if (defender === null) return 2
check('攻方新号主城等级 = 16（dev 提速档生效）', attacker.cityLevel, 16)
check('守方新号主城等级 = 16（dev 提速档生效）', defender.cityLevel, 16)
if (attacker.cityLevel !== 16 || defender.cityLevel !== 16) {
  console.error('  提速档没生效：请用 IRONOATH_DEV_CITY_LEVEL=16 IRONOATH_DEV_START_AMOUNT=2000000 重启后端')
  return 2
}
console.log(`  攻方号：${attacker.playerId}｜守方号：${defender.playerId}`)
// 供"WAR 榜有行的客户端截图"登录同一个号：客户端按 deviceId 建/登号，注入错就是别人的空榜。
console.log(`  攻方 deviceId：${attacker.deviceId}`)

// ---------- 1.5 编队：补发武将 + 上阵（新号带兵上限=0，不上阵就练不了兵） ----------
// 兵力一律真训练（本探针不做发兵作弊）；这里补的是"账号标准配置"——与 verify-army-queue.mjs
// 同一条通路：仓库唯一的凭空发奖励口 /ops/mail/send（type=HERO，dev 后端限定），再领取、上阵。
const OPS_TOKEN = process.env.WAR_REAL_OPS_TOKEN ?? 'art-verify-local'
const HERO_ID = process.env.WAR_REAL_HERO ?? 'hero_ssr_01'
async function equipHero(player, nick) {
  const mail = await call('POST', '/ops/mail/send', {
    requestId: rid('hero-mail'), playerId: player.playerId, title: '国战真链路探针用武将',
    text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-war-real-battle',
    rewards: [{ type: 'HERO', id: HERO_ID, count: 1, name: HERO_ID }],
  }, undefined, { 'X-Ops-Token': OPS_TOKEN })
  check(`${nick} 补发武将被受理（${mail.detail ?? mail.msg}）`, mail.code, 0)
  const claimed = await call('POST', '/mail/claimAll', { requestId: rid('claim') }, player)
  check(`${nick} 领取武将邮件`, claimed.code, 0)
  const lineup = await call('POST', '/hero/lineup', {
    requestId: rid('lineup'), presetIndex: 0, main: HERO_ID, sub1: null, sub2: null,
  }, player)
  check(`${nick} 上阵 ${HERO_ID}（带兵上限=${lineup.data?.troopCap ?? '-'}）`, lineup.code, 0)
  return lineup.data?.troopCap ?? 0
}
const capHeroA = await equipHero(attacker, '攻方')
const capHeroB = await equipHero(defender, '守方')
checkThat('上阵后两号带兵上限 > 0', capHeroA > 0 && capHeroB > 0)

// ---------- 2. 城建兵营（A、B 各自一座：练兵的前置） ----------
async function placeBarracks(player, nick) {
  const cells = [[3, 3], [4, 4], [2, 2], [5, 5], [0, 0], [6, 6], [7, 3], [3, 7]]
  let last = null
  for (const [gridX, gridY] of cells) {
    last = await call('POST', '/city/upgrade', { requestId: rid('barracks'), configId: 'barracks', gridX, gridY }, player)
    if (last.code === 0) { ok(`${nick} 兵营开建（grid ${gridX},${gridY}）`); return true }
  }
  bad(`${nick} 兵营放不下（最后一试 ${last.code} ${last.msg} ${last.detail ?? ''}）`)
  return false
}
async function waitTrainable(player, nick) {
  const done = await waitUntil(async () => {
    const list = await call('GET', '/army/list', undefined, player)
    if (list.code !== 0) return null
    const unit = (list.data?.units ?? []).find((u) => u.unitId === T1)
    return unit?.unlocked === true ? list.data : null
  }, 90_000, 2000, `${nick} 兵营建完（T1 解锁）`)
  return done
}
const barracksA = await placeBarracks(attacker, '攻方')
const barracksB = await placeBarracks(defender, '守方')
const listA = barracksA ? await waitTrainable(attacker, '攻方') : null
const listB = barracksB ? await waitTrainable(defender, '守方') : null
checkThat('攻方 T1 已解锁（兵营建完）', listA !== null)
checkThat('守方 T1 已解锁（兵营建完）', listB !== null)
console.log(`  带兵上限：攻方 ${listA?.troopCap ?? '-'}｜守方 ${listB?.troopCap ?? '-'}`)

// 带兵上限不足时按上限缩量（仍保持 A ≥ B），并在读数里说清用了多少。
const cap = Math.min(listA?.troopCap ?? 0, listB?.troopCap ?? 0)
const bCount = Math.min(B_TROOPS, Math.max(1, Math.floor(cap / 2)))
const aCount = Math.min(A_TROOPS, Math.max(bCount, cap))
checkThat(`两号可练 ${aCount}/${bCount} 兵（cap=${cap}，上限不足会缩量）`, cap >= 20)

// ---------- 3. 练兵（真扣资源、真占队列、真等完成） ----------
async function train(player, nick, count) {
  const outcome = await call('POST', '/army/train', { requestId: rid('train'), unitId: T1, count }, player)
  check(`${nick} 练兵下单 ${count} 只（${outcome.detail ?? outcome.msg}）`, outcome.code, 0)
  return outcome.code === 0
}
const trainA = await train(attacker, '攻方', aCount)
const trainB = await train(defender, '守方', bCount)
async function waitTrained(player, nick, want) {
  return await waitUntil(async () => {
    const list = await call('GET', '/army/list', undefined, player)
    if (list.code !== 0) return null
    const unit = (list.data?.units ?? []).find((u) => u.unitId === T1)
    return (unit?.count ?? 0) >= want ? unit.count : null
  }, 300_000, 3000, `${nick} 训练到 ${want}（count 现看）`)
}
const gotA = trainA ? await waitTrained(attacker, '攻方', aCount) : null
const gotB = trainB ? await waitTrained(defender, '守方', bCount) : null
checkThat(`攻方兵力到齐（读到 ${gotA ?? '超时'}，目标 ${aCount}）`, gotA !== null)
checkThat(`守方兵力到齐（读到 ${gotB ?? '超时'}，目标 ${bCount}）`, gotB !== null)

// ---------- 4. 建国（两国；击杀要记进国战账，双方都得在国里） ----------
const runTag = `${Date.now() % 1000000}`
async function foundNation(player, nick, tagPrefix, capitalX, capitalY) {
  // tag（≤4 字符）尾数只有 1000 组，而 dev 内存跨轮不清（国家收尾会解散，**联盟不会**）
  // ⇒ 重复跑必然撞 10016。撞了就换尾数重试——别把"上一轮的联盟还在"当成功能坏了。
  let alliance = null
  for (let attempt = 0; attempt < 5; attempt++) {
    const tag = `${tagPrefix}${String((Number(runTag) + attempt * 137) % 1000).padStart(3, '0')}`
    alliance = await call('POST', '/alliance/create', {
      requestId: rid('alliance'), name: `实战盟${nick}${runTag}${attempt}`, tag,
    }, player)
    if (alliance.code === 0 || alliance.code !== 10016) break
  }
  check(`${nick} 建盟成功（${alliance.detail ?? alliance.msg}）`, alliance.code, 0)
  const found = await call('POST', '/nation/found', {
    requestId: rid('found'), name: `实战国${nick}${runTag}`, capitalX, capitalY,
  }, player)
  check(`${nick} 建国成功（${found.detail ?? found.msg}）`, found.code, 0)
  return found.data?.nation?.nationId ?? null
}
const nationA = await foundNation(attacker, '攻', 'A', 300 + (runTag % 40), 300 + (runTag % 37))
const nationB = await foundNation(defender, '守', 'B', 380 + (runTag % 40), 380 + (runTag % 37))
checkThat('两国 id 都拿到', nationA !== null && nationB !== null)
if (nationA === null || nationB === null) {
  console.error('[war-real] 建国没成，后续（宣战/记账）无从验；先看上面 detail')
  console.log(`\n=== 真链路打真仗：${pass} 通过 / ${fail} 失败 ===`)
  return 1
}

// ---------- 5. 宣战（攻方国王；NEUTRAL 即可宣） ----------
const declares = await call('POST', '/nation/war/declare', {
  requestId: rid('declare'), targetNationId: nationB,
}, attacker)
check(`宣战成功（${declares.detail ?? declares.msg}）`, declares.code, 0)
const warBefore = await call('GET', '/nation/war', undefined, attacker)
check('宣战后 /nation/war hasWar=true', warBefore.data?.hasWar, true)
const sides = warBefore.data?.scores ?? []
checkThat('参战方登记了攻方本国', sides.some((s) => s.nationId === nationA))
checkThat('参战方登记了守方本国', sides.some((s) => s.nationId === nationB))
const killsBase = warBefore.data?.totalKills ?? -1

// ---------- 6. 出兵攻城（目标 = 守方家坐标；读到 arriveAt 后跨过它即结算） ----------
const bHome = await call('GET', '/world/marches', undefined, defender)
const home = bHome.data?.home ?? null
checkThat('守方家坐标可读（GET /world/marches.home）', home !== null && Number.isInteger(home.x))

const march = await call('POST', '/world/march', {
  requestId: rid('march'), toX: home.x, toY: home.y,
  units: [{ unitId: T1, count: aCount }], action: 'ATTACK',
}, attacker)
check(`出兵成功（${march.detail ?? march.msg}）`, march.code, 0)
const marchId = march.data?.march?.marchId ?? null
checkThat('响应带回 marchId', marchId !== null)
checkThat(`行军时长由服务端算（distance=${march.data?.distance} 格, ${march.data?.durationSec}s）`,
  (march.data?.durationSec ?? 0) >= 1)

/** 结算 = 那支行军不再是 MARCHING（到达那一读触发战斗）。 */
const arrived = marchId === null ? null : await waitUntil(async () => {
  const list = await call('GET', '/world/marches', undefined, attacker)
  const mine = (list.data?.marches ?? []).find((m) => m.marchId === marchId)
  return !mine || mine.status !== 'MARCHING' ? (mine?.status ?? 'GONE') : null
}, 180_000, 1500, '行军到达并结算')
checkThat(`行军已到达并结算（末态 ${arrived ?? '超时'}）`, arrived !== null)

// ---------- 7. 账本断言：击杀进国战账 + 疲劳 + WAR 榜 ----------
let killsAfter = -1
let fatigueA = -1
const ledgerOk = await waitUntil(async () => {
  const war = await call('GET', '/nation/war', undefined, attacker)
  if (war.code !== 0) return null
  killsAfter = war.data?.totalKills ?? -1
  fatigueA = war.data?.myFatigue ?? -1
  return killsAfter - killsBase >= MIN_KILLS ? true : null
}, 30_000, 2000, `总击杀从 ${killsBase} 至少 +${MIN_KILLS}`)
checkThat(`战后全服击杀入账（${killsBase} → ${killsAfter}，增量 ${killsAfter - killsBase} ≥ ${MIN_KILLS}）`, ledgerOk === true)
checkThat(`攻方疲劳已累积（myFatigue=${fatigueA} ≥ 1）`, fatigueA >= 1)

// WAR 榜是"国战结算那一刻"才把个人击杀分写进去的（3b-1 的 reportWarSeasonPoints 挂在这里：
// WarAppService.warStatus → settleIfExpired → settledNow 才进账，见该文件 :132-138）。
// ⇒ 等这一场打满 3 小时窗口（WAR_DURATION_HOURS=3，倍速 100× 下 ≈110 秒真实时间）。
// ⚠️ 结算后 hasWar 仍是 true、只有 phase 变 SETTLED（仗还"在库里"）—— 判据必须是 phase。
const warEnded = await waitUntil(async () => {
  const war = await call('GET', '/nation/war', undefined, attacker)
  return war.code === 0 && (war.data?.phase === 'SETTLED' || war.data?.hasWar === false) ? true : null
}, 360_000, 4000, '国战打满 3 小时窗口并结算')
checkThat('国战已结算（phase=SETTLED）', warEnded === true)

let warRank = null
const onBoard = await waitUntil(async () => {
  warRank = await call('GET', '/rank/list?type=WAR&page=1&size=50', undefined, attacker)
  return (warRank.data?.myRank ?? null) !== null ? true : null
}, 30_000, 3000, 'WAR 榜出现我的行（结算后）')
check('WAR 榜可读', warRank?.code, 0)
checkThat(`攻方已进 WAR 榜（myRank=${warRank?.data?.myRank}，myValue=${warRank?.data?.myValue}）`,
  onBoard === true && (warRank?.data?.myValue ?? 0) >= MIN_KILLS)

// 战报可读（内容口径随协议演进，这里只钉"读得到"这一层；击杀的权威读数在上面的国战账）
const reports = await call('GET', '/battle/reports', undefined, attacker)
check('战报列表可读（GET /battle/reports）', reports.code, 0)

// ---------- 8. 404 对照组（证明"能区分不存在"） ----------
const missing = await call('GET', `/definitely-not-a-route-${runTag}`)
check('对照组：不存在的路径回 404', missing.http, 404)

// ---------- 8.5 到顶拒发起（#769：验收 7「超过上限后无法继续行军」的服务端执行者） ----------
// ⚠️ 前置认知（本相位第一版实测踩到）：**结算后疲劳不再累积** —— addFatigue 对已 SETTLED 的板子
// 记 NO_ACTIVE_WAR（日志实测），疲劳停在战后值、永远到不了顶。所以本相位先对**新第三国**重开
// 一场活跃战事（旧对手有 24h 宣战冷却，第三国没有交手史）。然后连发 SCOUT 到空地（每次 +5），
// 到顶（myFatigue >= fatigueMax）后的下一次发起必须被 13026（WAR_FATIGUE_MAX_REACHED）拒。
// 兵：SCOUT 到达自动返程、到家归还（#768 修复的形状），少量兵即可周转；战损后不足先补练。
const WAR_FATIGUE_MAX_REACHED = 13026   // 出处：ErrorCode.java（探针不引 Java 枚举）
let capRival = null
{
  const c = await initPlayer('warcap-c')
  if (c === null) {
    bad('到顶相前置：第三国号建不出来')
  } else {
    const cAlliance = await call('POST', '/alliance/create', {
      requestId: rid('cap-alliance'), name: `到顶相国${runTag}`, tag: `FC${runTag.slice(-2)}`,
    }, c)
    const cFound = cAlliance.code === 0 ? await call('POST', '/nation/found', {
      requestId: rid('cap-found'), name: `到顶相国${runTag}`, capitalX: 430 + (runTag % 30), capitalY: 430 + (runTag % 27),
    }, c) : { code: cAlliance.code, detail: cAlliance.detail ?? cAlliance.msg }
    checkThat(`到顶相前置：第三国建成（盟 ${cAlliance.code} / 国 ${cFound.code}）`, cFound.code === 0)
    if (cFound.code === 0) {
      capRival = c
      const reDeclare = await call('POST', '/nation/war/declare', {
        requestId: rid('cap-declare'), targetNationId: cFound.data?.nation?.nationId,
      }, attacker)
      check(`到顶相前置：对第三国重开一场活跃战事（${reDeclare.detail ?? reDeclare.msg}）`, reDeclare.code, 0)
    }
  }
}
const capHome = (await call('GET', '/world/marches', undefined, attacker)).data?.home ?? null
checkThat('到顶相前置：攻方家坐标可读', capHome !== null && Number.isInteger(capHome.x))
if (capHome !== null && capRival !== null) {
  const armyNow = await call('GET', '/army/list', undefined, attacker)
  const haveNow = (armyNow.data?.units ?? []).find((u) => u.unitId === T1)?.count ?? 0
  if (haveNow < 6) {
    const need = 6 - haveNow
    const retrain = await call('POST', '/army/train', { requestId: rid('cap-train'), unitId: T1, count: need }, attacker)
    check(`到顶相：补练 ${need} 兵下单（战损后周转）`, retrain.code, 0)
    await waitUntil(async () => {
      const list = await call('GET', '/army/list', undefined, attacker)
      const unit = (list.data?.units ?? []).find((u) => u.unitId === T1)
      return (unit?.count ?? 0) >= 6 ? unit.count : null
    }, 300_000, 3000, '到顶相：补练到齐')
  }
  let capVerdict = null
  for (let round = 1; round <= 30; round++) {
    const war = await call('GET', '/nation/war', undefined, attacker)
    const fatigue = war.data?.myFatigue ?? -1
    const fatigueMax = war.data?.fatigueMax ?? -1
    const shot = await call('POST', '/world/march', {
      requestId: rid('cap-march'), toX: capHome.x + 2, toY: capHome.y + 2,
      units: [{ unitId: T1, count: 1 }], action: 'SCOUT',
    }, attacker)
    if (fatigue >= fatigueMax && fatigueMax > 0) {
      capVerdict = { round, code: shot.code, detail: shot.detail ?? shot.msg, fatigue, fatigueMax }
      break
    }
    console.log(`    （到顶相）第 ${round} 次：code=${shot.code} 疲劳(发前)=${fatigue}${shot.code !== 0 ? ` ${shot.detail ?? shot.msg}` : ''}`)
    if (shot.code !== 0) break   // 别的条件先挡（队列/兵等）：不硬判——下面的作废结论会照实说
    await sleep(900)
  }
  if (capVerdict === null) {
    bad('到顶相：30 轮内没取到判据（疲劳没到顶或别的条件先挡——上面逐轮读数与服务端日志可查）')
  } else {
    checkThat(`到顶相：疲劳 ${capVerdict.fatigue}/${capVerdict.fatigueMax} 时第 ${capVerdict.round} 次发起被拒`
      + `（code=${capVerdict.code} ${capVerdict.detail}）`, capVerdict.code === WAR_FATIGUE_MAX_REACHED)
  }
}

// ---------- 9. 收尾（尽力而为，不判错）：解散两国，给下一轮让名额 ----------
for (const [player, nick] of [[attacker, '攻方'], [defender, '守方'], [capRival, '丙方（到顶相第三国）']]) {
  if (player === null || player === undefined) continue
  const disband = await call('POST', '/nation/disband', { requestId: rid('disband') }, player)
  console.log(`  （收尾）${nick} 解散：code=${disband.code} ${disband.detail ?? disband.msg}${disband.code !== 0 ? '（有活跃战事时被拒是预期形状之一）' : ''}`)
}

console.log(`\n=== 真链路打真仗（宣战 → 出兵 → 结算 → 击杀进账）：${pass} 通过 / ${fail} 失败 ===`)
return fail > 0 ? 1 : 0
})()

// 刻意不调 process.exit：本机 Node(win) 下 exit 与此前 fetch 的 keep-alive 收尾赛跑会触发
// uv_async 断言崩溃（真实退出码变 127，把"前置退 2"读成另一种东西）。自然退出 + exitCode 实测 0.1s 干净收场。
process.exitCode = exitCode
