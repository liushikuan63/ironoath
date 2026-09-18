/**
 * 职责：登录会话编排 —— 把 NetModule、Store、TimeSync 串成 B01 的「客户端登录」链路。
 * 依赖：net/NetModule、game/store/Store、core/TimeSync、core/EventBus、生成的契约类型。
 *
 * 这是客户端唯一会<b>写入</b> Store 的地方（铁律 2、3）：
 * UI 只读 Store，所有权威数值都在收到服务端响应后由本类写入，
 * 因此「服务端结果到达 → 覆盖本地 → UI 自动纠偏」是一条单行道。
 *
 * 本类不做任何数值判定：不校验战力、不计算产出、不预测战斗结果。
 * 它只负责把服务端给的东西原样放进 Store。
 */

import { gameBus } from '../../core/EventBus'
import type { TimeSync } from '../../core/TimeSync'
import type { NetModule, NetOutcome } from '../../net/NetModule'
import type { PlayerInitReq, PlayerInitResp, TimeSyncResp } from '../../net/generated/Protocol'
import type { Store } from '../store/Store'

export interface SessionDeps {
  readonly net: NetModule
  readonly store: Store
  readonly timeSync: TimeSync
  readonly now: () => number
  readonly newRequestId: () => string
}

export class GameSession {
  private readonly deps: SessionDeps

  constructor(deps: SessionDeps) {
    this.deps = deps
  }

  /**
   * 登录或建号（同一个接口，服务端按 deviceId 区分）。
   *
   * requestId 必须携带：断网重试时服务端凭它去重，否则会建出两个号（B01 验收 11）。
   */
  async login(deviceId: string, nickName: string,
              wxCode: string | null = null): Promise<NetOutcome<PlayerInitResp>> {
    const request: PlayerInitReq = {
      requestId: this.deps.newRequestId(),
      deviceId,
      nickName,
      clientTime: this.deps.now(),
      // 微信小游戏传 wx.login 的 code；浏览器/编辑器预览保持空串（服务端只看空白）
      wxCode: wxCode ?? '',
    }
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.post<PlayerInitReq, PlayerInitResp>(
      '/player/init',
      request,
      { idempotent: true, queueWhenOffline: false },
    )
    const rttMs = this.deps.now() - sentAt

    if (outcome.kind !== 'ok') {
      this.deps.store.patch({ online: outcome.kind !== 'biz' })
      return outcome
    }

    const data = outcome.data
    const isNewPlayer = this.deps.store.getState().playerId === null
    this.deps.store.patch({
      playerId: data.playerId,
      nickName: data.profile.nickName,
      cityLevel: data.cityLevel,
      resources: data.resources,
      power: data.power,
      protectUntil: data.protectUntil,
      serverNow: data.serverNow,
      online: true,
    })

    // 响应里的 serverNow 就是一次免费的时钟校准样本，rtt 用本次请求的实测值
    this.deps.timeSync.offer(rttMs, data.serverNow - request.clientTime)

    // 三个身份动作按这个顺序，缺一不可，且顺序不能反：
    // setPlayer 让之后的每个 HTTP 请求带上服务端必填的身份头；票据要在开连接之前设好，
    // 因为握手 URL 与连接建立时那一发 bind 都是从 NetModule 的当前身份现取的
    this.deps.net.setPlayer(data.playerId)
    // 服务端签发的会话票据。旧服务端不带这个字段时退化为空串，
    // 本地宽松身份实现照样放行；严格实现下空票据会被 2006 拒掉，这是应该的
    // 旧服务端（或旧夹具）没有这个字段：undefined/null 都按"没有票据"处理，
    // 不去改几十个测试夹具 —— 协议是增量字段，客户端必须能滚动升级
    const authToken = data.authToken
    this.deps.net.setAuthToken(typeof authToken === 'string' && authToken.length > 0 ? authToken : null)
    // 长连接由登录这一步开：晚于身份（URL 要带凭据），而"报身份"不用这里操心 ——
    // 握手是异步的，NetModule 在每条连接建立时（含断线重连）自己发 bind
    this.deps.net.connectSocket()
    gameBus.emit('playerReady', { playerId: data.playerId, isNewPlayer })
    return outcome
  }

  /**
   * 主动做一次时间校准。
   *
   * 登录时已经顺带校准过一次，这里用于长时间挂后台回到前台后的重新对齐：
   * 手机休眠期间本地时钟可能漂移，而产出倒计时全靠它。
   */
  async syncTime(): Promise<number> {
    const sentAt = this.deps.now()
    const outcome = await this.deps.net.post<{ clientTime: number }, TimeSyncResp>(
      '/time/sync',
      { clientTime: sentAt },
      { idempotent: false },
    )
    if (outcome.kind !== 'ok') {
      return this.deps.timeSync.offsetMs()
    }
    const rttMs = this.deps.now() - sentAt
    this.deps.timeSync.offer(rttMs, outcome.data.sync.offset)
    this.deps.store.patch({ serverNow: outcome.data.sync.syncAt })
    return this.deps.timeSync.offsetMs()
  }

  /** 订阅网络事件，把连通性状态同步进 Store 供 UI 显示。 */
  bindNetworkEvents(): () => void {
    const offDisconnected = gameBus.on('netDisconnected', () => {
      this.deps.store.patch({ online: false })
    })
    const offReconnected = gameBus.on('netReconnected', () => {
      this.deps.store.patch({ online: true })
      // 重连期间的推送全丢了，必须主动补拉（B01 要求：重连期间消息走 HTTP 补拉）
      void this.syncTime()
    })
    const offHint = gameBus.on('serverTimeHint', (hint) => {
      this.deps.store.patch({ serverNow: hint.serverNow })
    })
    return () => {
      offDisconnected()
      offReconnected()
      offHint()
    }
  }
}
