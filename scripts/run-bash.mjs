import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

const script = process.argv[2]
if (!script) {
  console.error('用法：node scripts/run-bash.mjs <script.sh> [args...]')
  process.exit(2)
}

const candidates = [
  process.env.BASH_EXE,
  'bash',
  'C:/Program Files/Git/bin/bash.exe',
  'C:/Program Files/Git/usr/bin/bash.exe',
].filter((value) => typeof value === 'string' && value.length > 0)

let bash = null
for (const candidate of candidates) {
  const probe = candidate === 'bash'
    ? spawnSync(candidate, ['--version'], { stdio: 'ignore' })
    : null
  if (probe === null ? existsSync(candidate) : probe.status === 0) {
    bash = candidate
    break
  }
}

if (bash === null) {
  console.error('[run-bash][FAIL] 找不到 bash；可设置 BASH_EXE 指向 Git Bash。')
  process.exit(1)
}

const result = spawnSync(bash, [script, ...process.argv.slice(3)], { stdio: 'inherit' })
if (result.error) {
  console.error(`[run-bash][FAIL] 启动 bash 失败：${result.error.message}`)
  process.exit(1)
}
process.exit(result.status ?? 1)
