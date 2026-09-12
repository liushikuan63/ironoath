/**
 * 职责：地图 chunk 的客户端缓存 —— 按版本号增量更新、离开视野即卸载（B07 §1/§4）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>这个模块管的是内存，不是画面</b>。B07 验收 3 是「连续拖动地图 60 秒，内存增长 < 10MB」，
 * 而内存增长的唯一来源就是「卸载了的 chunk 仍然被引用」。所以本类的核心不变量是：
 * **同时持有的 chunk 数永远不超过 viewportChunks**，超出的一律驱逐。
 * 节点池的回收是 Cocos 侧的事（这里测不到），但「什么时候该回收」由本类决定 ——
 * 它给出 `evicted` 列表，表现层据此把节点还回池子。
 *
 * <p><b>版本号是唯一的新旧判据</b>：服务端只在块的版本比客户端手里的高时才下发实体
 * （B07 验收 6：无变化时二次请求实体数为 0）。客户端绝不能靠「时间戳」或「我上次什么时候拉的」
 * 来判断新旧 —— 那样服务端不发数据时客户端会以为自己的缓存过期，反复请求，
 * 把省下来的流量又全花回去。
 */

import type { ChunkData, WorldEntity } from '../../net/generated/WorldProtocol'

/** 一次 viewport 响应之后，缓存发生的三类变化。 */
export interface CacheDiff {
  /** 本次新写入或更新的块。 */
  readonly updated: readonly string[]
  /** 客户端版本已是最新、服务端没有下发实体的块（B07 验收 6）。 */
  readonly unchanged: readonly string[]
  /** 因为离开视野或超出容量而被驱逐的块 —— 表现层必须据此把节点还回池子。 */
  readonly evicted: readonly string[]
}

export interface ChunkCacheOptions {
  /** 同时持有的块数上限。来源：global.VIEWPORT_CHUNK_COUNT */
  readonly maxChunks: number
}

interface Entry {
  readonly version: number
  readonly entities: readonly WorldEntity[]
  /** 最近一次被访问的序号，用于 LRU 驱逐。 */
  lastTouch: number
}

export class ChunkCache {
  private readonly entries = new Map<string, Entry>()
  private readonly maxChunks: number
  private clock = 0

  constructor(options: ChunkCacheOptions) {
    if (!(options.maxChunks > 0)) {
      throw new Error(`maxChunks 必须为正，否则任何块都存不下：${options.maxChunks}`)
    }
    this.maxChunks = options.maxChunks
  }

  /** 当前持有的块数。恒定 <= maxChunks。 */
  get size(): number {
    return this.entries.size
  }

  /** 某块的版本号；没有缓存时返回 -1（服务端把 -1 视为「客户端什么都没有」）。 */
  versionOf(chunkKey: string): number {
    return this.entries.get(chunkKey)?.version ?? -1
  }

  get(chunkKey: string): readonly WorldEntity[] | undefined {
    const entry = this.entries.get(chunkKey)
    if (entry === undefined) {
      return undefined
    }
    entry.lastTouch = ++this.clock
    return entry.entities
  }

  has(chunkKey: string): boolean {
    return this.entries.has(chunkKey)
  }

  /**
   * 构造下次请求要带的版本清单。
   *
   * <p>只上报当前视野内的块：把缓存里所有块的版本都发上去，会让服务端
   * 对一堆客户端已经不看的块做无谓的版本比对，请求体也白白变大。
   */
  versionsFor(chunkKeys: readonly string[]): Array<{ key: string; version: number }> {
    const out: Array<{ key: string; version: number }> = []
    for (const key of chunkKeys) {
      const entry = this.entries.get(key)
      if (entry !== undefined) {
        out.push({ key, version: entry.version })
      }
    }
    return out
  }

  /**
   * 应用一次 viewport 响应。
   *
   * @param chunks       服务端下发的块（只含版本变化的那些）
   * @param viewport     本次视野内的全部块键（3×3）。不在其中的块会被驱逐
   * @param staleChunks  服务端因 payload 上限没能下发的块 —— <b>保留旧缓存</b>，
   *                     下次请求继续带上它的版本号。删掉旧缓存会让玩家看到一块空白地图，
   *                     而 stale 的含义只是「这次没更新」，不是「你的数据作废了」
   */
  apply(chunks: readonly ChunkData[], viewport: readonly string[],
        staleChunks: readonly string[] = []): CacheDiff {
    const updated: string[] = []
    const unchanged: string[] = []
    const stale = new Set(staleChunks)
    const inViewport = new Set(viewport)

    for (const chunk of chunks) {
      const existing = this.entries.get(chunk.key)
      if (existing !== undefined && existing.version === chunk.version && chunk.entities.length === 0) {
        // 服务端回了块但实体为空且版本相同 ⇒ 无变化（B07 验收 6 的客户端一侧）
        existing.lastTouch = ++this.clock
        unchanged.push(chunk.key)
        continue
      }
      if (existing !== undefined && chunk.version < existing.version) {
        // 版本倒退只可能是响应乱序到达（弱网重传）。接受它会让地图上的实体退回过去，
        // 玩家会看到「刚打掉的野怪又回来了」，所以直接丢弃并留下警告
        console.warn(`[ChunkCache] 丢弃版本倒退的块 ${chunk.key}: 本地 ${existing.version} > 下发 ${chunk.version}`)
        continue
      }
      this.entries.set(chunk.key, {
        version: chunk.version,
        entities: chunk.entities,
        lastTouch: ++this.clock,
      })
      updated.push(chunk.key)
    }

    // 离开视野的块立即驱逐（stale 的除外：它还在视野内，只是这次没更新）
    const evicted: string[] = []
    for (const key of [...this.entries.keys()]) {
      if (!inViewport.has(key) && !stale.has(key)) {
        this.entries.delete(key)
        evicted.push(key)
      }
    }
    // 视野内的块也可能超过容量上限（例如服务端多发了几块），按 LRU 驱逐到上限为止
    while (this.entries.size > this.maxChunks) {
      let oldestKey: string | null = null
      let oldestTouch = Number.POSITIVE_INFINITY
      for (const [key, entry] of this.entries) {
        if (inViewport.has(key) && entry.lastTouch < oldestTouch) {
          oldestTouch = entry.lastTouch
          oldestKey = key
        }
      }
      if (oldestKey === null) {
        // 全都是视野外的（上面已经删过了），退化成删任意最旧的一块，保证不变量成立
        for (const [key, entry] of this.entries) {
          if (entry.lastTouch < oldestTouch) {
            oldestTouch = entry.lastTouch
            oldestKey = key
          }
        }
      }
      if (oldestKey === null) {
        break
      }
      this.entries.delete(oldestKey)
      evicted.push(oldestKey)
    }
    return { updated, unchanged, evicted }
  }

  /**
   * 缓存里的实体总数。用于断言「内存不会随拖动无限增长」——
   * 只要它被 maxChunks × 单块实体数封顶，验收 3 的内存增长就有上界。
   */
  entityCount(): number {
    let total = 0
    for (const entry of this.entries.values()) {
      total += entry.entities.length
    }
    return total
  }

  clear(): void {
    this.entries.clear()
  }
}
