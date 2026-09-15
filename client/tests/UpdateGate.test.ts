/**
 * 职责：客户端版本闸门的证据（B16 §5「强制更新」）。
 * 依赖：真实的 UpdateGate 模块，只有传输结果是假的。
 *
 * <p>这个文件存在的理由是一条实测出来的缺口：协议里写明 `forceUpdate=true` 时客户端
 * 「必须停在提示页，不得进入游戏」，而客户端此前只在启动时取过 `trackPolicy` ——
 * `forceUpdate` 与 `notice` 两个字段**全仓零引用**。服务端算出"该拦"，玩家照样登录、
 * 照样拉十个面板。所以这里钉的不是"函数返回了什么"，而是**"什么情况下必须拦住玩家"**。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { decideUpdateGate } from '../assets/scripts/game/release/UpdateGate'
import type { AppVersionResp } from '../assets/scripts/net/generated/OpsProtocol'

const CLIENT = '1.0.0'

function resp(overrides: Partial<AppVersionResp> = {}): AppVersionResp {
  return {
    latest: '2.0.0',
    forceUpdate: false,
    grayEnabled: false,
    notice: null,
    trackPolicy: { maxBatchSize: 10, flushSeconds: 10 },
    ...overrides,
  }
}

test('forceUpdate=true 必须拦住，并把服务端那句文案原样带给玩家', () => {
  const notice = '旧版本的接口与配置表已不再维护，继续游戏会出现数据异常。'
  const decision = decideUpdateGate(
    { kind: 'ok', data: resp({ forceUpdate: true, notice }), traceId: 't', serverNow: 1 },
    CLIENT,
  )

  assert.equal(decision.blocked, true, '服务端说该拦就必须拦')
  assert.equal(decision.notice, notice, '文案来自服务端（global.RELEASE_FORCE_UPDATE_NOTICE），不是客户端编的')
  assert.equal(decision.latest, '2.0.0')
  assert.equal(decision.serverSeen, true)
})

test('服务端漏配文案时兜一句带版本号的说明，而不是让玩家对着空白页', () => {
  const decision = decideUpdateGate(
    { kind: 'ok', data: resp({ forceUpdate: true, notice: null }), traceId: 't', serverNow: 1 },
    CLIENT,
  )

  assert.equal(decision.blocked, true)
  assert.ok((decision.notice ?? '').length > 0, '拦了人却不说为什么，玩家会以为游戏坏了')
  assert.ok((decision.notice ?? '').includes(CLIENT), '兜底文案要说清当前版本：' + decision.notice)
  assert.ok((decision.notice ?? '').includes('2.0.0'), '也要说清该升到哪个版本：' + decision.notice)

  // 空白串与缺失走同一条兜底：配了一个空格也是"没说清"
  const blank = decideUpdateGate(
    { kind: 'ok', data: resp({ forceUpdate: true, notice: '   ' }), traceId: 't', serverNow: 1 },
    CLIENT,
  )
  assert.ok((blank.notice ?? '').trim().length > 0)
})

test('不强制更新就不拦：有新版本但不是强制档，玩家该照常进游戏', () => {
  const decision = decideUpdateGate(
    { kind: 'ok', data: resp({ forceUpdate: false, latest: '9.9.9' }), traceId: 't', serverNow: 1 },
    CLIENT,
  )

  assert.equal(decision.blocked, false)
  assert.equal(decision.notice, null, '不拦的时候不该甩一句提示出来打扰玩家')
  assert.equal(decision.latest, '9.9.9')
})

test('版本查询失败不拦，但必须看得出来"这次没判"', () => {
  const network = decideUpdateGate(
    { kind: 'network', message: '连接超时', queued: false, retryable: true } as never,
    CLIENT,
  )
  assert.equal(network.blocked, false, '外部依赖抖一下就把全服变成打不开的提示页，比放行更糟')
  assert.equal(network.serverSeen, false, '没问成与"判定为放行"在日志里必须能分开')

  const biz = decideUpdateGate(
    { kind: 'biz', code: 1000, msg: '系统繁忙', detail: null, traceId: 't', serverNow: 1 } as never,
    CLIENT,
  )
  assert.equal(biz.blocked, false)
  assert.equal(biz.serverSeen, false)

  const none = decideUpdateGate(null, CLIENT)
  assert.equal(none.blocked, false)
  assert.equal(none.serverSeen, false)
  assert.equal(none.latest, CLIENT, '没问到的时候"最新版本"只能回落成自己，不能编一个')
})
