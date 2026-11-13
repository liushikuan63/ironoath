#!/usr/bin/env node
/**
 * 城内建造队列的暂停 / 恢复 / 帮助加速（`/city/pause`、`/city/resume`、`/city/speedUp`）。
 *
 * <p><b>为什么单独一份</b>：这三条写接口在覆盖面表上一直「没有任何探针碰过」。
 * 而它们保护的是一条**容易做错的语义**：暂停必须让时钟真的停下、恢复要把这段时间原样还回去。
 * `CityState.pause` 的注释里写着早先那版没有 `now`，于是「暂停」只是不显示倒计时 ——
 * 时钟照走、恢复即完工，玩家以为自己保住的时间根本没被保住，而屏上一切正常。
 *
 * <p><b>为什么不点界面</b>：暂停按钮只在「升级中」的建筑行上出现，要先造出一条升级队列，
 * 再按网格坐标点中那一行 —— 那是面板导航探针的活。这里直接用真 HTTP 走同一批端点，
 * 断言的是服务端语义（时钟是否真停、剩余时间是否原样接上、加速是否真扣了秒数）。
 *
 * <p><b>失败即红，退出码非零</b>；`CITY_CLOCK_NEGCTL=no-pause` 跳过那次 pause 调用，
 * 让「暂停期间时钟不走」那条必须红。
 */
const BASE = (process.env.BACKEND_ORIGIN || '').replace(/\/+$/, '')
if (BASE === '') {
  console.error('缺 BACKEND_ORIGIN（例：BACKEND_ORIGIN=http://127.0.0.1:8199）')
  process.exit(2)
}
const NEGCTL = (process.env.CITY_CLOCK_NEGCTL || '').trim()
const PAUSE_WAIT_MS = Number(process.env.CITY_CLOCK_PAUSE_WAIT_MS ?? 6000)

let passed = 0
const failures = []
function check(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  if (ok) { passed += 1; console.log(`  PASS  ${name}`) }
  else { failures.push(name); console.log(`  FAIL  ${name}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`) }
}
function checkThat(name, ok, detail = '') {
  if (ok) { passed += 1; console.log(`  PASS  ${name}`) }
  else { failures.push(name); console.log(`  FAIL  ${name}${detail ? '：' + detail : ''}`) }
}

const rid = (tag) => `cityclock-${tag}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`

async function call(method, url, body, playerId) {
  const res = await fetch(BASE + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(playerId ? { 'X-Player-Id': playerId } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON 也照样报读数 */ }
  return { http: res.status, code: json?.code ?? null, data: json?.data ?? null, raw: text.slice(0, 200) }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function newPlayer(tag) {
  const res = await call('POST', '/player/init', {
    requestId: rid(tag), deviceId: `dev-${rid(tag)}`, nickName: `城钟${tag}`, clientTime: Date.now(), wxCode: '',
  })
  if (res.code !== 0 || !res.data?.playerId) throw new Error(`建号失败：${res.raw}`)
  return res.data.playerId
}

/** 从 /city/list 里挑一栋能升级的建筑（优先已放置、未升级中的低等级建筑）。 */
function pickUpgradable(list) {
  const rows = list?.data?.buildings ?? []
  const idle = rows.filter((b) => b.status === 'IDLE' || b.status === 'READY')
  idle.sort((a, b) => a.level - b.level)
  return idle[0] ?? null
}

async function main() {
  console.log(`=== 城内队列时钟：后端 ${BASE}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
  const playerId = await newPlayer('a')

  // ---- A 组：先造一条真的升级队列 ----
  let list = await call('GET', '/city/list', undefined, playerId)
  check('A 城内列表读得出来', list.code, 0)
  let target = pickUpgradable(list)
  if (target === null) {
    // 没有可升级建筑时先放一栋（建造也是升级队列的一种形态）
    const opts = list?.data?.buildOptions ?? []
    checkThat('A 有建筑可放或可升级', opts.length > 0, `buildings=${list?.data?.buildings?.length ?? 0} buildOptions=0`)
    if (opts.length === 0) return finish()
    const placed = await call('POST', '/city/upgrade', {
      requestId: rid('place'), configId: opts[0].configId, gridX: opts[0].gridX ?? 0, gridY: opts[0].gridY ?? 0,
    }, playerId)
    check('A 放下一栋建筑（造出队列）', placed.code, 0)
    list = await call('GET', '/city/list', undefined, playerId)
    target = (list?.data?.buildings ?? []).find((b) => b.status === 'UPGRADING') ?? null
  } else {
    const up = await call('POST', '/city/upgrade', {
      requestId: rid('up'), configId: target.configId, gridX: null, gridY: null,
    }, playerId)
    check('A 点一栋建筑开始升级', up.code, 0)
    list = await call('GET', '/city/list', undefined, playerId)
    target = (list?.data?.buildings ?? []).find((b) => b.configId === target.configId) ?? null
  }
  checkThat('A 找到进入升级的那一行', target !== null && target.status === 'UPGRADING', `status=${target?.status}`)
  if (target === null || target.status !== 'UPGRADING') return finish()

  const buildingId = target.id
  const beforePause = target.remainingSeconds

  // ---- B 组：暂停 ----
  const paused = await call('POST', '/city/pause', { requestId: rid('pause'), buildingId }, playerId)
  check('B 暂停回 code 0', paused.code, 0)
  if (paused.data !== null) {
    check('B 暂停后状态是 PAUSED', paused.data.status, 'PAUSED')
    check('B 暂停时剩余秒数为 0（服务端不给暂停中的建筑算倒计时）', paused.data.remainingSeconds, 0)
  }

  // ---- C 组：暂停期间时钟真的不走（这条是关键判据）----
  // 依据 `CityState.pause(instanceId, now)` 的注释：早先没有 `now` 时「暂停」只是不显示倒计时，
  // 时钟照走、恢复即完工。真正该验的是：等一段时间再恢复，剩余时间**原样接上**。
  if (NEGCTL === 'clock-runs') {
    // 自检口：暂停之后立刻恢复，把「暂停期间状态」故意弄回 UPGRADING，
    // C 组那两条必须由此变红 —— 否则说明它们根本没在看时钟，只是照着读数抄。
    await call('POST', '/city/resume', { requestId: rid('negctl-resume'), buildingId }, playerId)
  }
  await sleep(PAUSE_WAIT_MS)
  const midList = await call('GET', '/city/list', undefined, playerId)
  const midRow = (midList?.data?.buildings ?? []).find((b) => b.id === buildingId) ?? null
  checkThat('C 暂停期间那栋楼仍在城列表里', midRow !== null, `buildingId=${buildingId}`)
  check('C 暂停期间状态保持 PAUSED', midRow?.status, 'PAUSED')
  check('C 暂停期间剩余秒数仍是 0', midRow?.remainingSeconds, 0)

  // ---- D 组：恢复要把暂停的那段时间还回去 ----
  const resumed = await call('POST', '/city/resume', { requestId: rid('resume'), buildingId }, playerId)
  check('D 恢复回 code 0', resumed.code, 0)
  if (resumed.data !== null) {
    check('D 恢复后状态是 UPGRADING', resumed.data.status, 'UPGRADING')
    const back = resumed.data.remainingSeconds
    checkThat('D 恢复后剩余时间与暂停前基本一致（暂停的时段被原样还回来）',
      Math.abs(back - beforePause) <= 3, `暂停前=${beforePause}s 恢复后=${back}s 等待=${PAUSE_WAIT_MS / 1000}s`)
    checkThat('D 恢复后的剩余时间没有被暂停等待吃掉', back >= beforePause - 3,
      `若这里是 0 或明显变小，说明暂停期间时钟还在走（正是 CityState.pause 注释里那个老 bug）`)
  }

  // ---- E 组：帮助加速真的扣秒数 ----
  const sped = await call('POST', '/city/speedUp', {
    requestId: rid('speed'), buildingId, source: 'ALLIANCE', itemId: null,
  }, playerId)
  check('E 加速回 code 0', sped.code, 0)
  if (sped.data !== null) {
    checkThat('E 加速确实提前了秒数（>0）', sped.data.reducedSeconds > 0, `reducedSeconds=${sped.data.reducedSeconds}`)
    const beforeSpeed = resumed.data === null ? null : resumed.data.remainingSeconds
    checkThat('E 加速后的剩余时间 ≤ 加速前', beforeSpeed === null || sped.data.remainingSeconds <= beforeSpeed,
      `前=${beforeSpeed}s 后=${sped.data.remainingSeconds}s`)
  }

  // ---- F 组：幂等键 ----
  const dupId = rid('dup')
  const first = await call('POST', '/city/pause', { requestId: dupId, buildingId }, playerId)
  const second = await call('POST', '/city/pause', { requestId: dupId, buildingId }, playerId)
  check('F 首次带新 requestId 的暂停回 code 0', first.code, 0)
  checkThat('F 同一 requestId 再来一次被拒（幂等键生效）', second.code !== 0,
    `第二次 code=${second.code} raw=${second.raw}`)

  return finish()
}

function finish() {
  console.log(`=== 城内队列时钟：${passed} 通过 / ${failures.length} 失败 ===`)
  if (failures.length > 0) {
    console.log('失败清单：\n' + failures.map((f) => `  - ${f}`).join('\n'))
    process.exit(1)
  }
}

main().catch((err) => {
  console.error(`运行失败：${err && err.message ? err.message : err}`)
  process.exit(2)
})
