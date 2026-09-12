/**
 * 职责：客户端红点树 —— 消费服务端下发的红点树，叠加本地来源，沿父链聚合（B12 §4，验收 1）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>双来源是本模块存在的理由</b>：服务端下发的红点覆盖「需要权威数据才能判断」的那些
 * （有没有可升级的建筑、有没有可领的邮件），而客户端本地能判断的只有「纯本地状态」
 * （本地缓存的聊天有没有未读、某个面板是不是第一次打开）。
 * 两类都必须能点亮同一个节点，所以聚合规则是<b>取或</b>：
 * {@code lit(key) = serverLit(key) || localLit(key) || 任一后代 lit}。
 *
 * <p><b>铁律 2：本模块不做任何业务数值判定</b>。它不比较资源、不比较等级、不比较战力 ——
 * 那些条件在服务端注册（{@code ReddotTree.register}）后随 {@code ReddotTreeResp} 下发。
 * 客户端自己判断的后果是双端各有一套「该不该亮」的口径，
 * 而红点是玩家最频繁看到的信号，两套口径迟早会对不上。
 * `scripts/check-no-scattered-reddot.sh` 会静态检查本文件里不出现数值比较。
 *
 * <p><b>不缓存聚合结果</b>：与服务端同一条理由 —— 缓存就需要失效机制，
 * 而失效漏掉一次的后果正是验收 1 要防的「1 帧内没聚合对」。
 * 叶子是几十个量级，每次现算的成本远低于维护缓存一致性。
 */

/**
 * 红点节点。形状的唯一归属是契约（`ReddotTreeResp.nodes` 的元素），这里只做再导出 ——
 * 本地再声明一份就是第二份定义：服务端加字段时这边不会报错，只会悄悄不一致。
 */
export type { ReddotNodeView } from '../../net/generated/SocialProtocol'
import type { ReddotNodeView } from '../../net/generated/SocialProtocol'

/** 路径分隔符。与服务端 ReddotTree.SEPARATOR 一致，改一处必须同时改另一处。 */
export const REDDOT_SEPARATOR = '/'

/** 一个本地红点条件。 */
export type LocalCondition = () => boolean

/** 一条本地注册记录。 */
export interface LocalLeaf {
  readonly key: string
  readonly condition: LocalCondition
  readonly description: string
}

export class ClientReddotTree {
  /** 服务端下发的树，按 key 拍平存放。父节点也在里面（服务端会下发完整链路） */
  private readonly serverLit = new Map<string, boolean>()
  private readonly serverChildren = new Map<string, string[]>()
  private readonly localLeaves = new Map<string, LocalLeaf>()
  private errors = 0

  /**
   * 落地一次服务端下发的红点树。
   *
   * <p><b>整体替换而不是合并</b>：服务端每次下发的是当前完整状态，
   * 合并会让「已经消失的红点」永远留在客户端 —— 玩家点进去发现没东西，
   * 而那正是验收 1 说的「假红点」。
   */
  applyServer(nodes: readonly ReddotNodeView[]): void {
    this.serverLit.clear()
    this.serverChildren.clear()
    for (const node of nodes) {
      this.flatten(node)
    }
  }

  private flatten(node: ReddotNodeView): void {
    this.serverLit.set(node.key, node.lit)
    const children: string[] = []
    for (const child of node.children) {
      children.push(child.key)
      this.flatten(child)
    }
    this.serverChildren.set(node.key, children)
  }

  /**
   * 注册一个本地红点条件。
   *
   * <p>同 key 重复注册直接抛错（与服务端同一纪律）：两个条件挂在一个叶子上，
   * 意味着其中一个是死代码，而删掉哪一个都不敢。
   */
  registerLocal(key: string, condition: LocalCondition, description: string): void {
    validateKey(key)
    if (typeof condition !== 'function') {
      throw new Error(`红点条件必须是函数：${key}`)
    }
    if (this.localLeaves.has(key)) {
      throw new Error(`红点叶子重复注册：${key}（已有描述「${this.localLeaves.get(key)?.description}」）`)
    }
    this.localLeaves.set(key, { key, condition, description })
  }

  unregisterLocal(key: string): boolean {
    return this.localLeaves.delete(key)
  }

  /**
   * 某个节点此刻是否亮。
   *
   * <p>三个来源取或：服务端对该节点的结论、本地注册在该节点上的条件、
   * 以及<b>任意后代</b>的亮灭。后代那一项要遍历全部已知 key 做前缀匹配 ——
   * 因为服务端可能只下发了子树的一部分，本地也可能注册了服务端不知道的叶子。
   */
  isLit(key: string): boolean {
    validateKey(key)
    const prefix = key + REDDOT_SEPARATOR
    if (this.serverLit.get(key) === true) {
      // 服务端已经聚合过它的子树了，直接采信
      return true
    }
    for (const [leafKey, leaf] of this.localLeaves) {
      if (leafKey !== key && !leafKey.startsWith(prefix)) {
        continue
      }
      if (this.evaluate(leaf)) {
        return true
      }
    }
    // 服务端下发的子节点里可能有本地不知道的叶子，它们的亮灭也要算进来
    for (const child of this.serverChildren.get(key) ?? []) {
      if (this.isLit(child)) {
        return true
      }
    }
    return false
  }

  /**
   * 当前整棵树的快照（供 UI 绑定）。
   *
   * <p>合并服务端结构与本地叶子：本地注册的 key 如果不在服务端树里，
   * 会按路径补出中间节点 —— 否则一个纯本地红点就没有可挂载的父级，
   * 表现是「叶子亮了但入口上没有红点」。
   */
  snapshot(): ReddotNodeView[] {
    const keys = new Set<string>(this.serverLit.keys())
    for (const leafKey of this.localLeaves.keys()) {
      for (const path of ancestors(leafKey)) {
        keys.add(path)
      }
    }
    const ordered = [...keys].sort()
    const litByKey = new Map<string, boolean>()
    const childrenByKey = new Map<string, string[]>()
    const roots: string[] = []
    for (const path of ordered) {
      const parent = parentOf(path)
      if (parent === null) {
        roots.push(path)
        continue
      }
      const siblings = childrenByKey.get(parent) ?? []
      siblings.push(path)
      childrenByKey.set(parent, siblings)
    }
    // 倒序：深的先算，浅的后算，父节点聚合时子节点的结果已经就绪
    for (let i = ordered.length - 1; i >= 0; i--) {
      const path = ordered[i] ?? ''
      litByKey.set(path, this.isLit(path))
    }
    return roots.map((key) => this.buildNode(key, litByKey, childrenByKey))
  }

  private buildNode(key: string, litByKey: ReadonlyMap<string, boolean>,
                    childrenByKey: ReadonlyMap<string, string[]>): ReddotNodeView {
    const children = (childrenByKey.get(key) ?? [])
      .slice()
      .sort()
      .map((child) => this.buildNode(child, litByKey, childrenByKey))
    return { key, lit: litByKey.get(key) === true, children }
  }

  /** 条件抛异常时按不亮处理并记数（与服务端同一纪律：静默吞掉会让那个红点永远不亮）。 */
  private evaluate(leaf: LocalLeaf): boolean {
    try {
      return leaf.condition() === true
    } catch (error) {
      this.errors++
      console.error(`[ClientReddotTree] 红点条件抛异常，按不亮处理 key=${leaf.key} 描述=${leaf.description}`, error)
      return false
    }
  }

  /** 条件函数抛异常的次数。单测与埋点都应当断言它为 0。 */
  errorCount(): number {
    return this.errors
  }

  /** 本地注册的叶子数。 */
  localLeafCount(): number {
    return this.localLeaves.size
  }

  /** 服务端下发过的节点数（含中间节点）。 */
  serverNodeCount(): number {
    return this.serverLit.size
  }

  clear(): void {
    this.serverLit.clear()
    this.serverChildren.clear()
    this.localLeaves.clear()
    this.errors = 0
  }
}

/** 一个 key 的全部祖先（含它自己），从深到浅。 */
function ancestors(key: string): string[] {
  const out: string[] = []
  let current: string | null = key
  while (current !== null && current.length > 0) {
    out.push(current)
    current = parentOf(current)
  }
  return out
}

function parentOf(key: string): string | null {
  const slash = key.lastIndexOf(REDDOT_SEPARATOR)
  return slash < 0 ? null : key.substring(0, slash)
}

function validateKey(key: string): void {
  if (typeof key !== 'string' || key.length === 0) {
    throw new Error('红点 key 不得为空')
  }
  if (key.startsWith(REDDOT_SEPARATOR) || key.endsWith(REDDOT_SEPARATOR)) {
    throw new Error(`红点路径不得以 ${REDDOT_SEPARATOR} 开头或结尾，实际=${key}`)
  }
  if (key.includes(REDDOT_SEPARATOR + REDDOT_SEPARATOR)) {
    throw new Error(`红点路径不得含连续分隔符，实际=${key}`)
  }
}
