/**
 * 职责：量一件事 —— **一个刚建档的 dev 号，只走生产路径（真 `/gacha/draw`），能不能长出武将**。
 * 依赖：node（≥20，全局 fetch）、一台跑着的 dev 后端（默认 8080）。**不改任何代码、不碰服务端存档**，
 *       它只创建自己的临时号（设备号带时间戳，跑完就留在 dev 的测试号堆里，与量具探针同一类）。
 *
 * 用法：GACHA_REAL_BACKEND=http://localhost:8080 node tools/probe-gacha-real-account.mjs
 *
 * <p><b>为什么要这么个量具</b>：台账 #267 与换装那一格都写着"阻塞：dev 新号 `heroes=0`、`instances=0`，
 * 造数据＝作弊端点被禁"。而抽卡入口刚接上（#294 / #295）—— 抽卡本身就是**生产路径**，
 * 不是造数据。这一格的答案决定那两条 SKIP 是"能复验了"还是"仍然够不到"：
 * 新号初始 GOLD 只够抽新手池 1 次 + 标准池几次，而装备实例由武将与关卡产出，不是抽卡直接给的。
 *
 * <p><b>它永远退 0</b>（报告器，不是门）：读不到结论就打印读不到，不卡任何人。
 */

const BACKEND = process.env.GACHA_REAL_BACKEND ?? 'http://localhost:8080'
const MAX_DRAWS = Number(process.env.GACHA_REAL_DRAWS ?? 6)

function newRequestId() {
  const hex = () => Math.floor(Math.random() * 16 ** 8).toString(16).padStart(8, '0')
  return `${hex()}${hex()}${hex()}${hex()}`.slice(0, 32)
}

async function call(path, playerId, body) {
  const headers = { 'content-type': 'application/json' }
  if (playerId !== null) {
    headers['X-Player-Id'] = playerId
  }
  const response = await fetch(`${BACKEND}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const json = await response.json().catch(() => null)
  return { status: response.status, json }
}

const deviceId = `gacha-real-${Date.now()}`
const init = await call('/player/init', null, {
  requestId: newRequestId(), deviceId, nickName: '量具号', clientTime: Date.now(), wxCode: '',
})
if (init.status !== 200 || init.json?.code !== 0) {
  console.log(`起跑失败：POST /player/init → HTTP ${init.status} ${JSON.stringify(init.json)?.slice(0, 160)}`)
  console.log('后端起来了吗？（默认 8080，可用 GACHA_REAL_BACKEND 覆盖）')
  process.exit(0)
}
const playerId = init.json.data.playerId
const goldAtStart = init.json.data.resources?.GOLD ?? init.json.data.resources?.gold ?? null
console.log(`=== dev 真号只走生产路径：设备 ${deviceId} · 初始 GOLD ${goldAtStart} ===`)

const detail = await call('/resource/detail', playerId)
const goldRow = (detail.json?.data?.resources ?? []).find((r) => r.type === 'GOLD')
console.log(`  /resource/detail 读到 GOLD = ${goldRow?.current ?? '(没有这一行)'}`)

let heroes = 0
let instances = 0
let draws = 0
const log = []
for (let i = 0; i < MAX_DRAWS; i++) {
  const poolId = i === 0 ? 'gacha_pool_newbie' : 'gacha_pool_standard'
  const drawn = await call('/gacha/draw', playerId, { requestId: newRequestId(), poolId, count: 1 })
  draws += 1
  const code = drawn.json?.code
  const names = (drawn.json?.data?.results ?? []).map((r) => `${r.name}${r.isNew ? '(新)' : `(+${r.fragments}片)`}`)
  log.push(`  第 ${draws} 抽 ${poolId} → code=${code} ${names.join('、') || drawn.json?.msg || ''}`)
  if (code !== 0) {
    // 拒绝也是结论：新手池限抽 1 次、GOLD 不够，都是"生产路径走到头"的那种证据
    continue
  }
}
for (const line of log) console.log(line)

const roster = await call('/hero/list', playerId)
heroes = (roster.json?.data?.heroes ?? []).length
const equip = await call('/equip/instances', playerId)
instances = (equip.json?.data?.instances ?? []).length
const after = await call('/resource/detail', playerId)
const goldAfter = (after.json?.data?.resources ?? []).find((r) => r.type === 'GOLD')?.current ?? null

console.log(`  抽了 ${draws} 次（含被拒的）之后：heroes=${heroes} · 装备实例=${instances} · GOLD=${goldAfter}`)
const verdict = heroes > 0
  ? (instances > 0
    ? '结论：**#267 与换装那两格的 SKIP 现在能用真数据复验**（有将且有装备实例）'
    : '结论：新号能长出武将（#267 的"heroes=0"不再是必然），但**装备实例仍为 0** ⇒ 那两格还要关卡产出或抽到带装备的将')
  : '结论：只靠抽卡长不出武将（读一下被拒的那几条：限抽 / GOLD 不够）⇒ #267 的阻塞仍然是真的，且原因要重新写'
console.log(verdict)
console.log('（本报告器永远退 0；它不改任何代码，也不碰服务端已有账号）')
