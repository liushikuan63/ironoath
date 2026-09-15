/**
 * 职责：最小主城场景 —— 一块空地 + 一个主城色块 + 顶部资源条（B01 交付物）。
 * 依赖：cc（渲染）、game/store/Store（只读订阅）。
 *
 * 铁律 2：本文件是纯表现层，只<b>读</b> Store，不改任何数据、不做任何数值判定。
 * 删掉这个文件，游戏逻辑不受任何影响 —— 这是判断「表现层有没有越界」的标准。
 *
 * 占位美术刻意用 Graphics 画纯色块而不是引图片资源：
 * B01 阶段还没有美术产出，用色块可以让全链路先跑通；
 * 换成正式美术时只需替换本文件的绘制部分，Store 订阅与刷新逻辑不用动。
 */

import { _decorator, Color, Component, Graphics, Label, Node, UITransform, Vec3, view } from 'cc'
import { gameStore } from '../game/store/Store'
import type { GameState } from '../game/store/Store'
import type { Unsubscribe } from '../core/EventBus'
import type { ResourceState } from '../net/generated/Protocol'
import { applySystemUiFont } from './UiFont'

const { ccclass } = _decorator

/**
 * 配色来自 B00「题材与调性」：铜金 + 暗红主色，写实厚重冷兵器乱世，无 Q 版、无二次元。
 * 这是美术方向常量而非游戏数值，因此可以写在代码里（铁律 1 约束的是时间/产量/攻击/掉落/冷却）。
 */
const COLOR_BACKGROUND = new Color(24, 20, 18, 255)
const COLOR_GROUND = new Color(58, 46, 36, 255)
const COLOR_MAIN_CITY = new Color(139, 26, 26, 255)
const COLOR_COPPER_GOLD = new Color(184, 134, 11, 255)
const COLOR_TEXT = new Color(226, 214, 190, 255)
const COLOR_WARNING = new Color(200, 60, 40, 255)

/** 资源条上并列展示的资源数量上限。超过就换行，避免挤成一条看不懂的数字。 */
const RESOURCE_SLOTS_PER_ROW = 5
const RESOURCE_SLOT_WIDTH = 190
const RESOURCE_SLOT_HEIGHT = 34
const RESOURCE_BAR_TOP_MARGIN = 24

const MAIN_CITY_SIZE = 160
const GROUND_HEIGHT = 220

@ccclass('MainCity')
export class MainCity extends Component {
  private readonly unsubscribes: Unsubscribe[] = []
  private readonly resourceLabels = new Map<string, Label>()
  private cityLevelLabel: Label | null = null
  private bannerLabel: Label | null = null

  override onLoad(): void {
    const size = view.getVisibleSize()
    this.buildBackground(size.width, size.height)
    this.buildGround(size.width)
    this.buildMainCity()
    this.buildBanner(size.height)
    this.buildResourceBar(size.width, size.height)
    this.bindStore()
    this.refreshAll(gameStore.getState())
  }

  override onDestroy(): void {
    // 必须退订：场景销毁后订阅还在的话，下一次数据变化会往已销毁的节点上写字，
    // 表现为「切场景后偶发报错」，这类 bug 极难复现
    for (const unsubscribe of this.unsubscribes) {
      unsubscribe()
    }
    this.unsubscribes.length = 0
    this.resourceLabels.clear()
  }

  // ---------- 搭建 ----------

  private buildBackground(width: number, height: number): void {
    this.drawBlock('Background', width, height, 0, 0, COLOR_BACKGROUND)
  }

  private buildGround(width: number): void {
    this.drawBlock('Ground', width, GROUND_HEIGHT, 0, -height0() + GROUND_HEIGHT / 2, COLOR_GROUND)
  }

  private buildMainCity(): void {
    // 主城色块放在地面中央偏上，作为视觉焦点
    this.drawBlock('MainCity', MAIN_CITY_SIZE, MAIN_CITY_SIZE, 0, -height0() + GROUND_HEIGHT + MAIN_CITY_SIZE / 2, COLOR_MAIN_CITY)
    const label = this.createLabel('MainCityLevel', '', COLOR_COPPER_GOLD)
    label.node.setPosition(new Vec3(0, -height0() + GROUND_HEIGHT + MAIN_CITY_SIZE / 2, 0))
    this.cityLevelLabel = label
  }

  private buildBanner(height: number): void {
    const label = this.createLabel('Banner', '', COLOR_TEXT)
    label.node.setPosition(new Vec3(0, height / 2 - RESOURCE_BAR_TOP_MARGIN - RESOURCE_SLOT_HEIGHT * 2, 0))
    label.fontSize = 22
    this.bannerLabel = label
  }

  /**
   * 顶部资源条。
   *
   * 槽位按 Store 里实际出现的资源类型动态创建 —— 配置表加一种资源，
   * 这里不需要改代码（铁律 6：配置驱动）。
   */
  private buildResourceBar(width: number, height: number): void {
    const types = Object.keys(gameStore.getState().resources)
    if (types.length === 0) {
      // 登录前 Store 是空的，槽位等首次数据到达后再建
      return
    }
    const startX = -Math.min(width, types.length * RESOURCE_SLOT_WIDTH) / 2 + RESOURCE_SLOT_WIDTH / 2
    const y = height / 2 - RESOURCE_BAR_TOP_MARGIN - RESOURCE_SLOT_HEIGHT / 2
    types.forEach((type, index) => {
      const row = Math.floor(index / RESOURCE_SLOTS_PER_ROW)
      const column = index % RESOURCE_SLOTS_PER_ROW
      const label = this.createLabel(`Resource_${type}`, '', COLOR_COPPER_GOLD)
      label.fontSize = 18
      label.node.setPosition(new Vec3(
        startX + column * RESOURCE_SLOT_WIDTH,
        y - row * RESOURCE_SLOT_HEIGHT,
        0,
      ))
      this.resourceLabels.set(type, label)
    })
  }

  private drawBlock(name: string, width: number, height: number, x: number, y: number, color: Color): void {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.setPosition(new Vec3(x, y, 0))

    const transform = node.addComponent(UITransform)
    transform.setContentSize(width, height)

    const graphics = node.addComponent(Graphics)
    graphics.fillColor = color
    graphics.rect(-width / 2, -height / 2, width, height)
    graphics.fill()
  }

  private createLabel(name: string, text: string, color: Color): Label {
    const node = new Node(name)
    node.layer = this.node.layer
    this.node.addChild(node)
    node.addComponent(UITransform)
    const label = applySystemUiFont(node.addComponent(Label))
    label.string = text
    label.color = color
    label.fontSize = 20
    label.horizontalAlign = Label.HorizontalAlign.CENTER
    label.verticalAlign = Label.VerticalAlign.CENTER
    return label
  }

  // ---------- 订阅与刷新 ----------

  private bindStore(): void {
    // 只订阅真正会影响本场景的字段，避免每次 Store 变化都重绘整个场景
    this.unsubscribes.push(gameStore.subscribe('resources', (resources) => {
      this.ensureResourceSlots()
      this.refreshResources(resources)
    }))
    this.unsubscribes.push(gameStore.subscribe('cityLevel', (cityLevel) => {
      if (this.cityLevelLabel !== null) {
        this.cityLevelLabel.string = `主城 ${cityLevel} 级`
      }
    }))
    this.unsubscribes.push(gameStore.subscribe('nickName', () => {
      this.refreshBanner(gameStore.getState())
    }))
  }

  private refreshAll(state: GameState): void {
    this.ensureResourceSlots()
    this.refreshResources(state.resources)
    if (this.cityLevelLabel !== null) {
      this.cityLevelLabel.string = `主城 ${state.cityLevel} 级`
    }
    this.refreshBanner(state)
  }

  /** 数据到达晚于场景搭建时补建槽位（登录前 Store 为空）。 */
  private ensureResourceSlots(): void {
    const types = Object.keys(gameStore.getState().resources)
    if (types.length === this.resourceLabels.size) {
      return
    }
    const size = view.getVisibleSize()
    this.buildResourceBar(size.width, size.height)
  }

  private refreshResources(resources: Readonly<Record<string, ResourceState>>): void {
    for (const [type, label] of this.resourceLabels) {
      const value = resources[type]
      if (value === undefined) {
        label.string = `${type} --`
        continue
      }
      // 只做展示格式化，不做任何数值判定：容量、产量、保护量全部照服务端给的原样显示
      label.string = `${type} ${value.current}/${value.cap}`
      label.color = value.current >= value.cap ? COLOR_WARNING : COLOR_COPPER_GOLD
    }
  }

  private refreshBanner(state: GameState): void {
    if (this.bannerLabel === null) {
      return
    }
    // 网络上这一句不在这里写：顶部那行（game/network/NetworkNotice）才是它的家。
    // 此前这里由 WebSocket 的 netDisconnected 驱动写死一句"正在重连"，而 HTTP 重投用尽
    // 并不会发那个事件 —— 结果是"链路早就恢复了，横幅还挂着正在重连"，
    // 同一件事两处文案、其中一处还会说谎。横幅只管身份与战力。
    this.bannerLabel.string = state.playerId === null
      ? '未登录'
      : `${state.nickName} · 战力 ${state.power?.displayPower ?? 0}`
    this.bannerLabel.color = COLOR_TEXT
  }
}

/**
 * 画布下半部分的高度偏移。
 *
 * Cocos 的 UI 坐标系原点在节点中心，而地面/主城需要贴底摆放。
 * 这里用可见高度的一半做偏移，避免在各处重复写 `-height / 2`。
 */
function height0(): number {
  return view.getVisibleSize().height / 2
}
