#!/usr/bin/env node
/**
 * B13-9 离线准入检查。只读取本任务的账号清单与回执，不建立连接，也不输出票据。
 * 通过表示输入形状与文件绑定正确；holder、资源归属和窗口仍需独立读回。
 */
import { createHash } from 'node:crypto'
import { readFile, realpath } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import process from 'node:process'

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')
const fail = (reason) => { throw new Error(reason) }
const nonempty = (value) => typeof value === 'string' && value.trim().length > 0
const utc = (value) => typeof value === 'string' && /Z$/.test(value) && Number.isFinite(Date.parse(value))
const readJson = (bytes, label) => {
  try { return JSON.parse(bytes.toString('utf8')) } catch { fail(`${label} 不是有效 JSON`) }
}

/** Pure shape check; the caller keeps the credentials in memory only. */
export function validateManifest(manifest) {
  if (manifest?.schemaVersion !== 1) fail('manifest.schemaVersion 必须为 1')
  if (!nonempty(manifest.targetOrigin)) fail('manifest.targetOrigin 缺失')
  let origin
  try { origin = new URL(manifest.targetOrigin) } catch { fail('targetOrigin 不是 URL') }
  if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password
      || origin.pathname !== '/' || origin.search || origin.hash) {
    fail('targetOrigin 必须是无凭据、无路径的 HTTP(S) origin')
  }
  if (origin.origin !== manifest.targetOrigin.replace(/\/$/, '')) fail('targetOrigin 必须是规范 origin')
  if (!Array.isArray(manifest.players) || manifest.players.length !== 5000) {
    fail('B13-9 必须恰好提供 5000 个独立玩家')
  }
  const ids = new Set()
  for (const [index, player] of manifest.players.entries()) {
    if (!nonempty(player?.playerId) || !nonempty(player?.token)) fail(`玩家 ${index} 缺少身份或票据`)
    if (ids.has(player.playerId)) fail(`玩家 ${index} 重复，不能用多连接冒充 5000 在线`)
    ids.add(player.playerId)
    const march = player.march
    if (!Number.isInteger(march?.toX) || !Number.isInteger(march?.toY)
        || !['ATTACK', 'GATHER', 'STATION', 'SCOUT', 'GARRISON'].includes(march?.action)
        || !Array.isArray(march?.units) || march.units.length === 0
        || march.units.some((unit) => !nonempty(unit?.unitId) || !Number.isInteger(unit?.count) || unit.count <= 0)) {
      fail(`玩家 ${index} 的行军请求不符合 MarchReq 基本形状`)
    }
  }
  return { targetOrigin: origin.origin, playerCount: ids.size }
}

/** Checks only verifiable local bindings. It does not grant runtime authority. */
export function validateAdmission(admission, manifestSha256, proofSha256, sourceSha, now = Date.now()) {
  if (admission?.schemaVersion !== 1 || !nonempty(admission.holderId)
      || !nonempty(admission.runId) || admission.targetKind !== 'isolated') {
    fail('准入回执缺 holder、runId 或隔离目标标记')
  }
  if (admission.manifestSha256 !== manifestSha256 || admission.resourceProofSha256 !== proofSha256
      || admission.sourceSha !== sourceSha) fail('准入回执与账号、资源证明或源码 SHA 不匹配')
  if (!Number.isInteger(admission.maxConnections) || !Number.isInteger(admission.maxMarchQps)
      || admission.maxConnections < 5000 || admission.maxMarchQps < 200) {
    fail('准入资源上限小于 B13-9 的 5000 连接或 200 QPS')
  }
  if (!utc(admission.notBeforeUtc) || !utc(admission.notAfterUtc)
      || Date.parse(admission.notBeforeUtc) >= Date.parse(admission.notAfterUtc)
      || now < Date.parse(admission.notBeforeUtc) || now >= Date.parse(admission.notAfterUtc)) {
    fail('准入 UTC 窗口未开始、已结束或格式无效')
  }
  return { holderId: admission.holderId, runId: admission.runId,
    notAfterUtc: admission.notAfterUtc, maxConnections: admission.maxConnections,
    maxMarchQps: admission.maxMarchQps }
}

export async function inspectFiles({ manifestPath, admissionPath, proofPath, sourceSha, now }) {
  const [manifestBytes, admissionBytes, proofBytes] = await Promise.all([
    readFile(manifestPath), readFile(admissionPath), readFile(proofPath),
  ])
  const manifest = readJson(manifestBytes, '账号清单')
  const admission = readJson(admissionBytes, '准入回执')
  const proof = readJson(proofBytes, '资源证明')
  const shape = validateManifest(manifest)
  if (admission.targetOrigin !== shape.targetOrigin) fail('准入目标与账号清单目标不同')
  if (proof?.schemaVersion !== 1 || proof.holderId !== admission.holderId
      || proof.targetOrigin !== shape.targetOrigin || proof.isolated !== true
      || !Number.isInteger(proof.maxConnections) || proof.maxConnections < 5000
      || !Number.isInteger(proof.maxMarchQps) || proof.maxMarchQps < 200
      || !nonempty(proof.cleanupPlan)) fail('资源证明缺少匹配的 holder、隔离、容量或清理方案')
  const approved = validateAdmission(admission, sha256(manifestBytes), sha256(proofBytes), sourceSha, now)
  return { ...shape, ...approved, manifestSha256: sha256(manifestBytes),
    resourceProofSha256: sha256(proofBytes), sourceSha,
    status: 'INPUT_BINDINGS_ONLY_NO_RUNTIME_AUTHORITY' }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const flags = Object.fromEntries(process.argv.slice(2).map((arg) => {
    const i = arg.indexOf('=')
    return i < 0 ? [arg, ''] : [arg.slice(0, i), arg.slice(i + 1)]
  }))
  try {
    if (flags['--mode'] !== 'inspect' || !flags['--manifest'] || !flags['--admission']
        || !flags['--proof']) fail('用法：--mode=inspect --manifest=... --admission=... --proof=...')
    const repoRoot = fileURLToPath(new URL('../', import.meta.url))
    for (const filename of [flags['--manifest'], flags['--admission'], flags['--proof']]) {
      const absolute = await realpath(filename)
      const relative = path.relative(repoRoot, absolute)
      if (relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))) {
        fail('账号、准入与资源证明文件必须放在仓库外，避免票据误入提交')
      }
    }
    const status = execFileSync('git', ['status', '--porcelain=v1', '--untracked-files=no'],
      { cwd: repoRoot, encoding: 'utf8' })
    if (status.trim()) fail('当前 checkout 有跟踪文件改动，不能绑定源码 SHA')
    const sourceSha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).trim()
    const result = await inspectFiles({ manifestPath: flags['--manifest'],
      admissionPath: flags['--admission'], proofPath: flags['--proof'], sourceSha })
    console.log(JSON.stringify(result))
  } catch (error) {
    console.error(`[b13-online-admission] ${error.message}`)
    process.exitCode = 2
  }
}
