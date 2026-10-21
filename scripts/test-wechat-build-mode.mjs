import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
const bash = process.env.IRONOATH_GIT_BASH
  ?? (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash')

function buildPreflight(debug, appId, releaseFlag = null) {
  const env = { ...process.env, COCOS_DEBUG: debug, COCOS_CREATOR: '/missing-cocos-creator' }
  if (appId === null) delete env.WECHAT_GAME_APPID
  else env.WECHAT_GAME_APPID = appId
  if (releaseFlag === null) delete env.WECHAT_RELEASE_BUILD
  else env.WECHAT_RELEASE_BUILD = releaseFlag
  const run = spawnSync(bash, ['scripts/build-wechatgame.sh'], {
    cwd: root, env, encoding: 'utf8', timeout: 10_000,
  })
  return { status: run.status, output: `${run.stdout}\n${run.stderr}` }
}

test('release fails before Cocos when AppID is absent or blank', () => {
  for (const value of [null, '', '   ']) {
    const run = buildPreflight('false', value)
    assert.equal(run.status, 2)
    assert.match(run.output, /WECHAT_GAME_APPID/)
    assert.doesNotMatch(run.output, /找不到 CocosCreator/)
  }
})

test('development retains its test AppID; explicit release AppID passes preflight', () => {
  const dev = buildPreflight('true', null)
  assert.match(dev.output, /找不到 CocosCreator/)
  assert.doesNotMatch(dev.output, /WECHAT_GAME_APPID.*缺失/)
  const release = buildPreflight('false', 'wx1234567890abcdef')
  assert.match(release.output, /找不到 CocosCreator/)
  assert.doesNotMatch(release.output, /WECHAT_GAME_APPID.*缺失/)
})

test('an explicit release flag also requires an AppID on a debug build', () => {
  const blocked = buildPreflight('true', null, 'true')
  assert.equal(blocked.status, 2)
  assert.match(blocked.output, /WECHAT_GAME_APPID/)
  const invalid = buildPreflight('true', 'wx1234567890abcdef', 'treu')
  assert.equal(invalid.status, 2)
  assert.match(invalid.output, /WECHAT_RELEASE_BUILD/)
})

function patchBackend(keepUrlCheck, origin = 'https://game.example.test') {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'ironoath-wechat-build-mode-'))
  const script = path.join(root, 'scripts/patch-wechat-backend.mjs')
  try {
    writeFileSync(path.join(dir, 'game.js'),
      'http://localhost:8080 ws://localhost:8080/ws', 'utf8')
    writeFileSync(path.join(dir, 'project.config.json'),
      JSON.stringify({ setting: { urlCheck: true } }), 'utf8')
    const args = [script, dir, origin]
    if (keepUrlCheck) args.push('--keep-url-check')
    const run = spawnSync(process.execPath, args, { cwd: root, encoding: 'utf8', timeout: 10_000 })
    assert.equal(run.status, 0, run.stderr)
    return {
      config: JSON.parse(readFileSync(path.join(dir, 'project.config.json'), 'utf8')),
      code: readFileSync(path.join(dir, 'game.js'), 'utf8'),
    }
  } finally {
    const base = path.resolve(os.tmpdir()) + path.sep
    if (!path.resolve(dir).startsWith(base)) throw new Error('fixture outside temp root')
    rmSync(dir, { recursive: true, force: true })
  }
}

test('release backend rewrite keeps urlCheck enabled while replacing addresses', () => {
  const result = patchBackend(true)
  assert.equal(result.config.setting.urlCheck, true)
  assert.match(result.code, /https:\/\/game\.example\.test/)
  assert.doesNotMatch(result.code, /localhost:8080/)
})

test('explicit development backend rewrite can disable urlCheck', () => {
  const result = patchBackend(false)
  assert.equal(result.config.setting.urlCheck, false)
})

test('release without an explicit backend still enforces urlCheck', () => {
  const result = patchBackend(true, '')
  assert.equal(result.config.setting.urlCheck, true)
  assert.match(result.code, /localhost:8080/)
})
