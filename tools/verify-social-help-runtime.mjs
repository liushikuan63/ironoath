#!/usr/bin/env node
/**
 * 职责：把「一键帮助」这条路走通并钉住语义 —— `POST /social/helpAll` 一次请求处理全部可帮助项，
 * 而不是让客户端逐条发（逐条发在弱网下会「点了全部只帮到一半」，见 SocialAppService#helpAll 的注释）。
 *
 * <p><b>为什么单开一份</b>：`/social/helpAll` 在 `check-probe-endpoint-coverage` 上长期「没有任何探针碰过」。
 * 它的前置可以真造出来：联盟里 A 发起一次**真的会跑一段时间**的升级（`CityAppService` 只在
 * `finishAt > now` 时登记求助，秒完成的不登记），B 就能在 `/social/helpRequests` 里看到这条并一键帮助。
 *
 * <p><b>它盯的五件事</b>：① 求助的**生产者**真的登记了（B 看得到 A 的那条，且 `alreadyHelped=false`）；
 * ② 一键帮助真的帮到（`helped ≥ 1`、`speedupGranted > 0`）；③ 每日额度按条数扣（`helpRemainingToday`
 * 减少量 == `helped`）；④ 红点按「帮助后的值」下发（`pendingHelps` 变小，验收 6 要求清零）；
 * ⑤ 重放是幂等而不是第二次帮助（`helped == 0`、`skipped ≥ 1`、额度不再扣）。
 *
 * <p><b>前提</b>：联盟创建要 500 金币（新号只有 200）⇒ 这一份要跑在开着 dev 提速档的后端上
 * （`IRONOATH_DEV_CITY_LEVEL=16` + `IRONOATH_DEV_START_AMOUNT=2000000`）；建盟回非 0 时按
 * 「前提不足」退 2 并说明，不伪装成功能红。
 *
 * 用法：必填 `BACKEND_ORIGIN`（不给退 2，绝不回退 8080）。自检口 `SOCIAL_HELP_NEGCTL=skip-help`
 * 跳过一键帮助调用、但照旧断言「帮到了」⇒ 必须红。
 */
const BASE = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[verify-social-help-runtime] 缺 BACKEND_ORIGIN：不给就不知道要打哪台后端（提速档约定 http://localhost:8299）')
  process.exit(2)
})()
const NEGCTL = process.env.SOCIAL_HELP_NEGCTL ?? ''
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

const rid = (tag) => `sh-${tag}-${rnd}`

async function call(method, path, body, playerId) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Player-Id': playerId ?? '' },
    body: method === 'GET' ? undefined : JSON.stringify(body ?? {}),
  })
  let json = null
  try { json = await res.json() } catch (e) { json = { parseError: String(e) } }
  return { http: res.status, code: json?.code, msg: json?.msg, detail: json?.detail, data: json?.data }
}

async function newPlayer(tag) {
  const out = await call('POST', '/player/init', {
    requestId: rid('init-' + tag),
    deviceId: `sh-dev-${tag}-${rnd}`,
    nickName: `sh${tag}`.slice(0, 12),
    clientTime: Date.now(),
    wxCode: '',
  }, '')
  if (out.code !== 0 || !out.data?.playerId) {
    console.log(`=== 前提不足（不是功能红）：建号失败 code=${out.code} msg=${out.msg} detail=${out.detail} ===`)
    process.exit(2)
  }
  return out.data.playerId
}

async function main() {
  console.log(`=== 一键帮助：后端 ${BASE}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
  const giver = await newPlayer('give')
  const taker = await newPlayer('take')
  checkThat('两个真号建得起来且不同人', giver !== taker, `${giver} / ${taker}`)

  // ---- A 组：建盟（taker 是盟主，giver 是成员：被帮助方在盟里才进得了 giver 的候选）----
  const created = await call('POST', '/alliance/create', {
    requestId: rid('create'),
    name: `帮${Date.now() % 1000000}`,
    // 盟标唯一：写死 'HPR' 时第二次跑就被「联盟标签已被占用」挡在前提上（实测过一次）
    tag: ('H' + rnd.replace(/[^a-z0-9]/g, '')).slice(0, 4).toUpperCase(),
  }, taker)
  if (created.code !== 0) {
    console.log(`=== 前提不足（不是功能红）：${created.detail ?? created.msg} ===`)
    console.log('   建盟要 500 金币、新号只有 200 ⇒ 这一份要跑在开着 dev 提速档的后端上。')
    process.exit(2)
  }
  const allianceId = created.data?.alliance?.id ?? null
  checkThat('A 建盟成功并有 allianceId', allianceId !== null, `allianceId=${allianceId}`)
  if (allianceId === null) return finish()

  const applied = await call('POST', '/alliance/apply', { requestId: rid('apply'), allianceId }, giver)
  check('A 成员申请入盟回 code 0', applied.code, 0)
  const approved = await call('POST', '/alliance/review', { requestId: rid('review'), applicantId: giver, approve: true }, taker)
  check('A 盟主通过申请回 code 0', approved.code, 0)

  // ---- B 组：被帮助方发起一次真的会跑一会儿的升级（求助的生产者）----
  const city = await call('GET', '/city/list', undefined, taker)
  check('B 城内列表回 code 0', city.code, 0)
  const buildings = city.data?.buildings ?? []
  checkThat('B 城里至少有可升级的一栋楼', buildings.length > 0, `buildings=${buildings.length}`)
  const target = buildings.find(b => b && typeof b.configId === 'string')
  checkThat('B 找到一栋带 configId 的楼', Boolean(target), JSON.stringify(buildings[0] ?? null).slice(0, 200))
  let helpRequestSeen = false
  if (target) {
    const upgraded = await call('POST', '/city/upgrade', {
      requestId: rid('upgrade'),
      configId: target.configId,
      gridX: target.gridX ?? null,
      gridY: target.gridY ?? null,
    }, taker)
    check('B 升级回 code 0（求助的生产者）', upgraded.code, 0)
    const finishAt = Number(upgraded.data?.finishAt ?? 0)
    const serverNow = Number(upgraded.data?.serverNow ?? upgraded.serverNow ?? 0)
    // CityUpgradeResp 只给 finishAt（完成时刻），没有 remainingSeconds —— 这条差值就是「会不会登记求助」的判据
    checkThat('B 这次升级会跑一段时间（秒完成的不登记求助）', finishAt > 0 && (serverNow === 0 || finishAt > serverNow),
      `finishAt=${finishAt} serverNow=${serverNow}`)

    // ---- C 组：帮助方看得到这条求助 ----
    const before = await call('GET', '/social/helpRequests', undefined, giver)
    check('C 帮助方读求助列表回 code 0', before.code, 0)
    const rows = before.data?.requests ?? []
    const mine = rows.find(r => r.fromPlayerId === taker)
    checkThat('C 求助的生产者真的登记了：列表里有被帮助方那一条', Boolean(mine),
      `rows=${rows.length} fromPlayerIds=${JSON.stringify(rows.map(r => r.fromPlayerId))}`)
    check('C 那条我还没帮过（alreadyHelped=false）', mine?.alreadyHelped, false)
    checkThat('C 被帮助方那一条带了服务端拼好的目标描述', typeof mine?.targetDesc === 'string' && mine.targetDesc.length > 0,
      `targetDesc=${JSON.stringify(mine?.targetDesc)}`)
    const pendingBefore = before.data?.pendingHelps ?? null
    helpRequestSeen = Boolean(mine)

    // ---- D 组：一键帮助 ----
    const before2 = await call('GET', '/social/helpRequests', undefined, giver)
    const remainingBefore = before2.data?.helpRemainingToday ?? null
    let helped = await call('POST', '/social/helpAll', { requestId: rid('helpAll') }, giver)
    if (NEGCTL === 'skip-help') {
      // 自检口：跳过真正的调用，但下面的断言照旧要成立 ⇒ 必须红
      helped = { code: 0, data: { helped: 0, skipped: 0, helpRemainingToday: remainingBefore, pendingHelps: pendingBefore, speedupGranted: 0 } }
    }
    check('D 一键帮助回 code 0', helped.code, 0)
    checkThat('D 真的帮到了（helped ≥ 1）', (helped.data?.helped ?? 0) >= 1, `helped=${helped.data?.helped}`)
    checkThat('D 下发了实际削减的时长（speedupGranted > 0）', (helped.data?.speedupGranted ?? 0) > 0,
      `speedupGranted=${helped.data?.speedupGranted}`)
    if (typeof remainingBefore === 'number') {
      check('D 每日额度按「真正帮到的条数」扣', helped.data?.helpRemainingToday, remainingBefore - (helped.data?.helped ?? 0))
    }
    if (typeof pendingBefore === 'number') {
      checkThat('D 红点按帮助后的值下发（变小或清零）', (helped.data?.pendingHelps ?? pendingBefore) < pendingBefore || pendingBefore === 0,
        `before=${pendingBefore} after=${helped.data?.pendingHelps}`)
    }

    // ---- E 组：重放是幂等，不是第二次帮助 ----
    const again = await call('POST', '/social/helpAll', { requestId: rid('helpAll') }, giver)
    check('E 再一键帮助仍回 code 0（不报错）', again.code, 0)
    check('E 重放没有第二次帮助（helped == 0）', again.data?.helped, 0)
    // 重放时候选集已经是空的（那一行已经 alreadyHelped），所以 skipped 是 0 而不是 1 ——
    // 首版按「重放会把已帮过的算 skipped」断言，是我把实现读反了（skipped 只统计**传进来但帮不了**的条目）。
    // 真正该钉的是「额度不再被扣」，这才是重放没有二次帮助的证据。
    check('E 重放不再扣每日额度', again.data?.helpRemainingToday, helped.data?.helpRemainingToday)

    // ---- F 组：帮过之后列表里那条变成 alreadyHelped=true ----
    const after = await call('GET', '/social/helpRequests', undefined, giver)
    const rowAfter = (after.data?.requests ?? []).find(r => r.fromPlayerId === taker)
    checkThat('F 帮过之后那条已消失或标成已帮', rowAfter === undefined || rowAfter.alreadyHelped === true,
      `row=${JSON.stringify(rowAfter)}`)
  } else {
    failures.push('B 没能造出求助（城里没有可升级的楼）')
  }

  checkThat('G 整条链路真的走了求助这条路（不是空跑）', helpRequestSeen, '没看到求助行，后面的断言等于没验')
  return finish()
}

function finish() {
  console.log(`\n=== 一键帮助：${passed} 通过 / ${failures.length} 失败 ===`)
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch(err => {
  console.error('[verify-social-help-runtime] 崩了：' + (err && err.stack ? err.stack : err))
  process.exit(1)
})
