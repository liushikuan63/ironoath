/**
 * 职责：钉住"资源中文名的客户端副本"与配置表、与契约枚举三方一致。
 * 依赖：node:test / node:assert / node:fs。
 *
 * <p>为什么值得单独立一条：`game/ui/ResourceNames.ts` 的注释自己写着
 * "名字与 `contract/config/resource.json` 的 `name` 列逐字相同"，而**这句话在写下来时没有任何东西判它**。
 * 它是配置表数据在客户端的第二份拷贝（理想形态是服务端下发，那条契约改动已单独排队），
 * 拷贝没有对账就一定会分叉 —— 分叉的症状不是崩溃，是界面上悄悄冒出 `WOOD 5000/20000`。
 *
 * <p>每条都能失败：
 * ① 表里改了中文名而这里没跟 ⇒ 玩家看到的还是旧叫法；
 * ② 契约枚举加了第七种资源而这里漏一行 ⇒ 那种资源在三个面板上都印枚举原文；
 * ③ 值写成 ASCII ⇒ 说明有人在映射里直接塞了 `WOOD: 'WOOD'` 这种假填充；
 * ④ 映射里留着表已删掉的行 ⇒ 白占一份不会有人读到的副本。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { RESOURCE_NAMES, resourceName } from '../assets/scripts/game/ui/ResourceNames'

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'resource.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/resource.json')
}

const rows = (JSON.parse(
  fs.readFileSync(path.join(repoRoot(), 'contract', 'config', 'resource.json'), 'utf8'),
).rows as { id: string, name: string }[])

/** 契约里那截 `export type ResourceType = ...` 的字面量集合（生成物，真源是 schema）。 */
function protocolResourceTypes(): string[] {
  const src = fs.readFileSync(
    path.join(repoRoot(), 'client', 'assets', 'scripts', 'net', 'generated', 'Protocol.ts'), 'utf8')
  const block = /export type ResourceType =\r?\n([\s\S]*?)\r?\n\r?\n/.exec(src)
  const body = block?.[1] ?? ''
  assert.ok(body !== '', '在 Protocol.ts 里找不到 ResourceType 的定义块 —— 生成格式变了，本判据要跟着改')
  return [...body.matchAll(/'([A-Z_]+)'/g)]
    .map((m) => m[1] ?? '')
    .filter((t) => t !== '')
}

test('resource.json 每一行的中文名都在映射里，且逐字相同', () => {
  for (const row of rows) {
    assert.equal(RESOURCE_NAMES[row.id], row.name,
      `资源 ${row.id} 的中文名对不上：表里是「${row.name}」，客户端副本是「${RESOURCE_NAMES[row.id] ?? '(没有这一行)'}」`)
    assert.ok(/[^\x00-\x7F]/.test(row.name), `表里 ${row.id} 的 name 仍是 ASCII，玩家读不出`)
  }
})

test('契约枚举里的每一种资源都有中文名（新增第七种资源时这条会红）', () => {
  const types = protocolResourceTypes()
  assert.ok(types.length >= 6, `只从 Protocol.ts 认出 ${types.length} 个 ResourceType 字面量，判据本身失效了`)
  const missing = types.filter((t) => resourceName(t) === t)
  assert.deepEqual(missing, [], `这些资源类型没有中文名，界面上会直接印枚举原文：${missing.join('、')}`)
})

test('映射里没有表已删掉的行，也没有把枚举原文当名字填进去', () => {
  const ids = new Set(rows.map((r) => r.id))
  const orphans = Object.keys(RESOURCE_NAMES).filter((k) => !ids.has(k))
  assert.deepEqual(orphans, [], `映射里留着 resource.json 没有的行：${orphans.join('、')}`)
  const fake = Object.entries(RESOURCE_NAMES).filter(([, label]) => /^[A-Z_]+$/.test(label))
  assert.deepEqual(fake.map(([k]) => k), [], '映射里有把枚举原文当中文名的假填充')
})

test('认不出的取值退回原文，不返回空串：资源条少一行比多一行英文更难查', () => {
  assert.equal(resourceName('NOT_A_RESOURCE'), 'NOT_A_RESOURCE')
})
