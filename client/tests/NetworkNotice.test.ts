/**
 * 职责：弱网文案与计数规则的证据（B16 验收 2「有超时重试提示」的后半句）。
 * 依赖：真实的 NetworkNotice（纯逻辑）。
 *
 * <p><b>为什么这些句子值得单测</b>：提示只有一行，改它的人不会去真机复现一次弱网，
 * 于是最容易滑进去的两件事是 ——
 * ① 重投已经用尽还写着"正在重试"（玩家就一直等，而等待的代价是他的时间）；
 * ② 并发的十几条请求把同一行刷成滚动字幕，或者计数往回走（看起来像网络在退步）。
 * 这两条都不报错，只让玩家对"该等还是该退出"做出错误判断。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { NetworkNotice } from '../assets/scripts/game/network/NetworkNotice'

const retry = (attempt: number, maxAttempts = 3, path = '/player/init') =>
  ({ kind: 'retry' as const, path, attempt, maxAttempts })

test('重投时给一句在等的画，恢复后收回', () => {
  const notice = new NetworkNotice()

  assert.equal(notice.current, null, '没出过事时那一行不该占屏幕')

  notice.observe(retry(1))
  assert.equal(notice.current, '网络不稳定，正在重试（第 1 次）')

  notice.observe(retry(2))
  assert.equal(notice.current, '网络不稳定，正在重试（第 2 次）', '玩家看的就是这个数在动')

  notice.observe({ kind: 'recovered' })
  assert.equal(notice.current, null, '链路通了必须收回 —— 留着就是在说还没好')
})

test('并发请求各报各的时只朝"更深"走，不把已经显示的次数往回改', () => {
  const notice = new NetworkNotice()

  notice.observe(retry(3, 3, '/stage/list'))
  notice.observe(retry(1, 3, '/bag/list'))

  assert.equal(notice.current, '网络不稳定，正在重试（第 3 次）',
    '另一个请求的第 1 次不该把计数拉回 1：那读起来像网络在退步')
  assert.ok(notice.current !== null && !notice.current.includes('/'),
    '端点路径不进玩家文案：玩家不需要知道是 /stage/list 还是 /bag/list 没通')
})

test('重投用尽后不再说"正在"，说没接通', () => {
  const notice = new NetworkNotice()
  notice.observe(retry(3))

  notice.observe({ kind: 'givenUp', path: '/player/init', attempts: 3 })

  const text = notice.current
  assert.ok(text !== null)
  assert.ok(text.includes('3 次'), `要报出真的重投过几次：${text}`)
  assert.ok(!text.includes('正在'), `最后一次已经失败，还说"正在重试"就是在撒谎：${text}`)
})

test('一次抖动结束后计数归零：下一次抖动从第 1 次重新开始', () => {
  const notice = new NetworkNotice()
  notice.observe(retry(2))
  notice.observe({ kind: 'recovered' })

  notice.observe(retry(1))

  assert.equal(notice.current, '网络不稳定，正在重试（第 1 次）',
    '把上一段抖动的深度带进这一段，会让玩家以为网络一直在恶化')
})

test('没接通时给的一句仍然不含具体原因（原因归面板那一路，两件事的正确动作相反）', () => {
  const notice = new NetworkNotice()
  notice.observe({ kind: 'givenUp', path: '/city/upgrade', attempts: 3 })

  const text = notice.current
  assert.ok(text !== null && text.includes('检查网络'), '弱网下玩家该做的是等或查网络')
  assert.ok(!text.includes('/city/upgrade') && !text.includes('失败原因'),
    `把"操作被拒绝"的原因塞进这一行，玩家就分不清该等还是该改操作：${text}`)
})
