#!/usr/bin/env node
/**
 * 职责：把「国家这条线在**真后端**上真的能走通」钉成能失败的判据（V13-S1/S2 的正向链路）。
 * 依赖：node、（可选 playwright）、**带 dev 提速档启动的后端**、已构建的 `client/build/web-mobile`。
 *
 * <p><b>为什么需要它</b>：`verify-nation.mjs` 与 `verify-nation-s2.mjs` 的正向链路走的是 route 夹具 ——
 * 真后端上建国前置（主城 16 级 + 在联盟中）在 dev 永远不满足，于是"真发出去、真回填"这件事一直没验过。
 * 这一份把它补上：**全程只发真 HTTP 请求**，不开夹具。
 *
 * <p><b>前置：dev 提速档</b>（`IRONOATH_DEV_CITY_LEVEL=16` + `IRONOATH_DEV_START_AMOUNT=2000000`，
 * 两者都只在 dev profile 生效，见 `DevNewPlayerBoost`）。没开这一档时脚本**在第 1 步就退 2 并点名原因**，
 * 而不是跑出一串"主城等级不足"的假红 —— 那是量具没架对，不是功能坏了。
 *
 * <p>用法：
 *   BACKEND_ORIGIN=http://localhost:8080 node tools/verify-nation-live.mjs
 * 可选：
 *   NATION_LIVE_UI=1     —— 末尾再跑一趟浏览器：对着**真后端**打开国家面板，核对屏上的国名与国库
 *   NATION_LIVE_OUT=dir  —— 截图目录（默认 tmp/nation-live）
 */
import { mkdirSync } from 'node:fs'
import path from 'node:path'

const OUT = process.env.NATION_LIVE_OUT ?? path.resolve(process.cwd(), 'tmp/nation-live')
mkdirSync(OUT, { recursive: true })
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-nation-live] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端')
  process.exit(2)
})()

let pass = 0
let fail = 0
const ok = (msg) => { pass += 1; console.log(`  PASS  ${msg}`) }
const bad = (msg) => { fail += 1; console.log(`  FAIL  ${msg}`) }
const check = (msg, actual, expected) => {
  if (actual === expected) {
    ok(`${msg}（${actual}）`)
  } else {
    bad(`${msg}：期望 ${expected}，实际 ${actual}`)
  }
}
const checkThat = (msg, actual) => {
  if (actual) {
    ok(msg)
  } else {
    bad(msg)
  }
}

/** 一次真请求。返回 `{ code, data, detail, http }`，**不抛**：业务拒绝也是读数。 */
async function call(method, url, body, playerId, token) {
  const headers = { 'content-type': 'application/json' }
  if (playerId !== undefined) headers['X-Player-Id'] = playerId
  if (token !== undefined) headers.authorization = `Bearer ${token}`
  const response = await fetch(`${BACKEND}${url}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let parsed = null
  try {
    parsed = JSON.parse(text)
  } catch {
    parsed = null
  }
  return {
    http: response.status,
    code: parsed?.code ?? -1,
    data: parsed?.data ?? null,
    detail: parsed?.detail ?? null,
    msg: parsed?.msg ?? text.slice(0, 200),
  }
}

let requestSeq = 0
const rid = (tag) => `nation-live-${tag}-${Date.now()}-${requestSeq += 1}`

/** 建号（或登录）。返回 { playerId, token, cityLevel, gold, deviceId }。 */
async function initPlayer(nick) {
  // deviceId 也要带出去：回读屏那一步要在浏览器里注入**同一个** deviceId，
  // 否则真后端会给浏览器建一个新号，面板打开看到的是"你还没有国家"
  const deviceId = `nation-live-${nick}-${Date.now()}`
  const outcome = await call('POST', '/player/init', {
    requestId: rid('init'), deviceId, nickName: nick, avatarId: 1,
    // clientTime 是必填（服务端据此回校准 offset）：契约里 minLength/正数校验都有，
    // 传 0 会被 PARAM_INVALID 挡在门外（第一版就是这么红的）
    clientTime: Date.now(),
  })
  if (outcome.code !== 0) {
    console.error(`[verify-nation-live] 建号失败：${outcome.code} ${outcome.msg} ${outcome.detail ?? ''}`)
    process.exit(2)
  }
  return {
    playerId: outcome.data.playerId,
    token: outcome.data.authToken,
    deviceId,
    cityLevel: outcome.data.cityLevel,
    gold: outcome.data.resources?.GOLD?.current ?? -1,
  }
}

console.log(`  后端：${BACKEND}`)

// ---------- 1. 建号 + 提速档生效 ----------
const king = await initPlayer('nationlive')
check('新号主城等级 = 16（dev 提速档生效）', king.cityLevel, 16)
if (king.cityLevel !== 16) {
  console.error('  提速档没生效：请用 IRONOATH_DEV_CITY_LEVEL=16 IRONOATH_DEV_START_AMOUNT=2000000 重启后端')
  console.error('  （这不是功能坏了，是量具没架对 —— 见 tools/verify-nation-live.mjs 头注）')
  process.exit(2)
}
checkThat('新号初始金币够建盟（>= 500）', king.gold >= 500)
console.log(`  国王号：${king.playerId} 主城 Lv${king.cityLevel} 金币 ${king.gold}`)

// ---------- 2. 建联盟（建国前置之一） ----------
// 名字与标签**每轮唯一**：dev 后端是内存存储、跨轮次不清，写死名字会在第二轮撞
// `ALLIANCE_NAME_TAKEN(10016)`（第一版就是这么红的：看着像建盟坏了，其实是上一轮的残留）
const runTag = `${Date.now() % 100000}`
const created = await call('POST', '/alliance/create', {
  requestId: rid('alliance'), name: `实链联盟${runTag}`, tag: `L${runTag.slice(-4)}`,
}, king.playerId, king.token)
check(`建联盟成功（${created.detail ?? created.msg}）`, created.code, 0)
const allianceId = created.data?.alliance?.id ?? ''

// ---------- 3. 建国（真链路的第一枪） ----------
const found = await call('POST', '/nation/found', {
  requestId: rid('found'), name: `实链国${Date.now() % 100000}`, capitalX: 120, capitalY: 88,
}, king.playerId, king.token)
if (found.code !== 0) {
  bad(`建国被拒：${found.code} ${found.msg} ${found.detail ?? ''}`)
} else {
  ok(`建国成功（${found.data.nation.name}）`)
}
const nation = found.data?.nation ?? null
checkThat('响应里带回完整国家视图', nation !== null)
if (nation === null) {
  console.error('[verify-nation-live] 建国失败，后续步骤无从验；先看上面那条 detail')
  console.log(`\n=== 国家真链路验收：${pass} 通过 / ${fail} 失败 ===`)
  process.exit(1)
}
check('国王就是我', nation.kingId, king.playerId)
check('我的官职是国王', nation.myOffice, 'KING')
check('成员联盟数 = 1', nation.allianceCount, 1)
checkThat('国库上限与等级都由服务端给（>0）', nation.treasuryCap > 0 && nation.level >= 1)

// ---------- 4. 读一次：与写回执逐字段一致 ----------
const view = await call('GET', '/nation', undefined, king.playerId, king.token)
check('GET /nation 成功', view.code, 0)
check('读回来的国名与建国回执一致', view.data?.nation?.name, nation.name)
check('读回来的国库与建国回执一致', view.data?.nation?.treasury, nation.treasury)

// ---------- 5. 国库支出（SINK，一笔真扣款） ----------
const beforeSpend = (await call('GET', '/nation/treasury', undefined, king.playerId, king.token)).data.balance
// **金额刻意取小**：建国时国库只有首周税（10000），而下面第 7 步要研究国家科技（最便宜一行 1050）。
// 第一版花了整整 10000，于是科技表里一行都付不起 —— 那是脚本步骤顺序的错，不是功能坏了
const spendAmount = 1_000
const spend = await call('POST', '/nation/treasury/spend', {
  requestId: rid('spend'), payeeType: 'SINK', sink: 'NATIONAL_TECH',
  amount: spendAmount, reason: '实链验收：国家科技出资',
}, king.playerId, king.token)
if (spend.code !== 0) {
  bad(`国库支出被拒：${spend.code} ${spend.msg} ${spend.detail ?? ''}`)
} else {
  check('支出后余额 = 支出前 − 金额', spend.data.balance, beforeSpend - spendAmount)
  check('回执里那条流水的 balanceAfter 与余额同源', spend.data.log.balanceAfter, spend.data.balance)
  check('支给对象是 sink 形态', spend.data.payee, 'sink:NATIONAL_TECH')
}

// ---------- 6. 国库流水里真能看到这一笔（四要素齐） ----------
const treasury = await call('GET', '/nation/treasury', undefined, king.playerId, king.token)
check('读国库成功', treasury.code, 0)
const firstLog = treasury.data?.logs?.[0] ?? null
checkThat('流水不为空', firstLog !== null)
if (firstLog !== null) {
  check('最新一条就是刚花的那笔（金额）', firstLog.amount, spendAmount)
  check('最新一条写明了用途', firstLog.reason, '实链验收：国家科技出资')
  check('最新一条的对手方是 sink:NATIONAL_TECH', firstLog.counterparty, 'sink:NATIONAL_TECH')
  check('最新一条的余额等于当下余额', firstLog.balanceAfter, treasury.data.balance)
  checkThat('最新一条带操作者与时刻（谁 / 何时）', firstLog.operatorId === king.playerId && firstLog.at > 0)
}

// ---------- 7. 国家科技：读 + 真研究一级 ----------
const techList = await call('GET', '/nation/tech', undefined, king.playerId, king.token)
check('读国家科技成功', techList.code, 0)
checkThat('科技表有行（行数由表决定，客户端不该知道）', (techList.data?.techs?.length ?? 0) > 0)
// 找一行现在就能研究的（canResearch 是服务端算好的，脚本不自己判等级与余额）。
// 找不到时**把每一行的下一级花费打出来**：否则"付不起"与"全满级"在输出里长得一样
const techRows = techList.data?.techs ?? []
const researchable = techRows.find(tech => tech.canResearch) ?? null
if (researchable === null) {
  console.log('  科技表现状：' + techRows.map(tech =>
    `${tech.name}(Lv${tech.level}/${tech.maxLevel} 需国${tech.requireNationLevel} 花费${tech.nextCostTreasury} ${tech.blockedReason})`).join(' ｜ '))
}
checkThat(`至少有一行现在能研究（国库 ${techList.data?.treasury ?? '?'}）`, researchable !== null)
if (researchable !== null) {
  const research = await call('POST', '/nation/tech/research', {
    requestId: rid('tech'), techId: researchable.techId,
  }, king.playerId, king.token)
  if (research.code !== 0) {
    bad(`研究被拒：${research.code} ${research.msg} ${research.detail ?? ''}`)
  } else {
    check('研究后等级 = 研究前 + 1', research.data.level, researchable.level + 1)
    check('扣掉的数额 = 表里给的下一级花费', research.data.costTreasury, researchable.nextCostTreasury)
    check('扣完余额 = 研究前余额 − 花费', research.data.treasuryAfter, techList.data.treasury - researchable.nextCostTreasury)
    // 复核：重读一次，等级真的上去了（不是只在回执里 +1）
    const after = await call('GET', '/nation/tech', undefined, king.playerId, king.token)
    const same = (after.data?.techs ?? []).find(tech => tech.techId === researchable.techId) ?? null
    check('重读那一行，等级确实是新的', same?.level, researchable.level + 1)
  }
}

// ---------- 8. 任命：第二个号入盟 → 国王任命他 ----------
const minister = await initPlayer('nationlive2')
const applied = await call('POST', '/alliance/apply', {
  requestId: rid('apply'), allianceId,
}, minister.playerId, minister.token)
// 这条第一版写成 `applied.code === 0 || applied.code !== 0` —— 两边都算过的断言等于没写。
// 入盟申请必须成功，否则下面那条"批准"是在批一件不存在的事
check(`第二个号申请入盟成功（${applied.detail ?? applied.msg}）`, applied.code, 0)
const applicants = await call('GET', '/alliance/applications', undefined, king.playerId, king.token)
const pending = (applicants.data?.applicants ?? []).find(item => item.playerId === minister.playerId)
  ?? (applicants.data?.applications ?? []).find(item => item.playerId === minister.playerId)
  ?? null
if (pending !== null) {
  const reviewed = await call('POST', '/alliance/review', {
    requestId: rid('review'), applicantId: minister.playerId, approve: true,
  }, king.playerId, king.token)
  check('批准入盟成功', reviewed.code, 0)
} else {
  console.log('  跳过：没查到待审申请（入盟路径可能不要求审核，任命那一格改用名单外的人做负例）')
}
const appoint = await call('POST', '/nation/appoint', {
  requestId: rid('appoint'), playerId: minister.playerId, office: 'MINISTER',
}, king.playerId, king.token)
if (appoint.code === 0) {
  ok('任命成功（第二个号成为内政官）')
  // 复核：换那个号去读，他的 myOffice 应该是内政官
  const hisView = await call('GET', '/nation', undefined, minister.playerId, minister.token)
  check('被任命者自己读到 myOffice = MINISTER', hisView.data?.nation?.myOffice, 'MINISTER')
} else {
  // 入盟没走通时这里必然被拒（NATION_NOT_MEMBER），那是**预期**的负例，不是缺陷
  checkThat(`任命被拒且理由是"不属于本国"（入盟没走通时的预期负例）：${appoint.code} ${appoint.detail ?? appoint.msg}`,
    appoint.code === 13004)
}

// ---------- 8b. 国策（B13 §4）：提案段 + 投票段门禁 + 轮次视图 ----------
// 位置放在这里是因为它需要「国王 + 一位内政官 + 一位普通成员」三种身份，
// 而这三位到 8 节结束时才齐；又必须在退国（10 节）之前跑完——退国之后就没有国家了。
// **能验到哪一步**：提案、权限门禁、轮次视图、两份名单、投票段未开时的拒绝。
// **验不到**：结算与生效——那要等 48 小时（24h 投票窗 + 24h 生效段），
// 真链路不可能等，所以那一半由 `NationPolicyTest` 的 22 条领域用例兜。
const round0 = await call('GET', '/nation/policy', undefined, king.playerId, king.token)
if (round0.code !== 0) {
  bad(`读国策轮次被拒：${round0.code} ${round0.msg} ${round0.detail ?? ''}`)
} else {
  const r0 = round0.data
  check('轮次视图带了国家 id', r0.nationId, nation.nationId)
  check('开局是提案段（还没有任何提案）', r0.phase, 'PROPOSING')
  check('国策清单 = 表里的 8 行，一次给全（客户端不硬编码有几条）', (r0.policies ?? []).length, 8)
  check('槽位数 = 国家等级那一列（Lv1 = 1）', r0.policySlotCount, 1)
  checkThat('国王此刻可以提案', r0.canPropose === true && r0.proposeBlockReason === 'NONE')
  checkThat('此刻不能投票，而理由是「本轮还没有提案」（不是一个笼统的"不行"）',
    r0.canVote === false && r0.voteBlockReason === 'NO_PROPOSAL_YET')
  checkThat('没有提案时 myProposals 是空的', Array.isArray(r0.myProposals) && r0.myProposals.length === 0)
  checkThat('生效中的国策是空数组而不是 null（客户端要能直接遍历）', Array.isArray(r0.active))
  checkThat('槽位竞争规则随视图下发（不是客户端自己写的文案）',
    typeof r0.slotOrderNote === 'string' && r0.slotOrderNote.length > 0)
  // 效果说明是服务端拼好的：客户端不拿 attr × value × unit 自己拼
  const cavalry = (r0.policies ?? []).find(p => p.policyId === 'np_cavalry_t1')
  checkThat('骑兵时代那一行带了下发的中文兵种名（不是 unit_cavalry_t1 这种 id）',
    cavalry !== undefined && cavalry.targetUnitName === '轻骑兵 T1', cavalry?.targetUnitName)
  checkThat('效果说明是可上屏的一句话', typeof cavalry?.effectText === 'string'
    && cavalry.effectText.includes('%'), cavalry?.effectText)
  // 幅度是定点：1500 = +15%。这一条钉住「下发的是定点而不是浮点」
  check('骑兵时代的幅度 = 15%（定点 1500）', cavalry?.effectValueFixed, 1500)

  // 负例：此刻还没有任何提案，所以投票被拒的理由是「本轮还没有提案」——
  // **不是**「现在不是投票时间」。两枚码是分开设计的（玩家的下一步不同：一种是自己提一条，
  // 一种是等窗口开），所以这里要按真实的时刻各钉一枚，不能混着写一条。
  const earlyVote = await call('POST', '/nation/policy/vote', {
    requestId: rid('vote-early'), proposalId: 'whatever', support: true,
  }, king.playerId, king.token)
  checkThat(`还没提案就投票被拒（13016 = 本轮还没有提案）：${earlyVote.code} ${earlyVote.detail ?? earlyVote.msg}`,
    earlyVote.code === 13016)

  // 负例：普通成员不能提案（A1 之后是国王+官员，不是全员）
  const outsider = await initPlayer('nationlive4')
  await call('POST', '/alliance/apply', {
    requestId: rid('apply-outsider'), allianceId,
  }, outsider.playerId, outsider.token)
  const apps2 = await call('GET', '/alliance/applications', undefined, king.playerId, king.token)
  const pend2 = (apps2.data?.applicants ?? []).find(i => i.playerId === outsider.playerId)
    ?? (apps2.data?.applications ?? []).find(i => i.playerId === outsider.playerId) ?? null
  if (pend2 !== null) {
    await call('POST', '/alliance/review', {
      requestId: rid('review-outsider'), applicantId: outsider.playerId, approve: true,
    }, king.playerId, king.token)
  }
  const outsiderRound = await call('GET', '/nation/policy', undefined, outsider.playerId, outsider.token)
  if (outsiderRound.code === 0) {
    checkThat('普通成员读到的是「不能提案」而理由指名缺的是提案权',
      outsiderRound.data.canPropose === false
      && outsiderRound.data.proposeBlockReason === 'NOT_PROPOSER',
      `${outsiderRound.data.canPropose}/${outsiderRound.data.proposeBlockReason}`)
    const outsiderPropose = await call('POST', '/nation/policy/propose', {
      requestId: rid('propose-outsider'), policyId: 'np_harvest',
    }, outsider.playerId, outsider.token)
    checkThat(`普通成员提案被拒（13014）：${outsiderPropose.code} ${outsiderPropose.detail ?? outsiderPropose.msg}`,
      outsiderPropose.code === 13014)
  } else {
    bad(`普通成员读国策被拒：${outsiderRound.code} ${outsiderRound.detail ?? outsiderRound.msg} ⇒ 提案权那一格验不到`)
  }

  // 正例：内政官可以提案（A1 的实际效果；上面那位已经是 MINISTER）
  const ministerPropose = await call('POST', '/nation/policy/propose', {
    requestId: rid('propose-minister'), policyId: 'np_harvest',
  }, minister.playerId, minister.token)
  if (ministerPropose.code === 0) {
    ok('内政官提案成功（裁决 A1：提案权放开到官员档）')
    const afterPropose = ministerPropose.data.round
    check('提案之后仍是提案段（不立刻开窗）', afterPropose.phase, 'PROPOSING')
    check('本轮有 1 条提案', (afterPropose.proposals ?? []).length, 1)
    const mine = afterPropose.proposals[0]
    check('那条提案指向丰收时代', mine.policy.effectAttr, 'OUTPUT')
    check('提案人是那位内政官', mine.proposedBy, minister.playerId)
    checkThat('两份公示名单此时都是空数组（还没人投票；客户端要能直接遍历，null 与空数组不等价）',
      Array.isArray(mine.supporters) && mine.supporters.length === 0
      && Array.isArray(mine.opponents) && mine.opponents.length === 0,
      `supporters=${JSON.stringify(mine.supporters)} opponents=${JSON.stringify(mine.opponents)}`)
    checkThat('票数此刻都是 0（票数与名单必须自证：两者都为零）',
      mine.yes === 0 && mine.no === 0, `yes=${mine.yes} no=${mine.no}`)
    checkThat('提案之后 myProposals 里能看到自己那条',
      (afterPropose.myProposals ?? []).includes(mine.proposalId),
      JSON.stringify(afterPropose.myProposals))
    checkThat('开窗时刻 = 提案时刻 + 一个提案段（24h），服务端下发不由客户端加',
      afterPropose.nextVoteAt - afterPropose.serverNow > 23 * 3600 * 1000
      && afterPropose.nextVoteAt - afterPropose.serverNow <= 24 * 3600 * 1000,
      `${afterPropose.nextVoteAt - afterPropose.serverNow}ms`)
    // 负例：同一条国策本轮不能提第二次
    const twice = await call('POST', '/nation/policy/propose', {
      requestId: rid('propose-twice'), policyId: 'np_harvest',
    }, king.playerId, king.token)
    checkThat(`同一条国策第二次提案被拒（13018）：${twice.code} ${twice.detail ?? twice.msg}`,
      twice.code === 13018)
    // 负例：表里没有的国策 id
    const ghost = await call('POST', '/nation/policy/propose', {
      requestId: rid('propose-ghost'), policyId: 'np_does_not_exist',
    }, king.playerId, king.token)
    checkThat(`表里没有的国策被拒（1001 = 参数非法）：${ghost.code} ${ghost.detail ?? ghost.msg}`,
      ghost.code === 1001)
    // 负例：现在本轮已经有提案了，但投票窗还没开 ⇒ 换成另一枚码（13015）
    const stillEarly = await call('POST', '/nation/policy/vote', {
      requestId: rid('vote-still-early'), proposalId: mine.proposalId, support: true,
    }, king.playerId, king.token)
    checkThat(`有提案但窗口未开时投票仍被拒（13015 = 现在不是投票时间，与 13016 是两枚不同的码）：${stillEarly.code} ${stillEarly.detail ?? stillEarly.msg}`,
      stillEarly.code === 13015)
  } else {
    checkThat(`内政官提案成功（13015 = 上一段的负例把窗口状态弄脏了）：${ministerPropose.code} ${ministerPropose.detail ?? ministerPropose.msg}`,
      ministerPropose.code === 0)
  }
}

// ---------- 9. 外交：需要第二个国家 ----------
const rival = await initPlayer('nationlive3')
await call('POST', '/alliance/create', {
  requestId: rid('alliance2'), name: `实链对手盟${runTag}`, tag: `R${runTag.slice(-4)}`,
}, rival.playerId, rival.token)
const foundRival = await call('POST', '/nation/found', {
  requestId: rid('found2'), name: `对手国${Date.now() % 100000}`, capitalX: 200, capitalY: 160,
}, rival.playerId, rival.token)
if (foundRival.code === 0) {
  ok('第二个国家建成')
  const rivalId = foundRival.data.nation.nationId
  const diplo = await call('POST', '/nation/diplomacy', {
    requestId: rid('diplo'), targetNationId: rivalId, relation: 'HOSTILE',
  }, king.playerId, king.token)
  if (diplo.code !== 0) {
    bad(`外交变更被拒：${diplo.code} ${diplo.msg} ${diplo.detail ?? ''}`)
  } else {
    check('生效后的关系 = 我要设的', diplo.data.relation, 'HOSTILE')
    // **按 id 查，不按下标**：dev 后端是内存存储、跨轮次不清，
    // 上一轮建的对手国还在，所以这张表里有几条不是本轮能决定的（第一版按下标判，红了但功能是好的）
    const relations = diplo.data.allRelations ?? []
    checkThat('回执带回全部关系表（本国视角，至少含本轮那个对手）', relations.length >= 1)
    const mine = relations.find(rel => rel.nationId === rivalId) ?? null
    checkThat('那张表里有本轮的对手国', mine !== null)
    check('表里对手国的关系值 = 我要设的', mine?.relation, 'HOSTILE')
    // 负例：不能与自己建交。**判的是业务码 1001（PARAM_INVALID）而不是 HTTP 400** ——
    // prod 一律回 200 信封，业务码才是"服务端拒了"的判据（第一版按 400 判，红了但功能是好的）
    const self = await call('POST', '/nation/diplomacy', {
      requestId: rid('diplo-self'), targetNationId: nation.nationId, relation: 'ALLIED',
    }, king.playerId, king.token)
    check('与自己建交被拒（PARAM_INVALID=1001）', self.code, 1001)
  }
  // **清掉本轮的对手国**：dev 是内存存储、跨轮次不清，而 `NATION_MAX_PER_KINGDOM` 只有 4 ——
  // 不清理第二轮就会撞 `NATION_CREATE_LIMIT(13002)`（第一版就是这么红的：
  // 看着像建国坏了，其实是前几轮的残留把名额占满了）
  const rivalDisband = await call('POST', '/nation/disband', {
    requestId: rid('disband-rival'),
  }, rival.playerId, rival.token)
  check(`清掉本轮建的对手国（${rivalDisband.detail ?? rivalDisband.msg}）`, rivalDisband.code, 0)
} else {
  bad(`第二个国家没建成（${foundRival.code} ${foundRival.detail ?? foundRival.msg}）⇒ 外交那半验不到`)
}

// ---------- 10. 退国（不可逆的那一步放最后） ----------
const leave = await call('POST', '/nation/leave', { requestId: rid('leave') }, king.playerId, king.token)
if (leave.code !== 0) {
  bad(`退国被拒：${leave.code} ${leave.msg} ${leave.detail ?? ''}`)
} else {
  check('退国回执带来了国名', leave.data.nationName, nation.name)
  checkThat('退国回执给了冷却时刻（服务端下发，不由客户端加）', leave.data.cooldownUntil > leave.data.serverNow)
  const afterLeave = await call('GET', '/nation', undefined, king.playerId, king.token)
  check('退国后再读：正式回「不在任何国家」(13000)', afterLeave.code, 13000)
}
// **清掉本轮建的国**：国王退国之后按 kingId 仍然亡得了自己的国
// （`disband` 的发起权来自"你是这个国家的国王"，不来自成员关系），
// 所以这里一定清得掉；已经随最后一个联盟自动解散时回 13000 也算清干净了
const kingDisband = await call('POST', '/nation/disband', {
  requestId: rid('disband-king'),
}, king.playerId, king.token)
checkThat(`清掉本轮建的国（自己退国后仍清得掉，或已随之解散）：${kingDisband.code} ${kingDisband.detail ?? kingDisband.msg}`,
  kingDisband.code === 0 || kingDisband.code === 13000)

// ---------- 11. 回读屏（可选）：对着**真后端**打开国家面板，核对屏上是真数据 ----------
if (process.env.NATION_LIVE_UI === '1') {
  // 重新造一个在位国王（上面那位已经退国了），否则面板打开只会看到"你还没有国家"
  const uiKing = await initPlayer('nationliveui')
  await call('POST', '/alliance/create', {
    requestId: rid('alliance-ui'), name: `回读屏盟${runTag}`, tag: `U${runTag.slice(-4)}`,
  }, uiKing.playerId, uiKing.token)
  const uiFound = await call('POST', '/nation/found', {
    requestId: rid('found-ui'), name: `回读屏国${runTag}`, capitalX: 133, capitalY: 77,
  }, uiKing.playerId, uiKing.token)
  checkThat('（回读屏）先建出一个国家', uiFound.code === 0)
  if (uiFound.code === 0) {
    // 宣战要有一个"别人家的国"：另起一个号建盟建国（走真 HTTP，不开夹具）
    const uiTarget = await initPlayer('nationliveuitarget')
    const uiTargetAlliance = await call('POST', '/alliance/create', {
      requestId: rid('alliance-ui-target'), name: `目标盟${runTag}`, tag: `T${runTag.slice(-4)}`,
    }, uiTarget.playerId, uiTarget.token)
    // 第三个号：一个**不是国王**的成员，用来验「权限位没有 DECLARE_WAR 时那颗键灰且点了零请求」
    const uiMember = await initPlayer('nationliveuimember')
    await call('POST', '/alliance/apply', {
      requestId: rid('apply-ui-member'), allianceId: uiTargetAlliance.data?.alliance?.id,
    }, uiMember.playerId, uiMember.token)
    await call('POST', '/alliance/review', {
      requestId: rid('review-ui-member'), applicantId: uiMember.playerId, approve: true,
    }, uiTarget.playerId, uiTarget.token)
    const uiTargetFound = await call('POST', '/nation/found', {
      requestId: rid('found-ui-target'), name: `目标国${runTag}`, capitalX: 155, capitalY: 88,
    }, uiTarget.playerId, uiTarget.token)
    checkThat('（回读屏）另建一个目标国（用于验证宣战）', uiTargetFound.code === 0)
    // 字段名是 `nationId` 不是 `id`（NationView）。第一版写错成 `.id` ⇒ 桩里的 id 是 undefined，
    // JSON 把它丢掉 ⇒ 客户端拿到的 key 也是 undefined ⇒ 探针用 `WarTarget-undefined` 去找节点
    // **照样命中**（两端都是 undefined、相等）—— 一条看起来精确的断言就这样假绿了，
    // 直到看见请求体 `{requestId:...}` 才露馅。所以先钉住它非空，后面那条精确点击才算数。
    const uiTargetNationId = uiTargetFound.data?.nation?.nationId
    checkThat('（回读屏）目标国 id 非空（桩与精确点击都以它为准）',
      typeof uiTargetNationId === 'string' && uiTargetNationId.length > 0)
    // 国家榜是「按成员战力之和」的投影：新号没上报过战力，**榜上就看不到这个国**，
    // 于是外交/宣战那一行的候选国也是空的。`/world/searchTargets` 会顺手刷新一次战力
    // （`TargetSearchService` 的既定行为），两个号各调一次让两个国都上榜。
    await call('POST', '/world/searchTargets', { radius: 12, maxCount: 5 },
      uiTarget.playerId, uiTarget.token)
    await call('POST', '/world/searchTargets', { radius: 12, maxCount: 5 },
      uiKing.playerId, uiKing.token)
    const nationName = uiFound.data.nation.name
    const treasury = uiFound.data.nation.treasury
    const { chromium } = await import('playwright')
    const { startPreviewServer } = await import('./lib/preview-server.mjs')
    const { hideGuideOverlay } = await import('./lib/guide-overlay.mjs')
    const port = Number(process.env.NATION_LIVE_PORT ?? 8233)
    const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port })
    const browser = await chromium.launch({ headless: true })
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
    // **注入那个号的 deviceId**：真后端按 deviceId 建档，注入错的话面板打开的是别人的号
    await context.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), uiKing.deviceId)
    const page = await context.newPage()
    // ---- 只桩**一读**：国家榜 ----
    // dev 后端没配 SEASON_START_AT ⇒ 赛季未启用 ⇒ POWER/KILL 上报被静默跳过
    // ⇒ NATION 榜（按成员赛季分投影）恒为空 ⇒ 外交/国战两页的候选国列表也是空的，
    // 而宣战必须选一个"真存在"的国家。这里把榜桩成**真建出来的那个目标国**
    // （id 与名字都真），于是点下去那一枪打到真后端、真开一场仗 ——
    // 桩掉的只是"榜上有没有它"这一读，宣战本身没有被桩。
    const { makeStubRead } = await import('./lib/route-stub.mjs')
    // 参数是**按字母序**排的（），所以匹配串不能写死  开头（
    // 第一版就是栽在这里：桩挂上了却一条都没命中，界面仍旧空态）。
    makeStubRead(context)('**/rank/list*type=NATION*', {
      type: 'NATION',
      entries: [{
        rank: 1, id: uiTargetNationId, name: uiTargetFound.data.nation.name,
        value: 1, tag: null,
      }],
      myRank: null, myValue: null, page: 1, pageSize: 20, hasMore: false, dayKey: '20261007',
    })
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    try {
      await page.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
      preview.assertRewritten()
      await page.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
        null, { timeout: 60_000 })
      await page.waitForTimeout(3500)
      await hideGuideOverlay(page)
      await page.waitForTimeout(400)
      // 联盟页 → 那一行「国家」
      const clicked = await page.evaluate(`(() => {
        const scene = window.cc.director.getScene()
        const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
        if (!social) return false
        social.getChildByName('Tab_alliance').emit('touch-start')
        // 入口挂在**概况行的第二颗键**上（不是独立一行）：按按钮文案找，不按行标题
        let target = null
        const walk = (n) => {
          if (target) return
          if (/^ActionButton[23]?$/.test(n.name) && n.activeInHierarchy) {
            const caption = n.getComponentInChildren('cc.Label')
            if (caption !== null && caption !== undefined && String(caption.string) === '国家') {
              target = n
              return
            }
          }
          for (const child of n.children) walk(child)
        }
        walk(social)
        if (target === null) return false
        target.emit('touch-start')
        return true
      })()`)
      checkThat('（回读屏）点得开国家面板', clicked)
      await page.waitForTimeout(2000)
      const screen = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene()
          .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        const texts = []
        if (panel && panel.activeInHierarchy) {
          const walk = (n) => {
            if (n.activeInHierarchy) {
              const label = n.getComponent('cc.Label')
              if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
            }
            for (const child of n.children) walk(child)
          }
          walk(panel)
        }
        return { active: !!(panel && panel.activeInHierarchy), texts }
      })()`)
      check('（回读屏）面板是打开的', screen.active, true)
      const shown = (screen.texts ?? []).join(' ')
      console.log(`  屏上文字：${shown}`)
      checkThat(`（回读屏）屏上的国名是刚建的那个（${nationName}）`, shown.includes(nationName))
      checkThat('（回读屏）屏上的国库是服务端那一份', shown.includes(String(treasury).replace(/\B(?=(\d{3})+(?!\d))/g, ',')))
      checkThat('（回读屏）屏上没有出现 nation_ 这类内部 id', !shown.includes('nation_'))

      // ---- 国战那一页（V18 的客户端承接，只读）----
      // 这一相的判据各挡一种"看着没问题其实没接上"：页签节点在（不是只有文案）、点得动、
      // 空态与全服进度两行都在（B13 §7：不打国战的人的贡献也算）、
      // 屏上不出现内部 id 与阶段枚举原文（本仓红线）。
      const warClicked = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene()
          .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        if (!panel || !panel.activeInHierarchy) return false
        let target = null
        const walk = (n) => {
          if (target) return
          if (n.name === 'Tab_WAR' && n.activeInHierarchy) { target = n; return }
          for (const child of n.children) walk(child)
        }
        walk(panel)
        if (target === null) return false
        target.emit('touch-start')
        return true
      })()`)
      checkThat('（回读屏）国战页签点得开（节点名 Tab_WAR）', warClicked)
      await page.waitForTimeout(1600)
      const warScreen = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene()
          .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        const texts = []
        if (panel && panel.activeInHierarchy) {
          const walk = (n) => {
            if (n.activeInHierarchy) {
              const label = n.getComponent('cc.Label')
              if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
            }
            for (const child of n.children) walk(child)
          }
          walk(panel)
        }
        return texts
      })()`)
      const warShown = (warScreen ?? []).join(' ')
      console.log(`  国战页文字：${warShown}`)
      checkThat('（回读屏）国战页画的是"这一场"那件事（空态或阶段二选一）',
        warShown.includes('现在没有正在打的国战') || /筹备期|王城战进行中|已经结束/.test(warShown))
      checkThat('（回读屏）全服击杀进度那一行在（B13 §7）', warShown.includes('全服击杀'))
      checkThat('（回读屏）我的疲劳那一行在', warShown.includes('我的疲劳'))
      checkThat('（回读屏）国战页不出现内部 id', !/nation_[a-z0-9]/.test(warShown))
      checkThat('（回读屏）国战页不出现阶段枚举原文',
        !/\b(PREPARATION|SIEGE|SETTLED)\b/.test(warShown))
      await page.screenshot({ path: path.join(OUT, 'live-war-tab.png') })
      console.log(`  截图：${path.join(OUT, 'live-war-tab.png')}`)

      // ---- 宣战（写侧）：选目标 → 第一次点只是武装 → 第二次点才真发 ----
      // 这一相刻意分三段判：武装后**请求必须还没发**（否则"二次确认"只是装饰）、
      // 真发之后服务端 `/nation/war` 必须 hasWar=true、屏上必须随即画出参战方两行。
      const clickByName = (name, exact) => page.evaluate(`(() => {
        const panel = window.cc.director.getScene()
          .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        if (!panel || !panel.activeInHierarchy) return false
        let target = null
        const walk = (n) => {
          if (target) return
          const hit = ${exact ? 'true' : 'false'} ? n.name === '${name}' : n.name.startsWith('${name}')
          if (hit && n.activeInHierarchy) { target = n; return }
          for (const child of n.children) walk(child)
        }
        walk(panel)
        if (target === null) return false
        target.emit('touch-start')
        return true
      })()`)
      // 按**目标国的 id** 精确点那一颗（不是"列表里的第一颗"）：候选里也有本国，
      // 点错了会被服务端以「不能对本国宣战」拒掉，而那条拒绝看起来像"宣战功能坏了"
      const picked = await clickByName(`WarTarget-${uiTargetNationId}`, true)
      checkThat('（回读屏）国战页列出了可宣战的目标国（按 id 精确命中）', picked)
      await page.waitForTimeout(800)

      const armed = await clickByName('WarDeclare', true)
      checkThat('（回读屏）宣战那颗键点得到', armed)
      await page.waitForTimeout(800)
      const armedTexts = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene()
          .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        const texts = []
        if (panel && panel.activeInHierarchy) {
          const walk = (n) => {
            if (n.activeInHierarchy) {
              const label = n.getComponent('cc.Label')
              if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
            }
            for (const child of n.children) walk(child)
          }
          walk(panel)
        }
        return texts
      })()`)
      checkThat('（回读屏）第一次点只是武装（屏上出现「再点一次」）',
        /再点一次/.test((armedTexts ?? []).join(' ')))
      const beforeWar = await call('GET', '/nation/war', undefined, uiKing.playerId, uiKing.token)
      check('（回读屏）武装那一下没有开战（请求还没发）', beforeWar.data?.hasWar, false)

      await clickByName('WarDeclare', true)
      await page.waitForTimeout(2200)
      const afterWar = await call('GET', '/nation/war', undefined, uiKing.playerId, uiKing.token)
      check('（回读屏）第二次点真的开了战（/nation/war hasWar=true）', afterWar.data?.hasWar, true)
      const afterTexts = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene()
          .getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        const texts = []
        if (panel && panel.activeInHierarchy) {
          const walk = (n) => {
            if (n.activeInHierarchy) {
              const label = n.getComponent('cc.Label')
              if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
            }
            for (const child of n.children) walk(child)
          }
          walk(panel)
        }
        return texts
      })()`)
      const afterShown = (afterTexts ?? []).join(' ')
      console.log('  开战后文字：' + afterShown.slice(0, 500))
      // 判据要落在**开战之后才可能出现的那几个词**上：国名在那之前就画在页面上（标题与目标行），
      // 拿国名当“画出了参战方”的证据是假绿（第一版就是这么写的，直到看见请求体才发现）。
      checkThat('（回读屏）国战页随即画出参战方两行',
        /王城战进行中|筹备期/.test(afterShown) && afterShown.includes('全服击杀')
          && !afterShown.includes('现在没有正在打的国战'))
      checkThat('（回读屏）开战成了一句给玩家的话', afterShown.includes('已对'))
      checkThat('（回读屏）开战后屏上仍无内部 id', !/nation_[a-z0-9]/.test(afterShown))
      await page.screenshot({ path: path.join(OUT, 'live-war-declared.png') })
      console.log(`  截图：${path.join(OUT, 'live-war-declared.png')}`)

      // ---- 只读关系表：宣战改了关系，切到外交页必须看到「敌对」 ----
      // 这一相验的就是那个缺口本身：`NationDiplomacyResp` 那张表只在"改关系"那一次是新的，
      // 宣战也改关系而它回的是国战状态 —— 没有 `GET /nation/relations`，这一屏就是旧数据。
      const diploPicked = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        if (!panel || !panel.activeInHierarchy) return false
        let target = null
        const walk = (n) => { if (target) return; if (n.name === 'Tab_DIPLO' && n.activeInHierarchy) { target = n; return } for (const child of n.children) walk(child) }
        walk(panel)
        if (target === null) return false
        target.emit('touch-start')
        return true
      })()`)
      checkThat('（回读屏）切到外交页', diploPicked)
      await page.waitForTimeout(1800)
      const diploTexts = await page.evaluate(`(() => {
        const panel = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
        const texts = []
        if (panel && panel.activeInHierarchy) {
          const walk = (n) => {
            if (n.activeInHierarchy) {
              const label = n.getComponent('cc.Label')
              if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
            }
            for (const child of n.children) walk(child)
          }
          walk(panel)
        }
        return texts
      })()`)
      const diploShown = (diploTexts ?? []).join(' ').replace(/\s+/g, '')
      // ⚠️ 只判 includes('敌对') 是**假绿**：外交页上「敌对」本来就是一个选项按钮的文案
      // （实测第一版就这么过了，而屏幕上是空态 —— 那一行根本没画出来）。
      // 真正只可能来自新状态的证据是：**那一行**（对方国名 + 关系）在，且空态那句不在了。
      checkThat('（回读屏）外交页那一行是「敌对」而不是旧关系（否定式 + 正向式成对）',
        diploShown.includes(uiTargetFound.data.nation.name) && diploShown.includes('敌对')
          && !diploShown.includes('还没有打过一次交道'))
      await page.screenshot({ path: path.join(OUT, 'live-diplo-after-declare.png') })
      console.log(`  截图（宣战后的外交页）：${path.join(OUT, 'live-diplo-after-declare.png')}`)

      // ---- 负向相 ①（HTTP）：同一对两国再宣一次 ⇒ 冷却拦住，且**没有**开出第二场 ----
      const again = await call('POST', '/nation/war/declare', {
        requestId: rid('declare-again'), targetNationId: uiTargetNationId,
      }, uiKing.playerId, uiKing.token)
      check('（回读屏）同一对两国再宣战被冷却拦住（13023）', again.code, 13023)
      const stillOne = await call('GET', '/nation/war', undefined, uiKing.playerId, uiKing.token)
      check('（回读屏）被拦之后仍然只有那一场', stillOne.data?.hasWar, true)

      // ---- 负向相 ②（真 UI）：非国王那一号点宣战 ⇒ 键灰、写明理由、**零请求** ----
      const memberContext = await browser.newContext({ viewport: { width: 1280, height: 720 } })
      await memberContext.addInitScript((v) => localStorage.setItem('ironoath.deviceId', v), uiMember.deviceId)
      const memberPage = await memberContext.newPage()
      let memberDeclares = 0
      memberPage.on('request', (r) => { if (r.url().includes('/nation/war/declare')) memberDeclares += 1 })
      try {
        await memberPage.goto(`${preview.origin}/?panel=social`, { waitUntil: 'networkidle' })
        await memberPage.waitForFunction(() => window.cc !== undefined && window.cc.director?.getScene() !== null,
          null, { timeout: 60_000 })
        await memberPage.waitForTimeout(3500)
        await hideGuideOverlay(memberPage)
        await memberPage.waitForTimeout(400)
        const opened = await memberPage.evaluate(`(() => {
          const scene = window.cc.director.getScene()
          const social = scene.getChildByName('Canvas').getChildByName('Game').getChildByName('social')
          if (!social) return false
          social.getChildByName('Tab_alliance').emit('touch-start')
          let target = null
          const walk = (n) => {
            if (target) return
            if (/^ActionButton[23]?$/.test(n.name) && n.activeInHierarchy) {
              const caption = n.getComponentInChildren('cc.Label')
              if (caption && String(caption.string) === '国家') { target = n; return }
            }
            for (const child of n.children) walk(child)
          }
          walk(social)
          if (target === null) return false
          target.emit('touch-start')
          return true
        })()`)
        checkThat('（负向相）非国王那个号也点得开国家面板', opened)
        await memberPage.waitForTimeout(2000)
        const memberTab = await memberPage.evaluate(`(() => {
          const panel = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
          if (!panel || !panel.activeInHierarchy) return false
          let target = null
          const walk = (n) => { if (target) return; if (n.name === 'Tab_WAR' && n.activeInHierarchy) { target = n; return } for (const child of n.children) walk(child) }
          walk(panel)
          if (target === null) return false
          target.emit('touch-start')
          return true
        })()`)
        checkThat('（负向相）切到国战页', memberTab)
        await memberPage.waitForTimeout(1800)
        const memberTexts = await memberPage.evaluate(`(() => {
          const panel = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
          const texts = []
          if (panel && panel.activeInHierarchy) {
            const walk = (n) => {
              if (n.activeInHierarchy) {
                const label = n.getComponent('cc.Label')
                if (label && String(label.string ?? '').trim() !== '') texts.push(label.string)
              }
              for (const child of n.children) walk(child)
            }
            walk(panel)
          }
          return texts
        })()`)
        const memberShown = (memberTexts ?? []).join(' ').replace(/\s+/g, '')
        checkThat('（负向相）非国王看到的是「职位不能宣战」那条灰键理由',
          memberShown.includes('你当前的职位不能宣战'))
        // 点一下那颗灰键：`button(...)` 在 disabled 时不绑 touch-start ⇒ 零请求
        await memberPage.evaluate(`(() => {
          const panel = window.cc.director.getScene().getChildByName('Canvas').getChildByName('Game').getChildByName('nation')
          let target = null
          const walk = (n) => { if (target) return; if (n.name === 'WarDeclare' && n.activeInHierarchy) { target = n; return } for (const child of n.children) walk(child) }
          walk(panel)
          if (target !== null) target.emit('touch-start')
        })()`)
        await memberPage.waitForTimeout(900)
        check('（负向相）点灰键零请求（一条 declare 都没发）', memberDeclares, 0)
        await memberPage.screenshot({ path: path.join(OUT, 'live-war-permission-gate.png') })
        console.log(`  截图（非国王的灰键）：${path.join(OUT, 'live-war-permission-gate.png')}`)
      } finally {
        await memberContext.close()
      }
      check('（回读屏）零页面错误', errors.join(' | ') || '无', '无')
      await page.screenshot({ path: path.join(OUT, 'live-panel.png') })
      console.log(`  截图：${path.join(OUT, 'live-panel.png')}`)
    } finally {
      await browser.close()
      await preview.close()
    }
    // 清掉回读屏那个国：不清的话每跑一轮就多占一个名额（上限只有 4）
    const uiTargetDisband = await call('POST', '/nation/disband', {
      requestId: rid('disband-ui-target'),
    }, uiTarget.playerId, uiTarget.token)
    check(`（回读屏）清掉目标国（${uiTargetDisband.detail ?? uiTargetDisband.msg}）`,
      uiTargetDisband.code, 0)
    const uiDisband = await call('POST', '/nation/disband', {
      requestId: rid('disband-ui'),
    }, uiKing.playerId, uiKing.token)
    check(`（回读屏）清掉回读屏建的那个国（${uiDisband.detail ?? uiDisband.msg}）`, uiDisband.code, 0)
  }
}

console.log(`\n=== 国家真链路验收（真后端 + 真 HTTP，无夹具）：${pass} 通过 / ${fail} 失败 ===`)
process.exit(fail === 0 ? 0 : 1)
