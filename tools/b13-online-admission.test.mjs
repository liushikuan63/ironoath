import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, writeFile, rm, mkdir, copyFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { execFileSync, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { inspectFiles, validateAdmission, validateManifest } from './b13-online-admission.mjs'

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex')
const sourceSha = 'a'.repeat(40)
const baseManifest = () => ({ schemaVersion: 1, targetOrigin: 'http://127.0.0.1:8299',
  players: Array.from({ length: 5000 }, (_, n) => ({ playerId: `p${n}`, token: `secret-${n}`,
    march: { toX: 10, toY: 20, action: 'SCOUT', units: [{ unitId: 'unit_infantry_t1', count: 1 }] } })) })
const baseAdmission = (manifestHash, proofHash) => ({ schemaVersion: 1, holderId: 'fixture-holder',
  runId: 'fixture-run', targetKind: 'isolated', targetOrigin: 'http://127.0.0.1:8299',
  manifestSha256: manifestHash, resourceProofSha256: proofHash, sourceSha,
  maxConnections: 5000, maxMarchQps: 200,
  notBeforeUtc: '2026-10-21T00:00:00Z', notAfterUtc: '2026-10-22T00:00:00Z' })
const baseProof = () => ({ schemaVersion: 1, holderId: 'fixture-holder',
  targetOrigin: 'http://127.0.0.1:8299', isolated: true,
  maxConnections: 5000, maxMarchQps: 200, cleanupPlan: 'fixture cleanup' })

test('5000 identities and a real MarchReq shape are required', () => {
  const manifest = baseManifest()
  assert.equal(validateManifest(manifest).playerCount, 5000)
  manifest.players[4999].playerId = manifest.players[0].playerId
  assert.throws(() => validateManifest(manifest), /重复/)
  manifest.players.pop()
  assert.throws(() => validateManifest(manifest), /恰好提供 5000/)
})

test('source, manifest, resources, capacity and fresh UTC window are all bound', () => {
  const admission = baseAdmission('m', 'r')
  assert.equal(validateAdmission(admission, 'm', 'r', sourceSha, Date.parse('2026-10-21T12:00:00Z')).runId, 'fixture-run')
  assert.throws(() => validateAdmission(admission, 'different', 'r', sourceSha, Date.parse('2026-10-21T12:00:00Z')), /不匹配/)
  assert.throws(() => validateAdmission({ ...admission, maxMarchQps: 199 }, 'm', 'r', sourceSha, Date.parse('2026-10-21T12:00:00Z')), /小于/)
  assert.throws(() => validateAdmission({ ...admission, maxMarchQps: '200' }, 'm', 'r', sourceSha, Date.parse('2026-10-21T12:00:00Z')), /小于/)
  assert.throws(() => validateAdmission(admission, 'm', 'r', sourceSha, Date.parse('2026-10-22T00:00:00Z')), /窗口/)
})

test('the offline receipt binds exact bytes and never includes credentials', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'b13-admission-'))
  try {
    const manifestBytes = Buffer.from(JSON.stringify(baseManifest()))
    const proofBytes = Buffer.from(JSON.stringify(baseProof()))
    const admission = baseAdmission(hash(manifestBytes), hash(proofBytes))
    const manifestPath = path.join(dir, 'manifest.json')
    const proofPath = path.join(dir, 'proof.json')
    const admissionPath = path.join(dir, 'admission.json')
    await Promise.all([writeFile(manifestPath, manifestBytes), writeFile(proofPath, proofBytes),
      writeFile(admissionPath, JSON.stringify(admission))])
    const receipt = await inspectFiles({ manifestPath, proofPath, admissionPath, sourceSha,
      now: Date.parse('2026-10-21T12:00:00Z') })
    assert.equal(receipt.playerCount, 5000)
    assert.equal(receipt.status, 'INPUT_BINDINGS_ONLY_NO_RUNTIME_AUTHORITY')
    assert.ok(!JSON.stringify(receipt).includes('secret-'))
    const wrongProof = { ...baseProof(), isolated: false }
    await writeFile(proofPath, JSON.stringify(wrongProof))
    await assert.rejects(inspectFiles({ manifestPath, proofPath, admissionPath, sourceSha,
      now: Date.parse('2026-10-21T12:00:00Z') }), /资源证明缺少/)
    await writeFile(proofPath, 'changed-proof')
    await assert.rejects(inspectFiles({ manifestPath, proofPath, admissionPath, sourceSha,
      now: Date.parse('2026-10-21T12:00:00Z') }), /有效 JSON/)
  } finally {
    assert.equal(path.dirname(dir), path.resolve(tmpdir()))
    await rm(dir, { recursive: true, force: true })
  }
})

test('CLI reads its own checkout SHA, with credential files outside the repo', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'b13-admission-cli-'))
  try {
    const repoRoot = path.join(dir, 'repo')
    const toolsDir = path.join(repoRoot, 'tools')
    await mkdir(toolsDir, { recursive: true })
    const scriptPath = path.join(toolsDir, 'b13-online-admission.mjs')
    await copyFile(fileURLToPath(new URL('./b13-online-admission.mjs', import.meta.url)), scriptPath)
    execFileSync('git', ['init', '-q', repoRoot])
    execFileSync('git', ['-C', repoRoot, 'add', '--', 'tools/b13-online-admission.mjs'])
    execFileSync('git', ['-C', repoRoot, '-c', 'user.name=B13 Fixture', '-c', 'user.email=b13-fixture@example.test',
      'commit', '-qm', 'fixture'])
    const currentSha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).trim()
    const manifestBytes = Buffer.from(JSON.stringify(baseManifest()))
    const proofBytes = Buffer.from(JSON.stringify(baseProof()))
    const admission = { ...baseAdmission(hash(manifestBytes), hash(proofBytes)), sourceSha: currentSha,
      notBeforeUtc: new Date(Date.now() - 60_000).toISOString(),
      notAfterUtc: new Date(Date.now() + 60_000).toISOString() }
    const manifestPath = path.join(dir, 'manifest.json')
    const proofPath = path.join(dir, 'proof.json')
    const admissionPath = path.join(dir, 'admission.json')
    await Promise.all([writeFile(manifestPath, manifestBytes), writeFile(proofPath, proofBytes),
      writeFile(admissionPath, JSON.stringify(admission))])
    const cli = spawnSync(process.execPath, [scriptPath,
      '--mode=inspect', `--manifest=${manifestPath}`, `--admission=${admissionPath}`, `--proof=${proofPath}`],
    { cwd: dir, encoding: 'utf8' })
    assert.equal(cli.status, 0, cli.stderr)
    assert.equal(JSON.parse(cli.stdout).sourceSha, currentSha)
    assert.ok(!cli.stdout.includes('secret-'))
  } finally {
    assert.equal(path.dirname(dir), path.resolve(tmpdir()))
    await rm(dir, { recursive: true, force: true })
  }
})
