/**
 * 职责：社交权限门控的纯逻辑用例（B26 S1）。
 * 依赖：node:test + node:fs + game/social/PermissionGates（不碰 cc）。
 *
 * <p><b>每条钉的都是一个会做错的地方</b>：
 * ① 两个 scope 不许合并（同名权限码在小队与联盟各自授予，合并会让另一页的按钮假亮）；
 * ② 只拉到一个 scope 不算"读到"（拿缺的那一半去猜就是放行）；
 * ③ 没读到权限时不能说"你不行" —— 那是把一次读失败伪装成身份结论；
 * ④ 理由里不许出现权限码与职位码（#268 那一族：`KICK_MEMBER` 不是玩家语言）；
 * ⑤ 客户端不翻权限矩阵 —— 唯一的 import 是本模块自己。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  EMPTY_PERMISSIONS, codesOf, gate, roleText, withPermissionScope,
} from '../assets/scripts/game/social/PermissionGates'

const BOTH = withPermissionScope(
  withPermissionScope(EMPTY_PERMISSIONS, 'SQUAD', ['KICK_MEMBER'], 'LEADER', false),
  'ALLIANCE', ['DONATE'], 'MEMBER', true)

test('两个 scope 各存各的：小队能踢人不代表联盟那一页也能踢', () => {
  assert.deepEqual(codesOf(BOTH, 'SQUAD'), ['KICK_MEMBER'])
  assert.deepEqual(codesOf(BOTH, 'ALLIANCE'), ['DONATE'])
  assert.equal(gate(BOTH, 'SQUAD', 'KICK_MEMBER').allowed, true)
  assert.equal(gate(BOTH, 'ALLIANCE', 'KICK_MEMBER').allowed, false,
    '合并成一份的话这一条会假亮：玩家点下去拿到的正是服务端那句拒绝')
})

test('合并第二个 scope 时不抹掉第一个（回执是两次独立的读）', () => {
  const onlySquad = withPermissionScope(EMPTY_PERMISSIONS, 'SQUAD', ['KICK_MEMBER'], 'LEADER', false)
  assert.deepEqual(codesOf(onlySquad, 'ALLIANCE'), [])
  assert.equal(onlySquad.loaded, false)
  const both = withPermissionScope(onlySquad, 'ALLIANCE', ['DONATE'], 'MEMBER', true)
  assert.deepEqual(codesOf(both, 'SQUAD'), ['KICK_MEMBER'], '先到的那份不能被后到的覆盖')
})

test('只拉到一个 scope 不算读到：一个按钮都不放开', () => {
  const half = withPermissionScope(EMPTY_PERMISSIONS, 'SQUAD', ['KICK_MEMBER'], 'LEADER', false)
  assert.equal(gate(half, 'SQUAD', 'KICK_MEMBER').allowed, false)
  assert.equal(gate(half, 'SQUAD', 'KICK_MEMBER').reason, '权限还没读到',
    '说"你当前的职位不能做"会把一次读失败伪装成身份结论 —— 玩家会去申请升职，而该做的只是重进页面')
})

test('有码却没职位：照样放行（权限码就是结论，客户端不再算矩阵）', () => {
  const state = withPermissionScope(
    withPermissionScope(EMPTY_PERMISSIONS, 'SQUAD', ['KICK_MEMBER'], null, false),
    'ALLIANCE', [], 'MEMBER', true)
  assert.equal(gate(state, 'SQUAD', 'KICK_MEMBER').allowed, true)
  assert.equal(roleText(state, 'SQUAD'), '未加入', '读到了却没职位就是没在这个组织里')
})

test('不能做的那一句只说"职位不行"，绝不出现权限码与职位码', () => {
  const denied = gate(BOTH, 'ALLIANCE', 'KICK_MEMBER')
  assert.equal(denied.allowed, false)
  assert.equal(denied.reason, '你当前的职位不能做这件事')
  assert.ok(!/KICK_MEMBER|MEMBER|LEADER|DONATE/.test(denied.reason ?? ''),
    `理由里出现了内部码：${denied.reason}`)
})

test('职位那一行只翻两种玩家听得懂的，其余说"未知职位"（不印原值）', () => {
  assert.equal(roleText(BOTH, 'SQUAD'), '队长')
  assert.equal(roleText(BOTH, 'ALLIANCE'), '成员')
  const odd = withPermissionScope(BOTH, 'ALLIANCE', [], 'OFFICER', true)
  assert.equal(roleText(odd, 'ALLIANCE'), '未知职位',
    '服务端加了新职位时宁可说未知，也不把 OFFICER 印给玩家')
})

test('空权限（刚建号没进任何组织）：两页都灰且理由一致', () => {
  const none = withPermissionScope(
    withPermissionScope(EMPTY_PERMISSIONS, 'SQUAD', [], null, false),
    'ALLIANCE', [], null, true)
  assert.equal(gate(none, 'SQUAD', 'KICK_MEMBER').reason, '你当前的职位不能做这件事')
  assert.equal(roleText(none, 'ALLIANCE'), '未加入',
    '读到了却没职位就是没加入；说"读取中"会让玩家一直等一个不会来的结果')
})

// ---------- 不抄表 ----------

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'role_permission.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/role_permission.json')
}

const OWN_LOGIC = 'client/assets/scripts/game/social/PermissionGates.ts'

test('本模块零 import：权限码与职位码一份都不抄', () => {
  const src = fs.readFileSync(path.join(repoRoot(), OWN_LOGIC), 'utf8')
  assert.deepEqual(src.match(/^import[^\n]*$/gm) ?? [], [],
    '抄了权限矩阵就是第二份真相 —— 服务端改一次授予，客户端就跟着说谎')
  const table = JSON.parse(fs.readFileSync(path.join(repoRoot(),
    'contract/config/role_permission.json'), 'utf8')) as { rows: Array<Record<string, unknown>> }
  const codes = new Set<string>()
  for (const row of table.rows) {
    for (const value of Object.values(row)) {
      if (Array.isArray(value)) value.forEach((v) => codes.add(String(v)))
    }
  }
  const leaked = [...codes].filter((code) => code.length > 3 && src.includes(code))
  assert.deepEqual(leaked, [], `源码里出现了表里的权限码：${leaked.join(', ')}`)
})
