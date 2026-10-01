// 存储层往返探针（#638）：真启动 → 建号落库 → **杀进程** → 重启 → 读回，
// 验证 #618 的保序修复**在持久化往返之后**仍然成立。
//
// 判据（每条都能失败）：
//   ① 建号那一刻的原始 JSON 键序 = 配置表顺序；
//   ② 杀进程重启后，GET /city/list 读回的原始 JSON 键序 **仍** = 配置表顺序。
//
// ⚠️ 全部取**原始响应文本**里的键出现顺序，不过任何 JSON 解析器 —— #619 栽在
// 「用解析后对象的属性顺序当 JSON 字段顺序」上，那一格读出来的「4/4 一致」是假绿。
//
// ⚠️ 第一版这个探针有三处错（都记在下面注释里，别再犯）：
//   ① 阶段二又调了一次 init ⇒ 读的是新建的另一个号，等于什么都没验证；
//   ② 用了不存在的端点 /player/login（404）；
//   ③ 把身份塞在 query 参数上，而真实口径是 `@RequestHeader("X-Player-Id")`
//      ⇒ 拿到 HTTP 200 的错误体，看起来像「响应里没有 resources」。
const EXPECT = ['WOOD', 'STONE', 'IRON', 'GRAIN', 'GOLD', 'STAMINA']

/** 从原始 JSON 文本里按出现顺序取第一个 "resources" 对象的键。 */
function rawOrder(text) {
  const i = text.indexOf('"resources"')
  if (i < 0) return null
  let depth = 0
  let start = -1
  let end = -1
  for (let k = i; k < text.length; k++) {
    if (text[k] === '{') {
      if (depth === 0) start = k
      depth++
    } else if (text[k] === '}') {
      depth--
      if (depth === 0) { end = k; break }
    }
  }
  if (start < 0) return null
  const seg = text.slice(start, end + 1)
  const keys = []
  const re = /"([A-Z_]+)"\s*:\s*\{/g
  let m
  while ((m = re.exec(seg)) !== null) keys.push(m[1])
  return keys
}

const origin = process.env.BACKEND_ORIGIN ?? 'http://localhost:8080'
const phase = process.env.RT_PHASE ?? 'write'

if (phase === 'write') {
  // ---- 阶段一：建号（写库）。这是**唯一**允许 init 的阶段 ----
  const deviceId = 'rt' + Date.now()
  const res = await fetch(`${origin}/player/init`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      requestId: 'r' + deviceId, deviceId, nickName: 'rt', clientTime: Date.now(), wxCode: '',
    }),
  })
  const text = await res.text()
  console.log(`[roundtrip] init HTTP ${res.status}`)
  if (res.status !== 200) {
    console.log(`[roundtrip] 判据失败：建号这一步就不是 200 ⇒ 后面两步都无意义；body=${text.slice(0, 200)}`)
    process.exit(1)
  }
  const order = rawOrder(text)
  console.log(`[roundtrip] 建号时键序 = ${order === null ? '(没找到 resources)' : order.join(',')}`)
  if (order === null || order.join(',') !== EXPECT.join(',')) {
    console.log('[roundtrip] 判据失败：建号那一刻的键序就不是配置表顺序')
    process.exit(1)
  }
  const j = JSON.parse(text)
  const playerId = j.data && j.data.playerId
  const token = j.data && j.data.authToken
  if (!playerId || !token) {
    console.log(`[roundtrip] 判据失败：拿不到 playerId/authToken；body=${text.slice(0, 200)}`)
    process.exit(1)
  }
  console.log(`[roundtrip] PLAYER_ID=${playerId} TOKEN=${token}`)
  console.log('[roundtrip] 阶段一全绿：落库完成，等外层脚本杀进程后重启再跑 RT_PHASE=read')
  process.exit(0)
}

// ---- 阶段二：进程已重启，凭阶段一拿到的身份**读回**（不再 init）----
const playerId = process.env.RT_PLAYER_ID
const token = process.env.RT_TOKEN
if (!playerId || !token) {
  console.log('[roundtrip] 判据失败：阶段二没拿到 RT_PLAYER_ID / RT_TOKEN')
  process.exit(1)
}
const res = await fetch(`${origin}/city/list`, { headers: { 'X-Player-Id': playerId, 'X-Auth-Token': token } })
const text = await res.text()
console.log(`[roundtrip] /city/list HTTP ${res.status}`)
if (res.status !== 200) {
  console.log(`[roundtrip] 判据失败：重启后连 /city/list 都读不到；body=${text.slice(0, 200)}`)
  process.exit(1)
}
const order = rawOrder(text)
console.log(`[roundtrip] 重启后读回键序 = ${order === null ? '(响应里没有 resources)' : order.join(',')}`)
if (order === null) {
  console.log(`[roundtrip] 判据失败：响应体里找不到 resources；body=${text.slice(0, 240)}`)
  process.exit(1)
}
const want = EXPECT.join(',')
const got = order.join(',')
console.log(`[roundtrip] 期望 = ${want}`)
if (got !== want) {
  console.log('[roundtrip] 判据失败：重启后读回的键序不是配置表顺序 ⇒ #618 的保序没扛过持久化往返')
  process.exit(1)
}
console.log('[roundtrip] 全绿：持久化往返之后键序仍是配置表顺序')
