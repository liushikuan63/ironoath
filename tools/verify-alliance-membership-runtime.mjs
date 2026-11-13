#!/usr/bin/env node
/**
 * 联盟成员管理面（`POST /alliance/kick` 与 `POST /alliance/disband`）的真 HTTP 探针。
 *
 * 为什么值得单独一份：这两条在 `check-probe-endpoint-coverage` 上一直挂着「没有任何探针碰过」。
 * 但它们守的是**权限位 + 名册一致性**——踢错人、解散后名册没清、非盟主能解散，症状都只是
 * 「名单不对」，任何用例都不会因此变红；等玩家报上来时，现场早已散掉。
 *
 * 口径说明：这一份**不起浏览器**。前置条件只是「三个人 + 一个盟」，走真 HTTP 最便宜，
 * 也不会把「点得动 / 点不动」混进读数。UI 侧另有 `verify-social-*` 那一族。
 *
 * 环境口：
 *   BACKEND_ORIGIN      必填，缺就退 2（**不回退 8080**：回退会让人以为跑的是自己要验的那台）
 *   ALLIANCE_MGMT_NEGCTL=skip-disband  自检口：跳过解散调用，但照旧断言「已解散」⇒ 必须红
 *
 * 前提：联盟创建要 `global.ALLIANCE_CREATE_COST_GOLD` 金币（出厂 500），新号只有 200 ⇒
 * 这一份要跑在**开着 dev 提速档**的后端上（`IRONOATH_DEV_CITY_LEVEL=16` +
 * `IRONOATH_DEV_START_AMOUNT=2000000`）。跑在普通档上会以「前提不足」退 2，不冒充功能红。
 */

const BASE = process.env.BACKEND_ORIGIN
if (!BASE) {
  console.log('=== 前提不足（不是功能红）：这一份要 BACKEND_ORIGIN 指向真后端 ===')
  console.log('   例：BACKEND_ORIGIN=http://127.0.0.1:8299 node tools/verify-alliance-membership-runtime.mjs')
  process.exit(2)
}
const NEGCTL = process.env.ALLIANCE_MGMT_NEGCTL || ''

const failures = []
let passed = 0

function check(label, actual, expected) {
  if (actual === expected) {
    passed += 1
    console.log(`  PASS  ${label}`)
  } else {
    failures.push(`${label}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
    console.log(`  FAIL  ${label}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}

function checkThat(label, ok, detail) {
  if (ok) {
    passed += 1
    console.log(`  PASS  ${label}`)
  } else {
    failures.push(`${label}：${detail}`)
    console.log(`  FAIL  ${label}：${detail}`)
  }
}

let seq = 0
const rid = (tag) => `alliance-mgmt-${tag}-${Date.now()}-${++seq}`

async function call(method, url, body, playerId) {
  const res = await fetch(BASE.replace(/\/$/, '') + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(playerId ? { 'X-Player-Id': playerId } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    json = null
  }
  return { http: res.status, code: json?.code ?? null, data: json?.data ?? null, detail: json?.detail ?? null, raw: text }
}

async function newPlayer(tag) {
  const res = await call('POST', '/player/init', {
    requestId: rid('init-' + tag),
    deviceId: `probe-${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    nickName: `盟${tag}${Date.now() % 100000}`,
    clientTime: Date.now(),
    wxCode: '',
  })
  if (res.code !== 0 || !res.data?.playerId) throw new Error(`建号失败：${res.raw}`)
  return res.data.playerId
}

const summary = (playerId) => call('GET', '/social/summary', undefined, playerId)
const allianceOf = async (playerId) => (await summary(playerId)).data?.alliance ?? null
// 名册人数只在 AllianceView.memberCount 上：成员数组走 /alliance/sync 的 changedMembers，摘要里没有。
const memberCount = (alliance) => alliance?.memberCount ?? 0

async function main() {
  console.log(`=== 联盟成员管理面：后端 ${BASE}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
  const leader = await newPlayer('lead')
  const first = await newPlayer('first')
  const second = await newPlayer('second')
  checkThat('A 三个真号建得起来且互不相同', new Set([leader, first, second]).size === 3, `${leader} / ${first} / ${second}`)

  // ---- A 组：建盟 ----
  const created = await call('POST', '/alliance/create', {
    requestId: rid('create'),
    name: `盟${Date.now() % 1000000}`,
    tag: 'PRB',
  }, leader)
  if (created.code !== 0) {
    console.log(`=== 前提不足（不是功能红）：${created.detail ?? created.raw} ===`)
    console.log('   建盟要金币（出厂 500），新号只有 200 ⇒ 这一份要跑在开着 dev 提速档的后端上。')
    process.exit(2)
  }
  const allianceId = created.data?.alliance?.id ?? null
  checkThat('A 建盟后我有联盟且我是盟主', allianceId !== null && created.data?.alliance?.leaderId === leader,
    `allianceId=${allianceId} leaderId=${created.data?.alliance?.leaderId}`)
  if (allianceId === null) return finish()

  // ---- B 组：两个人申请、盟主审核通过 ----
  for (const [tag, player] of [['first', first], ['second', second]]) {
    const applied = await call('POST', '/alliance/apply', { requestId: rid('apply-' + tag), allianceId }, player)
    check(`B ${tag} 申请入盟回 code 0`, applied.code, 0)
    const approved = await call('POST', '/alliance/review', {
      requestId: rid('review-' + tag), applicantId: player, approve: true,
    }, leader)
    check(`B 盟主通过 ${tag} 的申请回 code 0`, approved.code, 0)
  }
  const three = await allianceOf(leader)
  check('B 联盟现在三个人', memberCount(three), 3)

  // ---- C 组：非盟主踢不了人（权限位） ----
  const firstKick = await call('POST', '/alliance/kick', { requestId: rid('fkick'), memberId: second }, first)
  checkThat('C 普通成员踢人被拒（权限位生效）', firstKick.code !== 0, `code=${firstKick.code} detail=${firstKick.detail}`)

  // ---- D 组：盟主踢出第二个人 ----
  const kicked = await call('POST', '/alliance/kick', { requestId: rid('kick'), memberId: second }, leader)
  check('D 盟主踢人回 code 0', kicked.code, 0)
  checkThat('D 被踢的人已经不在盟里', (await allianceOf(second)) === null,
    `alliance=${JSON.stringify(await allianceOf(second))?.slice(0, 80)}`)
  check('D 盟主这边名册剩两个人', memberCount(await allianceOf(leader)), 2)

  // ---- E 组：盟主不能被踢 ----
  const kickLeader = await call('POST', '/alliance/kick', { requestId: rid('kicklead'), memberId: leader }, leader)
  checkThat('E 盟主踢自己也被拒（不能把盟踢空）', kickLeader.code !== 0,
    `code=${kickLeader.code} detail=${kickLeader.detail}`)

  // ---- F 组：非盟主不能解散 ----
  const firstDisband = await call('POST', '/alliance/disband', { requestId: rid('fdisband') }, first)
  checkThat('F 普通成员解散被拒（权限位生效）', firstDisband.code !== 0,
    `code=${firstDisband.code} detail=${firstDisband.detail}`)

  // ---- G 组：盟主解散 ----
  // 负控相**故意不解散、但照旧断言「已解散」**：只跳过调用还断言"还在"，那是自证自洽，
  // 证明不了判据会咬到真状态（assertion-discipline 的负控要求）。
  if (NEGCTL !== 'skip-disband') {
    const disbanded = await call('POST', '/alliance/disband', { requestId: rid('disband') }, leader)
    check('G 盟主解散回 code 0', disbanded.code, 0)
  }
  checkThat('G 解散后盟主自己也没有联盟了', (await allianceOf(leader)) === null,
    `alliance=${JSON.stringify(await allianceOf(leader))?.slice(0, 80)}`)
  checkThat('G 解散后留下的那个成员也没有联盟了', (await allianceOf(first)) === null,
    `alliance=${JSON.stringify(await allianceOf(first))?.slice(0, 80)}`)

  return finish()
}

function finish() {
  console.log(`=== 联盟成员管理面：${passed} 通过 / ${failures.length} 失败 ===`)
  if (failures.length > 0) {
    console.log('失败清单：\n' + failures.map((f) => `  - ${f}`).join('\n'))
    process.exit(1)
  }
  process.exit(0)
}

main().catch((error) => {
  console.log(`=== 探针自身异常（不是产品结论）：${error?.stack ?? error} ===`)
  process.exit(1)
})
