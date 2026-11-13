#!/usr/bin/env node
/**
 * 社交关系写路径的真后端探针（#2529）。
 *
 * 为什么要有这一份：`check-probe-endpoint-coverage.sh` 现跑有 30 条「没有任何探针碰过」的绑定路径，
 * 里面六条是社交关系这一族 —— 两条读（`GET /social/blocks`、`GET /social/follows`）与四条写
 * （`POST /social/block`、`/unblock`、`/follow`、`/unfollow`）。它们一直是「代码在、没人验」：
 * 拉黑要影响私聊与频道过滤、关注要出现在会话列表的昵称表里，写坏了只会表现为「名单不对」，
 * 不会有任何红。
 *
 * 这一份刻意**不起浏览器**：这几条接口的前置条件只有一个「另一个玩家」（用两个真号即可），
 * 值得用最便宜的形态把「写进去 → 读回来」这条链钉住。界面上的那一层另由社交页探针覆盖。
 *
 * 判据（每条都能失败）：
 *   A 两个真号建得起来（否则后续全是假绿）
 *   B 初始名单是空的（否则「加进去了」不算数）
 *   C 拉黑后读回：名单里恰好有他一次，且 `name` 不是内部 id（铁律：不把内部标识印给玩家）
 *   D 重复拉黑仍然只有一条（幂等）
 *   E 取消拉黑后读回：名单里没有他
 *   F 关注后读回：名单里有他，且带上昵称
 *   G 重复关注仍然只有一条（幂等）
 *   H 取消关注后读回：名单里没有他
 *   I 拉黑一个不存在的玩家不许 5xx（要么业务拒绝要么忽略，但不能炸）
 *
 * 自检口 `SOCIAL_REL_NEGCTL`：
 *   `no-block`  —— 跳过拉黑这一步，C 必须红（证明 C 真的在看服务端状态，不是在看本地变量）
 *   `no-follow` —— 跳过关注这一步，F 必须红
 */
import { setTimeout as sleep } from 'node:timers/promises'

const BACKEND = process.env.BACKEND_ORIGIN ?? process.env.SOCIAL_REL_BACKEND ?? ''
const NEGCTL = process.env.SOCIAL_REL_NEGCTL ?? ''

if (!BACKEND) {
  console.error('[social-rel][前置] 缺 BACKEND_ORIGIN（本探针不会回退到 8080：跑错后端会得到一片假绿）')
  process.exit(2)
}

let failures = 0
let passes = 0
const check = (label, actual, expected) => {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  if (ok) { passes += 1; console.log(`  PASS  ${label}`) }
  else { failures += 1; console.log(`  FAIL  ${label}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`) }
}
const checkThat = (label, ok, detail) => {
  if (ok) { passes += 1; console.log(`  PASS  ${label}`) }
  else { failures += 1; console.log(`  FAIL  ${label}${detail === undefined ? '' : `：${JSON.stringify(detail).slice(0, 300)}`}`) }
}

const post = async (url, body, playerId) => {
  const headers = { 'Content-Type': 'application/json' }
  if (playerId) headers['X-Player-Id'] = playerId
  const response = await fetch(`${BACKEND}${url}`, { method: 'POST', headers, body: JSON.stringify(body) })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON 由调用处报 */ }
  return { status: response.status, json, text }
}
const get = async (url, playerId) => {
  const response = await fetch(`${BACKEND}${url}`, { headers: playerId ? { 'X-Player-Id': playerId } : {} })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 同上 */ }
  return { status: response.status, json, text }
}

const stamp = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`
const initPlayer = async (nickName) => {
  const deviceId = `social-rel-${nickName}-${stamp}`
  const init = await post('/player/init', {
    requestId: `social-rel-init-${nickName}-${stamp}`,
    deviceId, nickName, clientTime: Date.now(), wxCode: '',
  })
  return init
}

console.log(`=== 社交关系写路径（真后端 ${BACKEND}${NEGCTL ? `，负控=${NEGCTL}` : ''}）===`)

const mine = await initPlayer('关系探针甲')
const other = await initPlayer('关系探针乙')
check('A1 甲号建得起来', mine.json?.code, 0)
check('A2 乙号建得起来', other.json?.code, 0)
const meId = mine.json?.data?.playerId
const otherId = other.json?.data?.playerId
checkThat('A3 两个号是不同的玩家', Boolean(meId) && Boolean(otherId) && meId !== otherId, { meId, otherId })
if (!meId || !otherId || meId === otherId) {
  console.error('=== 社交关系：0 通过 / 1 失败（建号失败，后续判据无法执行）===')
  process.exit(1)
}

// ---- B 初始名单为空 ----
const blocks0 = await get('/social/blocks', meId)
const follows0 = await get('/social/follows', meId)
check('B1 初始拉黑名单为空', blocks0.json?.data?.blocked?.length, 0)
check('B2 初始关注名单为空', follows0.json?.data?.friends?.length, 0)

// ---- C/D 拉黑 ----
if (NEGCTL !== 'no-block') {
  const blocked = await post('/social/block', { requestId: `social-rel-block-1-${stamp}`, targetPlayerId: otherId }, meId)
  check('C0 拉黑回 code 0', blocked.json?.code, 0)
}
const blocks1 = await get('/social/blocks', meId)
const rows1 = blocks1.json?.data?.blocked ?? []
check('C 拉黑后名单里恰好有他一次', rows1.filter((row) => row.playerId === otherId).length, 1)
const name1 = rows1.find((row) => row.playerId === otherId)?.name
checkThat('C2 名单里带的是昵称而不是内部 id', typeof name1 === 'string' && name1.length > 0 && name1 !== otherId, { name: name1, playerId: otherId })

const blocked2 = await post('/social/block', { requestId: `social-rel-block-2-${stamp}`, targetPlayerId: otherId }, meId)
check('D0 重复拉黑仍回 code 0', blocked2.json?.code, 0)
const blocks2 = await get('/social/blocks', meId)
check('D 重复拉黑后名单里仍只有一条', (blocks2.json?.data?.blocked ?? []).filter((row) => row.playerId === otherId).length, 1)

// ---- E 取消拉黑 ----
const unblocked = await post('/social/unblock', { requestId: `social-rel-unblock-${stamp}`, targetPlayerId: otherId }, meId)
check('E0 取消拉黑回 code 0', unblocked.json?.code, 0)
const blocks3 = await get('/social/blocks', meId)
check('E 取消拉黑后名单里没有他', (blocks3.json?.data?.blocked ?? []).filter((row) => row.playerId === otherId).length, 0)

// ---- F/G 关注 ----
if (NEGCTL !== 'no-follow') {
  const followed = await post('/social/follow', { requestId: `social-rel-follow-1-${stamp}`, targetPlayerId: otherId }, meId)
  check('F0 关注回 code 0', followed.json?.code, 0)
}
const follows1 = await get('/social/follows', meId)
const friends1 = follows1.json?.data?.friends ?? []
check('F 关注后名单里恰好有他一次', friends1.filter((row) => row.playerId === otherId).length, 1)
const friendName = friends1.find((row) => row.playerId === otherId)?.name
checkThat('F2 关注名单里带昵称', typeof friendName === 'string' && friendName.length > 0 && friendName !== otherId, { name: friendName })

const followed2 = await post('/social/follow', { requestId: `social-rel-follow-2-${stamp}`, targetPlayerId: otherId }, meId)
check('G0 重复关注仍回 code 0', followed2.json?.code, 0)
const follows2 = await get('/social/follows', meId)
check('G 重复关注后名单里仍只有一条', (follows2.json?.data?.friends ?? []).filter((row) => row.playerId === otherId).length, 1)

// ---- H 取消关注 ----
const unfollowed = await post('/social/unfollow', { requestId: `social-rel-unfollow-${stamp}`, targetPlayerId: otherId }, meId)
check('H0 取消关注回 code 0', unfollowed.json?.code, 0)
const follows3 = await get('/social/follows', meId)
check('H 取消关注后名单里没有他', (follows3.json?.data?.friends ?? []).filter((row) => row.playerId === otherId).length, 0)

// ---- I 不存在的目标不许炸 ----
const ghost = await post('/social/block', { requestId: `social-rel-block-ghost-${stamp}`, targetPlayerId: `ghost-${stamp}` }, meId)
checkThat('I 拉黑不存在的玩家不许 5xx', ghost.status < 500, { status: ghost.status, body: ghost.text?.slice(0, 200) })

await sleep(50)
console.log(`=== 社交关系：${passes} 通过 / ${failures} 失败 ===`)
process.exit(failures === 0 ? 0 : 1)
