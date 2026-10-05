#!/usr/bin/env node
// 跨 JVM 确定性（B05 验收 3 缺的那一半）：**两个独立 JVM 进程**跑同一批 seed，
// 结算摘要必须逐字相同。
//
// 为什么断言必须在两个进程里：同一个 JVM 内跑两次只能证明「代码没有随机源泄漏」，
// 证明不了「换个 JVM 结果一样」—— 而后者才是这条验收要的（B05 验收矩阵那条长期
// 标着 ✅ 却带着「同机器多 JVM 未跑」）。
//
// 摘要取自 `balance-sim --determinism`：每 case 的 winner / rounds / atkLoss / defLoss /
// skills。全是 `RoundSnapshot` 里的定点量，不含耗时、不含集合迭代顺序。

import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const bash = process.env.BASH_BIN || 'C:\\Program Files\\Git\\bin\\bash.exe'

/** 跑一个独立 JVM 进程，取出它的摘要行。 */
function digest() {
  const raw = execFileSync(bash, [
    '-c',
    "source scripts/env.sh >/dev/null 2>&1 && " +
    "mvn -f server/pom.xml -q -pl tools/balance-sim compile exec:java " +
    "-Dexec.args='--determinism' 2>&1",
  ], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, cwd: root })
  const lines = raw.split(/\r?\n/).filter((l) => /^case\d+ /.test(l))
  if (lines.length === 0) {
    console.error('[determinism] 没抓到任何 case 行 —— 后端没编译或模式没跑起来：')
    console.error(raw.split(/\r?\n/).slice(-20).join('\n'))
    process.exit(2)
  }
  return lines
}

const first = digest()
const second = digest()

if (first.length !== second.length) {
  console.error(`[determinism][FAIL] 两个进程的 case 数不同：${first.length} vs ${second.length}`)
  process.exit(1)
}

for (let i = 0; i < first.length; i++) {
  if (first[i] !== second[i]) {
    console.error('[determinism][FAIL] 第 ' + (i + 1) + ' 个 case 两个 JVM 跑出不同结果：')
    console.error('  process A: ' + first[i])
    console.error('  process B: ' + second[i])
    process.exit(1)
  }
}

console.log('[determinism] 两个独立 JVM 进程，' + first.length + ' 个 case，摘要逐字相同：')
first.forEach((l) => console.log('  ' + l))
console.log('[determinism] 通过。')