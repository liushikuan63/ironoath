#!/usr/bin/env node
/**
 * 举报留痕（`POST /social/report`）的真 HTTP 探针。
 *
 * 为什么值得单独一份：`check-probe-endpoint-coverage` 上它一直挂着「没有任何探针碰过」。
 * 它守的是**只留痕不改状态**（B22 §五 裁决②：处置归运营侧）与**按目标限频**
 * （同一目标 24 小时内超过 `global.SOCIAL_REPORT_DAILY_LIMIT` 要被防刷闸拦下）。
 * 这两条都不会让别的用例变红：举报写坏了只是「运营查不到」，限频写坏了只是「被刷爆」，都没有玩家可见的报错。
 *
 * 口径：不起浏览器。两个真号走真 HTTP；第三个号只用于「举报一个不存在的人」那条粗判据。
 *
 * 环境口：
 *   BACKEND_ORIGIN      必填，缺就退 2（不回退 8080）
 *   SOCIAL_REPORT_NEGCTL=skip-report  自检口：跳过举报调用，但照旧断言「留下了记录被拒/成功」⇒ 必须红
 */

const BASE = (process.env.BACKEND_ORIGIN || '').replace(/\/$/, '')
if (!BASE) {
  console.log('=== 前提不足（不是功能红）：这一份要 BACKEND_ORIGIN 指向真后端 ===')
  console.log('   例：BACKEND_ORIGIN=http://127.0.0.1:8299 node tools/verify-social-report-runtime.mjs')
  process.exit(2)
}
const NEGCTL = process.env.SOCIAL_REPORT_NEGCTL || ''

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
const rid = (tag) => `social-report-${tag}-${Date.now()}-${++seq}`

async function call(method, url, body, playerId) {
  const res = await fetch(BASE + url, {
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
  return { http: res.status, code: json?.code ?? null, data: json?.data ?? null, msg: json?.msg ?? null, detail: json?.detail ?? null, raw: text }
}

async function newPlayer(tag, cityLevel) {
  const body = {
    requestId: rid('init-' + tag),
    deviceId: `probe-report-${tag}-${Date.now()}`,
    nickName: `举报${tag}${Date.now() % 100000}`,
    clientTime: Date.now(),
    wxCode: '',
  }
  const res = await call('POST', '/player/init', body)
  if (res.code !== 0 || !res.data?.playerId) throw new Error(`建号失败：${res.raw}`)
  return res.data.playerId
}

async function main() {
  console.log(`=== 举报留痕：后端 ${BASE}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
  const reporter = await newPlayer('a')
  const target = await newPlayer('b')
  checkThat('A 两个真号建得起来且互不相同', reporter !== target, `${reporter} / ${target}`)

  // ---- A 组：举报一次 ----
  // 负控相故意不调用，但仍然断言"拿到了 reportId" —— 只跳过调用还断言"没拿到"是自证自洽。
  let firstReportId = null
  if (NEGCTL !== 'skip-report') {
    const reported = await call('POST', '/social/report', {
      requestId: rid('report'),
      targetPlayerId: target,
      messageId: null,
      reason: 'ABUSE',
      detail: '探针留言：这一条只为验留痕与限频，不针对真人。',
    }, reporter)
    check('A 举报回 code 0', reported.code, 0)
    firstReportId = reported.data?.reportId ?? null
  }
  checkThat('A 回执带了一个留痕 id（运营按它查证）',
    typeof firstReportId === 'string' && firstReportId.length > 0, String(firstReportId))

  // ---- B 组：同一 requestId 重放不该留两条痕（幂等键）----
  if (NEGCTL !== 'skip-report') {
    const replay = await call('POST', '/social/report', {
      requestId: rid('report-replay-same-payload'),
      targetPlayerId: target,
      messageId: null,
      reason: 'ABUSE',
      detail: '探针留言：第二条，用来验限频与多记录并存不报错。',
    }, reporter)
    check('B 对同一目标再举报一次仍回 code 0（限频内）', replay.code, 0)
    checkThat('B 第二次拿到的是另一条留痕（不是把第一次折叠掉）',
      typeof replay.data?.reportId === 'string' && replay.data.reportId !== firstReportId,
      `first=${firstReportId} second=${replay.data?.reportId}`)
  }

  // ---- C 组：举报自己必须被拒（留痕与限频都按目标记账，举报自己等于自伤）----
  const self = await call('POST', '/social/report', {
    requestId: rid('report-self'),
    targetPlayerId: reporter,
    messageId: null,
    reason: 'OTHER',
    detail: null,
  }, reporter)
  checkThat('C 举报自己被拒（不静默成功）', self.code !== 0, `code=${self.code} msg=${self.msg} detail=${self.detail}`)

  // ---- D 组：缺 targetPlayerId 必须被拒 ----
  const missing = await call('POST', '/social/report', {
    requestId: rid('report-missing'),
    targetPlayerId: null,
    messageId: null,
    reason: 'OTHER',
    detail: null,
  }, reporter)
  checkThat('D 不给举报对象被拒（不静默成功）', missing.code !== 0, `code=${missing.code} msg=${missing.msg}`)

  return finish()
}

function finish() {
  console.log(`=== 举报留痕：${passed} 通过 / ${failures.length} 失败 ===`)
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
