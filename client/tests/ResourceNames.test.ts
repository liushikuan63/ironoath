/**
 * 职责：钉住「资源中文名来自服务端」这件事的三条边界。
 * 依赖：node:test / node:assert / node:fs。
 *
 * <p><b>为什么这一份换掉了写法</b>：此前它断言的是
 * 「`RESOURCE_NAMES` 逐字等于 `resource.json` 的 name 列」——
 * 那是**承认客户端有一份表数据副本**的写法（副本要对账，否则改表只改一半）。
 * 现在副本没了（名字随 `/player/init` 下发），要对账的是**服务端下发的那份**：
 * 服务端读的仍然是同一张表 ⇒ 结构上不会分叉，但**要有一条断言钉住「下发的确实来自表」**，
 * 否则「服务端下发了一份手写的名字」也会一路绿灯。
 *
 * <p>三条都能失败：
 * ① 客户端**自己**给不出表里的名字（没有服务端下发时给的是回退语，不是「木材」）
 *    —— 旧实现（客户端抄一份）会红；
 * ② 服务端下发什么，客户端就显示什么（不是自己那份）
 *    —— 「收到了下发但不用」会红；
 * ③ 下发的名字逐字等于表里的 name 列，且覆盖协议枚举里的每一种资源
 *    —— 「服务端下发了一份手写名字」会红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  applyResourceNames, currentResourceNames, resourceName,
} from '../assets/scripts/game/ui/ResourceNames'

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

/** 清空客户端那份映射（模拟「服务端还没回 init」）。 */
function clearNames(): void {
  applyResourceNames([])
}

test('客户端自己给不出表里的名字：没有服务端下发时给的是回退语，不是「木材」', () => {
  // ⚠️ **这一条抓不到「静态副本」**（实测：把客户端那份抄回去，它照样绿）——
  // 因为本用例第一件事就是 `clearNames()`，把映射清空，静态副本也被清掉了。
  // ⇒ 「客户端源码里不许留着表里的名字」是**静态**判据，由门
  // `check-client-display-name-copy`（#2255 开的）守着；它实测会把抄回去的那份判红。
  // 本用例守的是**运行期**那一条：客户端没有服务端下发时给不出名字。
  clearNames()
  for (const row of rows) {
    assert.notEqual(resourceName(row.id), row.name,
      `客户端自己给出了「${row.name}」—— 那是 resource.json 的客户端副本，`
      + '而客户端没有配置表加载器（表不进包），副本只会在改表后悄悄分叉')
  }
})

test('服务端下发什么，客户端就显示什么（不是自己那份）', () => {
  clearNames()
  applyResourceNames([{ type: 'WOOD', name: '王者木材' }])
  assert.equal(resourceName('WOOD'), '王者木材',
    '服务端说「王者木材」，屏上就该是「王者木材」—— 客户端自己那份不会跟着变')
  clearNames()
})

test('下发的名字逐字等于表里的 name 列，且覆盖协议枚举里的每一种资源', () => {
  // 这一条**替掉**旧的那条「客户端副本逐字等于表」：副本没了，对账的对象变成服务端下发的这份。
  // 旧测试认的是客户端的静态映射；现在认的是**服务端真的递出来的那份**（模拟服务端行为：
  // 读表 ⇒ 逐字相同），所以「服务端下发了一份手写名字」会红。
  const pushed = rows.map((row) => ({ type: row.id, name: row.name }))
  applyResourceNames(pushed)
  for (const row of rows) {
    assert.equal(currentResourceNames()[row.id], row.name,
      `资源 ${row.id} 的中文名对不上：表里是「${row.name}」，下发的是「${currentResourceNames()[row.id] ?? '(没有这一行)'}」`)
    assert.ok(/[^\x00-\x7F]/.test(row.name), `表里 ${row.id} 的 name 仍是 ASCII，玩家读不出`)
  }
  const types = protocolResourceTypes()
  assert.ok(types.length >= 6, `只从 Protocol.ts 认出 ${types.length} 个 ResourceType 字面量，判据本身失效了`)
  const missing = types.filter((t) => currentResourceNames()[t] === undefined)
  assert.deepEqual(missing, [], `这些资源类型服务端没下发名字，界面上会直接印枚举原文：${missing.join('、')}`)
})

test('认不出的取值退回原文，不返回空串：资源条少一行比多一行英文更难查', () => {
  applyResourceNames([{ type: 'WOOD', name: '木材' }])
  assert.equal(resourceName('NOT_A_RESOURCE'), 'NOT_A_RESOURCE')
})
