/**
 * 职责：场景层通用节点池 —— 地图实体、行军图标、飘字、列表 item 都从这里取（B07 §4）。
 * 依赖：cc（渲染）。
 *
 * <p><b>为什么必须池化，而不是每次 new Node</b>：B07 验收 3 是「连续拖动地图 60 秒，
 * 内存增长 < 10MB」。拖动时实体不断进出视野，如果每个实体都新建节点、离开视野就 destroy，
 * 那么 60 秒内会产生成千上万次「分配 → 销毁」，而 Cocos 的节点销毁是<b>延迟</b>的
 * （帧末统一回收），高频拖动时销毁跟不上分配，内存就会一路涨上去。
 * 池化把「销毁」换成「归还」，节点总数被峰值实体数封顶。
 *
 * <p><b>归还时 removeFromParent，而不是只置 active=false</b>：
 * 置灰的节点虽然不渲染，但仍然留在父节点的子列表里，Cocos 每帧遍历变换层级时照样要走一遍。
 * 地图层同时可能有几百个实体，留在列表里的死节点会直接吃掉帧时间。
 * removeFromParent 让「在用的节点数」等于「真正要画的节点数」。
 *
 * <p><b>铁律 2</b>：本模块只管节点的生老病死，不碰任何游戏数据。
 */

import { Node } from 'cc'

export class NodePool {
  private readonly free: Node[] = []
  private readonly parent: Node
  private readonly factory: () => Node
  private live = 0
  private created = 0

  /**
   * @param parent  取出的节点挂到哪个父节点下。所有实体共用一个父节点，合批才有可能生效
   * @param factory 池空时怎么造一个新节点
   * @param warmup  预热数量。开局就知道大概要画多少时填它，避免第一帧集中分配造成掉帧
   */
  constructor(parent: Node, factory: () => Node, warmup = 0) {
    this.parent = parent
    this.factory = factory
    for (let i = 0; i < warmup; i++) {
      this.free.push(this.newNode())
    }
  }

  /** 取一个节点。池空则新建。返回的节点已挂到父节点下且 active=true。 */
  acquire(): Node {
    const node = this.free.pop() ?? this.newNode()
    node.active = true
    this.parent.addChild(node)
    this.live++
    return node
  }

  /** 归还一个节点。归还后不再参与渲染与遍历。 */
  release(node: Node): void {
    if (node.parent !== this.parent) {
      // 不是从本池取出去的节点（或已经被别处接管）。把它 destroy 掉而不是塞回池子，
      // 否则池里会混进结构不同的节点，下次 acquire 出来的东西画不成
      node.removeFromParent()
      node.destroy()
      return
    }
    node.active = false
    node.removeFromParent()
    this.free.push(node)
    this.live--
  }

  /** 归还一批。场景做差集回收时用。 */
  releaseAll(nodes: Iterable<Node>): void {
    for (const node of nodes) {
      this.release(node)
    }
  }

  /** 池中空闲节点数。 */
  get idleCount(): number {
    return this.free.length
  }

  /** 正在使用的节点数。 */
  get liveCount(): number {
    return this.live
  }

  /**
   * 累计创建过的节点数。<b>这是判断池有没有真起作用的唯一指标</b>：
   * 拖动 60 秒后它应当稳定在峰值实体数附近；如果它随时间线性增长，
   * 说明归还路径没走到（漏了 release），验收 3 一定会挂。
   */
  get createdCount(): number {
    return this.created
  }

  /**
   * 彻底销毁池里所有空闲节点。场景 onDestroy 时必须调用 ——
   * 池是为了复用，不是为了在场景切换后还把几百个节点挂在内存里（B07 §4：防内存泄漏）。
   */
  destroy(): void {
    for (const node of this.free) {
      node.removeFromParent()
      node.destroy()
    }
    this.free.length = 0
    this.live = 0
  }

  private newNode(): Node {
    this.created++
    return this.factory()
  }
}
