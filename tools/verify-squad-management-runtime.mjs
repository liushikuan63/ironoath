#!/usr/bin/env node
/**
 * 小队的管理面：退出、踢人、转让队长、解散（`/squad/leave`、`/squad/kick`、`/squad/transfer`、`/squad/disband`）。
 *
 * <p><b>为什么单独一份</b>：这四条在覆盖面表上一直「没有任何探针碰过」。它们守的是**权限与存亡**：
 * 队长不能直接退（必须先转让或解散）、踢人要有 `KICK_MEMBER` 位、转让之后原队长变成普通队员。
 * 写坏了症状都只是「按钮点了没反应」或「人还在队里」—— 屏上不会报错，只会有人被静默地留在原地。
 *
 * <p><b>为什么不点界面</b>：这四条是纯状态迁移，界面上要逐个点开成员菜单；这里用两个真号走真 HTTP，
 * 断言的是服务端语义（谁是队长、谁还能不能动）。界面那一层由社交页探针覆盖。
 *
 * <p><b>失败即红，退出码非零</b>。自检口 `SQUAD_MGMT_NEGCTL=skip-transfer` 跳过那次转让，
 * 让「转让后原队长不再能踢人」那条必须红。
 */
const BASE = (process.env.BACKEND_ORIGIN || '').replace(/\/+$/, '')
if (BASE === '') {
  console.error('缺 BACKEND_ORIGIN（例：BACKEND_ORIGIN=http://127.0.0.1:8199）')
  process.exit(2)
}
const NEGCTL = (process.env.SQUAD_MGMT_NEGCTL || '').trim()

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

const rid = (tag) => `squadmgmt-${tag}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`

async function call(method, url, body, playerId) {
  const res = await fetch(BASE + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(playerId ? { 'X-Player-Id': playerId } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON 也照样报读数 */ }
  return { http: res.status, code: json?.code ?? null, data: json?.data ?? null, raw: text.slice(0, 240) }
}

async function newPlayer(tag) {
  const res = await call('POST', '/player/init', {
    requestId: rid(tag), deviceId: `dev-${rid(tag)}`, nickName: `队务${tag}`, clientTime: Date.now(), wxCode: '',
  })
  if (res.code !== 0 || !res.data?.playerId) throw new Error(`建号失败：${res.raw}`)
  return res.data.playerId
}

const summary = (playerId) => call('GET', '/social/summary', undefined, playerId)
// SquadMember 生成 DTO 的字段叫 id（不是 playerId）：照抄别的 DTO 会读出 undefined，
// 于是「队伍里有几个人」看起来永远不对 —— 这条是本探针自己第一版踩过的坑。
const memberIds = (view) => (view?.members ?? []).map((m) => m.id ?? m.playerId)

async function main() {
  console.log(`=== 小队管理面：后端 ${BASE}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
  const leader = await newPlayer('lead')
  const mate = await newPlayer('mate')
  checkThat('A 两个真号建得起来且不同人', leader !== mate, `${leader} / ${mate}`)

  // ---- A 组：建队 + 入队 ----
  const created = await call('POST', '/squad/create', { requestId: rid('create'), name: `队务${Date.now() % 100000}` }, leader)
  if (created.code === 10004) {
    // 小队有主城等级门槛（`Squad.checkUnlock`：主城级 + 开服天数）。这是**前提不足**，不是功能红：
    // 跑提速档后端（`IRONOATH_DEV_CITY_LEVEL=16`）再来，别把「新号一级」读成缺陷。
    console.log(`=== 前提不足（不是功能红）：${JSON.parse(created.raw).message ?? created.raw} ===`)
    console.log('   这一份要跑在开着提速档的后端上（IRONOATH_DEV_CITY_LEVEL=16）。')
    process.exit(2)
  }
  check('A 建队回 code 0', created.code, 0)
  const squadId = created.data?.squad?.id ?? null
  checkThat('A 建队后我有小队且我是队长', squadId !== null && created.data?.squad?.leaderId === leader,
    `squadId=${squadId} leaderId=${created.data?.squad?.leaderId}`)
  if (squadId === null) return finish()

  const joined = await call('POST', '/squad/join', { requestId: rid('join'), squadId }, mate)
  check('A 第二个人加入回 code 0', joined.code, 0)

  let view = (await summary(leader)).data?.squad ?? null
  checkThat('A 队伍里现在两个人', memberIds(view).length === 2, `members=${JSON.stringify(memberIds(view))}`)
  check('A 队长仍是我', view?.leaderId, leader)

  // ---- B 组：队员不能踢人、也不能解散（权限位是真的在判）----
  const mateKick = await call('POST', '/squad/kick', { requestId: rid('mkick'), memberId: leader }, mate)
  checkThat('B 队员踢不了人（权限位生效）', mateKick.code !== 0, `code=${mateKick.code} raw=${mateKick.raw}`)
  const mateDisband = await call('POST', '/squad/disband', { requestId: rid('mdisband') }, mate)
  checkThat('B 队员解散不了小队（权限位生效）', mateDisband.code !== 0, `code=${mateDisband.code} raw=${mateDisband.raw}`)

  // ---- C 组：队长不能直接退（必须转让或解散）----
  const leaderLeave = await call('POST', '/squad/leave', { requestId: rid('lleave') }, leader)
  checkThat('C 队长直接退出被拒（队长必须先转让或解散）', leaderLeave.code !== 0,
    `code=${leaderLeave.code} raw=${leaderLeave.raw}`)

  // ---- D 组：转让队长 ----
  // 负控相（`SQUAD_MGMT_NEGCTL=skip-transfer`）**故意不转让、但照旧按「已转让」断言**：
  // 这样 D/E/G 必须红。第一版负控写成了「不转让就断言队长还是原来那个」——那样它会绿，
  // 等于只证明了「探针自洽」，没证明判据会咬到真状态（assertion-discipline 的负控要求）。
  if (NEGCTL !== 'skip-transfer') {
    const transferred = await call('POST', '/squad/transfer', { requestId: rid('transfer'), memberId: mate }, leader)
    check('D 转让回 code 0', transferred.code, 0)
  }
  view = (await summary(mate)).data?.squad ?? null
  check('D 转让后新队长是他', view?.leaderId, mate)
  // 转让之后原队长成了普通队员 —— 这时他去踢人必须被拒（这条是「转让是否真的生效」的反证）
  const staleKick = await call('POST', '/squad/kick', { requestId: rid('stale'), memberId: mate }, leader)
  checkThat('D 转让后原队长不再能踢人', staleKick.code !== 0, `code=${staleKick.code} raw=${staleKick.raw}`)

  // ---- E 组：新队长踢掉原队长 ----
  const kicked = await call('POST', '/squad/kick', { requestId: rid('kick'), memberId: leader }, mate)
  check('E 新队长踢人回 code 0', kicked.code, 0)
  const afterKick = (await summary(leader)).data?.squad ?? null
  checkThat('E 被踢的人已经没有小队了', afterKick === null, `squad=${afterKick === null ? 'null' : afterKick.id}`)

  // ---- F 组：被踢之后再退出必须被拒 ----
  const leaveAgain = await call('POST', '/squad/leave', { requestId: rid('leave2') }, leader)
  checkThat('F 不在队里时退出被拒', leaveAgain.code !== 0, `code=${leaveAgain.code} raw=${leaveAgain.raw}`)

  // ---- G 组：解散 ----
  const disbanded = await call('POST', '/squad/disband', { requestId: rid('disband') }, mate)
  check('G 队长解散回 code 0', disbanded.code, 0)
  const afterDisband = (await summary(mate)).data?.squad ?? null
  checkThat('G 解散后自己也没有小队了', afterDisband === null, `squad=${afterDisband === null ? 'null' : afterDisband.id}`)

  return finish()
}

function finish() {
  console.log(`=== 小队管理面：${passed} 通过 / ${failures.length} 失败 ===`)
  if (failures.length > 0) {
    console.log('失败清单：\n' + failures.map((f) => `  - ${f}`).join('\n'))
    process.exit(1)
  }
}

main().catch((err) => {
  console.error(`运行失败：${err && err.message ? err.message : err}`)
  process.exit(2)
})
