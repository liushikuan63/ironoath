#!/usr/bin/env node
/**
 * 职责：把「微信开发者工具里跑通了」这句话变成一条能失败、可复跑的判据。
 * 依赖：node（18+ 的 fetch）、**已启动的后端**、**已在开发者工具里起过模拟器**（本工具不驱动 IDE）。
 *
 * 用法：
 *   DEVTOOLS_OPS_TOKEN=<运维令牌> node tools/verify-devtools-runtime.mjs
 *   DEVTOOLS_BACKEND=http://localhost:8155 … 同上（默认 8080）
 *
 * <p><b>为什么需要它</b>：客户端的 `[boot]` 自检行只存在于开发者工具的 Console 里，而 IDE 什么都不落文件
 * （日志目录里只有 `[ideplugin]`）。那句「我在模拟器里看到了跑通的那一行」既没有证据也不可复核。
 * 现在同一份事实作为 `boot_check` 事件落库（收口清单 #143），本工具把它读回来判。
 *
 * <p><b>它量的是模拟器，不是真机</b>：本工具不启动也不点击开发者工具，所以它证明的是
 * 「最近那一次模拟器启动真的走通了链路并且服务端留下了回执」。
 * 真机项（FPS、内存、真 3G）不在它的射程内，输出里刻意不复用阶段 6 那些字段名。
 *
 * <p><b>bootMs 只报不判</b>：它是客户端自报的**下界**（从本包 JS 第一次被求值算起，不含包体下载与
 * 引擎初始化），而 `PERF_FIRST_SCREEN_MAX_MS` 是给「外部墙钟 → 看见首屏」那个口径定的数
 * （与帧率不进判定同一条理由，见 `verify-perf-runtime.mjs` 的量具纪律）。
 * 拿它判红等于造一个永远红的卡口；所以这里打数、打预算、打上「口径不同」，不判失败。
 *
 * <p><b>对照组</b>：先打一条不存在的路径，要求它回 404 且是 JSON。
 * 共享机器上端口随时会被别人的服务占着（本项目踩过：对着旧构建量首屏，量出来的全是别人的数），
 * 少了这一步，本工具会对着一台不是本项目的后端读出「一切正常」。
 */
// 必须显式给后端：静默回落到 8080 等于"打到另一台机器上读数"，读数错得像产品缺陷
// （同族已按谓词收过 30+ 份，这两份因写成 `(env.X ?? 'http://…').replace(...)` 而漏网 —— 台账 #420/#419 的回扫）
const rawBackend = process.env.DEVTOOLS_BACKEND ?? (() => {
  console.error('[verify-devtools-runtime] 缺 DEVTOOLS_BACKEND：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const BACKEND = rawBackend.replace(/\/$/, '')
const TOKEN = process.env.DEVTOOLS_OPS_TOKEN ?? ''

const failures = []
const lines = []

function verdict(ok, label, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'}  ${label}  ${detail}`)
  if (!ok) {
    failures.push(label)
  }
}

async function getJson(pathname) {
  const res = await fetch(`${BACKEND}${pathname}`, { headers: { 'X-Ops-Token': TOKEN } })
  const body = await res.json().catch(() => null)
  return { status: res.status, contentType: res.headers.get('content-type') ?? '', body }
}

/** 读数前先把运维令牌这条硬前提说清楚：没有它，下面每一条都会以「没数据」的形态假失败。 */
function requireToken() {
  if (TOKEN !== '') {
    return true
  }
  console.error(`运维令牌没有来源：请设 DEVTOOLS_OPS_TOKEN（与部署侧 ironoath.ops.token 同一个值）。`)
  console.error('  运维端点 fail-closed —— 不配令牌时一律拒绝，所以这里不能给一个仓库内的默认值，')
  console.error('  否则「谁都能读全服聚合数」就从事故形态变成了默认形态。')
  return false
}

const CONTROL_PATH = '/no-such-endpoint-for-devtools-check-9x'

async function main() {
  if (!requireToken()) {
    process.exit(2)
  }

  // ---------- 对照组：这台后端真的是本项目吗 ----------
  const control = await getJson(CONTROL_PATH)
  const controlOk = control.status === 404 && control.contentType.includes('json')
  verdict(controlOk, '对照组：不存在的路径回 404 JSON',
    `${CONTROL_PATH} → status=${control.status} type=${control.contentType}`)
  if (!controlOk) {
    console.log(lines.join('\n'))
    console.log(`\n=== 判定中止：${BACKEND} 上那台不是本项目后端（或根本没起），后面的读数都不可信 ===`)
    process.exit(1)
  }

  // ---------- 读数 ----------
  const boot = (await getJson('/ops/track/recent?name=boot_check&limit=10')).body?.data ?? null
  const failedPanel = (await getJson('/ops/track/recent?name=panel_load_failed&limit=10')).body?.data ?? null
  const crashes = (await getJson('/ops/crash/recent?limit=20')).body?.data ?? null
  const dashboard = (await getJson('/ops/crash/dashboard')).body?.data ?? null

  const rows = boot?.events ?? []
  verdict(rows.length >= 1, '模拟器把启动自检发到了这台后端',
    boot === null ? '读端点本身失败（见鉴权与对照组）'
      : `boot_check total=${boot.total}（回显的名字=${boot.eventName}）`)
  if (rows.length === 0) {
    console.log(lines.join('\n'))
    console.log('\n读不到回执只有三种可能：① 模拟器这一场压根没起（先在开发者工具里 open 一次）；')
    console.log('② 包里的后端地址不是这台（构建时用 WECHAT_BASE_URL 指过来）；')
    console.log('③ 读错了事件名 —— 这条由上面的 eventName 回显排除：空表 + 名字对得上就是真没发生。')
    console.log('\n=== 判定：开发者工具没跑通（无回执）===')
    process.exit(1)
  }

  const p = rows[0].params ?? {}
  verdict(p.platform === 'wechat', '运行的是小游戏运行时而不是 web 产物', `platform=${p.platform}`)
  verdict(p.started === 'true', '登录链路跑通（started=true）',
    `started=${p.started}${p.blocked === undefined ? '' : ` blocked=${p.blocked}`}`)

  const attempted = Number(p.attemptedPanels)
  const mounted = Number(p.mountedPanels)
  verdict(Number.isFinite(attempted) && attempted > 0, '面板视图查找真的跑过（反空转下限）',
    `attemptedPanels=${p.attemptedPanels} —— 为 0 说明装配整段被跳过，那时 missing 为空只是没人找过`)
  verdict(mounted === attempted, '面板视图全部装配（找过的都找到了；缺一个就是永远空白且没人知道）',
    `mounted=${mounted} attempted=${attempted} missing="${p.missingPanels ?? ''}"`)

  const bootMs = Number(p.bootMs)
  verdict(Number.isFinite(bootMs) && bootMs > 0, '首屏耗时自报有数（下界，只报不判）', `bootMs=${p.bootMs}`)

  verdict((failedPanel?.total ?? -1) === 0, '没有任何面板读取失败',
    `panel_load_failed total=${failedPanel?.total}`
    + (failedPanel?.listed ? ' 明细=' + JSON.stringify(failedPanel.events.map(e => e.params)) : ''))

  const version = p.clientVersion ?? ''
  const mine = (crashes?.crashes ?? []).filter(c => c.clientVersion === version)
  /**
   * 逐条按堆栈归类。**判据只吃"产品自己的崩溃"**：开发者工具会在自己的 appservice 扩展里
   * 打一条对 IDE 本地服务（`/apihelper/assdk`）的 XHR，那条错误一样会撞进全局 onError ——
   * 实测本轮就收到一条（traceId 见下面那行 IDE 噪音）。
   * 拿它判红等于造一个永远红的卡口（与"帧率不进判定"同一条理由）；
   * 但也不静默丢弃：单独报一行并给 traceId，人一眼就能否掉。
   * 归类规则刻意保守：**只要有一帧不属于 IDE 自己的运行时就算产品的**，
   * 误判方向只会是"把噪音判成缺陷"，不会反过来漏掉真崩溃。
   */
  const IDE_OWNED = /(ide:\/\/|\/__dev__\/|\/apihelper\/)/
  const ours = []
  const ideNoise = []
  for (const row of mine) {
    const detail = (await getJson(`/ops/crash/detail?traceId=${encodeURIComponent(row.traceId)}`)).body?.data
    const frames = String(detail?.stack ?? '').split('\n').filter(l => l.trimStart().startsWith('at '))
    const allIde = frames.length > 0 && frames.every(f => IDE_OWNED.test(f))
    ;(allIde ? ideNoise : ours).push({ ...row, frames })
  }
  verdict(ours.length === 0, '这一场模拟器没有产品侧崩溃上报',
    `版本 ${version}：崩溃 ${mine.length} 条，其中产品自己的 ${ours.length} 条`
    + (ours.length === 0 ? '' : ' 明细=' + JSON.stringify(ours.map(c => `${c.message} @ ${c.traceId}`))))
  if (ideNoise.length > 0) {
    console.log(`NOTE  ${ideNoise.length} 条整条栈都在开发者工具自己的运行时里（IDE 本地服务），`
      + `不计入判定：${ideNoise.map(c => `${c.message} @ ${c.traceId}`).join('、')}`)
  }

  const boardRow = (dashboard?.rows ?? []).find(r => r.clientVersion === version)
  console.log(lines.join('\n'))
  console.log(`\n最近五次模拟器启动：bootMs ${rows.slice(0, 5).map(r => r.params.bootMs).reverse().join(' ← ')}`)
  console.log(`看板同版本一行：版本=${version} 启动数=${boardRow?.startups ?? '（无行）'} `
    + `崩溃数=${boardRow?.crashes ?? '（无行）'} 崩溃率=${boardRow?.crashRate ?? '（无行，分母为 0 时刻意回 null）'}`)
  console.log('`PERF_FIRST_SCREEN_MAX_MS` 只对「外部墙钟 → 看见首屏」那个口径生效'
    + '（verify-perf-runtime.mjs 量的是它），本工具的 bootMs 是下界，不进判定。')
  console.log(failures.length === 0
    ? '\n=== 判定：开发者工具跑通（回执可复核，真机项不在射程内）==='
    : `\n=== 判定：没跑通，FAIL ${failures.length} 条 ===`)
  process.exit(failures.length === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error(`ERROR ${e.message}`)
  process.exit(2)
})
