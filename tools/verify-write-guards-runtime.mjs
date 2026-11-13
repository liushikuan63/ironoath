#!/usr/bin/env node
/**
 * 职责：把「写接口拿到不合法的入参时，用业务码说清楚，而不是 500 或静默成功」变成能失败的判据。
 *
 * <p><b>为什么需要这一份</b>：覆盖面门上有 13 条客户端绑定路径没有任何探针碰过，全是写接口
 * （`/hero/equip`、`/stage/sweep`、`/world/recall`、`/rally/quit`、`/battle/share`、`/squad/leave`、
 * `/army/collectTreated` …）。它们的 happy path 需要「有武将 / 三星通关 / 在途行军 / 活跃集结」这类
 * 真前置状态，dev 新号造不出来；但**拒绝面**不需要前置 —— 而「非法入参走 500」恰恰是线上最常见的
 * 一类事故（B10 明写绝不静默失败，5xx 连 msg 都没有）。
 *
 * <p><b>它证明什么、不证明什么</b>：
 *  - 证明：这些路径被真的调用到；非法入参一律回 HTTP 200 + 明确的业务码 + 可读 msg，绝不 5xx；
 *    `/army/collectTreated` 在「没有伤兵」时是幂等的空操作（回 0），不是炸。
 *  - **不证明**：happy path 的发放正确性。这一格刻意只覆盖拒绝面，台账与门注释都照实写明，
 *    因此覆盖面门的下限收紧时，不能把它当成「这些接口已经被验过」。
 *
 * 用法：必填 `BACKEND_ORIGIN`（不给退 2，绝不回退 8080 —— 台账 #371/#372 的教训）。
 * 可选 `WRITE_GUARD_NEGCTL=expect-zero`：把一条本该被拒的期望改成「回 0」，用来证明这套判据会红。
 */
const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-write-guards-runtime] 缺 BACKEND_ORIGIN：不给就不知道要打哪台后端（dev 约定 http://localhost:8199；需要提速档的接口用 8299）')
  process.exit(2)
})()

const NEGCTL = process.env.WRITE_GUARD_NEGCTL ?? ''
const rnd = Math.random().toString(36).slice(2, 10)

let passed = 0
const failures = []

function check(label, actual, expected) {
  if (actual === expected) {
    passed += 1
    console.log(`PASS  ${label}`)
  } else {
    failures.push(label)
    console.log(`FAIL  ${label}：期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}

function checkThat(label, ok, detail) {
  if (ok) {
    passed += 1
    console.log(`PASS  ${label}`)
  } else {
    failures.push(label)
    console.log(`FAIL  ${label}${detail ? '：' + detail : ''}`)
  }
}

async function call(method, path, body, playerId) {
  const res = await fetch(BACKEND + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Player-Id': playerId ?? '' },
    body: method === 'GET' ? undefined : JSON.stringify(body ?? {}),
  })
  let json = null
  try {
    json = await res.json()
  } catch (e) {
    json = { parseError: String(e) }
  }
  return { http: res.status, code: json?.code, msg: json?.msg, detail: json?.detail, data: json?.data }
}

/** 拒绝面期望：业务码 + msg 里必须出现的关键字（msg 是给玩家看的，关键字用来确认「说的是这件事」）。 */
const EXPECT_REJECT = [
  { path: '/hero/equip', body: () => ({ requestId: 'wg-hero-' + rnd, heroId: 'no-such-hero', equipUid: 'no-such-equip' }), code: 1001, keyword: 'slot' },
  { path: '/stage/sweep', body: () => ({ requestId: 'wg-sweep-a-' + rnd, stageId: 'no_such_stage', count: 1 }), code: 1006, keyword: '关卡不存在' },
  { path: '/stage/sweep', body: () => ({ requestId: 'wg-sweep-b-' + rnd, stageId: 'no_such_stage', count: 99 }), code: 1001, keyword: '1~10' },
  { path: '/world/recall', body: () => ({ requestId: 'wg-recall-' + rnd, marchId: 'no-such-march' }), code: 6006, keyword: '行军不存在' },
  { path: '/rally/quit', body: () => ({ requestId: 'wg-rally-' + rnd, rallyId: 'no-such-rally' }), code: 10050, keyword: '集结' },
  { path: '/battle/share', body: () => ({ requestId: 'wg-share-' + rnd, reportId: 'no-such-report' }), code: 1001, keyword: 'channel' },
  { path: '/squad/leave', body: () => ({ requestId: 'wg-squad-' + rnd }), code: 10000, keyword: '小队' },
]

async function main() {
  const init = await call('POST', '/player/init', {
    requestId: 'wg-init-' + rnd,
    deviceId: 'wg-dev-' + rnd,
    nickName: 'wg' + rnd,
    clientTime: Date.now(),
    wxCode: '',
  }, '')
  check('建号回 code 0', init.code, 0)
  const playerId = init.data?.playerId
  checkThat('建号回了 playerId', typeof playerId === 'string' && playerId.length > 0, JSON.stringify(init).slice(0, 200))
  if (!playerId) {
    console.log(`\n=== 写接口拒绝面：${passed} 通过 / ${failures.length} 失败（建号失败，后续无从谈起）===`)
    process.exit(1)
  }

  for (const item of EXPECT_REJECT) {
    const out = await call('POST', item.path, item.body(), playerId)
    const expectedCode = NEGCTL === 'expect-zero' && item.path === '/world/recall' ? 0 : item.code
    check(`${item.path} 非法入参回业务码 ${expectedCode}（不是 5xx、不是静默成功）`, out.code, expectedCode)
    checkThat(`${item.path} 没有 5xx`, out.http < 500, `http=${out.http}`)
    // 具体到「哪个字段不对」的那句话通常落在 detail 里（BizException 的 msg 是给玩家看的概括），
    // 所以关键字要在 msg + detail 合起来的文本里找 —— 只认 msg 会把正确的实现判红（本条首跑就红在这里）。
    const readable = String(out.msg ?? '') + ' ' + String(out.detail ?? '')
    checkThat(`${item.path} 的说明说到了这件事（含「${item.keyword}」）`,
      readable.includes(item.keyword), `msg=${JSON.stringify(out.msg)} detail=${JSON.stringify(out.detail)}`)
    // 拒绝面还必须有 detail 或 msg 之一 —— 只回一个数字等于让玩家/客服无从判断（B10「绝不静默失败」）
    checkThat(`${item.path} 的拒绝带了可读说明`,
      (typeof out.msg === 'string' && out.msg.length > 0) || (typeof out.detail === 'string' && out.detail.length > 0),
      JSON.stringify({ msg: out.msg, detail: out.detail }))
  }

  // 没有伤兵时的「收治」是幂等空操作：不该炸、也不该编出伤兵来
  const treated = await call('POST', '/army/collectTreated', { requestId: 'wg-treat-' + rnd }, playerId)
  check('/army/collectTreated 在「没有伤兵」时是幂等空操作（回 0）', treated.code, 0)
  checkThat('/army/collectTreated 没有 5xx', treated.http < 500, `http=${treated.http}`)

  // 重复同一个 requestId 再点一次：仍必须是同一个答复（幂等），不是第二次换了个结果
  const again = await call('POST', '/stage/sweep', { requestId: 'wg-sweep-a-' + rnd, stageId: 'no_such_stage', count: 1 }, playerId)
  check('同一 requestId 重放得到同一答复（幂等）', again.code, 1006)

  console.log(`\n=== 写接口拒绝面：${passed} 通过 / ${failures.length} 失败 ===`)
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch(err => {
  console.error('[verify-write-guards-runtime] 崩了：' + (err && err.stack ? err.stack : err))
  process.exit(1)
})
