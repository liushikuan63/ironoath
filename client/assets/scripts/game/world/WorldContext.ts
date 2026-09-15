/**
 * 职责：世界数据的运行期上下文 —— 唯一持有 WorldViewModel 实例的地方（B07 表现层入口）。
 * 依赖：WorldViewModel（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>为什么需要一个单例持有者，而不是让场景自己 new 一个 WorldViewModel</b>：
 * 构造 WorldViewModel 需要三个地图参数（worldSize / chunkSize / maxChunks），
 * 它们都在 global.json 里，而客户端的 `config/generated` 只有<b>类型</b>没有<b>值</b> ——
 * 铁律 1（数值零硬编码）不允许场景里写 `512`、`32`、`9`。
 * 所以这三个数必须由网络适配层从服务端拿到后注入进来，场景只读结果。
 * 把注入点收在本模块，是为了让「客户端的地图尺寸从哪来」只有一个答案。
 *
 * <p><b>请求由适配层执行，场景只表达意图</b>：场景每帧问 {@link WorldViewModel#nextRequest}
 * 要不要拉数据，要拉就交给 {@link WorldRequester}。这样场景里不出现任何 fetch/wx.request，
 * 换传输层（微信 / 浏览器 / 编辑器预览）不用动表现层。
 *
 * <p><b>地图布局参数随 MarchListResp 下发</b>（2026-09-13 起）：worldSize / chunkSize / maxChunks
 * 三个值与 home 一起从服务端来，客户端不再镜像 global.json 的常数（铁律 1）。
 * <b>为什么不是 ViewportResp</b>：建地图模型要用这三个值，而第一个 viewport 请求必须等模型建立
 * （要算视野中心块与 chunk 键）—— 放在 viewport 响应里就是鸡生蛋；marches 是进世界建模之前
 * 唯一必拉的响应。
 */

import { WorldViewModel } from './WorldViewModel'
import type { WorldLayout } from './WorldViewModel'
import type { Coord, MarchListResp, ViewportReq, ViewportResp } from '../../net/generated/WorldProtocol'

/**
 * 传输层要实现的世界请求。
 *
 * <p>前两条是「拉数据」；第三条 {@code exile} 是<b>场景表达的动作意图</b> ——
 * 场景仍然不碰传输（这是本文件的既有纪律），但它必须能把「玩家按了流亡迁城」这件事
 * 交给适配层去做，否则按钮就只是一张图。
 */
export interface WorldRequester {
  /** 拉取视野内的块。适配层负责把响应喂给 {@link feedViewport}。 */
  viewport(req: ViewportReq): void
  /** 拉取自己的全部行军。适配层负责把响应喂给 {@link feedMarches}。 */
  marches(): void
  /** 发起一次流亡迁城。适配层负责调 {@code POST /world/exile} 并落地结果。 */
  exile(): void | Promise<void>
  /** 召回一支自己的行军。适配层负责请求与刷新行军列表。 */
  recall(marchId: string): Promise<WorldActionResult>
  /** 结束采集并让队伍返程。适配层负责请求与刷新行军列表。 */
  collectGather(marchId: string): Promise<WorldActionResult>
}

/** 场景动作的最终结果。成功与失败都要有玩家能读的说明。 */
export interface WorldActionResult {
  readonly ok: boolean
  readonly message: string
}

/** 流亡迁城的客户端事实。全部来自服务端下发，本地一份都不自己算。 */
export interface ExileSnapshot {
  readonly nextExileAt: number | null
  readonly peaceUntil: number | null
  readonly troopsAway: number
  readonly serverNow: number
}

const EMPTY_EXILE: ExileSnapshot = {
  nextExileAt: null, peaceUntil: null, troopsAway: 0, serverNow: 0,
}

let model: WorldViewModel | null = null
let requester: WorldRequester | null = null
let exile: ExileSnapshot = EMPTY_EXILE

/**
 * 建立世界上下文（登录成功、拿到家坐标之后调用一次）。
 *
 * @param layout   地图尺寸参数，来源 global.json
 * @param offsetMs 服务端时刻 - 本地时刻，来源 core/TimeSync
 * @param home     视野初始中心。用家坐标而不是 (0,0)：玩家打开大地图第一眼要看到自己的城
 */
export function initializeWorld(layout: WorldLayout, offsetMs: number, home: Coord): WorldViewModel {
  model = new WorldViewModel(layout, offsetMs, home)
  return model
}

/** 当前世界模型；未初始化（未登录）时为 null。场景据此画「未连接」而不是画一张假地图。 */
export function worldModel(): WorldViewModel | null {
  return model
}

/** 绑定传输层。不绑定时场景照常渲染已有数据，只是不会发起新请求（编辑器预览用）。 */
export function bindWorldRequester(next: WorldRequester | null): void {
  requester = next
}

export function worldRequester(): WorldRequester | null {
  return requester
}

/** 落地一次 viewport 响应。适配层收到响应后调它，不要直接摸 WorldViewModel。 */
export function feedViewport(resp: ViewportResp): void {
  model?.applyViewport(resp)
}

/** 落地一次行军列表响应。localNow 必须是<b>收到响应那一刻</b>的本地时刻，理由见 applyMarches。 */
export function feedMarches(resp: MarchListResp, localNow: number): void {
  model?.applyMarches(resp, localNow)
  // 免战与流亡冷却随同一次响应下发：客户端不为它们多发一个请求，也不自己算第二份窗口
  exile = {
    nextExileAt: resp.nextExileAt,
    peaceUntil: resp.peaceUntil,
    troopsAway: resp.marches.length,
    serverNow: resp.serverNow,
  }
}

/** 流亡迁城按钮要读的事实。未拿到任何响应时为 {@link EMPTY_EXILE}（视为可迁，由服务端否决）。 */
export function exileSnapshot(): ExileSnapshot {
  return exile
}

/**
 * 落地一次成功的迁城：立刻改本地事实并把视野中心搬到新家。
 *
 * <p>不等下一次列表刷新，是因为「搬完了地图还停在旧址」会让玩家以为迁城失败又点一次；
 * 而在外的队伍数归零是服务端的前置条件给出来的事实，不是猜测。
 */
export function applyExileResult(coord: Coord, peaceUntil: number, nextExileAt: number,
                                 serverNow: number): void {
  exile = { nextExileAt, peaceUntil, troopsAway: 0, serverNow }
  model?.relocateHome(coord)
}

/** 时间校准更新（每个 HTTP 响应都带 serverNow，等于免费校准一次）。 */
export function feedTimeOffset(offsetMs: number): void {
  model?.updateOffset(offsetMs)
}

/**
 * 清空（退出登录、切换账号）。同时解绑传输层，否则旧账号的响应会写进新账号的地图；
 * 流亡冷却与免战也必须一起清，否则新号会显示上一个号「还在 3 天冷却中」。
 */
export function resetWorld(): void {
  exile = EMPTY_EXILE
  model?.clear()
  model = null
  requester = null
}
