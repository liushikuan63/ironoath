/**
 * 职责：客户端红点树的单测 —— B12 §4（双来源）、验收 1（父级链路正确聚合、无遗漏、无假红点）。
 * 依赖：node:test / node:assert。
 *
 * <p>与服务端 ReddotTreeTest 的分工：服务端测「条件注册与聚合」，
 * 这里测「两个来源合并」—— 那是客户端独有的部分，也是铁律 2 最容易破的地方
 * （客户端一旦自己判断「资源够不够升级」，双端就有两套口径）。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { ClientReddotTree } from '../assets/scripts/game/reddot/ReddotTree'
import type { ReddotNodeView } from '../assets/scripts/game/reddot/ReddotTree'

function node(key: string, lit: boolean, children: ReddotNodeView[] = []): ReddotNodeView {
  return { key, lit, children }
}

/** 服务端下发的一棵典型树：主城 → 建筑页 → 两个建筑。 */
function serverTree(lit: boolean): ReddotNodeView[] {
  return [node('city', lit, [
    node('city/building', lit, [
      node('city/building/woodmill', lit),
      node('city/building/farm', false),
    ]),
    node('city/army', false),
  ])]
}

test('服务端来源：下发的树被拍平，父节点的结论被直接采信', () => {
  const tree = new ClientReddotTree()
  tree.applyServer(serverTree(true))
  assert.equal(tree.isLit('city'), true)
  assert.equal(tree.isLit('city/building'), true)
  assert.equal(tree.isLit('city/building/woodmill'), true)
  assert.equal(tree.isLit('city/building/farm'), false)
  assert.equal(tree.serverNodeCount(), 5)
})

test('整体替换而不是合并：服务端说某个红点消失了，客户端就不能留着它（假红点）', () => {
  const tree = new ClientReddotTree()
  tree.applyServer(serverTree(true))
  assert.equal(tree.isLit('city'), true)

  tree.applyServer(serverTree(false))
  assert.equal(tree.isLit('city'), false, '玩家点进去发现没东西，就是验收 1 说的假红点')
  assert.equal(tree.isLit('city/building/woodmill'), false)
})

test('本地来源：注册的叶子能点亮自己与全部祖先', () => {
  const tree = new ClientReddotTree()
  let unread = false
  tree.registerLocal('chat/squad/unread', () => unread, '本地缓存的小队频道有未读')

  assert.equal(tree.isLit('chat'), false)
  unread = true
  assert.equal(tree.isLit('chat/squad/unread'), true)
  assert.equal(tree.isLit('chat/squad'), true, '中间层由本地叶子推导出来')
  assert.equal(tree.isLit('chat'), true, '根层同理')
  assert.equal(tree.isLit('city'), false, '兄弟链路不该被点亮')
})

test('双来源取或：服务端与本地各自都能点亮同一个节点', () => {
  const tree = new ClientReddotTree()
  tree.applyServer(serverTree(false))
  let localLit = false
  // 同一个父路径下挂一个本地叶子
  tree.registerLocal('city/building/firstOpen', () => localLit, '建筑页第一次打开')

  assert.equal(tree.isLit('city/building'), false)
  localLit = true
  assert.equal(tree.isLit('city/building'), true)
  assert.equal(tree.isLit('city'), true, '本地来源也要沿父链聚合上去')
})

test('服务端只下发子树的一部分时，本地叶子仍然能补上聚合链', () => {
  const tree = new ClientReddotTree()
  tree.applyServer([node('city', false, [node('city/army', false)])])
  tree.registerLocal('city/building/woodmill', () => true, '本地判断的可升级建筑')

  assert.equal(tree.isLit('city/building'), true, 'city/building 不在服务端树里也要被推导出来')
  assert.equal(tree.isLit('city'), true)
  assert.equal(tree.isLit('city/army'), false)
})

test('snapshot 合并两侧结构，本地独有的分支会补出中间节点', () => {
  const tree = new ClientReddotTree()
  tree.applyServer(serverTree(false))
  tree.registerLocal('mail/unread', () => true, '本地缓存的未读邮件')

  const snapshot = tree.snapshot()
  assert.deepEqual(snapshot.map((item) => item.key), ['city', 'mail'], '顶层按 key 排序')

  const city = snapshot[0]
  assert.equal(city?.lit, false)
  assert.deepEqual(city?.children.map((item) => item.key), ['city/army', 'city/building'])

  const mail = snapshot[1]
  assert.equal(mail?.lit, true)
  // 本地叶子本身也是树上一个节点：UI 要能绑到它，所以它出现在补出来的父节点下
  assert.deepEqual(mail?.children.map((item) => item.key), ['mail/unread'])
  assert.equal(mail?.children[0]?.lit, true)
})

test('snapshot 的父节点亮灭等于后代叶子的或（自底向上聚合）', () => {
  const tree = new ClientReddotTree()
  tree.applyServer(serverTree(false))
  tree.registerLocal('city/building/farm', () => true, '本地判断的农田')

  const city = tree.snapshot()[0]
  assert.equal(city?.lit, true)
  const building = city?.children.find((item) => item.key === 'city/building')
  assert.equal(building?.lit, true)
  const farm = building?.children.find((item) => item.key === 'city/building/farm')
  assert.equal(farm?.lit, true)
  const army = city?.children.find((item) => item.key === 'city/army')
  assert.equal(army?.lit, false, '无假红点')
})

test('条件抛异常按不亮处理并记数：静默吞掉会让那个红点永远不亮而没人知道', () => {
  const tree = new ClientReddotTree()
  tree.registerLocal('broken/leaf', () => {
    throw new Error('本地状态读不到')
  }, '会抛异常的红点')
  tree.registerLocal('city/ok', () => true, '正常的红点')

  assert.equal(tree.isLit('broken'), false)
  assert.equal(tree.errorCount(), 1)
  assert.equal(tree.isLit('city'), true, '一个叶子坏了不该让整棵树查询失败')

  tree.clear()
  assert.equal(tree.errorCount(), 0)
  assert.equal(tree.localLeafCount(), 0)
  assert.equal(tree.serverNodeCount(), 0)
})

test('条件返回非 true 一律按不亮处理（undefined / 0 / "yes" 都不算）', () => {
  const tree = new ClientReddotTree()
  tree.registerLocal('a', () => undefined as unknown as boolean, '返回 undefined')
  tree.registerLocal('b', () => 1 as unknown as boolean, '返回数字')
  assert.equal(tree.isLit('a'), false)
  assert.equal(tree.isLit('b'), false)
  assert.equal(tree.errorCount(), 0, '返回奇怪的值不是异常，不该计入 errorCount')
})

test('同 key 重复注册抛错；注销后不再参与聚合', () => {
  const tree = new ClientReddotTree()
  tree.registerLocal('city/x', () => true, '第一次')
  assert.throws(() => tree.registerLocal('city/x', () => false, '第二次'), /重复注册/)

  assert.equal(tree.isLit('city'), true)
  assert.equal(tree.unregisterLocal('city/x'), true)
  assert.equal(tree.unregisterLocal('city/x'), false)
  assert.equal(tree.isLit('city'), false, '功能下线了红点还亮着，是最难排查的一类残留')
})

test('非法路径在注册期与查询期都被拒绝', () => {
  const tree = new ClientReddotTree()
  assert.throws(() => tree.registerLocal('/city', () => true, 'x'), /开头或结尾/)
  assert.throws(() => tree.registerLocal('city/', () => true, 'x'), /开头或结尾/)
  assert.throws(() => tree.registerLocal('city//x', () => true, 'x'), /连续分隔符/)
  assert.throws(() => tree.registerLocal('', () => true, 'x'), /不得为空/)
  assert.throws(() => tree.isLit('/city'), /开头或结尾/)
})

test('路径前缀匹配按段而不是按字符串：city/buildingx 不是 city/building 的子节点', () => {
  const tree = new ClientReddotTree()
  tree.registerLocal('city/buildingx/other', () => true, '另一个分支')
  assert.equal(tree.isLit('city/building'), false)
  assert.equal(tree.isLit('city/buildingx'), true)
  assert.equal(tree.isLit('city'), true)
})

test('铁律2：本模块不做业务数值判定 —— 条件由调用方给，树只负责聚合', () => {
  // 这条断言的意义在于「测试自己就是文档」：
  // ClientReddotTree 的公开 API 里没有任何接受数值参数的方法，
  // 所以想在这里判断「资源够不够升级」连入口都没有。
  // 静态层面由 scripts/check-no-scattered-reddot.sh 兜住。
  const api = Object.getOwnPropertyNames(ClientReddotTree.prototype)
  for (const name of api) {
    assert.doesNotMatch(name, /resource|level|power|cap|cost/i,
      `红点树不该有与业务数值相关的方法：${name}`)
  }
})
