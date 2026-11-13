/**
 * 驻留内存探针（上线检查清单 §四-5「内存泄漏：GC 后回到基线，无持续上涨」/ §四-12「对象池效果」）。
 *
 * <p>为什么需要一份专门的量具：`tools/verify-perf-runtime.mjs` 只报「JS 堆峰值」，而峰值对采样相位
 * 极敏感 —— 同一产物、同一后端、同样 120s 挂机，两轮读数可以是 23.2MB 与 54.6MB（见收口清单 #2508 记的那笔）。
 * 峰值大不等于泄漏，峰值小也不等于没问题：真正要回答的是**强制 GC 之后还剩下多少**、以及**它是不是只涨不落**。
 *
 * <p>手法：单一页面内循环「换一个页签 → 等它把接口画完 → 通过 CDP `HeapProfiler.collectGarbage`
 * 强制回收 → 读 `Runtime.getHeapUsage`」。强回收之后再采样，把「这一轮新产生、但还能被回收的临时对象」
 * 从读数里去掉，剩下的才是驻留量。
 *
 * <p>判据（全部可失败）：
 *   A 至少采到 `MEM_MIN_SAMPLES`（默认 5）个样本，否则判红不判绿（谓词失效）；
 *   B 末样本相对基线的增长 ≤ `MEM_MAX_GROWTH_MB`（默认 12MB）；
 *   C 单轮跳变 ≤ `MEM_MAX_STEP_MB`（默认 6MB）—— 抓「某一轮突然翻倍」，那通常是一次性大对象没释放；
 *   D 全程零 `pageerror`。
 *
 * <p>负控 `MEM_NEGCTL=retain`：每轮往页面里塞一块 ~8MB 的数组并挂在 window 上（模拟"忘了释放"），
 * 期望 B 组判红 —— 用来证明这条判据真的在看增长，而不是恒绿。
 *
 * 用法：`BACKEND_ORIGIN=http://localhost:8199 node tools/verify-memory-residency-runtime.mjs`
 * 退码：0 全部通过 / 1 有失败 / 2 前提不足（缺后端或客户端产物）。
 */
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { clickNodeViaCocos } from './lib/cocos-click.mjs'

const BACKEND = process.env.BACKEND_ORIGIN ?? (() => {
  console.error('[mem] 缺 BACKEND_ORIGIN：不给就退回 http://localhost:8080，那可能不是本轮要打的后端（dev 约定 http://localhost:8199）')
  process.exit(2)
})()
const PORT = Number(process.env.MEM_PROBE_PORT ?? 8195)
const CYCLES = Number(process.env.MEM_CYCLES ?? 6)
const CYCLE_WAIT_MS = Number(process.env.MEM_CYCLE_WAIT_MS ?? 3000)
const MAX_GROWTH_MB = Number(process.env.MEM_MAX_GROWTH_MB ?? 12)
const MAX_STEP_MB = Number(process.env.MEM_MAX_STEP_MB ?? 6)
const MIN_SAMPLES = Number(process.env.MEM_MIN_SAMPLES ?? 5)
const NEGCTL = process.env.MEM_NEGCTL ?? ''
const ARTIFACT = path.resolve(process.cwd(), 'client/build/web-mobile/index.html')
if (!existsSync(ARTIFACT)) {
  console.error(`[mem] 前提不足：产物不存在 ${ARTIFACT}（先跑 env -u ELECTRON_RUN_AS_NODE bash scripts/build-webmobile.sh）`)
  process.exit(2)
}
const OUT = path.resolve(process.cwd(), 'client/build/memory-residency-verify')
mkdirSync(OUT, { recursive: true })

// 驱动用哪两个页签：只挑**底部常驻**的那几个。其余页签（军队/武将/背包/关卡/社交/战力…）在「更多」抽屉里，
// 未展开时 `resolveCocosClickPoint` 会返回 `node-inactive`，点了等于没点（本轮实测 4 个全 false）——
// 那样这几轮就没在换面板，等于没压。任务 ↔ 邮件来回切已经覆盖「卸掉一个面板视图、建起另一个」的池复用路径。
const TABS = ['任务', '邮件']

let pass = 0
let fail = 0
const check = (msg, actual, expected) => {
  if (actual === expected) { pass += 1; console.log(`  PASS  ${msg}（${String(actual)}）`) }
  else { fail += 1; console.log(`  FAIL  ${msg}：期望 ${String(expected)}，实际 ${JSON.stringify(actual)}`) }
}

const preview = await startPreviewServer({ root: 'client/build/web-mobile', backend: BACKEND, port: PORT })
console.log(`=== 驻留内存：产物经 ${preview.origin}，后端 ${BACKEND}${NEGCTL ? `，负控=${NEGCTL}` : ''} ===`)
console.log(`  轮数 ${CYCLES}，每轮等 ${CYCLE_WAIT_MS}ms；判据：样本 ≥ ${MIN_SAMPLES}、总增长 ≤ ${MAX_GROWTH_MB}MB、单轮跳变 ≤ ${MAX_STEP_MB}MB`)

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
await context.addInitScript(() => {
  try { window.localStorage.setItem('ironoath.deviceId', `probe-mem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`) } catch { /* 隐私模式下忽略 */ }
})
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', (err) => pageErrors.push(String(err)))
const cdp = await context.newCDPSession(page)

/** 强制回收再读堆：先 GC 再采样，读数才是驻留量而不是「还没回收的临时对象」。 */
const sampleHeapMb = async () => {
  await cdp.send('HeapProfiler.collectGarbage')
  const usage = await cdp.send('Runtime.getHeapUsage')
  // 判据量的是「驻留总量」= JS 堆 + backing store。**只读 `usedSize` 会漏掉 ArrayBuffer / TypedArray**：
  // 负控塞 CGFloat 数组时 usedSize 一点不动、backingStorageSize 才涨 —— 第一次跑负控因此假绿（记在 #2526）。
  const usedMb = usage.usedSize / 1024 / 1024
  const backingMb = (usage.backingStorageSize ?? 0) / 1024 / 1024
  return { usedMb, backingMb, residencyMb: usedMb + backingMb, totalMb: usage.totalSize / 1024 / 1024 }
}

await page.goto(`${preview.origin}/`, { waitUntil: 'domcontentloaded' })
await page.waitForFunction(() => {
  const cc = window.cc
  const scene = cc && cc.director ? cc.director.getScene() : null
  return scene != null && scene.getChildByName('Canvas') != null
}, { timeout: 120_000 })
console.log('  场景已就绪，开始采样')

const samples = []
let retainedByNegctl = 0
const first = await sampleHeapMb()
samples.push({ cycle: 0, tab: '（刚启动）', ...first })
console.log(`  样本 0（基线）：驻留=${first.residencyMb.toFixed(1)}MB（JS 堆 ${first.usedMb.toFixed(1)} + backing ${first.backingMb.toFixed(1)}）`)

for (let cycle = 1; cycle <= CYCLES; cycle += 1) {
  const tab = TABS[(cycle - 1) % TABS.length]
  const clicked = await clickNodeViaCocos(page, { labelPrefix: tab }).catch((err) => ({ verified: false, reason: String(err) }))
  if (NEGCTL === 'retain') {
    // 负控：每轮留一块 ~8MB 的数组在 window 上，模拟"忘了释放"。
    // 返回值要读回来：注入失败（例如被 CSP 或沙箱挡了）会让负控**假绿**，那就等于这条判据没验过。
    const retainedMb = await page.evaluate(() => {
      const bucket = (window.__memNegctl ??= [])
      bucket.push(new Uint8Array(8 * 1024 * 1024))
      return (bucket.length * 8 * 1024 * 1024) / 1024 / 1024
    }).catch(() => -1)
    console.log(`  负控注入：window.__memNegctl 现持有 ${retainedMb}MB`)
    retainedByNegctl = retainedMb
  }
  await page.waitForTimeout(CYCLE_WAIT_MS)
  const sample = await sampleHeapMb()
  samples.push({ cycle, tab, clicked: clicked.verified === true, ...sample })
  console.log(`  样本 ${cycle}（点「${tab}」verified=${clicked.verified === true} reason=${clicked.reason ?? '—'}）：驻留=${sample.residencyMb.toFixed(1)}MB（JS 堆 ${sample.usedMb.toFixed(1)} + backing ${sample.backingMb.toFixed(1)}）`)
}

const baseline = samples[0].residencyMb
const last = samples[samples.length - 1].residencyMb
// 第 1 轮是**预热**：客户端在第一次真正交互时才会把几个懒加载子系统建起来（本轮实测 12.0 → 18.3MB），
// 那是一次性开销，不是「只涨不落」。所以稳态增长从第 2 个样本算起，预热增量单独记账、只报不判。
const warmBaseline = samples.length > 1 ? samples[1].residencyMb : baseline
let maxStep = 0
let maxStepAt = 2
for (let i = 2; i < samples.length; i += 1) {
  const step = samples[i].residencyMb - samples[i - 1].residencyMb
  if (step > maxStep) { maxStep = step; maxStepAt = samples[i].cycle }
}
const growth = last - warmBaseline
const warmupGrowth = warmBaseline - baseline
const verifiedClicks = samples.filter((s) => s.clicked === true).length
const report = {
  backend: BACKEND, negctl: NEGCTL || null, cycles: CYCLES,
  thresholds: { maxGrowthMb: MAX_GROWTH_MB, maxStepMb: MAX_STEP_MB, minSamples: MIN_SAMPLES, minVerifiedClicks: 2 },
  samples, baselineMb: baseline, warmBaselineMb: warmBaseline, lastMb: last,
  warmupGrowthMb: warmupGrowth, growthMb: growth, maxStepMb: maxStep, maxStepAt,
  verifiedClicks, pageErrors,
}
writeFileSync(path.join(OUT, 'memory-residency.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8')

console.log(`  汇总：基线 ${baseline.toFixed(1)}MB →（预热 +${warmupGrowth.toFixed(1)}MB）→ 稳态基线 ${warmBaseline.toFixed(1)}MB → 末样本 ${last.toFixed(1)}MB（稳态增长 ${growth.toFixed(1)}MB）；单轮最大跳变 ${maxStep.toFixed(1)}MB（第 ${maxStepAt} 轮）；页签点击生效 ${verifiedClicks}/${CYCLES}`)
check(`A 采到的样本数 ≥ ${MIN_SAMPLES}（谓词失效要判红不判绿）`, samples.length >= MIN_SAMPLES, true)
check(`B 强制 GC 后的稳态增长 ≤ ${MAX_GROWTH_MB}MB（第 1 轮预热只记账不判）`, growth <= MAX_GROWTH_MB, true)
check(`C 稳态单轮跳变 ≤ ${MAX_STEP_MB}MB`, maxStep <= MAX_STEP_MB, true)
check('D 全程零 pageerror', pageErrors.length, 0)
check('E 页签点击至少生效 2 次（否则这几轮没真的换面板，等于没压）', verifiedClicks >= 2, true)
if (NEGCTL === 'retain') {
  // 负控自检：注入没生效的话「B 组没红」不能算通过 —— 那只是没压到，不是没涨。
  check('F 负控注入确实挂住了一大块内存（≥ 16MB，否则负控无效）', retainedByNegctl >= 16, true)
}

await browser.close()
await preview.close()
console.log(`=== 驻留内存：${pass} 通过 / ${fail} 失败 ===`)
console.log(`报告：${path.join(OUT, 'memory-residency.json')}`)
process.exit(fail === 0 ? 0 : 1)
