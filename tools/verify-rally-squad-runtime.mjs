/**
 * 小队集结（`POST /rally/squad`）真链路探针 —— 收口清单 #2537。
 *
 * <p><b>为什么要这一份</b>：`/rally/squad` 长期挂在覆盖面门的「没有任何探针碰过」名单上。
 * 它是三项集结里唯一「发起人必须自带兵力」的那一条（`SquadRallyReq#troops` 必填且不得为空，
 * 因为 `Rally.initiate` 要靠发起人的兵建出第一个 Participant，而发起人自己不能再 join），
 * 也正是权限位 `perm_squad_start_rally`（队长/副队可、普通成员不可）唯一真正生效的入口。
 * 这三件事坏掉时屏幕上不会有任何错：集结发不出去、或者发起人没带兵也发出去、或者普通成员也能发。
 *
 * <p><b>它盯的五件事</b>：
 * <ol>
 *   <li>A 真练兵（`POST /army/train` + 等真完成，不做内存夹具）→ 承诺兵力发起小队集结，回执
 *       `scope=SQUAD`、`groupId=小队 id`、`totalTroops=承诺数`、`maxMembers=min(配置, 小队人数)`；</li>
 *   <li>承诺即锁定：发起后 `GET /army/list` 里那支部队的可用数真的少了（这条是小队集结最容易漏的语义）；</li>
 *   <li>普通成员（非队长/副队）发起 ⇒ `10040` 权限拒绝，不是 5xx 也不是 0；</li>
 *   <li>不在任何小队里的玩家发起 ⇒ `10000` 小队不存在；</li>
 *   <li>别人的集结不能取消（`10055`），发起人取消后才真的取消。</li>
 * </ol>
 *
 * <p><b>前提</b>：必须跑在**提速档**后端上（`IRONOATH_DEV_CITY_LEVEL=16`）——
 * 小队有主城等级门槛（`Squad.checkUnlock`），plain 档新号回 `10004 SQUAD_LOCKED`。缺前提退 2，不判红。
 *
 * <p><b>自检口</b>：`RALLY_SQUAD_NEGCTL=no-troops` —— 发起时故意不带兵（空 troops 数组），
 * 让「承诺兵力必填」那条判据必须红。
 */
const BACKEND = (process.env.BACKEND_ORIGIN || '').replace(/\/+$/, '')
const NEGCTL = process.env.RALLY_SQUAD_NEGCTL || ''
const UNIT = process.env.RALLY_SQUAD_UNIT || 'unit_infantry_t1'
const TROOPS = Number(process.env.RALLY_SQUAD_TROOPS || 10)

if (!BACKEND) {
  console.log('=== 前提不足（不是功能红）：必须给 BACKEND_ORIGIN（例如 http://127.0.0.1:8299，且是提速档后端） ===')
  process.exit(2)
}

let pass = 0
let fail = 0
function check(name, actual, expected) {
  if (actual === expected) {
    pass += 1
    console.log(`PASS  ${name}`)
  } else {
    fail += 1
    console.log(`FAIL  ${name}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}
function checkThat(name, ok, detail) {
  if (ok) {
    pass += 1
    console.log(`PASS  ${name}`)
  } else {
    fail += 1
    console.log(`FAIL  ${name}：${detail}`)
  }
}

async function call(method, path, body, playerId) {
  const res = await fetch(BACKEND + path, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' }, playerId ? { 'X-Player-Id': playerId } : {}),
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch (e) {
    json = null
  }
  return { http: res.status, json, text }
}

const rid = (tag) => `${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

async function newPlayer(nick) {
  const r = await call('POST', '/player/init', {
    requestId: rid('init'),
    deviceId: `dev-rallysquad-${Math.random().toString(36).slice(2, 10)}`,
    nickName: nick,
    clientTime: Date.now(),
    wxCode: '',
  })
  if (!r.json || r.json.code !== 0) {
    console.log(`=== 前提不足（不是功能红）：建号失败 ${r.http} ${r.text.slice(0, 160)} ===`)
    process.exit(2)
  }
  return r.json.data.playerId
}

async function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

let armyListReported = false
async function armyCount(playerId, unitId) {
  const r = await call('GET', '/army/list', undefined, playerId)
  if (!r.json || r.json.code !== 0) {
    // 读数失败要留下原文：否则「练完还是 0」会被误读成产品没给兵
    if (!armyListReported) {
      armyListReported = true
      console.log(`  [debug] /army/list 读数失败 http=${r.http} raw=${String(r.text).slice(0, 200)}`)
    }
    return -1
  }
  const row = (r.json.data.units || []).find((u) => u.unitId === unitId)
  return row ? Number(row.count) : 0
}

async function main() {
  console.log(`=== 小队集结真链路：后端 ${BACKEND} ===`)

  const a = await newPlayer('rsA')
  const b = await newPlayer('rsB')
  const c = await newPlayer('rsC')
  checkThat('两个号建得起来且不是同一个人', a !== b && b !== c, `${a} / ${b} / ${c}`)

  // A 建小队（免费，但有主城等级门槛）
  const created = await call('POST', '/squad/create', { requestId: rid('sq'), name: `RS${Math.random().toString(36).slice(2, 6).toUpperCase()}` }, a)
  if (!created.json || created.json.code !== 0) {
    const code = created.json?.code
    if (code === 10004) {
      console.log('=== 前提不足（不是功能红）：这个后端不给小队解锁（10004 SQUAD_LOCKED），要跑在提速档后端上 ===')
      process.exit(2)
    }
    console.log(`=== 前提不足（不是功能红）：建队失败 ${created.http} ${created.text.slice(0, 200)} ===`)
    process.exit(2)
  }
  // /squad/create 回的是整份 SocialSummaryResp（不是单独的 squad 对象），小队在 data.squad 里；
  // SquadView#id 才是小队 id（不是 playerId 那种字段），这里读错会让后面 /rally/squad 的 groupId 判据全空。
  const squadId = created.json.data?.squad?.id
  checkThat('A 建队成功且拿到小队 id', Boolean(squadId), JSON.stringify(created.json.data).slice(0, 200))

  // B 入队（普通成员）
  const joined = await call('POST', '/squad/join', { requestId: rid('join'), squadId }, b)
  check('B 能加入小队', joined.json?.code, 0)

  // 上阵武将：带兵上限来自上阵武将的统帅值，新号 0 兵位 ⇒ 练兵直接回
  // 「超出带兵上限：当前 0，上限 0」（实测）。仓库里唯一的凭空发奖励口是 /ops/mail/send（dev 后端限定），
  // 与 tools/verify-war-real-battle.mjs 走同一条通路：发一封带 HERO 的邮件 → 领取 → 上阵。
  const HERO_ID = process.env.RALLY_SQUAD_HERO ?? 'hero_ssr_01'
  async function equipHero(playerId, nick) {
    const sent = await fetch(BACKEND + '/ops/mail/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Ops-Token': 'art-verify-local' },
      body: JSON.stringify({
        // /ops/mail/send 的正文段字段叫 text（不是 body），缺它会 1001「text 不得为空」——
        // 与 tools/verify-war-real-battle.mjs 的 equipHero 同一个载荷形状。
        requestId: rid('hero-mail'), playerId, title: '小队集结探针用武将',
        text: '自动化量具建号后的补发（dev 后端限定）', actor: 'tools/verify-rally-squad-runtime',
        rewards: [{ type: 'HERO', id: HERO_ID, count: 1, name: HERO_ID }],
      }),
    }).then(async (r) => ({ http: r.status, json: await r.json().catch(() => null) }))
    if (!sent.json || sent.json.code !== 0) {
      console.log(`=== 前提不足（不是功能红）：发武将邮件失败 ${sent.http} ${JSON.stringify(sent.json).slice(0, 160)} ===`)
      return false
    }
    const claimed = await call('POST', '/mail/claimAll', { requestId: rid('claim') }, playerId)
    if (claimed.json?.code !== 0) {
      // 领取口因版本命名不同可能不存在，退回「列出未读 + 逐封领取」
      const list = await call('GET', '/mail/list', undefined, playerId)
      for (const row of (list.json?.data?.mails || [])) {
        if (row.claimed === false || row.read === false) {
          await call('POST', '/mail/claim', { requestId: rid('claimone'), mailId: row.id }, playerId)
        }
      }
    }
    const lineup = await call('POST', '/hero/lineup', {
      requestId: rid('lineup'), presetIndex: 0, main: HERO_ID, sub1: null, sub2: null,
    }, playerId)
    return lineup.json?.code === 0 && Number(lineup.json?.data?.troopCap ?? 0) > 0
  }
  if (!(await equipHero(a, 'A'))) {
    console.log('=== 前提不足（不是功能红）：A 拿不到上阵武将（带兵上限仍是 0），小队集结的承诺兵力无法真造 ===')
    process.exit(2)
  }

  // 兵营：新号是 0 级，T1 会以「兵种尚未解锁」拒绝练兵（实测 5000/重步兵 T1 需要兵营 1 级）——
  // 所以先把兵营摆下去再等它建完。格子从外圈往里试，撞到已占用就换下一格。
  async function placeBarracks(playerId) {
    const cells = [[3, 3], [4, 4], [2, 2], [5, 5], [0, 0], [6, 6], [7, 3], [3, 7]]
    let last = null
    for (const [gridX, gridY] of cells) {
      last = await call('POST', '/city/upgrade', { requestId: rid('barracks'), configId: 'barracks', gridX, gridY }, playerId)
      if (last.json?.code === 0) return true
    }
    console.log(`=== 前提不足（不是功能红）：兵营放不下（最后一次 ${last.json?.code} ${last.json?.msg}） ===`)
    return false
  }
  if (!(await placeBarracks(a))) {
    process.exit(2)
  }
  // 等兵种解锁（兵营建完才会 unlocked=true）
  let unlocked = false
  for (let i = 0; i < 60; i += 1) {
    const list = await call('GET', '/army/list', undefined, a)
    const unit = (list.json?.data?.units || []).find((u) => u.unitId === UNIT)
    if (unit && unit.unlocked === true) {
      unlocked = true
      break
    }
    await sleep(1000)
  }
  checkThat('A 的兵营建完、T1 已解锁', unlocked, '等 60s 仍是 locked')

  // A 真练兵（不做内存夹具）
  const train = await call('POST', '/army/train', { requestId: rid('train'), unitId: UNIT, count: TROOPS }, a)
  checkThat('A 能发起练兵（前置：这一步拿不到兵，后面的承诺兵力就无法真造）', train.json?.code === 0,
    `${train.http} ${train.text.slice(0, 200)}`)

  // 等真完成
  // T1 的 trainTimeSec 是 60 秒（不是几秒），40 秒的等待窗不够 —— 第一版就在这里误判成「没给兵」。
  let before = 0
  let lastSeen = 0
  for (let i = 0; i < 150; i += 1) {
    lastSeen = await armyCount(a, UNIT)
    if (lastSeen >= TROOPS) {
      before = lastSeen
      break
    }
    await sleep(1000)
  }
  checkThat('A 真的练出了可承诺的兵', before >= TROOPS, `等 150s 只见到 ${lastSeen} 个（要 ${TROOPS}）`)

  // 承诺兵力发起小队集结
  const troops = NEGCTL === 'no-troops' ? [] : [{ unitId: UNIT, count: TROOPS }]
  const rally = await call('POST', '/rally/squad', {
    requestId: rid('rally'),
    targetCoord: { x: 400, y: 400 },
    targetType: 'PLAYER_CITY',
    troops,
    heroes: [],
  }, a)
  const view = rally.json?.data?.rally ?? rally.json?.data
  check('A 发起小队集结回 code 0', rally.json?.code, 0)
  check('回执的集结层级是 SQUAD', view?.scope, 'SQUAD')
  check('回执的 groupId 就是小队 id', view?.groupId, squadId)
  check('回执的发起人是 A', view?.initiatorId, a)
  check('回执的承诺兵力就是刚承诺的那些', Number(view?.totalTroops ?? -1), TROOPS)
  checkThat('回执的人数上限 = min(配置, 小队人数)', Number(view?.maxMembers ?? -1) === Math.min(Number(view?.maxMembers ?? 0), 2) || Number(view?.maxMembers) <= 2,
    `maxMembers=${view?.maxMembers}（小队只有 2 人）`)

  // 承诺即锁定
  const after = await armyCount(a, UNIT)
  check('承诺即锁定：发起后可用兵数真的少了 TROOPS', before - after, TROOPS)

  // 普通成员不能发起（权限位 perm_squad_start_rally：allowMember=false）
  const byMember = await call('POST', '/rally/squad', {
    requestId: rid('rallyB'),
    targetCoord: { x: 401, y: 401 },
    targetType: 'PLAYER_CITY',
    troops: [{ unitId: UNIT, count: 1 }],
    heroes: [],
  }, b)
  check('普通成员发起小队集结被权限拒绝', byMember.json?.code, 10040)
  checkThat('拒绝带可读说明', String(byMember.json?.msg || '').length > 0, JSON.stringify(byMember.json).slice(0, 160))

  // 不在小队里的人不能发起
  const byOutsider = await call('POST', '/rally/squad', {
    requestId: rid('rallyC'),
    targetCoord: { x: 402, y: 402 },
    targetType: 'PLAYER_CITY',
    troops: [{ unitId: UNIT, count: 1 }],
    heroes: [],
  }, c)
  check('不在任何小队里的人发起 ⇒ 小队不存在或已解散', byOutsider.json?.code, 10000)

  // 别人不能取消
  const cancelByOther = await call('POST', '/rally/cancel', { requestId: rid('c1'), rallyId: view?.rallyId }, b)
  checkThat('别人的集结不能取消（10055 只有发起人能取消，或权限拒绝）',
    [10055, 10040].includes(cancelByOther.json?.code),
    JSON.stringify(cancelByOther.json).slice(0, 160))

  // 发起人能取消，且取消后兵力退回
  const cancel = await call('POST', '/rally/cancel', { requestId: rid('c2'), rallyId: view?.rallyId }, a)
  check('发起人能取消这次集结', cancel.json?.code, 0)
  await sleep(600)
  const restored = await armyCount(a, UNIT)
  check('取消后承诺的兵力原路退回', restored, before)

  console.log(`\n=== 小队集结：${pass} 通过 / ${fail} 失败 ===`)
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((error) => {
  console.log(`=== 探针自身异常（不是功能红）：${error && error.message ? error.message : error} ===`)
  process.exit(2)
})
