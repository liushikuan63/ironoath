#!/usr/bin/env node
/**
 * 邮件已读（`POST /mail/read`）的真 HTTP 探针。
 *
 * 为什么值得单独一份：这条在 `check-probe-endpoint-coverage` 上一直挂着「没有任何探针碰过」。
 * 它守的是**红点与列表同源**：未读封数由服务端算（`MailListResp.unreadCount`），客户端不自己数。
 * 一旦服务端标了已读却没减计数（或反过来），症状只是「点开一封，红点还在」或「全读完了红点不灭」——
 * 没有任何既有用例会因此变红。
 *
 * 口径：不起浏览器。邮件由 `/ops/mail/send` 造（唯一「凭空给玩家东西」的运维入口，带幂等键与审计），
 * 然后只用客户端真正会调的两条接口（`/mail/list`、`/mail/read`）断言。
 *
 * 环境口：
 *   BACKEND_ORIGIN   必填，缺就退 2（不回退 8080）
 *   OPS_TOKEN        运维令牌，默认 `art-verify-local`（`verify-runtime.sh` 起后端时用的那个，非凭据）
 *   MAIL_READ_NEGCTL=skip-read  自检口：跳过标已读调用，但照旧断言「已读」⇒ 必须红
 */

const BASE = (process.env.BACKEND_ORIGIN || '').replace(/\/$/, '')
if (!BASE) {
  console.log('=== 前提不足（不是功能红）：这一份要 BACKEND_ORIGIN 指向真后端 ===')
  console.log('   例：BACKEND_ORIGIN=http://127.0.0.1:8299 node tools/verify-mail-read-runtime.mjs')
  process.exit(2)
}
const TOKEN = process.env.OPS_TOKEN || 'art-verify-local'
const NEGCTL = process.env.MAIL_READ_NEGCTL || ''

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
const rid = (tag) => `mail-read-${tag}-${Date.now()}-${++seq}`

async function call(method, url, body, playerId, headers = {}) {
  const res = await fetch(BASE + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(playerId ? { 'X-Player-Id': playerId } : {}), ...headers },
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

async function main() {
  console.log(`=== 邮件已读：后端 ${BASE}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
  const init = await call('POST', '/player/init', {
    requestId: rid('init'),
    deviceId: `probe-mail-read-${Date.now()}`,
    nickName: `邮件${Date.now() % 100000}`,
    clientTime: Date.now(),
    wxCode: '',
  })
  if (init.code !== 0) throw new Error(`建号失败：${init.raw}`)
  const playerId = init.data.playerId
  checkThat('A 建得起来一个新号', typeof playerId === 'string' && playerId.startsWith('P'), playerId)

  // ---- A 组：新号没有邮件（否则下面的"未读 1"可能被别的信撞上）----
  const empty = await call('GET', '/mail/list', undefined, playerId)
  check('A 新号未读封数为 0', empty.data?.unreadCount, 0)
  check('A 新号邮件列表为空', (empty.data?.mails ?? []).length, 0)

  // ---- B 组：造一封系统信 ----
  const sent = await call('POST', '/ops/mail/send', {
    requestId: rid('send'),
    playerId,
    title: '运维补发：探针用',
    text: '这一封由探针经 /ops/mail/send 发出，只用来验 /mail/read 的已读与未读计数。',
    rewards: [],
    actor: 'probe-mail-read',
  }, undefined, { 'X-Ops-Token': TOKEN })
  if (sent.code !== 0) {
    console.log(`=== 前提不足（不是功能红）：/ops/mail/send 被拒 code=${sent.code} detail=${sent.detail} ===`)
    console.log('   这一份要后端带 --ironoath.ops.token（scripts/verify-runtime.sh 默认 art-verify-local）。')
    process.exit(2)
  }
  const mailId = sent.data.mailId
  checkThat('B 运维补发回了一个邮件 id', typeof mailId === 'string' && mailId.length > 0, String(mailId))

  const list1 = await call('GET', '/mail/list', undefined, playerId)
  const first = (list1.data?.mails ?? [])[0]
  check('B 列表里恰好一封', (list1.data?.mails ?? []).length, 1)
  check('B 未读封数为 1', list1.data?.unreadCount, 1)
  checkThat('B 那一封就是刚发的、且未读', first?.mailId === mailId && first?.read === false,
    `mailId=${first?.mailId} read=${first?.read}`)

  // ---- C 组：标已读 ----
  // 负控相故意不调用，但仍然断言"已读" —— 只跳过调用还断言"未读"，那是自证自洽。
  if (NEGCTL !== 'skip-read') {
    const read = await call('POST', '/mail/read', { requestId: rid('read'), mailId }, playerId)
    check('C 标已读回 code 0', read.code, 0)
    check('C 回执回显的是同一封', read.data?.mailId, mailId)
    check('C 回执里的未读数已经减到 0', read.data?.unreadCount, 0)
  }

  const list2 = await call('GET', '/mail/list', undefined, playerId)
  const second = (list2.data?.mails ?? [])[0]
  check('C 再查列表：这一封已读', second?.read, true)
  check('C 再查列表：未读封数为 0（红点与列表同源）', list2.data?.unreadCount, 0)

  // ---- D 组：幂等（同 requestId 再点一次不该出错，也不该把未读减成负数）----
  const again = await call('POST', '/mail/read', { requestId: rid('read-again'), mailId }, playerId)
  check('D 对已读邮件再点一次仍回 code 0', again.code, 0)
  check('D 再点一次未读数还是 0', again.data?.unreadCount, 0)

  // ---- E 组：不存在的邮件要明确拒绝 ----
  const missing = await call('POST', '/mail/read', { requestId: rid('read-missing'), mailId: 'mail_does_not_exist' }, playerId)
  checkThat('E 标一封不存在的邮件被拒（不静默成功）', missing.code !== 0, `code=${missing.code} msg=${missing.msg}`)
  check('E 被拒的错误码是 12004（邮件不存在或已过期）', missing.code, 12004)

  return finish()
}

function finish() {
  console.log(`=== 邮件已读：${passed} 通过 / ${failures.length} 失败 ===`)
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
