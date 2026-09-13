/**
 * 职责：把服务端下发的世界数据组装成「这一帧要画什么」（B07 §1/§3/§4，验收 3/4/6/10）。
 * 依赖：ChunkCache、MarchInterpolator（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>为什么要有这一层，而不是让场景直接读 ChunkCache</b>：
 * 场景里能跑的只有「拿到一坨数据 → 画出来」，一旦把「哪些块该画成迷雾」「我的行军和
 * 块里的行军实体哪个优先」「服务端纠偏了没有」这些判断写进 cc 组件，它们就再也测不到了 ——
 * `tsconfig.test.json` 刻意把 `scene/` 排除在 `node --test` 之外（场景 import 了 cc）。
 * 所以所有<b>可判定的</b>逻辑都收在本模块，场景只剩绘制与节点池。
 *
 * <p><b>本模块不做任何数值判定</b>（铁律 2）：它不算距离、不算行军时间、不判断能不能打，
 * 只把服务端给的结论搬运到渲染列表里。唯一自己算的是插值位置，而那是 MarchInterpolator 的
 * 既定职责，且服务端一推送就立即被覆盖（验收 10）。
 *
 * <p><b>内存有上界</b>（验收 3）：本模块持有的实体全部来自 ChunkCache，而 ChunkCache 的不变量是
 * 「同时持有的块数 <= maxChunks」。所以「连续拖动 60 秒内存增长 < 10MB」不需要靠场景自觉，
 * 只要场景老老实实按 `frame().tiles` 与 `frame().marches` 的差集回收节点即可。
 */

import { ChunkCache } from './ChunkCache'
import { MarchInterpolator } from './MarchInterpolator'
import type { MarchTrack } from './MarchInterpolator'
import type {
  Coord,
  MarchListResp,
  MarchStatus,
  ViewportReq,
  ViewportResp,
  WorldEntity,
} from '../../net/generated/WorldProtocol'
import type { Unsubscribe } from '../../core/EventBus'

/**
 * 地图尺寸参数。三个数都在 global.json 里，由调用方（网络适配层）从服务端注入。
 *
 * <p>TODO(B07 表现层缺口): ViewportResp 目前不下发这三个值，客户端只能靠注入。
 * 但铁律 1 要求数值零硬编码，而客户端的 config/generated 只有<b>类型</b>没有<b>值</b>。
 * 正解是让服务端像 MarchListResp.maxConcurrent 那样把它们随响应下发（那条字段的注释已经
 * 写明了这个先例：「下发是为了让客户端能显示 2/3 而不是自己读配置」）。
 */
export interface WorldLayout {
  /** 世界边长（格）。来源 global.WORLD_SIZE */
  readonly worldSize: number
  /** 分块边长（格），必须是 2 的幂。来源 global.WORLD_CHUNK_SIZE */
  readonly chunkSize: number
  /** 同时持有的块数上限，必须是完全平方数（3×3=9）。来源 global.VIEWPORT_CHUNK_COUNT */
  readonly maxChunks: number
}

/** 视野里的一块。 */
export interface ChunkTile {
  readonly key: string
  /** 块索引（不是世界格坐标）。左上角世界坐标 = (cx * chunkSize, cy * chunkSize) */
  readonly cx: number
  readonly cy: number
  /**
   * 是否已从服务端取到。false 表示请求还没回来 —— 场景要画成「加载中」，
   * 绝不能画成迷雾：两者的玩家含义完全不同（一个是网络慢，一个是没探索过）。
   */
  readonly loaded: boolean
  /** 是否是迷雾块。服务端对迷雾块一律不下发实体（否则改客户端就能透视全图），场景盖黑遮罩 */
  readonly fogged: boolean
  /** 本块要画的实体。EMPTY 与「已由本地插值接管的己方行军」已被剔除 */
  readonly entities: readonly WorldEntity[]
}

/** 一支要画出来的行军。坐标已由 MarchInterpolator 按服务端时间插值。 */
export interface MarchRender {
  readonly marchId: string
  readonly x: number
  readonly y: number
  readonly status: MarchStatus
  /** 0~1 的行程进度，只用于画进度条/拖尾，不参与任何判定 */
  readonly progress: number
  readonly remainingMs: number
  readonly load: number
  readonly loadCap: number
  /**
   * 本帧是否刚被服务端纠偏（B07 验收 10）。<b>一次性信号</b>：
   * 只在纠偏发生后的第一次 `frame()` 里为 true，读过即清。
   * 场景据此闪一下残影，提示玩家「位置被校正了」。
   */
  readonly corrected: boolean
}

/** 一帧渲染数据。 */
export interface WorldFrame {
  readonly center: Coord
  readonly zoom: number
  readonly tiles: readonly ChunkTile[]
  readonly marches: readonly MarchRender[]
}

/** 数据变化通知。场景订阅它来重绘，而不是每帧轮询。 */
export type WorldListener = () => void

/**
 * 同一块连续「服务端说它还没发完」的重试上限。
 *
 * <p>这是表现层的健壮性常量，不是游戏数值（铁律 1 管的是时间/产量/攻击/掉落/冷却）。
 * 没有上限会怎样：某块实体太多，永远塞不进 20KB 的 payload 上限，服务端每次都把它列进
 * staleChunks，客户端每次都立刻再请求一次 —— 一个吃满带宽的死循环，而且日志里什么错都没有。
 * 达到上限后停止重试并留下警告，玩家看到的是「这块地图暂时不全」，比卡死好。
 */
const STALE_RETRY_LIMIT = 3

/**
 * 判定「服务端纠偏」时容忍的格数差。
 *
 * <p>插值全程向下取整（见 MarchInterpolator：格子是离散的，四舍五入会让队伍在两格之间来回跳），
 * 于是同一条直线换一种参数化后，在某个时刻可以差出 1 格。把这 1 格当成纠偏，
 * 残影就会每次拉取都闪一下，玩家会以为网络一直在抖。
 * 而验收 10 要抓的是「模拟时间偏差 5 分钟」—— 那种偏差动辄几十格，1 格的容忍度挡不掉它。
 */
const CORRECTION_TOLERANCE_CELLS = 1

export class WorldViewModel {
  private readonly layout: WorldLayout
  private readonly chunksPerSide: number
  private readonly viewportSide: number
  private readonly cache: ChunkCache
  private readonly interpolator: MarchInterpolator

  /** 视野中心（格）。字段名与同名访问器 center() 刻意错开，否则 TS 报重复标识符 */
  private focus: Coord
  /** 家坐标（格）。来源是 MarchListResp.home，迁城成功后也由服务端结果改写。 */
  private homeCoord: Coord
  private zoom: number
  private viewportKeys: string[] = []
  /** 发起最近一次 viewport 请求时的块键集合。响应回来时用它而不是当前视野做驱逐判据 */
  private requestedKeys: string[] = []
  /** 需要重新请求：视野移动过、缩放变过、或首次还没有数据 */
  private needsFetch = true
  /** 服务端没能发全的块 → 已重试次数 */
  private readonly stale = new Map<string, number>()
  private readonly explored = new Set<string>()
  private readonly fogged = new Set<string>()
  private readonly tracks = new Map<string, MarchTrack>()
  private readonly trackMeta = new Map<string, { load: number; loadCap: number }>()
  /** 待消费的纠偏标记 */
  private readonly pendingCorrections = new Set<string>()
  private readonly listeners = new Set<WorldListener>()

  constructor(layout: WorldLayout, offsetMs: number, center: Coord, zoom = 0) {
    validateLayout(layout)
    this.layout = layout
    this.chunksPerSide = layout.worldSize / layout.chunkSize
    this.viewportSide = Math.round(Math.sqrt(layout.maxChunks))
    this.cache = new ChunkCache({ maxChunks: layout.maxChunks })
    this.interpolator = new MarchInterpolator(offsetMs)
    this.focus = this.clamp(center)
    this.homeCoord = this.focus
    this.zoom = zoom
    this.viewportKeys = this.computeViewportKeys()
    // 首次请求一定是为这个视野发的，先对齐；nextRequest 每次都会重新记录
    this.requestedKeys = this.viewportKeys.slice()
  }

  // ---------- 只读访问 ----------

  get worldSize(): number {
    return this.layout.worldSize
  }

  get chunkSize(): number {
    return this.layout.chunkSize
  }

  center(): Coord {
    return this.focus
  }

  /** 当前家坐标。家是回城按钮的唯一权威，调用方不得再保存一份平行副本。 */
  home(): Coord {
    return this.homeCoord
  }

  currentZoom(): number {
    return this.zoom
  }

  /** 缓存里的实体总数。验收 3 的内存上界断言用它。 */
  entityCount(): number {
    return this.cache.entityCount()
  }

  /** 当前持有的块数，恒定 <= maxChunks。 */
  chunkCount(): number {
    return this.cache.size
  }

  /** 视野内的块键（3×3，已按世界边界裁剪）。与 nextRequest 上报给服务端的一致。 */
  chunkKeys(): readonly string[] {
    return this.viewportKeys
  }

  subscribe(listener: WorldListener): Unsubscribe {
    this.listeners.add(listener)
    let cancelled = false
    return () => {
      if (cancelled) {
        return
      }
      cancelled = true
      this.listeners.delete(listener)
    }
  }

  // ---------- 视野操作 ----------

  /**
   * 移动视野中心（拖动地图、坐标跳转、一键回城都走这里）。
   *
   * <p>只有<b>跨块</b>时才标记需要重新请求：在同一块内拖动几格，3×3 的块集合根本没变，
   * 再发一次请求就是白烧流量（B07 头号红线是「绝不一次性下发整张地图」，
   * 而频繁重发是它的近亲 —— 都是把省下来的流量花回去）。
   */
  setCenter(coord: Coord): void {
    const next = this.clamp(coord)
    if (next.x === this.focus.x && next.y === this.focus.y) {
      return
    }
    const before = this.centerChunkKey()
    this.focus = next
    if (this.centerChunkKey() !== before) {
      this.viewportKeys = this.computeViewportKeys()
      this.needsFetch = true
    }
    this.notify()
  }

  /**
   * 更新家坐标，不改变当前视野。
   *
   * <p>行军列表每次都会带权威 home；单独提供这个入口是为了让适配层在
   * 第一次进入世界时也能把初始化坐标纳入同一份状态。
   */
  setHome(coord: Coord): void {
    this.homeCoord = this.clamp(coord)
  }

  /**
   * 迁城成功后同时移动家与视野。
   *
   * <p>两件事必须落成一次模型变更：只改家会让地图停在旧址，只改视野会让回城跳回旧址，
   * 两种半成功都会表现成“迁城没生效”。
   */
  relocateHome(coord: Coord): void {
    this.homeCoord = this.clamp(coord)
    this.setCenter(coord)
  }

  /**
   * 切换缩放档位（B07 §1：0=世界 / 1=区域 / 2=城市）。
   *
   * <p>缩放到 2 时场景应切换到城内主城场景 —— 但<b>切换动作在场景里做</b>，
   * 本模块只负责把 zoom 透传进请求（服务端可能按档位给不同粒度的实体）。
   */
  setZoom(zoom: number): void {
    if (!Number.isInteger(zoom) || zoom < 0 || zoom > 2) {
      throw new RangeError(`zoom 只支持 0/1/2 三档，实际=${zoom}`)
    }
    if (zoom === this.zoom) {
      return
    }
    this.zoom = zoom
    this.needsFetch = true
    this.notify()
  }

  /**
   * 下一次该发的 viewport 请求；不需要请求时返回 null。
   *
   * <p>带上手里各块的版本号（B07 验收 6）：服务端只回版本更高的块，
   * 无变化时二次请求的实体数为 0。首次请求版本清单为空数组，服务端据此下发全量。
   */
  nextRequest(): ViewportReq | null {
    if (!this.needsFetch && this.stale.size === 0) {
      return null
    }
    this.requestedKeys = this.viewportKeys.slice()
    return {
      centerX: this.focus.x,
      centerY: this.focus.y,
      zoom: this.zoom,
      chunkVersions: this.cache.versionsFor(this.viewportKeys),
    }
  }

  /** 请求已发出。防止场景在响应回来之前每帧重发同一个请求。 */
  markRequested(): void {
    this.needsFetch = false
  }

  // ---------- 服务端数据落地 ----------

  /**
   * 应用一次 viewport 响应。
   *
   * <p>迷雾与探索<b>以本次响应为准整体替换</b>，不累加：服务端每次都会把视野内的块
   * 分成 exploredChunks / fogChunks 两份下发。累加会让「曾经探索过、现在超出视野」的块
   * 永远留在 explored 集合里，玩家把地图拖回来时会看到一片本该是黑雾的区域是亮的。
   */
  applyViewport(resp: ViewportResp): void {
    const staleKeys = new Set(resp.staleChunks)
    for (const chunk of resp.chunks) {
      if (chunk.truncated) {
        // 实体没发完的块等同于 stale：必须再取一次，否则玩家看到「明明有怪却显示空地」
        staleKeys.add(chunk.key)
      }
    }

    const retry: string[] = []
    for (const key of staleKeys) {
      const attempts = (this.stale.get(key) ?? 0) + 1
      if (attempts > STALE_RETRY_LIMIT) {
        console.warn(`[WorldViewModel] 块 ${key} 连续 ${STALE_RETRY_LIMIT} 次未能完整下发，停止重试（可能是实体数超出 payload 上限）`)
        this.stale.delete(key)
        continue
      }
      this.stale.set(key, attempts)
      retry.push(key)
    }
    // 本次不再 stale 的块要清掉计数，否则玩家反复进出同一区域会累加到上限
        for (const key of Array.from(this.stale.keys())) {
      if (!staleKeys.has(key)) {
        this.stale.delete(key)
      }
    }

    // 用「发起这次请求时的视野」而不是「当前视野」做驱逐判据：响应在路上时玩家可能已经拖走了，
    // 拿当前视野去 apply 会把刚收到的块全部当成离开视野驱逐掉 —— 快速拖动时地图会一片空白
    this.cache.apply(resp.chunks, this.requestedKeys, retry)

    this.explored.clear()
    for (const key of resp.exploredChunks) {
      this.explored.add(key)
    }
    this.fogged.clear()
    for (const key of resp.fogChunks) {
      this.fogged.add(key)
    }

    this.viewportKeys = this.computeViewportKeys()
    if (!sameKeys(this.viewportKeys, this.requestedKeys)) {
      // 响应回来时视野已经变了：立刻补发一次，否则会拿旧视野的块画新视野的地图
      this.needsFetch = true
    }
    this.notify()
  }

  /**
   * 应用一次 /world/marches 响应，并在同一时刻比对旧时间轴算出纠偏（B07 验收 10）。
   *
   * <p><b>纠偏必须在「换轴的那一刻」判定，不能在 frame() 里判定</b>：
   * 行军途中位置每帧都在变，「本帧和上帧不一样」是常态，拿它当纠偏信号会让残影闪个不停。
   * 真正的判据是 MarchInterpolator.correction —— 同一个本地时刻下，
   * 用旧时间轴算出的位置和用新时间轴算出的位置不一致。
   *
   * <p><b>返程段必须重新锚定</b>：MarchView 里没有「返程起点坐标」这个字段，
   * 而 MarchTrack 的默认回退是拿目的地 to 当返程起点 —— 那正是服务端修过的那个 bug
   * （召回瞬间队伍先瞬移到目的地，再开始往回走）。这里改用服务端下发的权威 position：
   * 「t=serverNow 时我在 position，t=returnArriveAt 时我到家」，两点定一条直线，
   * 既没有瞬移，也不需要协议补字段。副作用是每次拉取都会重锚一次，
   * 而重锚后的直线与重锚前是同一条（position 本来就在旧直线上），所以不会引入抖动。
   *
   * <p><b>时钟偏移不在这里更新</b>：单个响应的 serverNow 含一个单程网络延迟，
   * 直接采用会让客户端时间系统性超前（RTT 200ms 时超前 100ms），
   * 表现为「倒计时结束了但服务端说还没结束」。偏移统一由 TimeSync 平滑后经
   * {@link WorldViewModel#updateOffset} 注入 —— 那才是本项目既定的时间校准契约。
   *
   * @param localNow 收到响应那一刻的本地时刻
   */
  applyMarches(resp: MarchListResp, localNow: number): void {
    this.homeCoord = this.clamp(resp.home)
    const nextIds = new Set<string>()
    for (const view of resp.marches) {
      nextIds.add(view.marchId)
      const returning = view.status === 'RETURNING'
      const track: MarchTrack = {
        fromX: view.from.x,
        fromY: view.from.y,
        toX: view.to.x,
        toY: view.to.y,
        startAt: view.startAt,
        arriveAt: view.arriveAt,
        status: view.status,
        returnStartAt: returning ? resp.serverNow : view.returnStartAt,
        returnArriveAt: view.returnArriveAt,
        returnFromX: returning ? view.position.x : null,
        returnFromY: returning ? view.position.y : null,
      }
      const previous = this.tracks.get(view.marchId)
      if (previous !== undefined) {
        const { from, to } = this.interpolator.correction(previous, track, localNow)
        // 容忍 1 格：插值全程向下取整，同一条直线的两种参数化在某一时刻可以差出 1 格，
        // 那是取整误差不是纠偏。真纠偏（验收 10 的 5 分钟偏差）动辄几十格，不会被这个阈值挡掉
        if (manhattan(from.x, from.y, to.x, to.y) > CORRECTION_TOLERANCE_CELLS) {
          this.pendingCorrections.add(view.marchId)
        }
      }
      this.tracks.set(view.marchId, track)
      this.trackMeta.set(view.marchId, { load: view.load, loadCap: view.loadCap })
    }
    // 响应里消失的行军 = 已到家 / 已被合并，必须从渲染里摘掉，否则地图上会留一支幽灵队伍
        for (const marchId of Array.from(this.tracks.keys())) {
      if (!nextIds.has(marchId)) {
        this.tracks.delete(marchId)
        this.trackMeta.delete(marchId)
        this.pendingCorrections.delete(marchId)
      }
    }
    this.notify()
  }

  /** 时间校准更新（每个 HTTP 响应都带 serverNow，等于免费校准一次）。 */
  updateOffset(offsetMs: number): void {
    this.interpolator.updateOffset(offsetMs)
  }

  // ---------- 渲染帧 ----------

  /**
   * 组装一帧。场景每帧调它，或订阅通知后调它。
   *
   * <p>本方法<b>会消费</b>纠偏标记：`MarchRender.corrected` 只在纠偏后的第一次调用里为 true。
   */
  frame(localNow: number): WorldFrame {
    const tiles: ChunkTile[] = []
    for (const key of this.viewportKeys) {
      const [cx, cy] = parseChunkKey(key)
      const cached = this.cache.get(key)
      const fogged = this.fogged.has(key)
      tiles.push({
        key,
        cx,
        cy,
        loaded: cached !== undefined,
        fogged,
        // 迷雾块一律不给实体：服务端本来就不下发，但缓存里可能还留着它被探索之前的旧数据。
        // 把这条不变量钉在逻辑层（可单测）而不是只靠场景里的守卫 —— 否则哪天有人重构场景，
        // 迷雾就悄悄退化成了纯客户端遮罩，改一行代码就能透视全图（B07 明令禁止）
        entities: (cached === undefined || fogged) ? [] : filterDrawable(cached, this.tracks),
      })
    }

    const marches: MarchRender[] = []
    for (const [marchId, track] of this.tracks) {
      const pos = this.interpolator.positionAt(track, localNow)
      const meta = this.trackMeta.get(marchId)
      marches.push({
        marchId,
        x: pos.x,
        y: pos.y,
        status: track.status,
        progress: pos.progress,
        remainingMs: pos.remainingMs,
        load: meta?.load ?? 0,
        loadCap: meta?.loadCap ?? 0,
        corrected: this.pendingCorrections.has(marchId),
      })
    }
    this.pendingCorrections.clear()
    return { center: this.focus, zoom: this.zoom, tiles, marches }
  }

  /** 清空全部本地数据（切换账号、被踢下线）。 */
  clear(): void {
    this.cache.clear()
    this.explored.clear()
    this.fogged.clear()
    this.tracks.clear()
    this.trackMeta.clear()
    this.pendingCorrections.clear()
    this.stale.clear()
    this.needsFetch = true
    this.notify()
  }

  // ---------- 内部 ----------

  private centerChunkKey(): string {
    return chunkKeyOf(this.focus.x, this.focus.y, this.layout.chunkSize)
  }

  /**
   * 算出视野内的 3×3 块键。
   *
   * <p>与服务端 `FogOfWar.viewportChunks` 保持同一套裁剪规则，唯一的差别是
   * <b>客户端还裁掉上边界</b>：服务端只跳过 cx/cy < 0，因为它的中心坐标本来就经过世界校验；
   * 客户端拖动时中心可能贴边，若不裁上界就会画出一排世界之外的黑块。
   * 这个差别不会造成数据问题 —— 服务端即使回了越界块也是空的，ChunkCache 会立刻把它驱逐。
   */
  private computeViewportKeys(): string[] {
    const radius = Math.floor(this.viewportSide / 2)
    const centerX = chunkIndexOf(this.focus.x, this.layout.chunkSize)
    const centerY = chunkIndexOf(this.focus.y, this.layout.chunkSize)
    const out: string[] = []
    for (let dy = -radius; dy <= radius; dy++) {
      for (let dx = -radius; dx <= radius; dx++) {
        const cx = centerX + dx
        const cy = centerY + dy
        if (cx < 0 || cy < 0 || cx >= this.chunksPerSide || cy >= this.chunksPerSide) {
          continue
        }
        out.push(`${cx}:${cy}`)
      }
    }
    return out
  }

  private clamp(coord: Coord): Coord {
    const max = this.layout.worldSize - 1
    return {
      x: Math.min(max, Math.max(0, Math.trunc(coord.x))),
      y: Math.min(max, Math.max(0, Math.trunc(coord.y))),
    }
  }

  private notify(): void {
        for (const listener of Array.from(this.listeners)) {
      try {
        listener()
      } catch (error) {
        console.error('[WorldViewModel] 订阅者抛出异常，已隔离', error)
      }
    }
  }
}

/**
 * 世界坐标 → 块索引。
 *
 * <p>服务端用的是位移（`x >> 5`，见 Coord.chunkKey），并要求 chunkSize 是 2 的幂。
 * 在坐标非负的前提下 `Math.floor(x / chunkSize)` 与位移完全等价，
 * 这里用除法是因为它不依赖「chunkSize 恰好是 32」这个隐含假设。
 */
export function chunkIndexOf(coord: number, chunkSize: number): number {
  return Math.floor(coord / chunkSize)
}

/** 世界坐标 → 块键 "cx:cy"。 */
export function chunkKeyOf(x: number, y: number, chunkSize: number): string {
  return `${chunkIndexOf(x, chunkSize)}:${chunkIndexOf(y, chunkSize)}`
}

/** 块键 → [cx, cy]。格式非法直接抛错：静默返回 0 会让一整块画到世界原点。 */
export function parseChunkKey(key: string): [number, number] {
  const parts = key.split(':')
  if (parts.length !== 2) {
    throw new Error(`chunk 键格式必须是 cx:cy，实际=${key}`)
  }
  const cx = Number(parts[0])
  const cy = Number(parts[1])
  if (!Number.isInteger(cx) || !Number.isInteger(cy)) {
    throw new Error(`chunk 键的两段必须是整数，实际=${key}`)
  }
  return [cx, cy]
}

/** 两个格坐标之间的曼哈顿距离。与服务端的距离口径一致（MarchResp.distance 也是曼哈顿）。 */
function manhattan(ax: number, ay: number, bx: number, by: number): number {
  return Math.abs(ax - bx) + Math.abs(ay - by)
}

/**
 * 两个块键集合是否相同。
 *
 * <p>逐项按序比较而不是转成 Set：两个数组都由 {@link WorldViewModel} 的 computeViewportKeys
 * 以同一遍历顺序生成，顺序天然一致，建 Set 只是白白多两次分配（这函数每次响应都要调）。
 */
function sameKeys(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) {
    return false
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      return false
    }
  }
  return true
}

/**
 * 挑出真正要画的实体。剔除两类：
 * <ul>
 *   <li>EMPTY：空格子没有可画的东西，留着只会让节点池白白占用</li>
 *   <li>己方行军：它已由 MarchInterpolator 本地插值接管。不剔除会在同一个位置画两个图标，
 *       而且块里的那个是上次拉取时的旧位置 —— 玩家会看到自己的队伍分裂成两支</li>
 * </ul>
 */
function filterDrawable(entities: readonly WorldEntity[],
                        ownTracks: ReadonlyMap<string, MarchTrack>): readonly WorldEntity[] {
  const out: WorldEntity[] = []
  for (const entity of entities) {
    if (entity.type === 'EMPTY') {
      continue
    }
    if (entity.type === 'MARCH' && ownTracks.has(entity.id)) {
      continue
    }
    out.push(entity)
  }
  return out
}

function validateLayout(layout: WorldLayout): void {
  if (!Number.isInteger(layout.worldSize) || layout.worldSize <= 0) {
    throw new RangeError(`worldSize 必须是正整数，实际=${layout.worldSize}`)
  }
  if (!Number.isInteger(layout.chunkSize) || layout.chunkSize <= 0
      || (layout.chunkSize & (layout.chunkSize - 1)) !== 0) {
    throw new RangeError(`chunkSize 必须是正的 2 的幂（与服务端 Coord.chunkKey 同一约束），实际=${layout.chunkSize}`)
  }
  if (layout.worldSize % layout.chunkSize !== 0) {
    throw new RangeError(`worldSize 必须能被 chunkSize 整除，否则边缘会出现半块：${layout.worldSize} / ${layout.chunkSize}`)
  }
  const side = Math.sqrt(layout.maxChunks)
  if (!Number.isInteger(side) || side % 2 === 0) {
    throw new RangeError(`maxChunks 必须是奇数边的完全平方数（3×3=9），否则视野没有中心块，实际=${layout.maxChunks}`)
  }
}
