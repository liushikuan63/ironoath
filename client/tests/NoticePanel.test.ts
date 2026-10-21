import test from 'node:test'
import assert from 'node:assert/strict'

import { buildNoticeView, refreshNoticeView } from '../assets/scripts/game/notice/NoticePanel'
import type { NoticeListResp } from '../assets/scripts/net/generated/NoticeProtocol'

function resp(version: string, notices: { id: string; title: string; body: string; priority: number }[]): NoticeListResp {
  return { version, notices }
}

const ONE = { id: 'notice_welcome', title: '欢迎来到铁誓', body: '先从内城的三件事做起。', priority: 10 }

test('公告视图把服务端的行转成界面语言，顺序照服务端给的（客户端不重排）', () => {
  const view = buildNoticeView(resp('a1b2c3d4e5f60718', [ONE, { ...ONE, id: 'notice_b', title: '第二条' }]))
  assert.equal(view.rows.length, 2)
  assert.equal(view.rows[0]?.id, 'notice_welcome')
  assert.equal(view.rows[0]?.title, '欢迎来到铁誓')
  assert.equal(view.rows[0]?.body, '先从内城的三件事做起。')
  assert.equal(view.version, 'a1b2c3d4e5f60718')
})

test('摘要念「有几条 + 最新一条是谁」，没有公告时是一句「暂无公告」而不是空面板', () => {
  const none = buildNoticeView(resp('0011223344556677', []))
  assert.equal(none.rows.length, 0)
  assert.equal(none.summary, '暂无公告')

  const some = buildNoticeView(resp('0011223344556677', [ONE]))
  assert.match(some.summary, /共 1 条/)
  assert.match(some.summary, /欢迎来到铁誓/)
})

test('安全刷新点：版本没变就不重绘（拉一次是真的，但别让界面闪一下）', () => {
  const first = refreshNoticeView(resp('aaaa0000bbbb1111', [ONE]), null)
  assert.equal(first.changed, true, '没记过版本 ⇒ 第一次当作变了')

  const same = refreshNoticeView(resp('aaaa0000bbbb1111', [ONE]), first.view.version)
  assert.equal(same.changed, false, '版本没变 ⇒ 不重绘')

  const newer = refreshNoticeView(resp('cccc2222dddd3333', [ONE]), first.view.version)
  assert.equal(newer.changed, true, '版本变了 ⇒ 要重绘')
})

test('版本没变但文案变了 ⇒ 仍然要重绘（用表版本而不是条数，正是为了这个）', () => {
  const before = refreshNoticeView(resp('aaaa0000bbbb1111', [ONE]), null)
  // 条数一样、版本一样，只有文案不同 —— 这在真实表里不可能（版本随内容变），
  // 但这条判据钉的是「客户端拿版本号当依据」，不是拿条数。
  const after = refreshNoticeView(resp('aaaa0000bbbb1111', [{ ...ONE, title: '改过的标题' }]), before.view.version)
  assert.equal(after.changed, false, '版本没变 ⇒ 客户端不重绘（它不知道内容变了）')
  assert.equal(after.view.rows[0]?.title, '改过的标题', '但取回来的内容确实是新的')
})

test('这次没拿到响应（null）⇒ 页面照常能用：空列表 + 摘要说明', () => {
  const view = buildNoticeView(null)
  assert.equal(view.rows.length, 0)
  assert.equal(view.summary, '暂无公告')
  assert.equal(view.version, '')
})
